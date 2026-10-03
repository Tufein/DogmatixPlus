package com.cortinadev.dogmatix.data.state

import android.content.Context
import com.cortinadev.dogmatix.util.FailureKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How the last scan of one source went: how many games it gave, or why it failed. */
data class SourceScanResult(
    val files: Int?,
    val failure: FailureKind? = null,
    val httpCode: Int? = null,
    val at: Long = System.currentTimeMillis()
)

/**
 * The outcome of the last scan of every source, shown under each URL in Sources so a source that
 * keeps failing (or suddenly gives nothing) stands out. Kept in a small file across restarts.
 */
@Singleton
class SourceScanResults @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = File(context.filesDir, "source_scan_results.json")
    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, SourceScanResult>> = _results.asStateFlow()

    fun record(consoleId: String, url: String, result: SourceScanResult) {
        _results.update { it + (key(consoleId, url) to result) }
        save(_results.value)
    }

    fun forget(consoleId: String) {
        _results.update { all -> all.filterKeys { !it.startsWith("$consoleId|") } }
        save(_results.value)
    }

    private fun load(): Map<String, SourceScanResult> = runCatching {
        if (!file.exists()) return emptyMap()
        JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("results").mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                o.get("key").asString to SourceScanResult(
                    files = o.get("files")?.takeUnless { it.isJsonNull }?.asInt,
                    failure = o.get("failure")?.takeUnless { it.isJsonNull }?.asString?.let { FailureKind.valueOf(it) },
                    httpCode = o.get("httpCode")?.takeUnless { it.isJsonNull }?.asInt,
                    at = o.get("at").asLong
                )
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save(map: Map<String, SourceScanResult>) {
        runCatching {
            val array = JsonArray()
            map.forEach { (k, r) ->
                array.add(JsonObject().apply {
                    addProperty("key", k)
                    r.files?.let { addProperty("files", it) }
                    r.failure?.let { addProperty("failure", it.name) }
                    r.httpCode?.let { addProperty("httpCode", it) }
                    addProperty("at", r.at)
                })
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonObject().apply { add("results", array) }.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        fun key(consoleId: String, url: String) = "$consoleId|$url"
    }
}
