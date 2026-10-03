package com.cortinadev.dogmatix.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The source list as one or more QR codes, so another device can take it over with a photo:
 * the text is gzipped, Base64-encoded and cut into parts of at most [CHUNK] characters, each
 * marked `DGXS1:<part>/<parts>:<id>:<data>` so the parts can be read in any order and parts of
 * two different lists are never mixed.
 */
object QrTransfer {
    const val PREFIX = "DGXS1"

    /** Characters of data per code: small enough for a phone camera to read off a screen. */
    const val CHUNK = 1000

    fun encode(text: String, id: String = randomId()): List<String> {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val parts = data.chunked(CHUNK).ifEmpty { listOf("") }
        return parts.mapIndexed { i, chunk -> "$PREFIX:${i + 1}/${parts.size}:$id:$chunk" }
    }

    data class Part(val index: Int, val total: Int, val id: String, val data: String)

    fun parsePart(content: String): Part? {
        val fields = content.trim().split(':', limit = 4)
        if (fields.size != 4 || fields[0] != PREFIX) return null
        val (index, total) = fields[1].split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        if (index < 1 || total < 1 || index > total || total > 99) return null
        return Part(index, total, fields[2], fields[3])
    }

    /** Collects parts from photos until one list is complete. */
    class Collector {
        private val parts = HashMap<Int, Part>()
        var id: String? = null
            private set
        var total: Int = 0
            private set
        val have: Int get() = parts.size

        /** Adds a decoded code; returns false for something that is not (or does not belong to) this list. */
        fun add(content: String): Boolean {
            val part = parsePart(content) ?: return false
            if (id != null && (part.id != id || part.total != total)) {
                // A code of another list: start over with that one.
                parts.clear()
            }
            id = part.id
            total = part.total
            parts[part.index] = part
            return true
        }

        val complete: Boolean get() = total > 0 && parts.size == total

        /** The whole text once [complete]; null before (or when the data is damaged). */
        fun text(): String? {
            if (!complete) return null
            val data = (1..total).joinToString("") { parts.getValue(it).data }
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(data))).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }

    private fun randomId(): String = (1..6).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
}
