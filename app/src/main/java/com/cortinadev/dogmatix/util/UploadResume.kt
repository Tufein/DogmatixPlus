package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * An upload to RomM that is cut short keeps its server-side session: chunks already sent stay
 * there, so the next try continues at [nextChunk] instead of sending the whole file again.
 */
data class UploadSession(
    /** The download this upload belongs to (what the Downloads list calls it). */
    val downloadFileName: String,
    val platformId: Int,
    /** Name of the file on disk being uploaded (one download can hold several files). */
    val fileName: String,
    val size: Long,
    val totalChunks: Int,
    val uploadId: String,
    /** Index of the next chunk to send; everything before it has been accepted. */
    val nextChunk: Int,
    val updatedAt: Long
) {
    val key: String get() = key(platformId, fileName, size)

    companion object {
        fun key(platformId: Int, fileName: String, size: Long) = "$platformId|$fileName|$size"
    }
}

object UploadSessions {
    /** A server drops half-finished uploads after a while; do not try to continue very old ones. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun encode(sessions: Collection<UploadSession>): String = JsonObject().apply {
        addProperty("version", 1)
        add("sessions", JsonArray().also { array ->
            sessions.forEach { s ->
                array.add(JsonObject().apply {
                    addProperty("downloadFileName", s.downloadFileName)
                    addProperty("platformId", s.platformId)
                    addProperty("fileName", s.fileName)
                    addProperty("size", s.size)
                    addProperty("totalChunks", s.totalChunks)
                    addProperty("uploadId", s.uploadId)
                    addProperty("nextChunk", s.nextChunk)
                    addProperty("updatedAt", s.updatedAt)
                })
            }
        })
    }.toString()

    /** Never throws: a damaged file simply means nothing to resume. */
    fun decode(text: String): List<UploadSession> = runCatching {
        JsonParser.parseString(text).asJsonObject.getAsJsonArray("sessions").mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                UploadSession(
                    downloadFileName = o.get("downloadFileName").asString,
                    platformId = o.get("platformId").asInt,
                    fileName = o.get("fileName").asString,
                    size = o.get("size").asLong,
                    totalChunks = o.get("totalChunks").asInt,
                    uploadId = o.get("uploadId").asString,
                    nextChunk = o.get("nextChunk").asInt,
                    updatedAt = o.get("updatedAt").asLong
                ).takeIf { it.uploadId.isNotBlank() && it.totalChunks > 0 && it.nextChunk in 0 until it.totalChunks }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** The session to continue for this file, or null when there is none or it is too old / does not fit. */
    fun resumable(sessions: Collection<UploadSession>, platformId: Int, fileName: String, size: Long, totalChunks: Int, now: Long): UploadSession? =
        sessions.firstOrNull { it.key == UploadSession.key(platformId, fileName, size) }
            ?.takeIf { it.totalChunks == totalChunks && it.nextChunk > 0 && now - it.updatedAt <= MAX_AGE_MS }

    /** Byte offset in the file where chunk [index] starts. */
    fun offsetOf(index: Int, chunkSize: Int): Long = index.toLong() * chunkSize

    /** Failures worth keeping the session for: no connection, a server hiccup. A 4xx says the session is gone. */
    fun isTransient(httpCode: Int?): Boolean = httpCode == null || httpCode >= 500 || httpCode == 408 || httpCode == 429
}
