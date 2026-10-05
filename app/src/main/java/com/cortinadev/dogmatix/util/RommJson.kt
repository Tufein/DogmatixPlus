package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Forgiving readers for RomM's JSON (5.0). RomM 3.x, 4.x and 5.x name and type some fields
 * differently (numbers sent as strings, lists wrapped in `{"items": [...]}`, objects that are
 * `null`), so every reader here returns null instead of throwing on a missing or odd value.
 */
object RommJson {

    /** The objects of a list payload: a bare array, or the `items` (or `data`) array of an object. */
    fun items(json: JsonElement?): List<JsonObject> {
        val array: JsonArray = when {
            json == null || json.isJsonNull -> return emptyList()
            json.isJsonArray -> json.asJsonArray
            json.isJsonObject -> {
                val o = json.asJsonObject
                (o.get("items") as? JsonArray) ?: (o.get("data") as? JsonArray) ?: (o.get("results") as? JsonArray) ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { it as? JsonObject }
    }

    /** [json] as an object, or null. */
    fun obj(json: JsonElement?): JsonObject? = json?.takeIf { it.isJsonObject }?.asJsonObject

    /** The child object [name], or null when it is missing, null or not an object. */
    fun JsonObject.child(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    /** Follows [path] through nested objects (`SYSTEM` → `VERSION`). */
    fun JsonObject.at(vararg path: String): JsonElement? {
        var current: JsonElement = this
        for (name in path) {
            current = (current as? JsonObject)?.get(name) ?: return null
        }
        return current.takeUnless { it.isJsonNull }
    }

    /** A string value; numbers and booleans are given as text, anything else is null. */
    fun JsonObject.text(name: String): String? = primitive(get(name))?.asString?.trim()?.takeIf { it.isNotEmpty() }

    fun JsonObject.int(name: String): Int? = number(get(name))?.let { if (it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) it.toInt() else null }

    fun JsonObject.long(name: String): Long? = number(get(name))?.toLong()

    fun JsonObject.double(name: String): Double? = number(get(name))

    /** A boolean; also accepts `1`/`0` and the strings "true"/"false". */
    fun JsonObject.bool(name: String): Boolean? = boolOf(get(name))

    /** A list of strings: plain strings, or objects carrying a `name` (IGDB style `[{"name": "RPG"}]`). */
    fun JsonObject.strings(name: String): List<String>? {
        val array = get(name) as? JsonArray ?: return null
        return array.mapNotNull { e ->
            when {
                e is JsonPrimitive -> e.asString.trim()
                e is JsonObject -> e.text("name") ?: e.text("title")
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }

    fun primitive(e: JsonElement?): JsonPrimitive? = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    fun number(e: JsonElement?): Double? {
        val p = primitive(e) ?: return null
        val d = when {
            p.isNumber -> runCatching { p.asDouble }.getOrNull()
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    fun boolOf(e: JsonElement?): Boolean? {
        val p = primitive(e) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> runCatching { p.asDouble != 0.0 }.getOrNull()
            p.isString -> when (p.asString.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    /**
     * A full URL for something the server serves: absolute URLs stay, `/paths` get the server in
     * front, and a bare relative path is taken to live below [defaultPrefix] (RomM's resources).
     */
    fun serverUrl(baseUrl: String, path: String?, defaultPrefix: String = "/assets/romm/resources/"): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) return p
        if (p.startsWith("//")) return "https:$p"
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val tail = if (p.startsWith("/")) p else defaultPrefix.trimEnd('/') + "/" + p
        return base + tail.replace(" ", "%20")
    }
}
