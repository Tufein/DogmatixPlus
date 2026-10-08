package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.SettingsKeys
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The parts of a backup file that are plain data, read and written by hand with fixed field
 * names. Reflection-based Gson would follow the field names of the classes, which R8 renames
 * in release builds — a backup made by one build would then not restore in another.
 *
 * Reading never throws on bad input: a malformed row or setting is skipped, so a hand-edited
 * or damaged file restores what it can instead of failing half-way.
 */
object BackupJson {

    const val FORMAT = "dogmatix-backup"
    const val VERSION = 1

    // ---- Settings -----------------------------------------------------------------------------

    /** Type tag per known setting; a backed-up value with another type is dropped (it would crash its reader). */
    private val expectedTypes: Map<String, String> = buildMap {
        listOf(
            SettingsKeys.SEPARATE_BY_CONSOLE, SettingsKeys.AUTO_UNZIP, SettingsKeys.SWAP_FACE_BUTTONS,
            SettingsKeys.ONBOARDING_DONE, SettingsKeys.TORBOX_ENABLED, SettingsKeys.ROMM_AUTO_UPLOAD,
            SettingsKeys.SAVE_SYNC_AUTO, SettingsKeys.ROMM_MARK_GAMES, SettingsKeys.SAVE_SYNC_DELETIONS,
            SettingsKeys.SAVE_SYNC_BACKGROUND, SettingsKeys.SAVE_SYNC_BG_WIFI_ONLY, SettingsKeys.SAVE_SYNC_BG_CHARGING,
            SettingsKeys.DOWNLOAD_WIFI_ONLY, SettingsKeys.DOWNLOAD_CHARGING_ONLY, SettingsKeys.DOWNLOAD_NIGHT_ONLY,
            SettingsKeys.UPDATE_PRE_RELEASES
        ).forEach { put(it.name, "b") }
        listOf(
            SettingsKeys.CONCURRENT_DOWNLOADS, SettingsKeys.METADATA_TIMEOUT_S, SettingsKeys.MAX_SEARCH_RESULTS,
            SettingsKeys.SAVE_SYNC_BG_INTERVAL_H, SettingsKeys.DOWNLOAD_NIGHT_START, SettingsKeys.DOWNLOAD_NIGHT_END
        ).forEach { put(it.name, "i") }
        put(SettingsKeys.LIMIT_SPEED.name, "f")
        listOf(
            SettingsKeys.DOWNLOAD_DIRECTORY, SettingsKeys.THEME_MODE, SettingsKeys.GAMEPAD_LAYOUT, SettingsKeys.ACCENT_COLOR,
            SettingsKeys.DEBRID_PROVIDER, SettingsKeys.TORBOX_API_KEY, SettingsKeys.REAL_DEBRID_API_KEY,
            SettingsKeys.ESDE_DIRECTORY, SettingsKeys.IISU_DIRECTORY, SettingsKeys.ROMM_URL, SettingsKeys.ROMM_TOKEN,
            SettingsKeys.SAVE_SYNC_SAVES_DIR, SettingsKeys.SAVE_SYNC_STATES_DIR, SettingsKeys.ROMM_TRUST_FINGERPRINT
        ).forEach { put(it.name, "s") }
        listOf(
            SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES, SettingsKeys.FAVORITE_LANGUAGES,
            SettingsKeys.ROMM_PLATFORM_MAP, SettingsKeys.CONSOLE_SCANNED_AT
        ).forEach { put(it.name, "ss") }
        putAll(CloudSettingKeys.TYPES)
        put("profiles", "s")
        put("active_profile", "s")
        put("profile_pin_hash", "s")
        put(VersionPreferences.PINNED_KEY, "s")
        put(VersionPreferences.OVERRIDES_KEY, "s")
    }

    /** Sets whose entries are `id:value`; their readers split on ':' and fail on anything else. */
    private val pairSets = setOf(
        SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES.name, SettingsKeys.ROMM_PLATFORM_MAP.name, SettingsKeys.CONSOLE_SCANNED_AT.name
    )

    /** `{"t": type, "v": value}` for a DataStore value; null for types a backup does not carry. */
    fun encodeSetting(value: Any): JsonObject? {
        val (type, json) = when (value) {
            is Boolean -> "b" to JsonPrimitive(value)
            is Int -> "i" to JsonPrimitive(value)
            is Long -> "l" to JsonPrimitive(value)
            // As text: the speed limit is +Infinity, which JSON numbers cannot hold.
            is Float -> "f" to JsonPrimitive(value.toString())
            is Double -> "d" to JsonPrimitive(value.toString())
            is String -> "s" to JsonPrimitive(value)
            is Set<*> -> "ss" to JsonArray().apply { value.filterIsInstance<String>().forEach { add(it) } }
            else -> return null
        }
        return JsonObject().apply { addProperty("t", type); add("v", json) }
    }

    /**
     * The typed value of a backed-up setting (Boolean, Int, Long, Float, Double, String or
     * Set<String>), or null when it is malformed, has the wrong type for a known setting, or
     * holds a value its reader could not cope with. Numbers are kept within what Settings offers.
     */
    fun decodeSetting(name: String, element: JsonElement?): Any? {
        // A secret that backups never carry (the WebDAV password) is ignored even when a file has it.
        if (CloudSettingKeys.isSecret(name)) return null
        val obj = element as? JsonObject ?: return null
        val type = (obj.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: return null
        val local = if (name.startsWith("personal:")) name.substringAfter(':').substringAfter(':') else name
        expectedTypes[local]?.let { if (it != type) return null }
        if (local.startsWith("fixed_version:") && type != "s") return null
        if (name == "smart_collection_rules" && type != "s") return null
        if (name == "offline_collections_quotas" && type != "ss") return null
        if (name == "offline_collections_reserve_gb" && type != "i") return null
        val v = obj.get("v") ?: return null
        val value: Any = runCatching {
            when (type) {
                "b" -> (v as JsonPrimitive).takeIf { it.isBoolean }?.asBoolean
                "i" -> (v as JsonPrimitive).takeIf { it.isNumber }?.asInt
                "l" -> (v as JsonPrimitive).takeIf { it.isNumber }?.asLong
                "f" -> (v as JsonPrimitive).asString.toFloat()
                "d" -> (v as JsonPrimitive).asString.toDouble()
                "s" -> (v as JsonPrimitive).takeIf { it.isString }?.asString
                "ss" -> (v as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }.toSet()
                else -> null
            }
        }.getOrNull() ?: return null
        return sanitize(name, value)
    }

    private fun sanitize(name: String, value: Any): Any? = when {
        name == SettingsKeys.CONCURRENT_DOWNLOADS.name -> (value as Int).coerceIn(1, 10)
        name == SettingsKeys.METADATA_TIMEOUT_S.name ->
            (value as Int).coerceIn(TorrentConstants.MIN_METADATA_TIMEOUT_S, TorrentConstants.MAX_METADATA_TIMEOUT_S)
        name == SettingsKeys.MAX_SEARCH_RESULTS.name -> (value as Int).coerceAtLeast(0)
        name == SettingsKeys.SAVE_SYNC_BG_INTERVAL_H.name -> (value as Int).coerceIn(1, 24)
        name == SettingsKeys.DOWNLOAD_NIGHT_START.name || name == SettingsKeys.DOWNLOAD_NIGHT_END.name -> (value as Int).coerceIn(0, 1439)
        name == SettingsKeys.LIMIT_SPEED.name -> (value as Float).let { if (it.isNaN() || it <= 0f) Float.POSITIVE_INFINITY else it }
        name in pairSets -> (value as Set<*>).filterIsInstance<String>()
            .filter { it.indexOf(':') > 0 && it.substringAfter(':').isNotEmpty() }.toSet()
        name in CloudSettingKeys.TYPES -> CloudSettingKeys.sanitize(name, value)
        else -> value
    }

    // ---- Favourites ---------------------------------------------------------------------------

    fun favouritesToJson(rows: List<FavouriteEntity>): JsonArray = JsonArray().apply {
        rows.forEach { f ->
            add(JsonObject().apply {
                addProperty("consoleId", f.consoleId)
                addProperty("fileName", f.fileName)
                addProperty("addedAt", f.addedAt)
                addProperty("profileId", f.profileId)
            })
        }
    }

    fun favouritesFromJson(array: JsonElement?): List<FavouriteEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            FavouriteEntity(
                consoleId = o.string("consoleId") ?: return@mapNotNull null,
                fileName = o.string("fileName") ?: return@mapNotNull null,
                addedAt = o.long("addedAt") ?: System.currentTimeMillis(),
                profileId = o.string("profileId").orEmpty()
            )
        }

    // ---- Wishlist -----------------------------------------------------------------------------

    fun wishlistToJson(rows: List<WishlistEntity>): JsonArray = JsonArray().apply {
        rows.forEach { w ->
            add(JsonObject().apply {
                addProperty("title", w.title)
                w.consoleId?.let { addProperty("consoleId", it) }
                addProperty("addedAt", w.addedAt)
                w.notifiedAt?.let { addProperty("notifiedAt", it) }
            })
        }
    }

    fun wishlistFromJson(array: JsonElement?): List<WishlistEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            WishlistEntity(
                title = o.string("title")?.trim()?.takeIf { it.length >= 2 } ?: return@mapNotNull null,
                consoleId = o.string("consoleId")?.takeIf { it.isNotBlank() },
                addedAt = o.long("addedAt") ?: System.currentTimeMillis(),
                notifiedAt = o.long("notifiedAt")
            )
        }

    // ---- Downloads list -----------------------------------------------------------------------

    /** Debrid ids are left out: they belong to transfers on the service that will not exist any more. */
    fun historyToJson(rows: List<DownloadHistoryEntity>): JsonArray = JsonArray().apply {
        rows.forEach { h ->
            add(JsonObject().apply {
                addProperty("fileName", h.fileName)
                addProperty("name", h.name)
                addProperty("consoleId", h.consoleId)
                addProperty("downloadUrl", h.downloadUrl)
                addProperty("fileSize", h.fileSize)
                addProperty("fileExtension", h.fileExtension)
                h.torrentFileIndex?.let { addProperty("torrentFileIndex", it) }
                h.torrentMagnet?.let { addProperty("torrentMagnet", it) }
                addProperty("status", h.status)
                addProperty("startedAt", h.startedAt)
                h.finishedAt?.let { addProperty("finishedAt", it) }
            })
        }
    }

    fun historyFromJson(array: JsonElement?): List<DownloadHistoryEntity> =
        (array as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val status = o.string("status")?.takeIf { s -> DownloadStatus.entries.any { it.name == s } } ?: return@mapNotNull null
            DownloadHistoryEntity(
                fileName = o.string("fileName") ?: return@mapNotNull null,
                name = o.string("name") ?: return@mapNotNull null,
                consoleId = o.string("consoleId") ?: return@mapNotNull null,
                downloadUrl = o.string("downloadUrl") ?: return@mapNotNull null,
                fileSize = o.long("fileSize") ?: 0L,
                fileExtension = o.string("fileExtension").orEmpty(),
                torrentFileIndex = o.long("torrentFileIndex")?.toInt(),
                torrentMagnet = o.string("torrentMagnet"),
                status = status,
                startedAt = o.long("startedAt") ?: 0L,
                finishedAt = o.long("finishedAt")
            )
        }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
}
