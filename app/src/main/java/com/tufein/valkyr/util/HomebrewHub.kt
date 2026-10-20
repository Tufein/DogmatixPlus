package com.tufein.valkyr.util

import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Homebrew Hub (https://hh.gbdev.io), the gbdev community archive of Game Boy and Game Boy Color
 * homebrew, as a library source. It publishes no directory listing: its API answers a search
 * with at most ten entries a page (`/api/search?platform=GB&page=N&page_elements=10`, newest
 * first), each entry being the manifest from the gbdev/database repository (title, developer,
 * licence, files). The ROM files are taken from that repository itself
 * (`raw.githubusercontent.com/gbdev/database/master/entries/<slug>/<file>`), which is where the
 * manifests say they live (`baserepo`). Both were checked by hand on 2026-10-15; nothing here was
 * tested on a device.
 *
 * Only entries whose licence allows passing the file on are kept ([licenceOf]); each becomes one
 * row (its default ROM) tagged with that licence and its author. Entries without a ROM file for
 * the console, ROM hacks and entries that come from another repository are skipped.
 */
object HomebrewHub {
    const val API = "https://hh3.gbdev.io/api/"
    private const val SEARCH = API + "search"
    /** Where the ROM files are downloaded from: the gbdev/database repository. */
    const val FILES = "https://raw.githubusercontent.com/gbdev/database/master/entries/"
    /** The `basepath` / `baserepo` of the entries [FILES] serves. */
    private const val BASE_PATH = "database-gb"
    private const val BASE_REPO = "https://github.com/gbdev/database"

    /** The API's largest page. */
    const val PAGE_SIZE = 10
    /** About 1,400 Game Boy and Game Boy Color entries were listed at the check; room to grow, not unbounded. */
    const val MAX_PAGES = 300
    const val MAX_PAGE_BYTES = 512 * 1024
    const val HOST = "hh3.gbdev.io"
    /** Raise when the rules for offering an entry change, so the next scan reads every page again. */
    const val PARSER_VERSION = 1

    /** The consoles the archive is read for, as the API names them. */
    enum class Platform(val code: String) { GB("GB"), GBC("GBC");
        companion object { fun of(code: String): Platform? = entries.firstOrNull { it.code == code } }
    }

    /** The source address a catalogue entry adds for [platform]. */
    fun sourceUrl(platform: Platform): String = SEARCH.toHttpUrl().newBuilder().addQueryParameter("platform", platform.code).build().toString()

    /** The platform of a Homebrew Hub source address, or null when [url] is not one. */
    fun platformOf(url: String): Platform? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host != HOST || parsed.encodedPath != "/api/search") return null
        if (parsed.querySize != 1 || parsed.username.isNotEmpty() || parsed.fragment != null) return null
        return parsed.queryParameter("platform")?.let(Platform::of)
    }

    fun isSource(url: String): Boolean = platformOf(url) != null

    /** Page [page] (from 1) of the search behind a source. */
    fun pageUrl(platform: Platform, page: Int): String {
        require(page in 1..MAX_PAGES)
        return SEARCH.toHttpUrl().newBuilder().addQueryParameter("platform", platform.code)
            .addQueryParameter("page", page.toString()).addQueryParameter("page_elements", PAGE_SIZE.toString()).build().toString()
    }

    /** One page of search results: [entries] are the raw manifests, read by [games]. */
    data class Page(val current: Int, val total: Int, val results: Int, val entries: List<JsonObject>)

    /**
     * Reads page [expected] strictly: anything but the API's page object (wrong page, too many
     * pages, a page that holds fewer or more entries than its place in the results says, not JSON)
     * is a failure of the whole source, like an unreadable listing.
     */
    fun parsePage(body: String, expected: Int): Page {
        require(body.length <= MAX_PAGE_BYTES) { "Page too large" }
        val root = JsonParser.parseString(body).asJsonObject
        val current = int(root, "page_current")
        val total = int(root, "page_total")
        val results = int(root, "results")
        val array = root.get("entries")
        require(array != null && array.isJsonArray) { "No entries" }
        val entries = array.asJsonArray.map { require(it.isJsonObject); it.asJsonObject }
        require(current == expected) { "Page $expected answered as $current" }
        require(total in 0..MAX_PAGES && results in 0..MAX_PAGES * PAGE_SIZE) { "Too many entries" }
        require(entries.size == minOf(PAGE_SIZE, maxOf(0, results - (current - 1) * PAGE_SIZE)) && (entries.isEmpty() || current <= total)) { "Unexpected page" }
        return Page(current, total, results, entries)
    }

    /** A redistributable entry, ready for the library. */
    data class Game(
        val slug: String,
        val title: String,
        val developers: List<String>,
        /** The licence(s) as published (SPDX), code first. */
        val licences: List<String>,
        /** The ROM's path below the entry, as published (`files/x.gb`). */
        val file: String,
        val downloadUrl: String
    ) {
        /**
         * The name the file gets in the library: its own, or after the entry's slug when it does not
         * start with it. Games ship as `game.gb` or `rom.gb` all the time, and the library keeps a
         * file under its base name only, so two entries would otherwise be the same file.
         */
        val storedName: String get() = file.substringAfterLast('/').let { if (it.startsWith(slug, ignoreCase = true)) it else "$slug-$it" }
        /** The row's identity within the source: the entry and the library name of its file, escaped like a link. */
        val fileName: String get() = FILES.toHttpUrl().newBuilder().addPathSegment(slug).addPathSegment(storedName).build().toString().removePrefix(FILES)
        val tags: List<String> get() = (licences + developers).distinct()
    }

    /** The games of [entries] for [platform], once each (pages can shift while they are read), each under a name of its own. */
    fun games(entries: List<JsonObject>, platform: Platform): List<Game> =
        entries.mapNotNull { runCatching { gameOf(it, platform) }.getOrNull() }
            .distinctBy { it.slug }
            .distinctBy { it.storedName.lowercase() }

    /** The game of one manifest, or null when it is not offered (see the class comment). */
    fun gameOf(entry: JsonObject, platform: Platform): Game? {
        val slug = text(entry, "slug")?.takeIf { slugPattern.matches(it) } ?: return null
        if (text(entry, "platform") != platform.code) return null
        if (text(entry, "basepath") != BASE_PATH || text(entry, "baserepo")?.trimEnd('/') != BASE_REPO) return null
        if (text(entry, "typetag")?.equals("hackrom", ignoreCase = true) == true) return null
        val licences = licenceOf(entry) ?: return null
        val file = romOf(entry) ?: return null
        val url = FILES.toHttpUrl().newBuilder().addPathSegment(slug).apply { file.split('/').forEach { addPathSegment(it) } }.build().toString()
        val title = text(entry, "title")?.take(160) ?: slug
        return Game(slug, title, developersOf(entry), licences, file, url)
            .takeIf { runCatching { FileParsingUtils.storageFileName(it.fileName) }.isSuccess }
    }

    /** The row [game] gives in the library of [consoleId]; [extraTags] are added after its own. */
    fun row(game: Game, consoleId: String, extraTags: List<String> = emptyList()): Pair<DownloadableFileEntity, List<String>> {
        val extension = game.file.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return DownloadableFileEntity(
            name = game.title,
            fileName = game.fileName,
            consoleId = consoleId,
            downloadUrl = game.downloadUrl,
            fileExtension = if (extension.isEmpty()) "" else ".$extension"
        ) to (game.tags + extraTags).distinct()
    }

    // ---- Licences ---------------------------------------------------------------------------

    /** SPDX ids of licences that allow handing the unchanged file on (some only non-commercially). */
    private val openLicences = setOf(
        "MIT", "MIT-0", "ZLIB", "ISC", "0BSD", "BSD-2-CLAUSE", "BSD-3-CLAUSE", "APACHE-2.0", "MPL-2.0", "UNLICENSE", "CC0-1.0", "WTFPL",
        "GPL-2.0", "GPL-2.0-ONLY", "GPL-2.0-OR-LATER", "GPL-3.0", "GPL-3.0-ONLY", "GPL-3.0-OR-LATER",
        "LGPL-2.1", "LGPL-2.1-ONLY", "LGPL-2.1-OR-LATER", "LGPL-3.0", "LGPL-3.0-ONLY", "LGPL-3.0-OR-LATER",
        "AGPL-3.0", "AGPL-3.0-ONLY", "AGPL-3.0-OR-LATER"
    )
    /** Every Creative Commons licence allows sharing unchanged copies (NC: not commercially). */
    private val creativeCommons = Regex("CC-BY(-NC)?(-SA|-ND)?-[1-4]\\.0", RegexOption.IGNORE_CASE)

    fun isOpenLicence(id: String): Boolean = id.uppercase() in openLicences || creativeCommons.matches(id)

    /**
     * The licences that cover the whole entry, or null when it may not be passed on. Manifests
     * spell this several ways: `license` alone (the whole entry), or the game and its assets
     * apart (`license`/`gameLicense` and `assetsLicense`/`assetLicense`). When only the game's
     * licence is given, the assets' terms are unknown and the entry is left out.
     */
    fun licenceOf(entry: JsonObject): List<String>? {
        val whole = text(entry, "license")
        val game = text(entry, "gameLicense")
        val assets = text(entry, "assetsLicense") ?: text(entry, "assetLicense")
        if (whole == null && (game == null || assets == null)) return null
        val all = listOfNotNull(whole, game, assets).distinctBy { it.uppercase() }
        return all.takeIf { list -> list.all(::isOpenLicence) }
    }

    // ---- Files and people -------------------------------------------------------------------

    private val romExtensions = setOf("gb", "gbc")
    private val slugPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** The entry's ROM: its default playable Game Boy file, else the first playable one, else the first one. */
    fun romOf(entry: JsonObject): String? {
        val files = entry.get("files")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val roms = files.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = text(obj, "filename")?.takeIf(::safePath) ?: return@mapNotNull null
            if (name.substringAfterLast('/').substringAfterLast('.', "").lowercase() !in romExtensions) return@mapNotNull null
            if (flag(obj, "playable") == false) return@mapNotNull null
            Triple(name, flag(obj, "default") == true, flag(obj, "playable") == true)
        }
        return (roms.firstOrNull { it.second } ?: roms.firstOrNull { it.third } ?: roms.firstOrNull())?.first
    }

    /** A relative path inside the entry: no escape upwards, no empty or hidden tricks. */
    private fun safePath(path: String): Boolean = path.length <= 200 && !path.startsWith('/') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c == '\\' || c.isISOControl() } }

    /** The developer as published: one name or a list of names (at most three kept). */
    fun developersOf(entry: JsonObject): List<String> {
        val value = entry.get("developer") ?: return emptyList()
        val names = when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { it.stringOrNull() }
            else -> listOfNotNull(value.stringOrNull())
        }
        return names.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) }.distinct().take(3)
    }

    private fun JsonElement.stringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun text(obj: JsonObject, key: String): String? =
        obj.get(key)?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }

    private fun flag(obj: JsonObject, key: String): Boolean? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun int(obj: JsonObject, key: String): Int {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "No $key" }
        return value.asString.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key")
    }
}
