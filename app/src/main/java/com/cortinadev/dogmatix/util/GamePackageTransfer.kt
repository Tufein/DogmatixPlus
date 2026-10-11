package com.cortinadev.dogmatix.util

import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Portable file records contain no device folder grants, download credentials or game bytes. */
object GamePackageTransfer {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_PARTS = 50_000
    const val PORTABLE_ROOT = "content://dogmatix.package-transfer/tree/selected"
    private const val FORMAT = "dogmatix-game-packages"
    private data class Document(val format: String = FORMAT, val version: Int = 1, val packages: List<String>)

    fun encode(records: List<GamePackages.Manifest>): ByteArray {
        require(records.isNotEmpty() && records.size <= GamePackages.MAX_PACKAGES)
        require(records.sumOf { it.parts.size.toLong() } <= MAX_PARTS)
        val portable = records.map { GamePackages.validate(it.copy(rootUri = PORTABLE_ROOT)) }
        validate(portable)
        return Gson().toJson(Document(packages = portable.map { GamePackages.encode(it).toString(Charsets.UTF_8) }))
            .toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Package export is too large" } }
    }

    fun decode(bytes: ByteArray): List<GamePackages.Manifest> {
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            var format: String? = null
            var version: String? = null
            var records: List<GamePackages.Manifest>? = null
            val seen = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name))
                when (name) {
                    "format" -> { require(reader.peek() == JsonToken.STRING); format = reader.nextString() }
                    "version" -> { require(reader.peek() == JsonToken.NUMBER); version = reader.nextString() }
                    "packages" -> {
                        val found = ArrayList<GamePackages.Manifest>()
                        var parts = 0L
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(found.size < GamePackages.MAX_PACKAGES && reader.peek() == JsonToken.STRING)
                            val manifest = GamePackages.decode(reader.nextString().toByteArray(Charsets.UTF_8))
                            parts += manifest.parts.size
                            require(parts <= MAX_PARTS)
                            found += manifest
                        }
                        reader.endArray()
                        records = found
                    }
                    else -> error("Unknown package-transfer field")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && format == FORMAT && version == "1")
            requireNotNull(records).also(::validate)
        }
    }

    private fun validate(records: List<GamePackages.Manifest>) {
        require(records.isNotEmpty() && records.size <= GamePackages.MAX_PACKAGES)
        require(records.sumOf { it.parts.size.toLong() } <= MAX_PARTS)
        require(records.map { it.sourceIdentity }.distinct().size == records.size) { "Duplicate package identity" }
        records.forEach { require(it.rootUri == PORTABLE_ROOT); GamePackages.validate(it) }
    }
}
