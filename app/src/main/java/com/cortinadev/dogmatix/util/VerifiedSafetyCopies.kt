package com.cortinadev.dogmatix.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Private, independently verified copies used by both save sync and manual save restoration. */
object VerifiedSafetyCopies {
    class IntegrityException : IOException("Safety copy integrity check failed")

    /** Returns only after both the contents and their checksum have been flushed and read back. */
    fun keep(root: File, kind: SaveKind, path: String, bytes: ByteArray, now: Long = System.currentTimeMillis()): File {
        if (path.startsWith('/') || '\\' in path || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Unexpected save path")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val batch = File(root, "$stamp-${UUID.randomUUID()}")
        val target = File(batch, "${kind.apiPath}/$path")
        requireInside(root, target)
        val parent = target.parentFile ?: throw IOException("Unexpected save path")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Could not create safety copy folder")
        try {
            writeVerified(target, bytes)
            writeVerified(File(target.path + ".sha256"), sha256(bytes).toByteArray(Charsets.US_ASCII))
            if (!read(root, target.relativeTo(root).invariantSeparatorsPath, bytes.size.toLong()).contentEquals(bytes)) {
                throw IntegrityException()
            }
            return target
        } catch (failure: Exception) {
            batch.deleteRecursively()
            throw failure
        }
    }

    /** Old copies have no checksum; new copies are checked before anything on the device is changed. */
    fun read(root: File, relative: String, maxBytes: Long): ByteArray {
        val target = File(root, relative)
        requireInside(root, target)
        if (!target.isFile) throw IOException("Safety copy is no longer available")
        if (target.length() > maxBytes) throw IOException("Safety copy is too large")
        val bytes = target.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size().toLong() + count > maxBytes) throw IOException("Safety copy is too large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val receipt = File(target.path + ".sha256")
        val batch = relative.replace('\\', '/').substringBefore('/')
        if (!receipt.exists() && Regex("\\d{8}-\\d{6}-[0-9a-fA-F-]{36}").matches(batch)) throw IntegrityException()
        if (receipt.exists()) {
            requireInside(root, receipt)
            if (!receipt.isFile || receipt.length() != 64L || receipt.readText(Charsets.US_ASCII) != sha256(bytes)) {
                throw IntegrityException()
            }
        }
        return bytes
    }

    private fun writeVerified(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.readBytes().contentEquals(bytes)) throw IntegrityException()
            if (target.exists() || !temporary.renameTo(target)) throw IOException("Could not commit safety copy")
        } finally {
            temporary.delete()
        }
    }

    private fun requireInside(root: File, file: File) {
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("Unexpected save path")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
