package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Only device-wide transfer settings; a preset never contains paths, accounts or profile data. */
data class DownloadPresetOptions(
    /** Shared transfer limit in KB/s, matching SettingsDataStore. Infinity means unlimited. */
    val limitSpeed: Float = Float.POSITIVE_INFINITY,
    val concurrentDownloads: Int = Constants.DEFAULT_CONCURRENT_DOWNLOADS,
    val perServerLimit: Int = 0,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val nightOnly: Boolean = false,
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 7 * 60,
    val speedLimitDayOnly: Boolean = false
) {
    fun bounded(): DownloadPresetOptions = copy(
        limitSpeed = if (!limitSpeed.isFinite() || limitSpeed <= 0f) Float.POSITIVE_INFINITY
            else limitSpeed.coerceIn(1f, DownloadPresets.MAX_SPEED_KB),
        concurrentDownloads = concurrentDownloads.coerceIn(1, 10),
        perServerLimit = perServerLimit.coerceIn(0, 10),
        nightStart = nightStart.coerceIn(0, 1439), nightEnd = nightEnd.coerceIn(0, 1439)
    )
}

data class DownloadPreset(val id: String, val name: String, val options: DownloadPresetOptions) {
    val builtIn: Boolean get() = id == DownloadPresets.DAYTIME_ID || id == DownloadPresets.NIGHT_ID
}

object DownloadPresets {
    const val KEY = "download_presets_v1"
    const val DAYTIME_ID = "builtin-daytime-quiet"
    const val NIGHT_ID = "builtin-night-maximum"
    const val MAX_CUSTOM = 30
    const val MAX_NAME = 60
    const val MAX_SPEED_KB = 5000f
    private const val MAX_JSON_LENGTH = 128 * 1024

    val builtIns = listOf(
        DownloadPreset(DAYTIME_ID, "", DownloadPresetOptions(limitSpeed = 1000f,
            concurrentDownloads = 1, perServerLimit = 1, wifiOnly = true)),
        DownloadPreset(NIGHT_ID, "", DownloadPresetOptions(concurrentDownloads = 4,
            perServerLimit = 2, wifiOnly = true, chargingOnly = true, nightOnly = true))
    )

    /** Built-ins keep stable identities so their saved overrides survive locale changes. */
    fun all(saved: List<DownloadPreset>): List<DownloadPreset> {
        val clean = saved.filter { it.id.isNotBlank() && it.id.length <= 80 && (it.builtIn || it.name.isNotBlank()) }
            .distinctBy { it.id }
        return builtIns.map { original -> clean.firstOrNull { it.id == original.id } ?: original } +
            clean.filterNot { it.builtIn }.take(MAX_CUSTOM)
    }

    /** Infinity is encoded as JSON null, never as invalid JSON's Infinity or a lossy numeric zero. */
    fun encode(saved: List<DownloadPreset>): String = JsonArray().apply {
        saved.forEach { preset ->
            val options = preset.options.bounded()
            add(JsonObject().apply {
                addProperty("id", preset.id); addProperty("name", preset.name.trim().take(MAX_NAME))
                if (options.limitSpeed.isFinite()) addProperty("speedKb", options.limitSpeed)
                else add("speedKb", com.google.gson.JsonNull.INSTANCE)
                addProperty("concurrent", options.concurrentDownloads); addProperty("perServer", options.perServerLimit)
                addProperty("wifi", options.wifiOnly); addProperty("charging", options.chargingOnly)
                addProperty("night", options.nightOnly); addProperty("nightStart", options.nightStart)
                addProperty("nightEnd", options.nightEnd); addProperty("dayLimit", options.speedLimitDayOnly)
            })
        }
    }.toString()

    /** One damaged row cannot hide other saved presets or crash Settings. */
    fun decode(json: String?): List<DownloadPreset> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_LENGTH) return emptyList()
        return runCatching {
            JsonParser.parseString(json).asJsonArray.take(MAX_CUSTOM + builtIns.size).mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    fun str(key: String) = obj.get(key)?.asString.orEmpty()
                    fun bool(key: String) = obj.get(key)?.asBoolean ?: false
                    fun int(key: String, default: Int) = obj.get(key)?.asInt ?: default
                    val id = str("id")
                    val name = str("name").trim().take(MAX_NAME)
                    val speed = obj.get("speedKb")?.takeUnless { it.isJsonNull }?.asFloat ?: Float.POSITIVE_INFINITY
                    DownloadPreset(id, name, DownloadPresetOptions(speed,
                        int("concurrent", Constants.DEFAULT_CONCURRENT_DOWNLOADS), int("perServer", 0),
                        bool("wifi"), bool("charging"), bool("night"), int("nightStart", 1380),
                        int("nightEnd", 420), bool("dayLimit")).bounded())
                        .takeIf { id.isNotBlank() && id.length <= 80 && (it.builtIn || name.isNotBlank()) }
                }.getOrNull()
            }.distinctBy { it.id }.let { rows ->
                rows.filter { it.builtIn } + rows.filterNot { it.builtIn }.take(MAX_CUSTOM)
            }
        }.getOrDefault(emptyList())
    }

    /** Canonicalizes backup input, discarding unexpected fields and malformed rows. */
    fun backupValue(json: String): String? {
        if (json.length > MAX_JSON_LENGTH) return null
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return null
        val decoded = decode(json)
        if (!array.isEmpty && decoded.isEmpty()) return null
        return encode(decoded)
    }
}
