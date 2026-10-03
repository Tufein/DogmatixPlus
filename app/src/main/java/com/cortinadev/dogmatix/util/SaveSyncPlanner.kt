package com.cortinadev.dogmatix.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** RomM keeps two kinds of game progress: in-game saves and emulator save states. */
enum class SaveKind(val apiPath: String, val fileField: String, val legacyFileField: String) {
    SAVE("saves", "saveFile", "saves"),
    STATE("states", "stateFile", "states")
}

/** A save or state file on the device, under the picked saves / states folder. */
data class LocalSaveFile(
    val kind: SaveKind,
    /** Path below the picked folder, `/`-separated ("mGBA/Pokemon Emerald.srm"). */
    val path: String,
    val size: Long,
    val modified: Long
) {
    val name: String get() = path.substringAfterLast('/')
    /** First folder below the picked folder ("mGBA"), or "" for a file directly inside it. */
    val topFolder: String get() = if (path.contains('/')) path.substringBefore('/') else ""
}

/** A save or state as RomM lists it (`/api/saves`, `/api/states`). */
data class RemoteSaveFile(
    val kind: SaveKind,
    val id: Int,
    val romId: Int,
    val fileName: String,
    val emulator: String?,
    val updatedAt: String,
    val size: Long,
    val downloadPath: String,
    /** MD5 of the file as RomM lists it (saves on newer servers); null when unknown. */
    val contentHash: String? = null
)

/**
 * What both sides looked like right after the last successful sync of one file. Comparing
 * against it tells *which* side changed, so the clocks of the device and the server never
 * have to agree.
 */
data class SaveSyncRecord(
    val kind: SaveKind,
    val path: String,
    val romId: Int,
    val remoteId: Int,
    val remoteUpdatedAt: String,
    val localSize: Long,
    val localModified: Long,
    /** Server size and hash at that moment: a change within the same second still shows. */
    val remoteSize: Long = -1,
    val remoteHash: String? = null
)

/** One step of a sync. */
sealed interface SaveSyncAction {
    /** Only the device has a new version (or the server has none): send it. */
    data class Upload(val local: LocalSaveFile, val romId: Int?, val replacing: RemoteSaveFile?) : SaveSyncAction
    /** Only the server has a new version (or the device has none): fetch it to [path]. */
    data class Download(val remote: RemoteSaveFile, val path: String, val replacing: LocalSaveFile?) : SaveSyncAction
    /** Both exist and were never synced: equal bytes are simply recorded, different bytes are a conflict. */
    data class Compare(val local: LocalSaveFile, val remote: RemoteSaveFile) : SaveSyncAction
    /** Both sides changed since the last sync: the user decides. */
    data class Conflict(val local: LocalSaveFile, val remote: RemoteSaveFile) : SaveSyncAction
    data class InSync(val local: LocalSaveFile, val remote: RemoteSaveFile) : SaveSyncAction
    /** Several server files fit this device file; nothing is done until only one does. */
    data class Ambiguous(val local: LocalSaveFile) : SaveSyncAction
    /** A server file whose place on the device is taken by a file that belongs elsewhere. */
    data class Blocked(val remote: RemoteSaveFile, val path: String) : SaveSyncAction
}

/**
 * Decides, per file, what a save sync does. Pure: the service feeds it the folder listing,
 * the server listing and the records of the previous sync, and carries the actions out.
 *
 * Pairing a device file with a server file: the record of an earlier sync wins (RomM may
 * have cleaned the name up on upload); otherwise the same file name, and when several games
 * have a save of that name, the one whose emulator is the folder the file sits in.
 * Deletions are not synced: a file missing on one side is copied back from the other.
 */
object SaveSyncPlanner {

    /** Files that sit in save folders but are not progress: thumbnails, configs, our temporaries. */
    private val ignoredExtensions = setOf(
        "png", "jpg", "jpeg", "bmp", "webp", "gif", "txt", "cfg", "opt", "json", "xml", "ini",
        "log", "tmp", "bak", "nomedia", "db", "lpl"
    )

    /** RetroArch state names: `.state`, `.state1`…`.state999`, `.state.auto`. */
    private val stateName = Regex("""\.state(\d+|\.auto)?$""", RegexOption.IGNORE_CASE)

    fun isSyncable(name: String): Boolean {
        if (name.isBlank() || name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in ignoredExtensions
    }

    /** For a saves folder that is also the states folder: which of the two a file is. */
    fun kindOf(name: String): SaveKind = if (stateName.containsMatchIn(name)) SaveKind.STATE else SaveKind.SAVE

    /**
     * The game a save file belongs to, as name stems from most to least specific:
     * `Game (USA).state.auto` → [`Game (USA).state`, `Game (USA)`]. ROM names keep their dots
     * (`Dr. Mario`), so every shorter stem is tried until one matches a ROM.
     */
    fun stems(fileName: String): List<String> {
        val out = mutableListOf<String>()
        var current = fileName
        while (true) {
            val dot = current.lastIndexOf('.')
            if (dot <= 0) break
            current = current.substring(0, dot)
            if (current.isNotBlank()) out += current.trim()
        }
        return out.distinct()
    }

    /** `Game (USA).sfc` → `Game (USA)`; folder ROMs and names like `Dr. Mario` stay whole. */
    fun romStem(fsName: String): String {
        val dot = fsName.lastIndexOf('.')
        if (dot <= 0) return fsName
        val ext = fsName.substring(dot + 1)
        return if (ext.length in 1..6 && ext.all { it.isLetterOrDigit() }) fsName.substring(0, dot) else fsName
    }

    /** RomM's emulator field must be one plain folder name; anything else is not sent. */
    fun emulatorFor(local: LocalSaveFile): String? =
        local.topFolder.takeIf { it.isNotBlank() && it.length <= 50 && it.none { c -> c in "/\\:*?\"<>|" || c.isISOControl() } }

    fun plan(
        locals: List<LocalSaveFile>,
        remotes: List<RemoteSaveFile>,
        records: List<SaveSyncRecord>,
        /** Folders directly inside the picked folder, per kind (emulator / core folders). */
        topFolders: Map<SaveKind, Set<String>> = emptyMap()
    ): List<SaveSyncAction> {
        val actions = mutableListOf<SaveSyncAction>()
        val recordByPath = records.associateBy { key(it.kind, it.path) }
        val remoteById = remotes.associateBy { it.kind to it.id }
        val claimed = mutableSetOf<Pair<SaveKind, Int>>()
        val pairs = mutableListOf<Pair<LocalSaveFile, RemoteSaveFile>>()
        val unpaired = mutableListOf<LocalSaveFile>()
        val ambiguous = mutableListOf<LocalSaveFile>()

        // 1. Pairs an earlier sync established.
        val rest = mutableListOf<LocalSaveFile>()
        locals.forEach { local ->
            val record = recordByPath[key(local.kind, local.path)]
            val remote = record?.let { remoteById[local.kind to it.remoteId] }
            if (remote != null && (remote.kind to remote.id) !in claimed) {
                claimed += remote.kind to remote.id
                pairs += local to remote
            } else rest += local
        }
        // 2. Same file name (and, among several games, the emulator folder it sits in).
        rest.forEach { local ->
            val sameName = remotes.filter {
                it.kind == local.kind && (it.kind to it.id) !in claimed && it.fileName.equals(local.name, ignoreCase = true)
            }
            val pick = when {
                sameName.size == 1 -> sameName.single()
                sameName.size > 1 -> sameName.filter { it.emulator.orEmpty().equals(local.topFolder, ignoreCase = true) }
                    .singleOrNull()
                else -> null
            }
            when {
                pick != null -> { claimed += pick.kind to pick.id; pairs += local to pick }
                sameName.size > 1 -> ambiguous += local
                else -> unpaired += local
            }
        }

        pairs.forEach { (local, remote) ->
            val record = recordByPath[key(local.kind, local.path)]?.takeIf { it.remoteId == remote.id }
            actions += if (record == null) SaveSyncAction.Compare(local, remote) else {
                val localChanged = local.size != record.localSize || local.modified != record.localModified
                val remoteChanged = !sameTime(remote.updatedAt, record.remoteUpdatedAt) ||
                    (record.remoteSize >= 0 && remote.size != record.remoteSize) ||
                    (record.remoteHash != null && remote.contentHash != null && !remote.contentHash.equals(record.remoteHash, ignoreCase = true))
                when {
                    localChanged && remoteChanged -> SaveSyncAction.Conflict(local, remote)
                    localChanged -> SaveSyncAction.Upload(local, remote.romId, remote)
                    remoteChanged -> SaveSyncAction.Download(remote, local.path, local)
                    else -> SaveSyncAction.InSync(local, remote)
                }
            }
        }
        unpaired.forEach { local ->
            actions += SaveSyncAction.Upload(local, recordByPath[key(local.kind, local.path)]?.romId, null)
        }
        ambiguous.forEach { actions += SaveSyncAction.Ambiguous(it) }

        // 3. Server files the device does not have yet go into their emulator folder when the
        // device has one of that name, else straight into the picked folder.
        val localPaths = locals.map { key(it.kind, it.path) }.toSet()
        val downloads = remotes.filter { (it.kind to it.id) !in claimed }.map { remote ->
            val folder = remote.emulator?.let { emulator ->
                topFolders[remote.kind].orEmpty().firstOrNull { it.equals(emulator, ignoreCase = true) }
            }
            remote to (if (folder != null) "$folder/${remote.fileName}" else remote.fileName)
        }
        val targets = downloads.groupingBy { (remote, path) -> key(remote.kind, path) }.eachCount()
        downloads.forEach { (remote, path) ->
            val target = key(remote.kind, path)
            actions += if (target in localPaths || (targets[target] ?: 0) > 1) SaveSyncAction.Blocked(remote, path)
            else SaveSyncAction.Download(remote, path, null)
        }
        return actions
    }

    /** A ROM as RomM's search returns it, enough to tie a save file to it. */
    data class RomCandidate(val id: Int, val fsName: String, val platformSlug: String = "", val platformFsSlug: String = "")

    sealed interface RomMatch {
        data class Found(val romId: Int) : RomMatch
        data object NotFound : RomMatch
        data object Ambiguous : RomMatch
    }

    /**
     * The ROM [local] is the save of: the longest stem of its name that equals a ROM's file
     * name without extension. When that fits ROMs on several platforms, a folder in the save's
     * path named like the platform (`saves/gba/…`, `saves/snes/…`) picks one; otherwise the
     * save is left alone rather than attached to the wrong game.
     */
    fun matchRom(local: LocalSaveFile, candidates: List<RomCandidate>): RomMatch {
        val byStem = candidates.groupBy { romStem(it.fsName).lowercase() }
        val hits = stems(local.name).firstNotNullOfOrNull { stem -> byStem[stem.lowercase()] }
            ?.distinctBy { it.id }
            ?: return RomMatch.NotFound
        if (hits.size == 1) return RomMatch.Found(hits.single().id)
        val folders = local.path.split('/').dropLast(1).map(ConsoleFolderAliases::normalize).filter { it.isNotEmpty() }.toSet()
        val byFolder = hits.filter { rom ->
            listOf(rom.platformSlug, rom.platformFsSlug).map(ConsoleFolderAliases::normalize).any { it.isNotEmpty() && it in folders }
        }
        return if (byFolder.size == 1) RomMatch.Found(byFolder.single().id) else RomMatch.Ambiguous
    }

    /**
     * What to ask RomM's search for: the title words of the file name, without the extension,
     * the state suffix, `(USA)` / `[!]` tags or punctuation (RomM matches every word, and its
     * full-text index treats brackets and `|` as operators). [matchRom] does the exact check.
     */
    fun searchTerm(fileName: String): String =
        title(fileName).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(Regex("""\s+"""), " ")

    /**
     * Searches to try in turn until one finds the game: [searchTerm] (RomM 4 and later match
     * each word), the title with its punctuation (RomM 3 matches the text as one phrase, so
     * `Dr Mario` misses `Dr. Mario`), and the longest word on its own.
     */
    fun searchTerms(fileName: String): List<String> {
        val words = searchTerm(fileName)
        val phrase = title(fileName).replace("|", " ").trim().replace(Regex("""\s+"""), " ")
        val longest = words.split(' ').maxByOrNull { it.length }.orEmpty()
        return listOf(words, phrase, longest).filter { it.isNotBlank() }.distinct()
    }

    /** The file name without extension, state suffix and `(…)` / `[…]` tags. */
    private fun title(fileName: String): String {
        val withoutState = fileName.replace(stateName, "")
        val stem = if (withoutState != fileName) withoutState else withoutState.substringBeforeLast('.', withoutState)
        return stem.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ").trim()
    }

    /**
     * RomM lists every version it keeps; only the newest of each game + kind + name is synced,
     * and slot saves (a history kept by other clients, with a date in the name) are left alone.
     */
    fun latestPerName(remotes: List<RemoteSaveFile>): List<RemoteSaveFile> =
        remotes.groupBy { Triple(it.kind, it.romId, it.fileName.lowercase()) }
            .values.map { group -> group.maxBy { epochMillis(it.updatedAt) ?: Long.MIN_VALUE } }

    /** RomM timestamps (`2026-10-03T12:00:00.123456+00:00`, older servers without offset). */
    fun epochMillis(timestamp: String): Long? {
        val t = timestamp.trim()
        if (t.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /**
     * RomM answers an upload with the time to the microsecond but lists it to the second (as
     * the database stores it), so two times are the same when they fall in the same second.
     */
    fun sameTime(a: String, b: String): Boolean {
        val x = epochMillis(a)
        val y = epochMillis(b)
        return if (x != null && y != null) Math.floorDiv(x, 1000L) == Math.floorDiv(y, 1000L) else a == b
    }

    fun key(kind: SaveKind, path: String): String = kind.name + "|" + path.lowercase()
}
