package com.cortinadev.dogmatix.util

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.StringReader
import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** Durable read-only ownership receipts. They are never a permission to delete files. */
object GamePackages {
    const val SCHEMA = 1
    const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    const val MAX_PACKAGES = 2000
    data class Part(val path: String, val bytes: Long, val sha256: String)
    data class Manifest(val schema: Int = SCHEMA, val sourceIdentity: String, val consoleId: String,
        val fileName: String, val rootUri: String, val subPath: String, val createdAt: Long,
        val parts: List<Part>)

    /** The absolute download URL survives queue history restoration; sourceUrl does not. */
    fun identity(console: String, url: String, name: String, magnet: String?, index: Int?): String =
        digest(listOf(console, url, name, magnet.orEmpty(), index?.toString().orEmpty())
            .joinToString("") { "${it.length}:$it" })
    fun receiptKey(identity: String, rootUri: String): String = digest("${identity.length}:$identity${rootUri.length}:$rootUri")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun encode(manifest: Manifest): ByteArray = Gson().toJson(validate(manifest)).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_MANIFEST_BYTES)
    }

    /** Strict tokens, exact integer types and unique fields: a corrupt receipt never grants files. */
    fun decode(bytes: ByteArray): Manifest {
        require(bytes.size <= MAX_MANIFEST_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            fun textValue(): String {
                require(reader.peek() == JsonToken.STRING)
                return reader.nextString()
            }
            fun numberValue(): Long {
                require(reader.peek() == JsonToken.NUMBER)
                val raw = reader.nextString()
                require(raw.matches(Regex("0|[1-9][0-9]*")))
                return raw.toLong()
            }
            var schema: Long? = null
            var identity: String? = null
            var console: String? = null
            var name: String? = null
            var root: String? = null
            var sub: String? = null
            var created: Long? = null
            var parts: List<Part>? = null
            val fields = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field))
                when (field) {
                    "schema" -> schema = numberValue()
                    "sourceIdentity" -> identity = textValue()
                    "consoleId" -> console = textValue()
                    "fileName" -> name = textValue()
                    "rootUri" -> root = textValue()
                    "subPath" -> sub = textValue()
                    "createdAt" -> created = numberValue()
                    "parts" -> {
                        val found = ArrayList<Part>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(found.size < Constants.MAX_ARCHIVE_ENTRIES)
                            var path: String? = null
                            var size: Long? = null
                            var hash: String? = null
                            val partFields = HashSet<String>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(partFields.add(key))
                                when (key) {
                                    "path" -> path = textValue()
                                    "bytes" -> size = numberValue()
                                    "sha256" -> hash = textValue()
                                    else -> error("Unknown package part field")
                                }
                            }
                            reader.endObject()
                            found += Part(requireNotNull(path), requireNotNull(size), requireNotNull(hash))
                        }
                        reader.endArray()
                        parts = found
                    }
                    else -> error("Unknown package field")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            require(schema == SCHEMA.toLong())
            validate(Manifest(SCHEMA, requireNotNull(identity), requireNotNull(console), requireNotNull(name),
                requireNotNull(root), requireNotNull(sub), requireNotNull(created), requireNotNull(parts)))
        }
    }

    fun validate(manifest: Manifest): Manifest {
        require(listOf(manifest.sourceIdentity, manifest.consoleId, manifest.fileName, manifest.rootUri, manifest.subPath)
            .all { Charsets.UTF_8.newEncoder().canEncode(it) })
        require(manifest.schema == SCHEMA && manifest.sourceIdentity.matches(Regex("[a-f0-9]{64}")))
        require(manifest.consoleId.isNotBlank() && manifest.consoleId.length <= 512)
        require(manifest.fileName.isNotBlank() && manifest.fileName.length <= 8192)
        require(manifest.rootUri.startsWith("content://") && manifest.rootUri.length <= 8192)
        require(manifest.createdAt > 0 && manifest.parts.isNotEmpty() && manifest.parts.size <= Constants.MAX_ARCHIVE_ENTRIES)
        require(manifest.subPath.isEmpty() || exactPath(manifest.subPath) &&
            manifest.subPath.split('/').none { it.startsWith(".dogmatix-") })
        val planned = ArchivePlan.check(manifest.parts.mapIndexed { i, part ->
            require(Charsets.UTF_8.newEncoder().canEncode(part.path))
            require(exactPath(part.path) && part.path.split('/').none { it.startsWith(".dogmatix-") })
            require(part.bytes >= 0 && part.sha256.matches(Regex("[a-f0-9]{64}")))
            ArchivePlan.Entry(i, part.path, size = part.bytes)
        })
        require(planned.files.size == manifest.parts.size)
        return manifest
    }
    private fun exactPath(path: String): Boolean = runCatching { ArchivePlan.safeRelativePath(path) == path }.getOrDefault(false)
}
