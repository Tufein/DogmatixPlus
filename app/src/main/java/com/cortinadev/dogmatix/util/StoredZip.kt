package com.cortinadev.dogmatix.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32

/**
 * A ZIP archive without compression ("stored"), produced as a stream while it is sent, so a game
 * of several files (folders included) travels as one file without a temporary copy on the device.
 * Every entry's size and CRC-32 are known before the first byte goes out (the package was read
 * once to check it), so the archive's exact length is known up front, which a chunked upload
 * needs. The same entries always give the same bytes. While streaming, every file is checked
 * against its size and CRC-32 again: a file that changed in between fails the stream instead of
 * producing an archive with wrong contents. No ZIP64: [fits] says whether a package can be packed.
 * Pure JVM for the tests.
 */
object StoredZip {
    /** One file in the archive: its path inside (with `/`), its size and its CRC-32. */
    data class Entry(val path: String, val size: Long, val crc32: Long)

    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val END = 0x06054b50
    private const val LIMIT = 0xFFFFFFFFL
    private const val MAX_ENTRIES = 0xFFFF
    private const val UTF8_FLAG = 0x0800
    private const val VERSION = 20

    /** Whether [entries] fit in a plain ZIP (every file and the whole archive below 4 GiB, fewer than 65,535 files). */
    fun fits(entries: List<Entry>): Boolean {
        if (entries.isEmpty() || entries.size >= MAX_ENTRIES) return false
        if (entries.any { it.size < 0 || it.size >= LIMIT || nameBytes(it).size > 0xFFFF }) return false
        return size(entries) < LIMIT
    }

    /** The exact length of the archive [stream] produces for [entries]. */
    fun size(entries: List<Entry>): Long {
        var total = 0L
        for (e in entries) {
            val name = nameBytes(e).size.toLong()
            total = Math.addExact(total, 30 + name + e.size)   // local header + name + data
            total = Math.addExact(total, 46 + name)            // central directory record
        }
        return Math.addExact(total, 22)                       // end of central directory
    }

    /**
     * The archive as a stream. [open] opens the file of the entry at an index; it is called only
     * when the stream reaches that file, so one file is open at a time. Reading fails with an
     * [IOException] when a file is shorter, longer or different from its entry.
     */
    fun stream(entries: List<Entry>, modifiedAt: Long, open: (Int) -> InputStream): InputStream {
        require(fits(entries)) { "Package too large for a ZIP archive" }
        val (time, date) = dosTime(modifiedAt)
        val offsets = LongArray(entries.size)
        var offset = 0L
        entries.forEachIndexed { i, e ->
            offsets[i] = offset
            offset += 30 + nameBytes(e).size + e.size
        }
        val central = ByteArrayOutputStream()
        entries.forEachIndexed { i, e -> central.write(centralRecord(e, time, date, offsets[i])) }
        val directory = central.toByteArray()
        val end = endRecord(entries.size, directory.size.toLong(), offset)
        val parts = ArrayList<() -> InputStream>()
        entries.forEachIndexed { i, e ->
            parts += { ByteArrayInputStream(localHeader(e, time, date)) }
            parts += { CheckedEntryStream(open(i), e) }
        }
        parts += { ByteArrayInputStream(directory + end) }
        val iterator = parts.iterator()
        return SequenceInputStream(object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = iterator.hasNext()
            override fun nextElement(): InputStream = iterator.next()()
        })
    }

    /** CRC-32 of a whole stream (closes it). */
    fun crc32(input: InputStream, check: () -> Unit = {}): Long = input.use {
        val crc = CRC32()
        val buffer = ByteArray(256 * 1024)
        while (true) {
            check()
            val n = it.read(buffer)
            if (n < 0) break
            crc.update(buffer, 0, n)
        }
        crc.value
    }

    private fun nameBytes(e: Entry): ByteArray = e.path.toByteArray(Charsets.UTF_8)

    /** MS-DOS time and date (UTC), at least 1980-01-01. */
    internal fun dosTime(millis: Long): Pair<Int, Int> {
        val t = Instant.ofEpochMilli(millis.coerceAtLeast(MIN_DOS_MILLIS)).atZone(ZoneOffset.UTC)
        val year = t.year.coerceIn(1980, 2107)
        val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
        val date = ((year - 1980) shl 9) or (t.monthValue shl 5) or t.dayOfMonth
        return time to date
    }
    private const val MIN_DOS_MILLIS = 315_532_800_000L // 1980-01-01T00:00:00Z

    private fun localHeader(e: Entry, time: Int, date: Int): ByteArray {
        val name = nameBytes(e)
        return Le(30 + name.size).apply {
            int(LOCAL); short(VERSION); short(UTF8_FLAG); short(0); short(time); short(date)
            int(e.crc32.toInt()); int(e.size.toInt()); int(e.size.toInt()); short(name.size); short(0)
            bytes(name)
        }.array
    }

    private fun centralRecord(e: Entry, time: Int, date: Int, offset: Long): ByteArray {
        val name = nameBytes(e)
        return Le(46 + name.size).apply {
            int(CENTRAL); short(VERSION); short(VERSION); short(UTF8_FLAG); short(0); short(time); short(date)
            int(e.crc32.toInt()); int(e.size.toInt()); int(e.size.toInt()); short(name.size); short(0); short(0)
            short(0); short(0); int(0); int(offset.toInt())
            bytes(name)
        }.array
    }

    private fun endRecord(count: Int, directorySize: Long, directoryOffset: Long): ByteArray = Le(22).apply {
        int(END); short(0); short(0); short(count); short(count); int(directorySize.toInt()); int(directoryOffset.toInt()); short(0)
    }.array

    private class Le(size: Int) {
        val array = ByteArray(size)
        private var p = 0
        fun short(v: Int) { array[p++] = v.toByte(); array[p++] = (v shr 8).toByte() }
        fun int(v: Int) { short(v and 0xFFFF); short(v ushr 16) }
        fun bytes(b: ByteArray) { b.copyInto(array, p); p += b.size }
    }

    /** Passes one file through and fails at its end when its length or CRC-32 is not the entry's. */
    private class CheckedEntryStream(input: InputStream, private val entry: Entry) : FilterInputStream(input) {
        private val crc = CRC32()
        private var count = 0L
        private var finished = false

        override fun read(): Int {
            val b = super.read()
            if (b < 0) finish() else { crc.update(b); count++; overflow() }
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = super.read(buffer, offset, length)
            if (n < 0) finish() else { crc.update(buffer, offset, n); count += n; overflow() }
            return n
        }

        override fun skip(n: Long): Long {
            // Skipping would leave bytes unchecked: read them instead.
            val buffer = ByteArray(minOf(n, 64L * 1024).toInt().coerceAtLeast(1))
            var left = n
            while (left > 0) {
                val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported(): Boolean = false

        private fun overflow() { if (count > entry.size) throw IOException("${entry.path} changed while it was being sent") }

        private fun finish() {
            if (finished) return
            finished = true
            if (count != entry.size || crc.value != entry.crc32) throw IOException("${entry.path} changed while it was being sent")
        }
    }
}
