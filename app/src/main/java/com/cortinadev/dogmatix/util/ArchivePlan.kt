package com.cortinadev.dogmatix.util

import java.text.Normalizer
import java.util.Locale

/** The complete archive is checked before an output stream can be opened. */
object ArchivePlan {
    data class Entry(val index: Int, val path: String, val directory: Boolean = false, val size: Long? = null)
    data class FileEntry(val index: Int, val path: String, val size: Long?)
    data class Checked(val files: List<FileEntry>, val knownBytes: Long)

    fun check(entries: List<Entry>, maximumEntries: Int = Constants.MAX_ARCHIVE_ENTRIES): Checked {
        if (entries.size > maximumEntries) throw ArchiveSafetyException(ArchiveSafetyException.Reason.TOO_MANY_ENTRIES)
        data class Node(val path: String, val directory: Boolean, val originalPath: String)
        val paths = LinkedHashMap<String, Node>()
        val files = mutableListOf<FileEntry>()
        var bytes = 0L
        for (entry in entries) {
            val path = safeRelativePath(entry.path, entry.directory)
            val parts = path.split('/')
            val originalParts = entry.path.replace('\\', '/').trimEnd('/').split('/')
            for (depth in 1..parts.size) {
                val current = parts.take(depth).joinToString("/")
                val directory = depth < parts.size || entry.directory
                val originalPath = originalParts.take(depth).joinToString("/")
                val key = portableKey(current)
                val previous = paths[key]
                if (previous != null && (previous.path != current || previous.originalPath != originalPath || !previous.directory || !directory)) {
                    throw ArchiveSafetyException(ArchiveSafetyException.Reason.NAME_COLLISION)
                }
                paths[key] = Node(current, directory, originalPath)
            }
            if (!entry.directory) {
                if (entry.size != null && entry.size < 0L) throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
                val size = entry.size ?: 0L
                if (size > Long.MAX_VALUE - bytes) throw ArchiveSafetyException(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE)
                bytes += size
                files += FileEntry(entry.index, path, entry.size)
            }
        }
        return Checked(files, bytes)
    }

    /** Keeps directory structure, rejects traversal, and checks collisions after sanitizing. */
    fun safeRelativePath(raw: String, directory: Boolean = false): String {
        val path = raw.replace('\\', '/').let { if (directory) it.trimEnd('/') else it }
        if (path.isBlank() || path.startsWith('/') || DRIVE_PREFIX.containsMatchIn(path) || path.length > 4096 ||
            path.any { it.code < 32 || it.code == 127 }) {
            throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
        }
        val parts = path.split('/')
        if (parts.size > 64 || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
        }
        return parts.joinToString("/") { original ->
            val name = original.replace(INVALID_NAME, "_").trimEnd(' ', '.')
            if (name.isBlank() || name.length > 255 || name == "." || name == "..") {
                throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
            }
            name
        }
    }

    /** Rejects aliases on FAT/exFAT and providers that normalize Unicode display names. */
    fun portableKey(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private val INVALID_NAME = Regex("[<>:\"|?*]")
}

class ArchiveSafetyException(val reason: Reason) : Exception("Archive safety check failed: $reason") {
    enum class Reason { UNSAFE_PATH, NAME_COLLISION, TOO_MANY_ENTRIES, INSUFFICIENT_SPACE, DESTINATION_CONFLICT, DESTINATION_UNAVAILABLE }
}
