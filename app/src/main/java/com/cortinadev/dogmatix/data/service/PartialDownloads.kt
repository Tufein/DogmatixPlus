package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which half-written files Dogmatix+ itself left behind, with the server's validator
 * (ETag / Last-Modified), in `files/partial_downloads.json`. Only a file listed here is continued
 * with a `Range` request; a file of the same name that came from elsewhere is never appended to.
 */
@Singleton
class PartialDownloads @Inject constructor(@param:ApplicationContext context: Context) {
    data class Record(val url: String, val validator: String?)

    private val file = File(context.filesDir, "partial_downloads.json")
    /** Read on first use, not while the app starts. */
    private val records: MutableMap<String, Record> by lazy { load() }

    @Synchronized fun get(fileName: String): Record? = records[fileName]

    @Synchronized fun put(fileName: String, record: Record) { records[fileName] = record; save() }

    @Synchronized fun remove(fileName: String) { if (records.remove(fileName) != null) save() }

    private fun load(): MutableMap<String, Record> = runCatching {
        val o = JsonParser.parseString(file.readText()).asJsonObject
        o.entrySet().associateTo(HashMap()) { (k, v) ->
            val r = v.asJsonObject
            k to Record(r.get("url")?.asString.orEmpty(), r.get("validator")?.takeUnless { it.isJsonNull }?.asString)
        }
    }.getOrElse { HashMap() }

    private fun save() {
        runCatching {
            val o = JsonObject()
            records.forEach { (k, r) -> o.add(k, JsonObject().apply { addProperty("url", r.url); r.validator?.let { addProperty("validator", it) } }) }
            file.writeText(o.toString())
        }
    }
}
