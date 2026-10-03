package com.cortinadev.dogmatix.util

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32

enum class HashAlgo(val tag: String, val hexLength: Int) {
    CRC32("crc32", 8), MD5("md5", 32), SHA1("sha1", 40), SHA256("sha256", 64);

    companion object {
        fun fromTag(tag: String): HashAlgo? = entries.firstOrNull { it.tag == tag.lowercase() }
    }
}

/** A hash a source published for a file. Stored as `algorithm:hex` (`sha1:da39…`). */
data class ExpectedHash(val algo: HashAlgo, val hex: String) {
    val spec: String get() = "${algo.tag}:$hex"
}

enum class VerifyState { CHECKING, VERIFIED, MISMATCH }

object Checksums {

    fun parse(spec: String?): ExpectedHash? {
        val (tag, hex) = (spec ?: return null).trim().lowercase().split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val algo = HashAlgo.fromTag(tag) ?: return null
        val clean = hex.trim()
        return if (clean.length == algo.hexLength && clean.all { it in '0'..'9' || it in 'a'..'f' }) ExpectedHash(algo, clean) else null
    }

    /** The strongest of the hashes a source lists: SHA-1, then MD5, then CRC32 (empty ones are ignored). */
    fun best(sha1: String?, md5: String?, crc32: String?): String? =
        listOf(HashAlgo.SHA1 to sha1, HashAlgo.MD5 to md5, HashAlgo.CRC32 to crc32)
            .firstNotNullOfOrNull { (algo, hex) -> parse("${algo.tag}:${hex.orEmpty().trim()}")?.spec }

    /** `Content-MD5` header: base64 of the 16 raw bytes. */
    fun fromContentMd5(header: String?): String? = runCatching {
        val bytes = Base64.getDecoder().decode(header!!.trim())
        bytes.takeIf { it.size == 16 }?.let { "md5:" + toHex(it) }
    }.getOrNull()

    /** RFC 3230 `Digest: sha-256=…, md5=…` (base64 values); the strongest known one wins. */
    fun fromDigestHeader(header: String?): String? {
        val parts = (header ?: return null).split(',').mapNotNull { part ->
            val (name, value) = part.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            name.trim().lowercase() to value.trim()
        }.toMap()
        fun decode(name: String, algo: HashAlgo): String? = parts[name]?.let { value ->
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()
                ?.takeIf { it.size * 2 == algo.hexLength }?.let { "${algo.tag}:${toHex(it)}" }
        }
        return decode("sha-256", HashAlgo.SHA256) ?: decode("sha", HashAlgo.SHA1) ?: decode("md5", HashAlgo.MD5)
    }

    /** Streams [input] through the hash of [algo]; returns lower-case hex. */
    fun hexOf(input: InputStream, algo: HashAlgo, onProgress: ((Long) -> Unit)? = null): String {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        if (algo == HashAlgo.CRC32) {
            val crc = CRC32()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n); total += n; onProgress?.invoke(total)
            }
            return "%08x".format(crc.value)
        }
        val digest = MessageDigest.getInstance(when (algo) {
            HashAlgo.MD5 -> "MD5"
            HashAlgo.SHA1 -> "SHA-1"
            else -> "SHA-256"
        })
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n); total += n; onProgress?.invoke(total)
        }
        return toHex(digest.digest())
    }

    fun matches(expected: ExpectedHash, actualHex: String): Boolean = expected.hex.equals(actualHex.trim(), ignoreCase = true)

    /** Archives are unpacked after the download, so the file on disk is no longer what the hash describes. */
    fun canVerify(extension: String, unpacked: Boolean): Boolean = !unpacked

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
