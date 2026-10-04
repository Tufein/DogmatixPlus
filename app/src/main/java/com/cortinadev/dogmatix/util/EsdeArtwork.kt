package com.cortinadev.dogmatix.util

/**
 * Where ES-DE looks for a game's media and data, and the gamelist entry Dogmatix+ adds after a
 * download so ES-DE (and Cocoon's ES-DE link) show a cover and description straight away.
 * ES-DE matches media by the ROM's file name without its extension, per system folder.
 */
object EsdeArtwork {

    /** `downloaded_media/gba/covers/Game (Europe).jpg` */
    fun coverPath(system: String, romFileName: String, imageExtension: String): String =
        "downloaded_media/$system/covers/${romFileName.substringBeforeLast('.')}.${imageExtension.lowercase().ifBlank { "jpg" }}"

    /** The image extension of a cover URL (`png`, `jpg`, `webp`), defaulting to `jpg`. */
    fun imageExtension(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
            ?.replace("jpeg", "jpg") ?: "jpg"

    /**
     * [existing] gamelist with an entry for [romFileName]; null when it already has one (ES-DE's
     * own scraper or the user's edits always win) or the file is not a gamelist.
     */
    fun gamelistWithGame(
        existing: String?, romFileName: String, name: String, description: String,
        released: String = "", developer: String = "", genre: String = ""
    ): String? {
        val path = "./$romFileName"
        val fields = buildString {
            append("\t\t<path>").append(xml(path)).append("</path>\n")
            append("\t\t<name>").append(xml(name)).append("</name>\n")
            if (description.isNotBlank()) append("\t\t<desc>").append(xml(description.trim())).append("</desc>\n")
            esdeDate(released)?.let { append("\t\t<releasedate>").append(it).append("</releasedate>\n") }
            if (developer.isNotBlank()) append("\t\t<developer>").append(xml(developer)).append("</developer>\n")
            if (genre.isNotBlank()) append("\t\t<genre>").append(xml(genre)).append("</genre>\n")
        }
        val entry = "\t<game>\n$fields\t</game>\n"
        if (existing == null) return "<?xml version=\"1.0\"?>\n<gameList>\n$entry</gameList>\n"
        if (existing.contains("<path>${xml(path)}</path>") || existing.contains("<path>$path</path>")) return null
        val idx = existing.lastIndexOf("</gameList>")
        if (idx < 0) return null
        return existing.substring(0, idx) + entry + existing.substring(idx)
    }

    /** `2004-11-21` → `20041121T000000` (ES-DE's format); null when it is not a date. */
    fun esdeDate(date: String): String? {
        val m = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").find(date.trim()) ?: return null
        val y = m.groupValues[1]; val mo = m.groupValues[2].ifEmpty { "01" }; val d = m.groupValues[3].ifEmpty { "01" }
        return "$y$mo${d}T000000"
    }

    fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** One game's play record in an ES-DE gamelist. */
data class EsdePlay(val system: String, val path: String, val name: String, val playCount: Int, val lastPlayed: Long?)

/** Reads ES-DE's `<playcount>` and `<lastplayed>` (ES-DE does not track play time itself). */
object EsdePlayStats {
    private val game = Regex("""<game\b[^>]*>(.*?)</game>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(system: String, gamelist: String): List<EsdePlay> = game.findAll(gamelist).mapNotNull { m ->
        val body = m.groupValues[1]
        val count = tag(body, "playcount")?.toIntOrNull() ?: 0
        val last = tag(body, "lastplayed")?.let(::parseDate)
        if (count <= 0 && last == null) return@mapNotNull null
        val path = tag(body, "path").orEmpty()
        val name = tag(body, "name")?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.')
        EsdePlay(system, path, unescape(name), count, last)
    }.toList()

    private fun tag(body: String, name: String): String? =
        Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim()

    /** `20261003T201500` → epoch millis (UTC). */
    fun parseDate(s: String): Long? {
        val m = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(s.trim()) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        return runCatching {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt()).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Most played first (then most recent), at most [limit]. */
    fun top(plays: List<EsdePlay>, limit: Int = 10): List<EsdePlay> =
        plays.sortedWith(compareByDescending<EsdePlay> { it.playCount }.thenByDescending { it.lastPlayed ?: 0L }).take(limit)

    fun recent(plays: List<EsdePlay>, limit: Int = 10): List<EsdePlay> =
        plays.filter { it.lastPlayed != null }.sortedByDescending { it.lastPlayed }.take(limit)
}
