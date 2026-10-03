package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.RemoteSaveFile
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomCandidate
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class RommException(message: String) : IOException(message)

data class RommRom(
    val id: Int,
    val fsName: String,
    val fsSizeBytes: Long,
    val name: String,
    /** Path of the cover below the server's resources (`roms/3/15/cover/big.png`), or "". */
    val coverPath: String = "",
    /** The strongest hash the server lists for the file, as `algorithm:hex` (see Checksums); null when none. */
    val hash: String? = null
)

data class RommPlatform(val id: Int, val slug: String, val fsSlug: String, val name: String, val displayName: String) {
    val label: String get() = displayName.ifBlank { name.ifBlank { slug } }
}

/**
 * The RomM endpoints Dogmatix uses: platform listing (for the console mapping) and the chunked
 * ROM upload. Base URL and token come from Settings → RomM. All paths live here.
 */
@Singleton
class RommClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private suspend fun baseUrl(): String =
        settingsRepository.rommUrl.first().trim().trimEnd('/').ifEmpty { throw RommException("RomM server URL not set") }

    private suspend fun headers(): Map<String, String> =
        mapOf("Authorization" to authHeader(settingsRepository.rommToken.first()))

    /** Configured server URL (no trailing slash) or "" when RomM is not set up. */
    suspend fun configuredBaseUrl(): String = settingsRepository.rommUrl.first().trim().trimEnd('/')

    /** Headers a plain HTTP download from this server needs. */
    suspend fun downloadHeaders(): Map<String, String> = headers()

    /** Every ROM of [platformId], paged through `/api/roms`. */
    suspend fun roms(platformId: Int): List<RommRom> = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val out = mutableListOf<RommRom>()
        var offset = 0
        val limit = 500
        while (true) {
            val response = JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/roms?platform_ids=$platformId&limit=$limit&offset=$offset", headers()))
            val json = response.json ?: throw RommException("RomM returned no JSON")
            val items = when {
                json.isJsonArray -> json.asJsonArray
                json.isJsonObject -> json.asJsonObject.getAsJsonArray("items") ?: throw RommException("Unexpected /api/roms payload")
                else -> throw RommException("Unexpected /api/roms payload")
            }
            items.map { it.asJsonObject }.forEach { r ->
                val fsName = r.str("fs_name").ifEmpty { r.str("file_name") }
                if (fsName.isNotEmpty()) out += RommRom(
                    id = r.get("id").asInt,
                    fsName = fsName,
                    fsSizeBytes = r.get("fs_size_bytes")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
                    name = r.str("name"),
                    coverPath = listOf("path_cover_large", "path_cover_l", "path_cover_small", "path_cover_s")
                        .firstNotNullOfOrNull { r.str(it).takeIf { v -> v.isNotBlank() } }.orEmpty(),
                    hash = Checksums.best(r.str("sha1_hash"), r.str("md5_hash"), r.str("crc_hash"))
                )
            }
            if (items.size() < limit) break
            offset += limit
        }
        out
    }

    /** A RomM collection: its id, name and the ROMs in it. */
    data class RommCollection(val id: Int, val name: String, val romIds: List<Int>)

    /** The user's collections (`GET /api/collections`). */
    suspend fun collections(): List<RommCollection> = withContext(Dispatchers.IO) {
        val json = JsonHttp.requireOk(JsonHttp.request("GET", "${baseUrl()}/api/collections", headers())).json
        val items = when {
            json == null -> return@withContext emptyList()
            json.isJsonArray -> json.asJsonArray
            json.isJsonObject -> json.asJsonObject.getAsJsonArray("items") ?: return@withContext emptyList()
            else -> return@withContext emptyList()
        }
        items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null
            val ids = (o.get("rom_ids") as? com.google.gson.JsonArray)?.mapNotNull { runCatching { it.asInt }.getOrNull() }
                ?: (o.get("roms") as? com.google.gson.JsonArray)?.mapNotNull { r -> (r as? JsonObject)?.get("id")?.asInt }
                ?: emptyList()
            RommCollection(id, o.str("name"), ids)
        }
    }

    /** Creates a collection named [name]; returns its id. */
    suspend fun createCollection(name: String): Int = withContext(Dispatchers.IO) {
        val (body, type) = JsonHttp.multipartBody(mapOf("name" to name, "description" to "Dogmatix+"))
        val obj = JsonHttp.requireOk(JsonHttp.request("POST", "${baseUrl()}/api/collections", headers(), body = body, contentType = type)).json
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: throw RommException("RomM did not return the new collection")
        obj.get("id").asInt
    }

    /**
     * Sets the ROMs of collection [id] (`PUT /api/collections/{id}` with `rom_ids` as a JSON list in
     * the form, as RomM's web interface sends it).
     */
    suspend fun setCollectionRoms(id: Int, name: String, romIds: List<Int>) = withContext(Dispatchers.IO) {
        val ids = romIds.distinct().joinToString(",", "[", "]")
        val (body, type) = JsonHttp.multipartBody(mapOf("name" to name, "rom_ids" to ids))
        JsonHttp.requireOk(JsonHttp.request("PUT", "${baseUrl()}/api/collections/$id", headers(), body = body, contentType = type))
        Unit
    }

    /** Returns the number of platforms the server reports, as a connection check. */
    suspend fun testConnection(url: String, token: String): Int = withContext(Dispatchers.IO) {
        platforms(url.trim().trimEnd('/'), mapOf("Authorization" to authHeader(token))).size
    }

    suspend fun platforms(): List<RommPlatform> = withContext(Dispatchers.IO) { platforms(baseUrl(), headers()) }

    private fun platforms(base: String, headers: Map<String, String>): List<RommPlatform> {
        val response = JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/platforms", headers))
        val array = when {
            response.json?.isJsonArray == true -> response.json.asJsonArray
            response.json?.isJsonObject == true -> response.json.asJsonObject.getAsJsonArray("items") ?: throw RommException("Unexpected /api/platforms payload")
            else -> throw RommException("RomM returned no JSON")
        }
        return array.map { it.asJsonObject }.map {
            RommPlatform(
                id = it.get("id").asInt,
                slug = it.str("slug"),
                fsSlug = it.str("fs_slug"),
                name = it.str("name"),
                displayName = it.str("display_name").ifEmpty { it.str("custom_name") }
            )
        }
    }

    /** Opens a chunked upload session and returns its id. */
    suspend fun uploadStart(platformId: Int, fileName: String, totalSize: Long, totalChunks: Int): String = withContext(Dispatchers.IO) {
        val response = JsonHttp.requireOk(
            JsonHttp.request(
                "POST", "${baseUrl()}/api/roms/upload/start",
                headers = headers() + mapOf(
                    "x-upload-platform" to platformId.toString(),
                    "x-upload-filename" to fileName,
                    "x-upload-total-size" to totalSize.toString(),
                    "x-upload-total-chunks" to totalChunks.toString()
                ),
                body = ByteArray(0)
            )
        )
        val obj = response.json?.takeIf { it.isJsonObject }?.asJsonObject ?: throw RommException("RomM did not return an upload session")
        listOf("upload_id", "id", "session_id").firstNotNullOfOrNull { obj.get(it)?.takeUnless { v -> v.isJsonNull }?.asString }
            ?: throw RommException("RomM upload session has no id")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray, length: Int) = withContext(Dispatchers.IO) {
        val payload = if (length == bytes.size) bytes else bytes.copyOf(length)
        JsonHttp.requireOk(
            JsonHttp.request(
                "PUT", "${baseUrl()}/api/roms/upload/$uploadId",
                headers = headers() + mapOf("x-chunk-index" to index.toString()),
                body = payload,
                contentType = "application/octet-stream",
                readTimeoutMs = 120_000
            )
        )
    }

    suspend fun uploadComplete(uploadId: String) = withContext(Dispatchers.IO) {
        JsonHttp.requireOk(JsonHttp.request("POST", "${baseUrl()}/api/roms/upload/$uploadId/complete", headers(), body = ByteArray(0), readTimeoutMs = 300_000))
    }

    suspend fun uploadCancel(uploadId: String) = withContext(Dispatchers.IO) {
        runCatching { JsonHttp.request("POST", "${baseUrl()}/api/roms/upload/$uploadId/cancel", headers(), body = ByteArray(0)) }
    }

    // ---- Saves and states ---------------------------------------------------------------------

    /**
     * Every save (or state) of the account, newest server version per name is picked by the
     * caller. Slot saves (a dated history other clients keep) are left out.
     */
    suspend fun saves(kind: SaveKind): List<RemoteSaveFile> = withContext(Dispatchers.IO) {
        val response = JsonHttp.requireOk(JsonHttp.request("GET", "${baseUrl()}/api/${kind.apiPath}", headers(), readTimeoutMs = 60_000))
        val items = when {
            response.json?.isJsonArray == true -> response.json.asJsonArray
            response.json?.isJsonObject == true -> response.json.asJsonObject.getAsJsonArray("items") ?: throw RommException("Unexpected /api/${kind.apiPath} payload")
            else -> throw RommException("RomM returned no JSON")
        }
        items.mapNotNull { (it as? JsonObject)?.let { obj -> remoteSave(kind, obj) } }
    }

    private fun remoteSave(kind: SaveKind, obj: JsonObject): RemoteSaveFile? {
        if (obj.str("slot").isNotEmpty() || obj.get("missing_from_fs")?.takeUnless { it.isJsonNull }?.asBoolean == true) return null
        val id = obj.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return null
        val romId = obj.get("rom_id")?.takeUnless { it.isJsonNull }?.asInt ?: return null
        val name = obj.str("file_name").ifEmpty { return null }
        return RemoteSaveFile(
            kind = kind, id = id, romId = romId, fileName = name,
            emulator = obj.str("emulator").ifEmpty { null },
            updatedAt = obj.str("updated_at"),
            size = obj.get("file_size_bytes")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
            downloadPath = obj.str("download_path"),
            contentHash = obj.str("content_hash").ifEmpty { null }
        )
    }

    /** The bytes of [save]: its `download_path`, or the `/content` route of newer servers. */
    suspend fun downloadSave(save: RemoteSaveFile, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val primary = save.downloadPath.takeIf { it.startsWith("/") }?.let { base + it.replace(" ", "%20") }
        val fallback = "$base/api/${save.kind.apiPath}/${save.id}/content"
        try {
            JsonHttp.download(primary ?: fallback, headers(), maxBytes)
        } catch (e: JsonHttp.HttpException) {
            if (primary == null || e.code == 401 || e.code == 403) throw e
            JsonHttp.download(fallback, headers(), maxBytes)
        }
    }

    /**
     * Uploads [bytes] as [fileName] for ROM [romId]; RomM replaces its save of the same name.
     * Newer servers take one `saveFile` / `stateFile` part, older ones a `saves` / `states`
     * list, so a refused first form is retried in the other. Returns the stored save.
     */
    suspend fun uploadSave(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray): RemoteSaveFile? = withContext(Dispatchers.IO) {
        val query = buildString {
            append("rom_id=").append(romId)
            emulator?.let { append("&emulator=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val url = "${baseUrl()}/api/${kind.apiPath}?$query"
        val auth = headers()
        fun post(field: String): JsonHttp.Response {
            val (body, contentType) = JsonHttp.multipartFileBody(field, fileName, bytes)
            return JsonHttp.request("POST", url, auth, body = body, contentType = contentType, readTimeoutMs = 120_000)
        }
        var response = post(kind.fileField)
        if (response.code == 400 || response.code == 422) response = post(kind.legacyFileField)
        val obj = JsonHttp.requireOk(response).json?.takeIf { it.isJsonObject }?.asJsonObject ?: return@withContext null
        remoteSave(kind, obj)
            ?: obj.getAsJsonArray(kind.apiPath)?.mapNotNull { (it as? JsonObject)?.let { o -> remoteSave(kind, o) } }
                ?.lastOrNull { it.fileName.equals(fileName, ignoreCase = true) }
    }

    /**
     * Deletes [save] on the server. RomM's bulk route (`POST /api/saves/delete` with the ids) is
     * tried first; servers without it answer 404 / 405 and get a plain `DELETE /api/saves/{id}`.
     */
    suspend fun deleteSave(save: RemoteSaveFile) = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val auth = headers()
        val body = com.google.gson.JsonObject().apply {
            add(save.kind.apiPath, com.google.gson.JsonArray().apply { add(save.id) })
        }.toString().toByteArray(Charsets.UTF_8)
        val bulk = JsonHttp.request("POST", "$base/api/${save.kind.apiPath}/delete", auth, body = body, contentType = JsonHttp.JSON)
        if (bulk.code == 404 || bulk.code == 405) {
            JsonHttp.requireOk(JsonHttp.request("DELETE", "$base/api/${save.kind.apiPath}/${save.id}", auth))
        } else JsonHttp.requireOk(bulk)
        Unit
    }

    /** ROMs whose name or file name holds every word of [term] (RomM's library search). */
    suspend fun searchRoms(term: String, limit: Int = 100): List<RomCandidate> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(term, "UTF-8")
        val url = "${baseUrl()}/api/roms?search_term=$q&limit=$limit&offset=0" +
            "&with_char_index=false&with_filter_values=false&with_rom_id_index=false&with_total=false"
        val json = JsonHttp.requireOk(JsonHttp.request("GET", url, headers(), readTimeoutMs = 60_000)).json
        val items = when {
            json?.isJsonArray == true -> json.asJsonArray
            json?.isJsonObject == true -> json.asJsonObject.getAsJsonArray("items") ?: return@withContext emptyList()
            else -> return@withContext emptyList()
        }
        items.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val fsName = r.str("fs_name").ifEmpty { r.str("file_name") }.ifEmpty { return@mapNotNull null }
            RomCandidate(
                id = r.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: return@mapNotNull null,
                fsName = fsName,
                platformSlug = r.str("platform_slug"),
                platformFsSlug = r.str("platform_fs_slug")
            )
        }
    }

    private fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    companion object {
        /** `rmm_…` client tokens go as Bearer; `user:password` becomes HTTP Basic. */
        fun authHeader(token: String): String {
            val t = token.trim()
            return if (t.contains(':') && !t.startsWith("rmm_")) {
                "Basic " + java.util.Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8))
            } else "Bearer $t"
        }
    }
}
