package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.ByteArrayOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object FileParsingUtils {
    private val urlSchemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun toUserReadablePath(uriString: String): String {
        if (uriString.isBlank()) return ""
        return try {
            val decodedPath = URLDecoder.decode(uriString, "UTF-8")
            val pathSegment = decodedPath.substringAfterLast(":")
            ".../$pathSegment"
        } catch (_: Exception) {
            uriString
        }
    }
    fun sanitizeFolderName(name: String): String {
        val sanitized = name.replace(Regex("[<>:\"/|?*]"), "")
        return sanitized.trim().replace(Regex("\\s{2,}"), " ")
    }

    fun buildDownloadUrl(baseUrl: String, href: String): String {
        // Sources are listing directories, including when their saved URL lacks a slash.
        // HttpUrl also handles root-relative and protocol-relative links without corrupting
        // the authority, query, escaped characters or literal plus signs.
        val parsed = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid source URL")
        val base = parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/') + "/").query(null).fragment(null).build()
        return base.resolve(href)?.toString() ?: throw IllegalArgumentException("Invalid file URL")
    }

    /** Compiled once: a scan parses tens of thousands of names. */
    private val tagPattern = Regex("\\(([^)]+)\\)")
    private val whitespace = Regex("\\s+")

    fun extractNameAndTags(displayName: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        var cleanName = displayName

        val matches = tagPattern.findAll(displayName)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            // Split by comma to handle multiple values in parentheses
            val values = content.split(",").map { it.trim() }

            for (value in values) {
                if (isValidTag(value)) {
                    tags.add(normalizeTag(value))
                }
            }

            // Remove the entire parentheses group from the name
            cleanName = cleanName.replace(match.value, "").trim()
        }

        // Clean up any extra spaces that might be left
        cleanName = cleanName.replace(whitespace, " ").trim()

        return Pair(cleanName, tags)
    }

    fun isValidTag(tag: String): Boolean {
        if (tag.length < 2) return false

        val cleanTag = tag.trim().uppercase()

        if (cleanTag in Constants.Tags.VIDEO_STANDARDS) return true

        if (cleanTag in Constants.Tags.CONTENT_TYPES) return true

        if (cleanTag.matches(Constants.Tags.LANGUAGE_CODE_PATTERN)) return true

        if (cleanTag.matches(Constants.Tags.VERSION_PATTERN)) return true

        if (cleanTag in Constants.Tags.ALL_REGIONS) return true

        for (matcher in Constants.Tags.PARTIAL_MATCHERS) {
            if (cleanTag.contains(matcher)) return true
        }

        return false
    }

    fun normalizeTag(tag: String): String {
        val trimmed = tag.trim()
        val upper = trimmed.uppercase()

        if (upper in Constants.Tags.VIDEO_STANDARDS) {
            return upper
        }

        if (trimmed.length <= 3) {
            return upper
        }

        return trimmed.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun decodeUrlEncodedFileName(fileName: String): String {
        return try {
            // URLDecoder is a form decoder: shield plus signs before decoding a URL path.
            URLDecoder.decode(fileName.replace("+", "%2B"), StandardCharsets.UTF_8.toString())
        } catch (_: Exception) {
            fileName
        }
    }

    /** Storage has a basename; the unmodified listing href remains the indexed identity. */
    fun storageFileName(reference: String): String {
        val withoutQuery = reference.substringBefore('?').substringBefore('#')
        val path = when {
            withoutQuery.startsWith("//") -> withoutQuery.drop(2).substringAfter('/', "")
            urlSchemePattern.containsMatchIn(withoutQuery) ->
                withoutQuery.substringAfter("://").substringAfter('/', "")
            else -> withoutQuery
        }
        val encoded = path.substringAfterLast('/')
        val name = try {
            decodePathSegment(encoded)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid escaped file name", e)
        }
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' || it.isISOControl() } &&
            name.toByteArray(Charsets.UTF_8).size <= 255) { "Invalid storage file name" }
        return name
    }

    /** Strict UTF-8 avoids mapping malformed escapes to a different, colliding replacement name. */
    private fun decodePathSegment(encoded: String): String {
        val bytes = ByteArrayOutputStream()
        var cursor = 0
        while (cursor < encoded.length) {
            if (encoded[cursor] == '%') {
                require(cursor + 2 < encoded.length) { "Incomplete percent escape" }
                val hi = encoded[cursor + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("Invalid percent escape")
                val lo = encoded[cursor + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("Invalid percent escape")
                bytes.write(hi * 16 + lo); cursor += 3
            } else {
                val next = encoded.indexOf('%', cursor).takeIf { it >= 0 } ?: encoded.length
                bytes.write(encoded.substring(cursor, next).toByteArray(Charsets.UTF_8)); cursor = next
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    /** The link of a listing row (`td.link a`, else the first link), found by walking the row, not by a CSS query. */
    fun linkOf(row: Element): Element? =
        row.getElementsByClass("link").firstOrNull { it.tagName() == "td" }?.getElementsByTag("a")?.firstOrNull()
            ?: row.getElementsByTag("a").firstOrNull()

    private fun sizeCellOf(row: Element): Element? =
        row.getElementsByClass("size").firstOrNull { it.tagName() == "td" }
            ?: row.getElementsByClass("fb-s").firstOrNull { it.tagName() == "td" }
            ?: row.getElementsByTag("td").getOrNull(1)

    fun parseFileFromRow(row: Element, baseUrl: String, consoleId: String, link: Element? = null): Pair<DownloadableFileEntity?, List<FileTagEntity>> {
        val linkCell = link ?: linkOf(row)
        val sizeCell = sizeCellOf(row)

        if (linkCell == null) return Pair(null, emptyList())

        val href = linkCell.attr("href")
        val title = linkCell.attr("title")
        val linkText = linkCell.text()

        if (shouldSkipFile(href)) return Pair(null, emptyList())

        val fileSize = sizeCell?.text()?.takeIf { it != ScrapingConstants.UNKNOWN_FILE_SIZE } ?: ScrapingConstants.DEFAULT_FILE_SIZE
        val downloadUrl = runCatching { buildDownloadUrl(baseUrl, href) }.getOrNull() ?: return Pair(null, emptyList())

        val storageName = runCatching { storageFileName(href) }.getOrNull() ?: return Pair(null, emptyList())
        val actualFileExtension = if (storageName.contains(".")) {
            "." + storageName.substringAfterLast(".")
        } else {
            ""
        }

        val displayName = title.ifEmpty { linkText }
        val (cleanName, tags) = extractNameAndTags(displayName)

        val fileEntity = DownloadableFileEntity(
            name = cleanName,
            fileName = href,
            consoleId = consoleId,
            downloadUrl = downloadUrl,
            fileSize = FileSizeUtils.parseFileSize(fileSize),
            fileExtension = actualFileExtension
        )

        val tagEntities = tags.map { tag ->
            FileTagEntity(
                fileId = 0L, // Will be set after file insertion
                tag = tag
            )
        }

        return Pair(fileEntity, tagEntities)
    }

    private fun shouldSkipFile(href: String): Boolean {
        return href == ScrapingConstants.PARENT_DIRECTORY ||
               href == ScrapingConstants.CURRENT_DIRECTORY ||
               href.endsWith("/")
    }

    /**
     * Magnet links with many trackers can slow down metadata fetching in libtorrent.
     * This trims the tracker list (tr parameters) to a reasonable limit.
     */
    fun optimizeMagnetUri(uri: String): String {
        if (!uri.startsWith("magnet:?")) return uri
        
        val parts = uri.split("&")
        val base = parts[0]
        val params = parts.drop(1)
        
        val trackers = params.filter { it.startsWith("tr=") }
        val otherParams = params.filter { !it.startsWith("tr=") }
        
        if (trackers.size <= TorrentConstants.MAX_TRACKERS_PER_MAGNET) return uri
        
        val optimizedTrackers = trackers.take(TorrentConstants.MAX_TRACKERS_PER_MAGNET)
        val optimizedParams = (otherParams + optimizedTrackers).joinToString("&")
        
        return if (optimizedParams.isEmpty()) base else "$base&$optimizedParams"
    }
}
