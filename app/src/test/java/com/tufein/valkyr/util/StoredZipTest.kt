package com.tufein.valkyr.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class StoredZipTest {
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    private val files = linkedMapOf(
        "Game (USA)/Game (USA).cue" to "FILE \"Game (USA) (Track 1).bin\" BINARY".toByteArray(),
        "Game (USA)/Game (USA) (Track 1).bin" to ByteArray(70_000) { (it % 251).toByte() },
        "Game (USA)/Game (USA) (Track 2).bin" to ByteArray(0),
        "Bonus/Ünïcode – name.txt" to "hi".toByteArray()
    )
    private val entries = files.map { (path, bytes) -> StoredZip.Entry(path, bytes.size.toLong(), crc(bytes)) }
    private fun build(contents: Map<String, ByteArray> = files): ByteArray {
        val list = contents.values.toList()
        return StoredZip.stream(entries, 1_700_000_000_000L) { ByteArrayInputStream(list[it]) }.readBytes()
    }

    @Test fun sizeIsKnownBeforeTheArchiveIsWritten() {
        assertEquals(StoredZip.size(entries), build().size.toLong())
    }

    @Test fun archiveKeepsFoldersAndContentsForStandardReaders() {
        val zip = build()
        val read = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                read[entry.name] = input.readBytes()
            }
        }
        assertEquals(files.keys.toList(), read.keys.toList())
        files.forEach { (path, bytes) -> assertArrayEquals(bytes, read.getValue(path)) }
        // The central directory is right too (what RomM and most tools read).
        val tmp = File.createTempFile("stored", ".zip").apply { deleteOnExit(); writeBytes(zip) }
        ZipFile(tmp).use { z ->
            assertEquals(files.size, z.size())
            files.forEach { (path, bytes) -> assertArrayEquals(bytes, z.getInputStream(z.getEntry(path)).readBytes()) }
        }
        val listed = ZipDirectory.list(zip.size.toLong()) { pos, len -> zip.copyOfRange(pos.toInt(), pos.toInt() + len) }!!
        assertEquals(files.size, listed.size)
    }

    @Test fun sameEntriesGiveTheSameBytes() {
        assertArrayEquals(build(), build())
    }

    @Test fun aFileThatChangedFailsTheStream() {
        val changed = LinkedHashMap(files).apply { put("Game (USA)/Game (USA).cue", "FILE \"other.bin\" BINARY".toByteArray()) }
        try { build(changed); fail("A changed file must not be packed") } catch (_: IOException) { }
        val shorter = LinkedHashMap(files).apply { put("Game (USA)/Game (USA) (Track 1).bin", ByteArray(10)) }
        try { build(shorter); fail("A shorter file must not be packed") } catch (_: IOException) { }
    }

    @Test fun onlyPlainZipSizesFit() {
        assertTrue(StoredZip.fits(entries))
        assertFalse(StoredZip.fits(emptyList()))
        assertFalse(StoredZip.fits(listOf(StoredZip.Entry("big.bin", 0xFFFFFFFFL, 0))))
        assertFalse(StoredZip.fits(listOf(StoredZip.Entry("a.bin", 3_000_000_000L, 0), StoredZip.Entry("b.bin", 2_000_000_000L, 0))))
    }

    @Test fun dosTimeIsNeverBefore1980() {
        assertEquals(0 to ((0 shl 9) or (1 shl 5) or 1), StoredZip.dosTime(0))
    }

    @Test fun crcOfAStream() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertEquals(crc(bytes), StoredZip.crc32(ByteArrayInputStream(bytes)))
    }
}
