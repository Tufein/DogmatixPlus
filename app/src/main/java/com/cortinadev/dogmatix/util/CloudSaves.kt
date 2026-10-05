package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A save or state as RomM lists it, with everything the per-game "Cloud saves" view shows (5.0).
 * Unlike [RemoteSaveFile] (what a sync pairs), slot saves and files missing on the server's disk
 * are kept: they are part of the game's history.
 */
data class CloudSaveEntry(
    val kind: SaveKind,
    val id: Int,
    val romId: Int,
    val fileName: String,
    val emulator: String?,
    /** As the server wrote it; compared the way the sync does ([SaveSyncPlanner.sameTime]). */
    val updatedAt: String,
    /** [updatedAt] as epoch millis; null when the server gave nothing readable. */
    val updatedMillis: Long?,
    val size: Long,
    val downloadPath: String,
    val contentHash: String? = null,
    /** Slot of a slot save (RomM 5: a history other clients keep); null for the plain save a sync uses. */
    val slot: String? = null,
    val missingFromFs: Boolean = false,
    /** Path (or absolute URL) of the screenshot RomM keeps with it; null when it has none. */
    val screenshotPath: String? = null,
    /** The device that wrote it, when the server says so; null otherwise. */
    val device: String? = null
) {
    /** The plain save a sync pairs with a device file. */
    val syncable: Boolean get() = slot == null && !missingFromFs

    /** "Thor", else the emulator ("mGBA"), else null: the "· Thor" part of "2 h ago · Thor". */
    val via: String? get() = device ?: emulator

    fun toRemote(): RemoteSaveFile = RemoteSaveFile(kind, id, romId, fileName, emulator, updatedAt, size, downloadPath, contentHash)
}

/** One server version in the per-game list. */
data class CloudSaveVersion(
    val entry: CloudSaveEntry,
    /** The newest plain save of its name: what a sync keeps on the devices. */
    val current: Boolean,
    /** The device holds exactly this version (the last sync left both sides equal). */
    val onDevice: Boolean,
    /** Full URL of its screenshot, when RomM has one. */
    val screenshotUrl: String? = null
)

/** How a device save of the game stands against the server. */
enum class DeviceSaveState { IN_SYNC, DEVICE_NEWER, SERVER_NEWER, BOTH_CHANGED, NOT_ON_SERVER, UNKNOWN }

data class DeviceSave(val local: LocalSaveFile, val state: DeviceSaveState, val server: CloudSaveEntry?) {
    /** "Upload now" makes sense: the device has something the server does not. */
    val canUpload: Boolean
        get() = state == DeviceSaveState.DEVICE_NEWER || state == DeviceSaveState.NOT_ON_SERVER ||
            state == DeviceSaveState.BOTH_CHANGED || state == DeviceSaveState.UNKNOWN
}

/** A copy kept in `files/save-backups/<stamp>/<saves|states>/<path>` before a file was replaced. */
data class SafetyCopy(
    val kind: SaveKind,
    /** Path below the picked folder, as the device file had it ("mGBA/Game.srm"). */
    val path: String,
    /** Path below `save-backups` ("20261003-120000/saves/mGBA/Game.srm"). */
    val relative: String,
    val takenAt: Long,
    val size: Long
) {
    val name: String get() = path.substringAfterLast('/')
}

/** Where "Restore this version" writes. */
sealed interface RestoreTarget {
    data class Path(val path: String) : RestoreTarget
    /** Several device files fit and none can be picked safely. */
    data object Ambiguous : RestoreTarget
    /** No folder is picked for this kind of file. */
    data object NoFolder : RestoreTarget
}

/** What a restore or upload from the per-game view came to. */
sealed interface CloudSaveResult {
    /** Done; [path] is the device file (restore) or the file name (upload). */
    data class Done(val path: String) : CloudSaveResult
    data class Failed(val message: String) : CloudSaveResult
    /** A save sync is running: nothing was touched. */
    data object Busy : CloudSaveResult
}

/**
 * The per-game cloud saves view: reads RomM's save / state listings defensively (RomM 3.10 to 5;
 * arrays or `{items}`, numbers as strings, fields missing), sorts the versions, ties device files and
 * safety copies to a game and picks where a restore goes. Pure JVM for the tests.
 */
object CloudSaves {

    /** Times closer than this count as the same moment (clocks of device and server differ a little). */
    private const val SAME_WINDOW_MS = 2_000L

    private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    // ---- Parsing --------------------------------------------------------------------------------

    /**
     * The objects of a listing: a bare array, or an object holding one under any of [keys]
     * (`items` for paged servers, `saves` / `states`, `user_saves` / `user_states` of a ROM).
     */
    fun items(json: JsonElement?, vararg keys: String): List<JsonObject> {
        val array: JsonArray = when {
            json == null || json.isJsonNull -> return emptyList()
            json.isJsonArray -> json.asJsonArray
            json.isJsonObject -> {
                val obj = json.asJsonObject
                (keys.toList() + "items").firstNotNullOfOrNull { k -> obj.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
                    ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { it as? JsonObject }
    }

    /** The saves (or states) of a listing; entries that cannot be read are skipped, never thrown on. */
    fun parse(kind: SaveKind, json: JsonElement?): List<CloudSaveEntry> =
        items(json, kind.apiPath, "user_${kind.apiPath}").mapNotNull { runCatching { entry(kind, it) }.getOrNull() }

    /** One save / state object; null without an id, a ROM id or a file name. */
    fun entry(kind: SaveKind, o: JsonObject): CloudSaveEntry? {
        val id = o.int("id") ?: return null
        val romId = o.int("rom_id") ?: (o.get("rom") as? JsonObject)?.int("id") ?: return null
        val name = o.string("file_name") ?: o.string("fs_name") ?: return null
        val updated = o.time("updated_at") ?: o.time("created_at")
        return CloudSaveEntry(
            kind = kind,
            id = id,
            romId = romId,
            fileName = name,
            emulator = o.string("emulator"),
            updatedAt = updated?.first.orEmpty(),
            updatedMillis = updated?.second,
            size = o.long("file_size_bytes") ?: o.long("size") ?: 0L,
            downloadPath = o.string("download_path").orEmpty(),
            contentHash = o.string("content_hash") ?: o.string("md5_hash"),
            slot = o.string("slot"),
            missingFromFs = o.bool("missing_from_fs") == true,
            screenshotPath = screenshotOf(o),
            device = deviceOf(o)
        )
    }

    /** `screenshot`: an object with `download_path` (or `file_path` + `file_name`), or a plain path. */
    private fun screenshotOf(o: JsonObject): String? {
        val shot = o.get("screenshot") ?: return o.string("screenshot_path")
        return when {
            shot.isJsonObject -> {
                val s = shot.asJsonObject
                s.string("download_path") ?: s.string("url")
                    ?: run {
                        val dir = s.string("file_path")
                        val file = s.string("file_name")
                        if (dir != null && file != null) "/api/raw/assets/${dir.trim('/')}/$file" else null
                    }
            }
            shot.isJsonPrimitive -> runCatching { shot.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /** RomM 5 can say which device wrote a save; several shapes are accepted. */
    private fun deviceOf(o: JsonObject): String? {
        o.string("device_name")?.let { return it }
        val device = o.get("device")
        if (device is JsonObject) return device.string("name") ?: device.string("device_name") ?: device.string("hostname")
        if (device is JsonPrimitive && device.isString) return device.asString.trim().takeIf { it.isNotEmpty() }
        return o.string("uploaded_by_device") ?: o.string("last_device")
    }

    /** `fs_name` and `platform_id` of `GET /api/roms/{id}`; null when either is missing. */
    fun romFileAndPlatform(json: JsonElement?): Pair<String, Int>? {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val name = o.string("fs_name") ?: o.string("file_name") ?: return null
        val platform = o.int("platform_id") ?: (o.get("platform") as? JsonObject)?.int("id") ?: return null
        return name to platform
    }

    // ---- URLs -----------------------------------------------------------------------------------

    /** Absolute URL of a server path ([base] has no trailing slash); absolute URLs pass through. */
    fun absoluteUrl(base: String, path: String): String {
        val p = path.trim()
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return encodeUrl(p)
        return base.trimEnd('/') + encodeUrl(if (p.startsWith("/")) p else "/$p")
    }

    fun screenshotUrl(base: String, entry: CloudSaveEntry): String? =
        entry.screenshotPath?.takeIf { it.isNotBlank() && base.isNotBlank() }?.let { absoluteUrl(base, it) }

    /**
     * RomM's paths are raw file paths (`/api/raw/assets/users/…/Pokémon Emerald (USA).png`): spaces,
     * `#` and non-ASCII characters are percent-encoded; the query string and valid `%xx` stay as they are.
     */
    fun encodeUrl(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        return encodePart(path, keepHash = false) + encodePart(query, keepHash = true)
    }

    private fun encodePart(part: String, keepHash: Boolean): String {
        val sb = StringBuilder(part.length + 16)
        var i = 0
        while (i < part.length) {
            val c = part[i]
            when {
                c == '%' && i + 2 <= part.lastIndex && isHex(part[i + 1]) && isHex(part[i + 2]) -> sb.append(c)
                c == '%' -> sb.append("%25")
                c == '#' && !keepHash -> sb.append("%23")
                c.code in 0x21..0x7E && c !in "\"<>\\^`{|}" -> sb.append(c)
                else -> {
                    // Whole code point (surrogate pairs included), UTF-8 encoded.
                    val cp = part.codePointAt(i)
                    String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { b ->
                        val v = b.toInt() and 0xFF
                        sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                    }
                    i += Character.charCount(cp)
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private const val HEX = "0123456789ABCDEF"

    // ---- The game -------------------------------------------------------------------------------

    /** The name a game's saves are named after: the library file name without its extension. */
    fun gameStem(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        // Only names that are really %-encoded are decoded; a plain "+" stays a "+" ("Mario + Luigi").
        val decoded = if ('%' in base) FileParsingUtils.decodeUrlEncodedFileName(base.replace("+", "%2B")) else base
        return SaveSyncPlanner.romStem(decoded.trim())
    }

    /** Whether a save file named [saveName] is a save of the game with stem [gameStem] (`Game (USA).state1` → `Game (USA)`). */
    fun belongsToGame(saveName: String, gameStem: String): Boolean {
        if (gameStem.isBlank()) return false
        return SaveSyncPlanner.stems(saveName).any { it.equals(gameStem, ignoreCase = true) }
    }

    // ---- Server versions ------------------------------------------------------------------------

    /**
     * The game's server versions, newest first. [records] are the sync's records and [locals] the
     * device listing: together they tell which version the device holds right now.
     */
    fun versions(
        entries: List<CloudSaveEntry>,
        records: Collection<SaveSyncRecord> = emptyList(),
        locals: List<LocalSaveFile> = emptyList(),
        baseUrl: String = ""
    ): List<CloudSaveVersion> {
        val currentIds = SaveSyncPlanner.latestPerName(entries.filter { it.syncable }.map { it.toRemote() })
            .map { it.kind to it.id }.toSet()
        val localByKey = locals.associateBy { SaveSyncPlanner.key(it.kind, it.path) }
        val onDevice = records.filter { r ->
            val local = localByKey[SaveSyncPlanner.key(r.kind, r.path)] ?: return@filter false
            val entry = entries.firstOrNull { it.kind == r.kind && it.id == r.remoteId } ?: return@filter false
            !localChanged(local, r) && !remoteChanged(entry, r)
        }.map { it.kind to it.remoteId }.toSet()
        return entries.distinctBy { it.kind to it.id }
            .sortedWith(compareByDescending<CloudSaveEntry> { it.updatedMillis ?: Long.MIN_VALUE }.thenByDescending { it.id })
            .map { e ->
                CloudSaveVersion(e, (e.kind to e.id) in currentIds, (e.kind to e.id) in onDevice, screenshotUrl(baseUrl, e))
            }
    }

    /** The newest plain server save of [kind] named [fileName] (for [romId], when known). */
    fun latest(entries: List<CloudSaveEntry>, kind: SaveKind, fileName: String, romId: Int? = null): CloudSaveEntry? =
        entries.filter { it.syncable && it.kind == kind && it.fileName.equals(fileName, ignoreCase = true) && (romId == null || it.romId == romId) }
            .maxWithOrNull(compareBy<CloudSaveEntry> { it.updatedMillis ?: Long.MIN_VALUE }.thenBy { it.id })

    // ---- Device files ---------------------------------------------------------------------------

    /**
     * The device's saves of the game (named after it, or tied to [romId] by an earlier sync) and how
     * each stands against the server.
     */
    fun deviceSaves(
        locals: List<LocalSaveFile>,
        gameStem: String,
        romId: Int?,
        records: Map<String, SaveSyncRecord>,
        entries: List<CloudSaveEntry>
    ): List<DeviceSave> = locals.mapNotNull { local ->
        val record = records[SaveSyncPlanner.key(local.kind, local.path)]
        val ours = belongsToGame(local.name, gameStem) || (romId != null && record?.romId == romId)
        if (!ours) return@mapNotNull null
        val paired = record?.let { r -> entries.firstOrNull { it.kind == local.kind && it.id == r.remoteId && it.syncable } }
        val server = paired ?: latest(entries, local.kind, local.name, romId)
        DeviceSave(local, stateOf(local, record?.takeIf { paired != null }, server), server)
    }.sortedByDescending { it.local.modified }

    fun stateOf(local: LocalSaveFile, record: SaveSyncRecord?, server: CloudSaveEntry?): DeviceSaveState {
        if (server == null) return DeviceSaveState.NOT_ON_SERVER
        if (record != null && record.remoteId == server.id) {
            val l = localChanged(local, record)
            val r = remoteChanged(server, record)
            return when {
                l && r -> DeviceSaveState.BOTH_CHANGED
                l -> DeviceSaveState.DEVICE_NEWER
                r -> DeviceSaveState.SERVER_NEWER
                else -> DeviceSaveState.IN_SYNC
            }
        }
        // Never synced: only the clocks can tell.
        val device = local.modified.takeIf { it > 0 }
        val remote = server.updatedMillis
        return when {
            device == null || remote == null -> DeviceSaveState.UNKNOWN
            kotlin.math.abs(device - remote) <= SAME_WINDOW_MS -> if (local.size == server.size) DeviceSaveState.IN_SYNC else DeviceSaveState.UNKNOWN
            device > remote -> DeviceSaveState.DEVICE_NEWER
            else -> DeviceSaveState.SERVER_NEWER
        }
    }

    /** The device file changed since the sync recorded it. */
    fun localChanged(local: LocalSaveFile, record: SaveSyncRecord): Boolean =
        local.size != record.localSize || local.modified != record.localModified

    /** The server file changed since the sync recorded it (same rules as the sync). */
    fun remoteChanged(entry: CloudSaveEntry, record: SaveSyncRecord): Boolean =
        !SaveSyncPlanner.sameTime(entry.updatedAt, record.remoteUpdatedAt) ||
            (record.remoteSize >= 0 && entry.size != record.remoteSize) ||
            (record.remoteHash != null && entry.contentHash != null && !entry.contentHash.equals(record.remoteHash, ignoreCase = true))

    /**
     * Whether the device file at [record]'s path holds exactly [server]'s bytes (the last sync left
     * them equal and neither changed since): then a copy of the device file is a copy of the server's.
     */
    fun deviceHolds(server: CloudSaveEntry, record: SaveSyncRecord?, local: LocalSaveFile?): Boolean =
        record != null && local != null && record.kind == server.kind && record.remoteId == server.id &&
            !localChanged(local, record) && !remoteChanged(server, record)

    /** Whether the server copy is still what the last sync saw (the device's changes may replace it as a sync would). */
    fun serverUnchangedSinceSync(server: CloudSaveEntry, record: SaveSyncRecord?): Boolean =
        record != null && record.kind == server.kind && record.remoteId == server.id && !remoteChanged(server, record)

    // ---- Restore --------------------------------------------------------------------------------

    /**
     * The device path "Restore this version" writes [version] to: the file a sync paired with it, else
     * the file of the same name (the emulator folder picks among several), else the game's own file
     * of the same type (slot saves and renamed files go where the emulator reads them), else a new
     * file where a sync would put it.
     */
    fun restoreTarget(
        version: CloudSaveEntry,
        gameStem: String,
        locals: List<LocalSaveFile>,
        records: Collection<SaveSyncRecord>,
        topFolders: Map<SaveKind, Set<String>>,
        noRootFolder: Set<SaveKind> = emptySet()
    ): RestoreTarget {
        val sameKind = locals.filter { it.kind == version.kind }
        records.firstOrNull { it.kind == version.kind && it.remoteId == version.id }?.let { r ->
            sameKind.firstOrNull { it.path.equals(r.path, ignoreCase = true) }?.let { return RestoreTarget.Path(it.path) }
        }
        val sameName = sameKind.filter { it.name.equals(version.fileName, ignoreCase = true) }
        pick(sameName, version.emulator)?.let { return RestoreTarget.Path(it.path) }
        if (sameName.size > 1) return RestoreTarget.Ambiguous
        val suffix = suffix(version.fileName)
        val ofGame = sameKind.filter { l ->
            suffix(l.name) == suffix && (
                belongsToGame(l.name, gameStem) ||
                    records.any { r -> r.kind == l.kind && r.path.equals(l.path, ignoreCase = true) && r.romId == version.romId }
                )
        }
        pick(ofGame, version.emulator)?.let { return RestoreTarget.Path(it.path) }
        if (ofGame.size > 1) return RestoreTarget.Ambiguous
        if (version.kind !in topFolders.keys) return RestoreTarget.NoFolder
        // A slot save's name carries its slot / date: the emulator reads the plain name.
        val name = if (version.slot != null && gameStem.isNotBlank() && suffix.isNotEmpty()) "$gameStem.$suffix" else version.fileName
        val folder = version.emulator?.let { e -> topFolders[version.kind].orEmpty().firstOrNull { it.equals(e, ignoreCase = true) } }
        return when {
            folder != null -> RestoreTarget.Path("$folder/$name")
            version.kind in noRootFolder -> RestoreTarget.NoFolder
            else -> RestoreTarget.Path(name)
        }
    }

    private fun pick(list: List<LocalSaveFile>, emulator: String?): LocalSaveFile? = when {
        list.size == 1 -> list.single()
        list.size > 1 -> list.filter { it.topFolder.equals(emulator.orEmpty(), ignoreCase = true) }.singleOrNull()
        else -> null
    }

    /** `Game.state1` → `state1`, `Game.srm` → `srm`, lower case; "" without one. */
    fun suffix(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * The sync record for a device file a restore just wrote:
     * - the restored version is the server's current one → both sides equal, in sync;
     * - the server has another current version of that name → paired with it but marked as changed
     *   on the device, so the next sync sends the restored version up (it becomes the current one;
     *   the caller has kept a copy of what it replaces);
     * - nothing of that name on the server → whatever was recorded stays.
     */
    fun recordAfterRestore(
        written: LocalSaveFile,
        restored: CloudSaveEntry?,
        latest: CloudSaveEntry?,
        existing: SaveSyncRecord?
    ): SaveSyncRecord? = when {
        latest == null -> existing
        restored != null && restored.kind == latest.kind && restored.id == latest.id ->
            SaveSyncEngine.record(written, latest.toRemote())
        else -> SaveSyncRecord(
            kind = written.kind, path = written.path, romId = latest.romId, remoteId = latest.id,
            remoteUpdatedAt = latest.updatedAt, localSize = -1, localModified = -1,
            remoteSize = latest.size, remoteHash = latest.contentHash
        )
    }

    // ---- Safety copies --------------------------------------------------------------------------

    /**
     * A file below `save-backups` ("20261003-120000/saves/mGBA/Game.srm"); null for anything else.
     * The stamp is local time ([zone]); [fallbackTime] (the file's date) is used when it cannot be read.
     */
    fun safetyCopy(relative: String, size: Long, fallbackTime: Long, zone: ZoneId): SafetyCopy? {
        val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val kind = SaveKind.entries.firstOrNull { it.apiPath == parts[1] } ?: return null
        val path = parts.drop(2).joinToString("/")
        if (!SaveSyncPlanner.isSyncable(path.substringAfterLast('/'))) return null
        val at = parseStamp(parts[0], zone) ?: fallbackTime
        return SafetyCopy(kind, path, parts.joinToString("/"), at, size)
    }

    /** `20261003-120000` (or `20261003-120000-2`) in [zone] → epoch millis. */
    fun parseStamp(stamp: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.parse(stamp.take(15), stampFormat).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** The game's safety copies, newest first. */
    fun safetyCopiesFor(
        copies: List<SafetyCopy>,
        gameStem: String,
        romId: Int?,
        records: Map<String, SaveSyncRecord>
    ): List<SafetyCopy> = copies.filter { c ->
        belongsToGame(c.name, gameStem) || (romId != null && records[SaveSyncPlanner.key(c.kind, c.path)]?.romId == romId)
    }.sortedWith(compareByDescending<SafetyCopy> { it.takenAt }.thenBy { it.path })

    // ---- Lenient JSON ---------------------------------------------------------------------------

    private fun JsonObject.prim(name: String): JsonPrimitive? = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.string(name: String): String? =
        prim(name)?.let { runCatching { it.asString }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.long(name: String): Long? = prim(name)?.let { p ->
        if (p.isNumber) runCatching { p.asNumber.toDouble() }.getOrNull()?.takeIf { !it.isNaN() }?.toLong()
        else runCatching { p.asString.trim() }.getOrNull()?.let { s -> s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() }
    }

    private fun JsonObject.int(name: String): Int? = long(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonObject.bool(name: String): Boolean? = prim(name)?.let { p ->
        when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> runCatching { p.asNumber.toInt() != 0 }.getOrNull()
            else -> when (runCatching { p.asString.trim().lowercase() }.getOrNull()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
        }
    }

    /** An ISO time (as the sync reads it) or epoch seconds / millis, with its millis. */
    private fun JsonObject.time(name: String): Pair<String, Long?>? {
        val p = prim(name) ?: return null
        if (p.isNumber) {
            val n = runCatching { p.asNumber.toDouble() }.getOrNull()?.takeIf { !it.isNaN() } ?: return null
            val millis = if (n < 100_000_000_000.0) (n * 1000).toLong() else n.toLong()
            return millis.toString() to millis
        }
        val s = runCatching { p.asString.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = s.toLongOrNull()
        if (numeric != null) return s to (if (numeric < 100_000_000_000L) numeric * 1000 else numeric)
        return s to SaveSyncPlanner.epochMillis(s)
    }
}
