package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What happened. The history screen turns the kind into a sentence in the user's language. */
enum class ActionKind {
    DOWNLOADED, DOWNLOAD_FAILED, REMOVED, RESTORED, PURGED, MOVED, MOVE_FAILED,
    SYNCED, SYNC_FAILED, BACKED_UP, BACKUP_FAILED, BACKUP_RESTORED, PLAYED, PINNED, UNPINNED, OTHER;

    companion object {
        fun fromName(name: String?): ActionKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Which feature a line without a game is about ([ActionEntry.topic]). Stored as is; shown in words. */
object ActionTopic {
    const val SAVE_SYNC = "save_sync"
    const val DEVICE_SYNC = "device_sync"
    const val CLOUD_BACKUP = "cloud_backup"
    const val LIBRARY_FOLDER = "library_folder"
    const val SMART_STORAGE = "smart_storage"
}

/**
 * Why something happened ([ActionEntry.reason]): a fixed code the screen maps to a phrase, never
 * free text. Failures of a download store [DOWNLOAD_PREFIX] + the failure category, failures of the
 * cloud [CLOUD_PREFIX] + the error code without its detail (which may hold a server address).
 */
object ActionReason {
    /** Removals. */
    const val BY_USER = "user"
    const val DUPLICATE = "duplicate"
    const val BETTER_VERSION = "better_version"
    const val FREE_SPACE = "free_space"
    const val OFFLINE_COLLECTION = "offline_collection"
    /** Trash emptied by the retention setting. */
    const val EXPIRED = "expired"
    /** Moves. */
    const val TO_SD = "to_sd"
    const val TO_INTERNAL = "to_internal"
    const val TO_SD_ESDE_LEFT = "to_sd_esde"
    const val TO_INTERNAL_ESDE_LEFT = "to_internal_esde"
    /** A library move that copied everything but left some originals behind. */
    const val ORIGINALS_LEFT = "originals_left"
    /** A sync that holds removals back until the user confirms. */
    const val HELD_BACK = "held_back"

    const val DOWNLOAD_PREFIX = "dl:"
    const val CLOUD_PREFIX = "cloud:"

    /**
     * [CloudErrors.encode] text without the detail part (addresses), so nothing that names a server
     * reaches the history file: "dav|NETWORK|0|https://…" becomes "cloud:dav|NETWORK|0|", any
     * other error "cloud:other".
     */
    fun cloud(encoded: String): String {
        val parts = encoded.split('|')
        return CLOUD_PREFIX + when (parts[0]) {
            "dav" -> parts.take(3).joinToString("|") + "|"
            "k" -> parts.take(2).joinToString("|")
            // Free text (an exception message) may name a path or a server: not kept.
            else -> CLOUD_OTHER
        }
    }

    const val CLOUD_OTHER = "other"
}

/** Names of the numbers a sync line carries in [ActionEntry.counts]. */
object ActionCount {
    const val UPLOADED = "up"
    const val DOWNLOADED = "down"
    const val CONFLICTS = "conflicts"
    const val FAILED = "failed"
    const val ADDED = "added"
    const val REMOVED = "removed"
    const val SENT = "sent"
    const val DELETED_DEVICE = "del_device"
    const val DELETED_SERVER = "del_server"
    const val HELD = "held"
    const val FILES = "files"
    const val LEFT = "left"
}

/**
 * One line of the action history (2.4.0). [id] is unique; [title] the game acted on as it was
 * called at the time (empty for a line about a feature, which then names its [topic]). [opId] links
 * a removal to its operation in the recovery journal ([com.cortinadev.dogmatix.data.service.OperationHistoryService]),
 * which stays the only source of truth for the files; Restore goes through it. [reason] and
 * [counts] are codes (see [ActionReason], [ActionCount]), never text: no path, address or error
 * message is stored. [count] > 0 with an empty title is a summary line ("37 more games
 * downloaded"); [bytes] is the size involved, 0 when unknown. [undone]: the way back was used.
 */
data class ActionEntry(
    val id: String,
    val at: Long,
    val kind: ActionKind,
    val title: String = "",
    val topic: String? = null,
    val consoleId: String? = null,
    val fileName: String? = null,
    val opId: String? = null,
    val reason: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    val count: Int = 0,
    val bytes: Long = 0L,
    val undone: Boolean = false
) {
    val isSummary: Boolean get() = title.isEmpty() && count > 0
}

/** Encoding of the history file: one JSON object per line, oldest first. Android-free so it is unit-testable. */
object ActionLogFormat {
    const val MAX_ENTRIES = 2000

    /** Entries older than this are dropped when the file is rewritten (180 days). */
    const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    /** The file is rewritten (cut to [MAX_ENTRIES]) once it holds this many lines: about 1 MB at most. */
    const val COMPACT_AT = MAX_ENTRIES + MAX_ENTRIES / 2

    fun line(e: ActionEntry): String = JsonObject().apply {
        addProperty("id", e.id)
        addProperty("at", e.at)
        addProperty("kind", e.kind.name)
        if (e.title.isNotEmpty()) addProperty("title", e.title)
        e.topic?.let { addProperty("topic", it) }
        e.consoleId?.let { addProperty("console", it) }
        e.fileName?.let { addProperty("file", it) }
        e.opId?.let { addProperty("op", it) }
        e.reason?.let { addProperty("reason", it) }
        if (e.counts.isNotEmpty()) add("c", JsonObject().apply { e.counts.forEach { (k, v) -> addProperty(k, v) } })
        if (e.count > 0) addProperty("n", e.count)
        if (e.bytes > 0) addProperty("bytes", e.bytes)
        if (e.undone) addProperty("undone", true)
    }.toString()

    fun parseLine(line: String): ActionEntry? = runCatching {
        val o = JsonParser.parseString(line).asJsonObject
        val at = o.get("at").asLong
        ActionEntry(
            id = o.get("id")?.asString ?: "t$at",
            at = at,
            kind = ActionKind.fromName(o.get("kind")?.asString),
            title = o.get("title")?.asString.orEmpty(),
            topic = o.get("topic")?.asString,
            consoleId = o.get("console")?.asString,
            fileName = o.get("file")?.asString,
            opId = o.get("op")?.asString,
            reason = o.get("reason")?.asString,
            counts = o.getAsJsonObject("c")?.entrySet()?.associate { it.key to it.value.asInt }.orEmpty(),
            count = o.get("n")?.asInt ?: 0,
            bytes = o.get("bytes")?.asLong ?: 0L,
            undone = o.get("undone")?.asBoolean ?: false
        )
    }.getOrNull()

    /** All readable entries of a file's lines, oldest first; broken lines (a cut-off last write) are skipped. */
    fun parse(lines: Sequence<String>): List<ActionEntry> = lines.filter { it.isNotBlank() }.mapNotNull(::parseLine).toList()

    /** The newest [MAX_ENTRIES] of [entries] (oldest first), without those older than [MAX_AGE_MS] before [now]. */
    fun trim(entries: List<ActionEntry>, now: Long): List<ActionEntry> {
        val recent = entries.filter { it.at >= now - MAX_AGE_MS }
        return if (recent.size <= MAX_ENTRIES) recent else recent.takeLast(MAX_ENTRIES)
    }
}

/**
 * The history file itself. Lines are appended; a rewrite (trim, clear, an undo mark) writes a
 * temporary file, syncs it to disk and renames it over the old one, so a crash leaves either the
 * old or the new file, never half of one. A line cut off by a crash is skipped on reading and the
 * next append starts on a fresh line. Not thread-safe: [com.cortinadev.dogmatix.data.service.ActionLogService]
 * is its only writer.
 */
class ActionLogFile(private val file: File) {
    private val tmp = File(file.parentFile, file.name + ".tmp")

    /** Lines in the file (readable or not), to know when to compact. */
    var lines: Int = 0
        private set

    /** Reads the file, oldest first. A leftover temporary file (a crash during a rewrite) is discarded: the rename never happened. */
    fun load(): List<ActionEntry> {
        runCatching { if (tmp.exists()) tmp.delete() }
        if (!file.exists()) { lines = 0; return emptyList() }
        val text = file.readText()
        lines = text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0
        // A cut-off last line: close it so the next append is not glued to it.
        if (text.isNotEmpty() && !text.endsWith('\n')) FileOutputStream(file, true).use { it.write('\n'.code) }
        return ActionLogFormat.parse(text.lineSequence())
    }

    fun append(entry: ActionEntry) {
        FileOutputStream(file, true).use { it.write((ActionLogFormat.line(entry) + "\n").toByteArray()) }
        lines++
    }

    /** Replaces the whole file with [entries] (oldest first), atomically. */
    fun rewrite(entries: List<ActionEntry>) {
        if (entries.isEmpty()) {
            // A failed atomic rename may have left a full copy of the private history here.
            if (tmp.exists() && !tmp.delete()) throw IOException("Temporary history could not be cleared")
            if (file.exists() && !file.delete()) throw IOException("History could not be cleared")
            lines = 0
            return
        }
        FileOutputStream(tmp).use { out ->
            out.write(entries.joinToString("") { ActionLogFormat.line(it) + "\n" }.toByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        lines = entries.size
    }
}
