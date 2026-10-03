package com.cortinadev.dogmatix.util

/**
 * Favourites from ES-DE: every `<game>` in `ES-DE/gamelists/<system>/gamelist.xml` with
 * `<favorite>true</favorite>`. Its `<path>` names the file on disk; the library row that belongs
 * to it is found by name without extension (a downloaded `.zip` is often unpacked into a `.gba`).
 */
object EsdeFavourites {

    private val gameBlock = Regex("""<game\b[^>]*>(.*?)</game>""", RegexOption.DOT_MATCHES_ALL)
    private val path = Regex("""<path>(.*?)</path>""", RegexOption.DOT_MATCHES_ALL)
    private val favourite = Regex("""<favorite>\s*true\s*</favorite>""", RegexOption.IGNORE_CASE)

    /** File names (without folders) of the favourite games in one gamelist. */
    fun favouriteFiles(gamelistXml: String): List<String> =
        gameBlock.findAll(gamelistXml).mapNotNull { m ->
            val block = m.groupValues[1]
            if (!favourite.containsMatchIn(block)) return@mapNotNull null
            path.find(block)?.groupValues?.get(1)?.trim()?.let(::unescape)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }.distinct().toList()

    /** Library file names (of one console) that match [favourites], by name without extension, ignoring case. */
    fun match(favourites: List<String>, libraryFileNames: List<String>): List<String> {
        val byBase = libraryFileNames.groupBy { base(FileParsingUtils.decodeUrlEncodedFileName(it)) }
        return favourites.flatMap { byBase[base(it)].orEmpty().take(1) }.distinct()
    }

    private fun base(name: String): String = name.substringBeforeLast('.').lowercase().trim()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}
