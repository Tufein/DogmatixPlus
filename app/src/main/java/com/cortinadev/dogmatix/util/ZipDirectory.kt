package com.cortinadev.dogmatix.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One file inside a ZIP archive, as its central directory lists it. */
data class ZipEntryInfo(val name: String, val size: Long, val crc: String)

/**
 * Lists a ZIP archive from its central directory at the end of the file, without reading the
 * compressed data: the CRC-32 and size of every file inside, which is what a DAT checks. Reads
 * through [readAt] (position, length) so it works on a SAF file descriptor and in unit tests.
 * Returns null for something that is not a ZIP (or a ZIP64 archive it cannot read).
 */
object ZipDirectory {
    private const val EOCD = 0x06054b50
    private const val CENTRAL = 0x02014b50

    fun list(fileSize: Long, readAt: (position: Long, length: Int) -> ByteArray): List<ZipEntryInfo>? {
        if (fileSize < 22) return null
        val tailLength = minOf(fileSize, 22L + 65_535).toInt()
        val tail = ByteBuffer.wrap(readAt(fileSize - tailLength, tailLength)).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (p in tailLength - 22 downTo 0) if (tail.getInt(p) == EOCD) { eocd = p; break }
        if (eocd < 0) return null
        val count = tail.getShort(eocd + 10).toInt() and 0xFFFF
        val dirSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val dirOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (dirOffset == 0xFFFFFFFFL || dirSize > Int.MAX_VALUE || dirOffset + dirSize > fileSize) return null
        val dir = ByteBuffer.wrap(readAt(dirOffset, dirSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<ZipEntryInfo>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > dir.limit() || dir.getInt(p) != CENTRAL) return null
            val flags = dir.getShort(p + 8).toInt()
            val crc = dir.getInt(p + 16).toLong() and 0xFFFFFFFFL
            val size = dir.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLength = dir.getShort(p + 28).toInt() and 0xFFFF
            val extraLength = dir.getShort(p + 30).toInt() and 0xFFFF
            val commentLength = dir.getShort(p + 32).toInt() and 0xFFFF
            if (p + 46 + nameLength > dir.limit()) return null
            val nameBytes = ByteArray(nameLength).also { dir.position(p + 46); dir.get(it) }
            val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            if (!name.endsWith("/")) out += ZipEntryInfo(name.substringAfterLast('/'), size, "%08x".format(crc))
            p += 46 + nameLength + extraLength + commentLength
        }
        return out
    }
}
