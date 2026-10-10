package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

/** Optional, reviewed directory sources. Importing a choice merges it; it never replaces sources. */
object RomsetCatalog {
    const val ASSET = "romsets.json"
    const val MAX_BYTES = 256 * 1024
    private val slug = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")

    data class Entry(
        val id: String,
        val manufacturerId: String,
        val manufacturerName: String,
        val consoleKey: String,
        val consoleName: String,
        val shortName: String,
        val aliases: List<String>,
        val folder: String,
        val fileCount: Int
    ) {
        val url: String get() = "https://buildbot.libretro.com/assets/cores/".toHttpUrl()
            .newBuilder().addPathSegment(folder).addPathSegment("").build().toString()
    }

    data class Catalog(val checkedAt: String, val publisher: String, val provenance: String, val entries: List<Entry>)

    fun parse(json: String): Catalog {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Catalog too large" }
        val root = JsonParser.parseString(json).asJsonObject
        require(integer(root, "schema") == 1) { "Unsupported catalog" }
        val checkedAt = string(root, "checkedAt")
        LocalDate.parse(checkedAt)
        val publisher = string(root, "publisher")
        val provenance = string(root, "provenance")
        require(publisher == "Libretro" && provenance == "https://github.com/libretro/libretro-content")
        val array = root.getAsJsonArray("entries")
        require(array.size() in 1..64)
        val entries = array.map { element ->
            val obj = element.asJsonObject
            val maker = string(obj, "manufacturerId").also { require(slug.matches(it)) }
            val key = string(obj, "consoleKey").also { require(slug.matches(it)) }
            val id = string(obj, "id").also { require(it == "${maker}_${key}") }
            val folder = string(obj, "folder").also {
                require(it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
            }
            val aliases = obj.getAsJsonArray("aliases").map { alias ->
                require(alias.isJsonPrimitive && alias.asJsonPrimitive.isString)
                alias.asString.also { require(it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl)) }
            }.distinct().also { require(it.size <= 16) }
            Entry(id, maker, string(obj, "manufacturerName"), key, string(obj, "consoleName"),
                string(obj, "shortName"), aliases, folder, integer(obj, "fileCount").also { require(it in 1..100_000) })
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        require(entries.map { it.url }.distinct().size == entries.size)
        return Catalog(checkedAt, publisher, provenance, entries.sortedBy { it.consoleName.lowercase() })
    }

    private fun string(obj: JsonObject, key: String): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl)) }
    }

    private fun integer(obj: JsonObject, key: String): Int {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asString.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key")
    }
}
