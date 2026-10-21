package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * SteamGridDB (steamgriddb.com, API v2) as a cover source (4.4.0): addresses, the JSON of the name
 * search and of the grid list, and which grid stands in for a box. Pure JVM for the tests; the
 * requests are in `SteamGridDbService`.
 *
 * Written from SteamGridDB's public API documentation, not tested against the real service. The
 * API key goes in an `Authorization: Bearer` header, never in an address. SteamGridDB art is made
 * by its community: a portrait grid looks like a box but is not always the official box.
 */
object SteamGridDb {

    const val API = "https://www.steamgriddb.com/api/v2/"
    const val HOST = "steamgriddb.com"

    /** Portrait sizes SteamGridDB documents for grids (600x900 is the usual one). */
    const val DIMENSIONS = "600x900,342x482,660x930"

    fun authorization(key: String): String = "Bearer $key"

    fun searchUrl(title: String): String = API + "search/autocomplete/" + CoverApis.pathSegment(title)

    fun gridsUrl(gameId: Long): String =
        API + "grids/game/$gameId?dimensions=$DIMENSIONS&types=static&nsfw=false&humor=any"

    data class Game(val id: Long, val name: String, val verified: Boolean)
    data class Grid(val url: String, val width: Int, val height: Int, val upvotes: Int, val downvotes: Int, val nsfw: Boolean, val humor: Boolean)

    private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    private fun JsonObject.long(name: String): Long? = text(name).toLongOrNull()
    private fun JsonObject.int(name: String): Int = text(name).toIntOrNull() ?: 0
    private fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.let { it.asString.equals("true", true) || it.asString == "1" } ?: false

    private fun data(json: JsonElement?): JsonArray? {
        val root = json as? JsonObject ?: return null
        if (root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return null
        return root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
    }

    /** `data` of `/search/autocomplete/{term}`, best first. */
    fun parseSearch(json: JsonElement?): List<Game> = data(json)?.mapNotNull { e ->
        (e as? JsonObject)?.let { g -> g.long("id")?.let { Game(it, g.text("name"), g.bool("verified")) } }
    }.orEmpty().filter { it.name.isNotEmpty() }

    /** `data` of `/grids/game/{id}`. */
    fun parseGrids(json: JsonElement?): List<Grid> = data(json)?.mapNotNull { e ->
        (e as? JsonObject)?.let { g ->
            val url = CoverApis.httpsOn(g.text("url"), HOST) ?: return@let null
            Grid(url, g.int("width"), g.int("height"), g.int("upvotes"), g.int("downvotes"), g.bool("nsfw"), g.bool("humor"))
        }
    }.orEmpty()

    /**
     * The game whose name is exactly [title] (normalised: tags, "the", accents and punctuation
     * ignored); null when no hit qualifies (the autocomplete is fuzzy). SteamGridDB has no platform
     * to check, so no extra word is allowed either: a SNES "Doom" must not take a PC game's grid.
     * Of several identical hits a verified one wins, else the first listed.
     */
    fun pickGame(title: String, games: List<Game>): Game? =
        CoverTitles.pick(title, games, names = { listOf(it.name) }, rank = { if (it.verified) 1 else 0 }, minLevel = 2)

    /**
     * The grid that stands in for the box: a portrait (taller than wide) one that is not marked
     * adult, with the most net votes; on a tie the one SteamGridDB listed first. One that more
     * people voted down than up is never taken. Null when none qualifies.
     */
    fun pickGrid(grids: List<Grid>): String? =
        grids.withIndex()
            .filter { (_, g) -> !g.nsfw && g.height > g.width && g.upvotes >= g.downvotes }
            .sortedWith(compareByDescending<IndexedValue<Grid>> { it.value.upvotes - it.value.downvotes }.thenBy { it.index })
            .firstOrNull()?.value?.url
}
