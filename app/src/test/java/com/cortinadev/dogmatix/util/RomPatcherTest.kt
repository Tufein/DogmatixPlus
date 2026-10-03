package com.cortinadev.dogmatix.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class RomPatcherTest {
    private val source = ByteArray(4096) { (it * 7).toByte() }
    private val target = source.copyOf(4200).also { t ->
        for (i in 100 until 140) t[i] = (t[i] + 1).toByte()
        for (i in 4096 until 4200) t[i] = 0x5A
        t[3000] = 0
    }

    @Test fun `ips patches change bytes and grow the file`() {
        val patch = RomPatcher.makeIps(source, target)
        assertEquals(RomPatcher.Format.IPS, RomPatcher.formatOf(patch))
        assertArrayEquals(target, RomPatcher.apply(source, patch))
    }

    @Test fun `ips run-length records and truncation work`() {
        val patch = ByteArrayOutputStream().apply {
            write("PATCH".toByteArray())
            write(byteArrayOf(0, 0, 10, 0, 0, 0, 5, 0x77))   // RLE: 5 × 0x77 at offset 10
            write("EOF".toByteArray())
            write(byteArrayOf(0, 0, 20))                      // truncate to 20 bytes
        }.toByteArray()
        val out = RomPatcher.apply(source, patch)
        assertEquals(20, out.size)
        assertEquals(0x77.toByte(), out[12])
    }

    @Test fun `ups patches are applied and refuse another dump`() {
        val patch = makeUps(source, target)
        assertArrayEquals(target, RomPatcher.apply(source, patch))
        // UPS works both ways.
        assertArrayEquals(source, RomPatcher.apply(target, patch))
        val other = source.copyOf().also { it[0] = 99 }
        assertThrows(PatchException::class.java) { RomPatcher.apply(other, patch) }
    }

    @Test fun `bps patches are applied and refuse another dump or a damaged patch`() {
        val patch = makeBps(source, target)
        assertArrayEquals(target, RomPatcher.apply(source, patch))
        val other = source.copyOf().also { it[1] = 99 }
        assertThrows(PatchException::class.java) { RomPatcher.apply(other, patch) }
        val damaged = patch.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertThrows(PatchException::class.java) { RomPatcher.apply(source, damaged) }
    }

    @Test fun `unknown formats are refused and output names are clean`() {
        assertThrows(PatchException::class.java) { RomPatcher.apply(source, "HELLO".toByteArray()) }
        assertEquals("Game (USA) [English v1.1].gba", RomPatcher.outputName("Game (USA).gba", "English v1.1.bps"))
    }

    // ---- small encoders for the tests ----------------------------------------------------------

    private fun ByteArrayOutputStream.varint(value: Long) {
        var data = value
        while (true) {
            val x = (data and 0x7F).toInt()
            data = data shr 7
            if (data == 0L) { write(0x80 or x); return }
            write(x)
            data--
        }
    }

    private fun ByteArrayOutputStream.u32(v: Long) { for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    private fun finish(out: ByteArrayOutputStream, src: ByteArray, dst: ByteArray): ByteArray {
        out.u32(crc(src)); out.u32(crc(dst))
        val body = out.toByteArray()
        return ByteArrayOutputStream().apply { write(body); u32(crc(body)) }.toByteArray()
    }

    private fun makeUps(src: ByteArray, dst: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("UPS1".toByteArray()); out.varint(src.size.toLong()); out.varint(dst.size.toLong())
        val size = maxOf(src.size, dst.size)
        var last = 0
        var i = 0
        while (i < size) {
            val a = if (i < src.size) src[i] else 0
            val b = if (i < dst.size) dst[i] else 0
            if (a == b) { i++; continue }
            out.varint((i - last).toLong())
            while (i < size) {
                val x = (if (i < src.size) src[i] else 0).toInt() xor (if (i < dst.size) dst[i] else 0).toInt()
                if (x == 0) break
                out.write(x and 0xFF); i++
            }
            out.write(0); i++; last = i
        }
        return finish(out, src, dst)
    }

    private fun makeBps(src: ByteArray, dst: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("BPS1".toByteArray()); out.varint(src.size.toLong()); out.varint(dst.size.toLong()); out.varint(0)
        var i = 0
        while (i < dst.size) {
            val same = i < src.size && src[i] == dst[i]
            var j = i
            while (j < dst.size && (j < src.size && src[j] == dst[j]) == same) j++
            val length = (j - i).toLong()
            out.varint(((length - 1) shl 2) or (if (same) 0L else 1L))
            if (!same) out.write(dst, i, j - i)
            i = j
        }
        return finish(out, src, dst)
    }
}
