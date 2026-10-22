package com.tufein.valkyr.util

import com.tufein.valkyr.data.state.LibraryFilterRequest
import java.net.URI
import java.net.URLDecoder

/**
 * `valkyr://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&q=mario&fav=1`
 *
 * - `console`: console ids (as in Sources), short names (`snes`) or folder aliases, comma-separated.
 * - `region`, `lang`, `type`, `filetype`, `tag`: library tags, comma-separated, any case. They all
 *   end up in the same tag set; the kind is derived from the value (see `TagCategorizer`).
 *
 * Values are kept verbatim here; `DeepLinkResolver` maps them onto real ids / tag spellings.
 * - `q`: search text. `fav`: `1`/`true` shows favourites only, `0`/`false` all games.
 * - `new`: `1` shows only what recent rescans found. `collection`: the id of an own collection.
 *
 * `valkyr://` is the scheme the app generates; the legacy `dogmatix://` is still accepted everywhere
 * (old shared links, `.dgmtx` shortcut files, frontends configured with it).
 *
 * Pure JVM (no `android.net.Uri`) so it can be unit-tested.
 */
object DeepLinkParser {

    /** Preferred scheme: what links the app generates start with. */
    const val SCHEME = "valkyr"

    /** Legacy scheme, still accepted (manifest filters, parser, `.dgmtx` files). */
    const val LEGACY_SCHEME = "dogmatix"

    /** True for the preferred or the legacy scheme, any case. */
    fun isAppScheme(scheme: String?): Boolean =
        scheme.equals(SCHEME, ignoreCase = true) || scheme.equals(LEGACY_SCHEME, ignoreCase = true)

    /** True when [text] starts with `valkyr://` or `dogmatix://`, any case. */
    fun hasAppScheme(text: String): Boolean =
        text.startsWith("$SCHEME://", ignoreCase = true) || text.startsWith("$LEGACY_SCHEME://", ignoreCase = true)
    const val HOST_LIBRARY = "library"

    private val TAG_PARAMS = setOf("region", "lang", "language", "type", "filetype", "tag")

    fun parse(uri: String?): LibraryFilterRequest? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!isAppScheme(parsed.scheme)) return null
        if (!parsed.host.equals(HOST_LIBRARY, ignoreCase = true)) return null
        val params = queryParams(parsed.rawQuery)

        val consoles = params.values("console")
        val tags = TAG_PARAMS.flatMap { params.values(it) }.toSet()
        val query = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val fav = params["fav"]?.lowercase()?.let { it == "1" || it == "true" || it == "yes" }
        val new = params["new"]?.lowercase()?.let { it == "1" || it == "true" || it == "yes" }
        val collection = params["collection"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        return LibraryFilterRequest(consoles = consoles, tags = tags, query = query, favouritesOnly = fav, newOnly = new, collectionId = collection)
    }

    private fun Map<String, String>.values(key: String): Set<String> =
        this[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private fun queryParams(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                decode(key).lowercase() to decode(value)
            }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
