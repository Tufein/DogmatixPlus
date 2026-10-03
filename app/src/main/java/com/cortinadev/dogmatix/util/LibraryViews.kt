package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/**
 * A saved set of library filters ("smart collection"): opened with one tap, and usable as an
 * ES-DE shortcut through its deep link.
 */
data class LibraryView(
    val id: String,
    val name: String,
    val query: String = "",
    val consoles: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val favouritesOnly: Boolean = false,
    val newOnly: Boolean = false,
    val collectionId: Long = 0L,
    val source: String = "ALL",
    val sort: String = "NAME_ASC"
) {
    /** `dogmatix://library?…` that opens this view (what a `.dgmtx` shortcut contains). */
    fun deepLink(): String = buildString {
        append("dogmatix://library?")
        val params = buildList {
            if (consoles.isNotEmpty()) add("console=" + enc(consoles.sorted().joinToString(",")))
            if (tags.isNotEmpty()) add("tag=" + enc(tags.sorted().joinToString(",")))
            if (query.isNotBlank()) add("q=" + enc(query))
            if (favouritesOnly) add("fav=1")
            if (newOnly) add("new=1")
            if (collectionId > 0) add("collection=$collectionId")
        }
        append(params.joinToString("&"))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object LibraryViews {
    fun toJson(views: List<LibraryView>): String = JsonArray().apply {
        views.forEach { v ->
            add(JsonObject().apply {
                addProperty("id", v.id); addProperty("name", v.name); addProperty("query", v.query)
                add("consoles", JsonArray().apply { v.consoles.forEach { add(it) } })
                add("tags", JsonArray().apply { v.tags.forEach { add(it) } })
                addProperty("fav", v.favouritesOnly); addProperty("new", v.newOnly)
                addProperty("collection", v.collectionId); addProperty("source", v.source); addProperty("sort", v.sort)
            })
        }
    }.toString()

    fun fromJson(json: String): List<LibraryView> = runCatching {
        JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                fun str(k: String, d: String = "") = o.get(k)?.takeUnless { it.isJsonNull }?.asString ?: d
                fun set(k: String) = (o.get(k) as? JsonArray)?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()
                LibraryView(
                    id = str("id"), name = str("name"), query = str("query"), consoles = set("consoles"), tags = set("tags"),
                    favouritesOnly = o.get("fav")?.asBoolean ?: false, newOnly = o.get("new")?.asBoolean ?: false,
                    collectionId = o.get("collection")?.asLong ?: 0L, source = str("source", "ALL"), sort = str("sort", "NAME_ASC")
                ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
