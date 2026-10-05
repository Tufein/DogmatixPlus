package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * The pure parts of sharing a game card or the wishlist (6.0): the deep link, the share text,
 * which tags go on the card, the card's layout maths and a safe file name. Pure JVM for the tests.
 */
object GameShareText {

    /** Card size in pixels (4:5, fits every messenger without cropping). */
    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1350

    /** Most tag chips on the card. */
    const val MAX_TAGS = 4

    /** Most wishes written into the text of a wishlist share. */
    const val MAX_WISHES = 60

    /**
     * `dogmatix://library?console=…&q=…`: the library filtered to this game (the link the app
     * resolves, see `DeepLinkParser`). Null when there is no title to search for.
     */
    fun deepLink(consoleId: String, title: String): String? {
        val q = title.trim()
        if (q.isEmpty()) return null
        val console = if (consoleId.isBlank()) "" else "console=${enc(consoleId)}&"
        return "${DeepLinkParser.SCHEME}://${DeepLinkParser.HOST_LIBRARY}?${console}q=${enc(q)}"
    }

    /** The message with the link under it, or just the message when there is no link. */
    fun withLink(message: String, link: String?): String =
        if (link.isNullOrBlank()) message else "$message\n$link"

    /** Up to [MAX_TAGS] tags for the card: regions, then languages, revisions and release kinds. */
    fun cardTags(tags: List<String>): List<String> {
        val order = listOf(TagClassifier.Kind.REGION, TagClassifier.Kind.LANGUAGE, TagClassifier.Kind.REVISION, TagClassifier.Kind.RELEASE)
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // All languages share one chip ("En, Fr, De") so they do not use up the row.
        val languages = clean.filter { TagClassifier.kindOf(it) == TagClassifier.Kind.LANGUAGE }
        val out = mutableListOf<String>()
        for (kind in order) {
            if (kind == TagClassifier.Kind.LANGUAGE) {
                if (languages.isNotEmpty()) out += languages.take(4).joinToString(", ")
            } else {
                out += clean.filter { TagClassifier.kindOf(it) == kind }
            }
        }
        return out.take(MAX_TAGS)
    }

    /** A file name for the card image: letters, digits, dash and underscore only. */
    fun fileName(title: String, extension: String = "png"): String {
        val slug = title.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-").trim('-')
            .take(48).trim('-')
        return "dogmatix-${slug.ifEmpty { "game" }}.$extension"
    }

    /** The wishlist as a text list: header, one line per wish (console in brackets), a "+N" line. */
    fun wishlistText(header: String, wishes: List<Pair<String, String?>>, more: (Int) -> String): String {
        val shown = wishes.take(MAX_WISHES)
        val lines = shown.map { (title, console) -> if (console.isNullOrBlank()) "• $title" else "• $title ($console)" }
        val rest = wishes.size - shown.size
        return (listOf(header) + lines + if (rest > 0) listOf(more(rest)) else emptyList()).joinToString("\n")
    }

    /** A rectangle in card pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Where the cover goes: 3:4, centred, [heightShare] of the card's height, from [top]. */
    fun coverBox(top: Float = 110f, heightShare: Float = 0.5f, width: Int = CARD_WIDTH, height: Int = CARD_HEIGHT): Box {
        val h = height * heightShare
        val w = h * 3f / 4f
        val left = (width - w) / 2f
        return Box(left, top, left + w, top + h)
    }

    /**
     * The source rectangle of a picture of [srcW] x [srcH] that fills a [dst] box without
     * stretching (centre crop): left, top, right, bottom in picture pixels.
     */
    fun centerCrop(srcW: Int, srcH: Int, dst: Box): Box {
        if (srcW <= 0 || srcH <= 0 || dst.width <= 0f || dst.height <= 0f) return Box(0f, 0f, srcW.toFloat(), srcH.toFloat())
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dst.width / dst.height
        return if (srcRatio > dstRatio) {
            val w = srcH * dstRatio
            val left = (srcW - w) / 2f
            Box(left, 0f, left + w, srcH.toFloat())
        } else {
            val h = srcW / dstRatio
            val top = (srcH - h) / 2f
            Box(0f, top, srcW.toFloat(), top + h)
        }
    }

    /** Text size that makes [textWidth] (measured at [size]) fit [maxWidth], never below [minSize]. */
    fun fitSize(size: Float, textWidth: Float, maxWidth: Float, minSize: Float): Float =
        if (textWidth <= maxWidth || textWidth <= 0f) size else (size * maxWidth / textWidth).coerceAtLeast(minSize)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
