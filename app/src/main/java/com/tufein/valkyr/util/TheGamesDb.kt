package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * TheGamesDB (api.thegamesdb.net) as a cover source (4.4.0): addresses, the console → platform
 * table and the JSON of `Games/ByGameName` and `Games/Images`. Pure JVM for the tests; the requests
 * are in `TheGamesDbService`.
 *
 * Written from TheGamesDB's public API documentation, not tested against the real service. The
 * API key travels as the `apikey` query parameter (that is how the service takes it), so these
 * addresses must never be logged; the service only ever sends them to api.thegamesdb.net.
 */
object TheGamesDb {

    const val API = "https://api.thegamesdb.net/v1/"
    const val HOST = "thegamesdb.net"

    /**
     * libretro-thumbnails repository (the app's console matching, see [LibretroThumbnails.systemFor])
     * → TheGamesDB platform id. The ids are the ones of TheGamesDB's public platforms list
     * (`/v1/Platforms`) as documented; the first sixteen are the same ones GameMetadataService
     * already uses for the game details. A console without an entry is searched without a platform
     * filter (the title matching alone decides); a wrong id would only make that console find nothing.
     * Not checked against a live response.
     */
    val PLATFORM_IDS: Map<String, Int> = mapOf(
        "Nintendo_-_Nintendo_Entertainment_System" to 7,
        "Nintendo_-_Super_Nintendo_Entertainment_System" to 6,
        "Nintendo_-_Game_Boy" to 4,
        "Nintendo_-_Game_Boy_Color" to 41,
        "Nintendo_-_Game_Boy_Advance" to 5,
        "Nintendo_-_Nintendo_64" to 3,
        "Nintendo_-_Nintendo_DS" to 8,
        "Nintendo_-_Nintendo_3DS" to 4912,
        "Nintendo_-_GameCube" to 2,
        "Nintendo_-_Wii" to 9,
        "Nintendo_-_Virtual_Boy" to 4918,
        "Sony_-_PlayStation" to 10,
        "Sony_-_PlayStation_2" to 11,
        "Sony_-_PlayStation_Portable" to 13,
        "Sega_-_Master_System_-_Mark_III" to 35,
        "Sega_-_Mega_Drive_-_Genesis" to 18,
        "Sega_-_Mega-CD_-_Sega_CD" to 21,
        "Sega_-_Dreamcast" to 16,
        "Sega_-_Saturn" to 17,
        "Sega_-_Game_Gear" to 20,
        "Sega_-_32X" to 33,
        "NEC_-_PC_Engine_-_TurboGrafx_16" to 34,
        "NEC_-_PC_Engine_CD_-_TurboGrafx-CD" to 4955,
        "SNK_-_Neo_Geo_Pocket" to 4922,
        "SNK_-_Neo_Geo_Pocket_Color" to 4923,
        "Bandai_-_WonderSwan" to 4925,
        "Bandai_-_WonderSwan_Color" to 4926,
        "Atari_-_2600" to 22,
        "Atari_-_7800" to 27,
        "Atari_-_Lynx" to 4924,
        "Atari_-_Jaguar" to 28,
        "Coleco_-_ColecoVision" to 31,
        "Mattel_-_Intellivision" to 32,
        "The_3DO_Company_-_3DO" to 25
    )

    fun platformId(consoleId: String): Int? = LibretroThumbnails.systemFor(consoleId)?.let { PLATFORM_IDS[it.repo] }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * A name search; `include=boxart` brings the box art of every game found in the same answer
     * (one request instead of two, TheGamesDB's monthly allowance is small). [platformId] narrows it.
     */
    fun searchUrl(key: String, title: String, platformId: Int?): String =
        API + "Games/ByGameName?apikey=" + enc(key) + "&name=" + enc(title) + "&include=boxart" +
            (platformId?.let { "&filter%5Bplatform%5D=$it" } ?: "")

    /** The box art of one game (used when the search answer did not include it). */
    fun imagesUrl(key: String, gameId: String): String =
        API + "Games/Images?apikey=" + enc(key) + "&games_id=" + enc(gameId) + "&filter%5Btype%5D=boxart"

    /** The platforms list: the cheapest call that checks a key and reports the allowance. */
    fun platformsUrl(key: String): String = API + "Platforms?apikey=" + enc(key)

    data class Game(val id: String, val title: String, val platform: Int?)
    data class Image(val type: String, val side: String, val filename: String)

    /**
     * One answer. [images] is by game id; [boxartIncluded] says the answer carried an `include.boxart`
     * block (a game missing from it then simply has no box art). [baseUrls] is by size name
     * (`large`, `medium`, `original`, …). [remaining] is the monthly allowance left, when sent.
     */
    data class Page(
        val games: List<Game>,
        val images: Map<String, List<Image>>,
        val baseUrls: Map<String, String>,
        val boxartIncluded: Boolean,
        val remaining: Int?
    )

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    private fun JsonObject.int(name: String): Int? = text(name).toIntOrNull()

    private fun imagesOf(data: JsonObject?): Map<String, List<Image>> {
        data ?: return emptyMap()
        return data.entrySet().associate { (id, list) ->
            id to (list as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { Image(it.text("type"), it.text("side"), it.text("filename")) }?.takeIf { it.filename.isNotEmpty() }
            }
        }
    }

    private fun baseUrlsOf(o: JsonObject?): Map<String, String> =
        o?.entrySet()?.mapNotNull { (k, v) -> (v as? com.google.gson.JsonPrimitive)?.asString?.let { k to it } }?.toMap().orEmpty()

    /** The answer of `Games/ByGameName` or `Games/Images`, or null when it is not JSON of that shape. */
    fun parse(json: JsonElement?): Page? {
        val root = json as? JsonObject ?: return null
        val data = root.obj("data") ?: return null
        val games = data.arr("games")?.mapNotNull { e ->
            (e as? JsonObject)?.let { g ->
                val id = g.text("id")
                if (id.isEmpty()) null else Game(id, g.text("game_title"), g.int("platform"))
            }
        }.orEmpty()
        val include = root.obj("include")?.obj("boxart")
        // The Images endpoint: data.base_url + data.images; the search with include: include.boxart.base_url + .data.
        val images = if (include != null) imagesOf(include.obj("data")) else imagesOf(data.obj("images"))
        val base = baseUrlsOf(include?.obj("base_url") ?: data.obj("base_url"))
        return Page(games, images, base, boxartIncluded = include != null, remaining = root.int("remaining_monthly_allowance"))
    }

    /** The game that is [title], preferring the one on [platformId]; null when no hit qualifies. */
    fun pick(title: String, page: Page, platformId: Int?): Game? =
        CoverTitles.pick(title, page.games, names = { listOf(it.title) }, rank = { if (platformId != null && it.platform == platformId) 1 else 0 })

    /**
     * The front box art of [images]: https on thegamesdb.net, at `large` size (else `medium`, else
     * `original`). Only a front cover; a back cover or a disc is no cover.
     */
    fun frontCover(images: List<Image>, baseUrls: Map<String, String>): String? {
        val front = images.firstOrNull { it.type.equals("boxart", true) && it.side.equals("front", true) } ?: return null
        val file = front.filename.trim().trimStart('/')
        if (file.isEmpty() || file.contains("..") || file.contains("://") || file.any { it.isISOControl() || it.isWhitespace() }) return null
        val base = listOf("large", "medium", "original").firstNotNullOfOrNull { baseUrls[it]?.takeIf { b -> b.isNotBlank() } } ?: return null
        return CoverApis.httpsOn(base.trimEnd('/') + "/" + file, HOST)
    }
}
