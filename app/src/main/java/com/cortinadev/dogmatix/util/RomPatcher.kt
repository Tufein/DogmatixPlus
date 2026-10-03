package com.cortinadev.dogmatix.util

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** A patch that could not be applied, with the reason in [message]. */
class PatchException(message: String) : Exception(message)

/**
 * Applies the patch formats fan translations and ROM hacks come in:
 *  - **IPS** (`PATCH` … `EOF`): records of offset + bytes, run-length records, optional truncation;
 *  - **UPS** (`UPS1`): XOR blocks with variable-length offsets and CRC-32 checks of source,
 *    target and patch;
 *  - **BPS** (`BPS1`): copy / read actions with CRC-32 checks of source, target and patch.
 * UPS and BPS refuse a ROM whose checksum is not the one the patch was made for (a different
 * dump or region), which is the usual cause of a "broken" patched game.
 */
object RomPatcher {

    enum class Format { IPS, UPS, BPS }

    /** Largest ROM patched in memory. */
    const val MAX_SIZE = 256L * 1024 * 1024

    fun formatOf(patch: ByteArray): Format? = when {
        patch.startsWith("PATCH") -> Format.IPS
        patch.startsWith("UPS1") -> Format.UPS
        patch.startsWith("BPS1") -> Format.BPS
        else -> null
    }

    fun apply(rom: ByteArray, patch: ByteArray): ByteArray = when (formatOf(patch)) {
        Format.IPS -> ips(rom, patch)
        Format.UPS -> ups(rom, patch)
        Format.BPS -> bps(rom, patch)
        null -> throw PatchException("Not an IPS, UPS or BPS patch")
    }

    /** The name of the patched copy: `Game (patched).gba`, or with the patch's own name when it has one. */
    fun outputName(romName: String, patchName: String): String {
        val dot = romName.lastIndexOf('.')
        val base = if (dot > 0) romName.substring(0, dot) else romName
        val ext = if (dot > 0) romName.substring(dot) else ""
        val label = patchName.substringBeforeLast('.').trim().ifEmpty { "patched" }
        return DatMatcher.safeFileName("$base [$label]$ext")
    }

    // ---- IPS ------------------------------------------------------------------------------------

    private fun ips(rom: ByteArray, patch: ByteArray): ByteArray {
        var out = rom.copyOf()
        var p = 5
        fun u8(): Int { if (p >= patch.size) throw PatchException("IPS patch ends early"); return patch[p++].toInt() and 0xFF }
        fun u16() = (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
        fun ensure(size: Int) { if (size > out.size) out = out.copyOf(size) }
        while (true) {
            if (p + 3 > patch.size) throw PatchException("IPS patch has no EOF marker")
            if (patch[p] == 'E'.code.toByte() && patch[p + 1] == 'O'.code.toByte() && patch[p + 2] == 'F'.code.toByte()) {
                p += 3
                // Optional 3-byte truncation length after EOF.
                if (p + 3 <= patch.size) out = out.copyOf(u24())
                return out
            }
            val offset = u24()
            val size = u16()
            if (size > 0) {
                ensure(offset + size)
                if (p + size > patch.size) throw PatchException("IPS patch ends early")
                System.arraycopy(patch, p, out, offset, size)
                p += size
            } else {
                val run = u16()
                val value = u8().toByte()
                ensure(offset + run)
                java.util.Arrays.fill(out, offset, offset + run, value)
            }
        }
    }

    // ---- UPS ------------------------------------------------------------------------------------

    private fun ups(rom: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 4 + 12) throw PatchException("UPS patch is too short")
        checkPatchCrc(patch, "UPS")
        var p = 4
        fun varint(): Long {
            var value = 0L
            var shift = 1L
            while (true) {
                if (p >= patch.size - 12) throw PatchException("UPS patch ends early")
                val x = patch[p++].toInt() and 0xFF
                value += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                value += shift
            }
            return value
        }
        val inputSize = varint()
        val outputSize = varint()
        val sourceCrc = readU32(patch, patch.size - 12)
        val targetCrc = readU32(patch, patch.size - 8)
        // UPS is symmetric: a patch can also turn the target back into the source.
        val (expectedIn, size, wantCrc) = when {
            rom.size.toLong() == inputSize && crc(rom) == sourceCrc -> Triple(inputSize, outputSize, targetCrc)
            rom.size.toLong() == outputSize && crc(rom) == targetCrc -> Triple(outputSize, inputSize, sourceCrc)
            else -> throw PatchException("This patch was made for another dump of the game (checksum differs)")
        }
        if (size > MAX_SIZE) throw PatchException("Result too large")
        val out = rom.copyOf(size.toInt())
        if (expectedIn < size) java.util.Arrays.fill(out, expectedIn.toInt(), size.toInt(), 0)
        var o = 0L
        while (p < patch.size - 12) {
            o += varint()
            while (p < patch.size - 12) {
                val x = patch[p++]
                if (x.toInt() == 0) { o++; break }
                if (o < size) out[o.toInt()] = (out[o.toInt()].toInt() xor x.toInt()).toByte()
                o++
            }
        }
        if (crc(out) != wantCrc) throw PatchException("The patched game has the wrong checksum")
        return out
    }

    // ---- BPS ------------------------------------------------------------------------------------

    private fun bps(rom: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 4 + 12) throw PatchException("BPS patch is too short")
        checkPatchCrc(patch, "BPS")
        val end = patch.size - 12
        var p = 4
        fun varint(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                if (p >= end) throw PatchException("BPS patch ends early")
                val x = patch[p++].toInt() and 0xFF
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                data += shift
            }
            return data
        }
        val sourceSize = varint()
        val targetSize = varint()
        val metadataSize = varint()
        p += metadataSize.toInt()
        val sourceCrc = readU32(patch, patch.size - 12)
        val targetCrc = readU32(patch, patch.size - 8)
        if (rom.size.toLong() != sourceSize || crc(rom) != sourceCrc) {
            throw PatchException("This patch was made for another dump of the game (checksum differs)")
        }
        if (targetSize > MAX_SIZE) throw PatchException("Result too large")
        val out = ByteArray(targetSize.toInt())
        var outPos = 0
        var sourceRel = 0
        var targetRel = 0
        while (p < end) {
            val data = varint()
            val command = (data and 3).toInt()
            val length = ((data shr 2) + 1).toInt()
            if (outPos + length > out.size) throw PatchException("BPS patch writes past the end")
            when (command) {
                0 -> { System.arraycopy(rom, outPos, out, outPos, length); outPos += length }   // SourceRead
                1 -> { System.arraycopy(patch, p, out, outPos, length); p += length; outPos += length }   // TargetRead
                2 -> {   // SourceCopy
                    val d = varint()
                    sourceRel += ((if (d and 1L != 0L) -1 else 1) * (d shr 1)).toInt()
                    System.arraycopy(rom, sourceRel, out, outPos, length)
                    sourceRel += length; outPos += length
                }
                else -> {   // TargetCopy (may overlap: byte by byte)
                    val d = varint()
                    targetRel += ((if (d and 1L != 0L) -1 else 1) * (d shr 1)).toInt()
                    repeat(length) { out[outPos++] = out[targetRel++] }
                }
            }
        }
        if (crc(out) != targetCrc) throw PatchException("The patched game has the wrong checksum")
        return out
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun checkPatchCrc(patch: ByteArray, label: String) {
        val stored = readU32(patch, patch.size - 4)
        val actual = CRC32().apply { update(patch, 0, patch.size - 4) }.value
        if (stored != actual) throw PatchException("The $label patch file is damaged (checksum differs)")
    }

    private fun readU32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    fun crc(b: ByteArray): Long = CRC32().apply { update(b) }.value

    private fun ByteArray.startsWith(magic: String): Boolean =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    // ---- writers, for tests and for making patches -----------------------------------------------

    /** A minimal IPS patch turning [source] into [target] (same length or longer). */
    fun makeIps(source: ByteArray, target: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("PATCH".toByteArray())
        var i = 0
        while (i < target.size) {
            if (i < source.size && source[i] == target[i]) { i++; continue }
            var j = i
            while (j < target.size && j - i < 0xFFFF && (j >= source.size || source[j] != target[j])) j++
            out.write(byteArrayOf((i shr 16).toByte(), (i shr 8).toByte(), i.toByte(), ((j - i) shr 8).toByte(), (j - i).toByte()))
            out.write(target, i, j - i)
            i = j
        }
        out.write("EOF".toByteArray())
        return out.toByteArray()
    }
}
