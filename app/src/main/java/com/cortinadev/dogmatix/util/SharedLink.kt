package com.cortinadev.dogmatix.util

/** What was shared to the app (from a browser, a chat, a file manager). */
data class SharedLink(val url: String, val kind: Kind, val fileName: String?) {
    enum class Kind {
        /** A magnet link: becomes a source of a console. */
        MAGNET,
        /** A `.torrent` file on the web: becomes a source. */
        TORRENT,
        /** A web directory (ends with `/`): becomes a source. */
        DIRECTORY,
        /** A single file: can be downloaded right away into a console's folder. */
        FILE
    }
}

object SharedLinks {
    private val magnet = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val web = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** The first magnet or web link in [text] (a shared text often has a title around it), or null. */
    fun parse(text: String?): SharedLink? {
        if (text.isNullOrBlank()) return null
        magnet.find(text)?.let { return SharedLink(it.value, SharedLink.Kind.MAGNET, null) }
        val url = web.find(text)?.value?.trimEnd('.', ',', ')', ']') ?: return null
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        return when {
            path.endsWith("/") || last.isEmpty() -> SharedLink(url, SharedLink.Kind.DIRECTORY, null)
            last.endsWith(".torrent", ignoreCase = true) -> SharedLink(url, SharedLink.Kind.TORRENT, null)
            '.' in last -> SharedLink(url, SharedLink.Kind.FILE, FileParsingUtils.decodeUrlEncodedFileName(last))
            else -> SharedLink(url, SharedLink.Kind.DIRECTORY, null)
        }
    }
}
