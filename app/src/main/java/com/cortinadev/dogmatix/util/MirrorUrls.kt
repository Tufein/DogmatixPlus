package com.cortinadev.dogmatix.util

/**
 * Other addresses for a file of a web source: when [downloadUrl] lies under the source's own
 * address or one of its reserve addresses, the same file under each of the other addresses (in
 * the order they are set up). Empty when the file does not belong to any of them.
 */
object MirrorUrls {
    fun alternatives(downloadUrl: String, sourceUrl: String, mirrors: List<String>): List<String> {
        val bases = (listOf(sourceUrl) + mirrors).map { it.trim() }.filter { it.startsWith("http", true) }.map { if (it.endsWith("/")) it else "$it/" }.distinct()
        val base = bases.firstOrNull { downloadUrl.startsWith(it) } ?: return emptyList()
        val rest = downloadUrl.removePrefix(base)
        return bases.filter { it != base }.map { it + rest }
    }
}
