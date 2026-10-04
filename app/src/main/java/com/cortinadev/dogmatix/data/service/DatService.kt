package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DatDao
import com.cortinadev.dogmatix.data.local.entity.DatRomEntity
import com.cortinadev.dogmatix.data.local.entity.DatSetEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DatCheck
import com.cortinadev.dogmatix.util.DatEntry
import com.cortinadev.dogmatix.util.DatMatcher
import com.cortinadev.dogmatix.util.DatParser
import com.cortinadev.dogmatix.util.DatStatus
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.ScannedFile
import com.cortinadev.dogmatix.util.ZipDirectory
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "DatService"

/** A finished DAT check of one console's folder. */
data class DatReport(
    val consoleId: String,
    val checks: List<Pair<DiskEntry, DatCheck>>,
    /** Games of the DAT none of the files matched. */
    val missing: List<String>,
    val gameCount: Int,
    val at: Long = System.currentTimeMillis()
) {
    fun count(status: DatStatus) = checks.count { it.second.status == status }
}

/** How far a running check is. */
data class DatProgress(val consoleId: String, val done: Int, val total: Int, val current: String)

/**
 * Checks a console's games against a DAT file (No-Intro, Redump, …) the user imported: which files
 * are good dumps with the right name, which are good under another name (and can be renamed), and
 * which the DAT does not know. ZIPs are checked from their own index (no unpacking); other files are
 * hashed once and remembered by size and date.
 */
@Singleton
class DatService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DatDao,
    private val settings: SettingsRepository,
    private val pathResolver: ConsoleDownloadPathResolver
) {
    val sets: Flow<List<DatSetEntity>> = dao.observeSets()

    private val _reports = MutableStateFlow<Map<String, DatReport>>(emptyMap())
    val reports: StateFlow<Map<String, DatReport>> = _reports.asStateFlow()

    private val _progress = MutableStateFlow<DatProgress?>(null)
    val progress: StateFlow<DatProgress?> = _progress.asStateFlow()

    /** Reads the DAT (or a ZIP with one) at [uri] for [consoleId]; returns the number of games. */
    suspend fun import(consoleId: String, uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")
        importBytes(consoleId, bytes, uri.lastPathSegment?.substringAfterLast('/').orEmpty())
    }

    /** Fetches the newest Redump DAT of [consoleId]'s system (no account needed); null when Redump has none. */
    suspend fun importFromRedump(consoleId: String): Int? = withContext(Dispatchers.IO) {
        val system = com.cortinadev.dogmatix.util.RedumpSystems.systemFor(consoleId) ?: return@withContext null
        val bytes = JsonHttp.download(com.cortinadev.dogmatix.util.RedumpSystems.url(system), maxBytes = 200L * 1024 * 1024, readTimeoutMs = 120_000)
        importBytes(consoleId, bytes, "Redump $system")
    }

    private suspend fun importBytes(consoleId: String, bytes: ByteArray, nameHint: String): Int {
        val text = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory && (it.name.endsWith(".dat", true) || it.name.endsWith(".xml", true)) }
                    ?.let { zip.readBytes().toString(Charsets.UTF_8) } ?: error("No .dat in the ZIP")
            }
        } else bytes.toString(Charsets.UTF_8)
        val dat = DatParser.parse(text)
        require(dat.games.isNotEmpty()) { "No games in this DAT" }
        val roms = dat.games.flatMap { g -> g.roms.map { DatRomEntity(consoleId = consoleId, gameName = g.name, romName = it.name, size = it.size, crc = it.crc, md5 = it.md5, sha1 = it.sha1) } }
        dao.replace(DatSetEntity(consoleId, dat.name.ifBlank { nameHint }, dat.version, dat.games.size, roms.size), roms)
        _reports.update { it - consoleId }
        return dat.games.size
    }

    suspend fun remove(consoleId: String) {
        dao.remove(consoleId)
        _reports.update { it - consoleId }
    }

    /** The folder [consoleId]'s games are in: its own folder from Sources, else its folder in the download folder. */
    suspend fun folderFor(consoleId: String): DiskDir? = withContext(Dispatchers.IO) {
        settings.consoleDownloadDirectories.first()[consoleId]?.takeIf { it.isNotBlank() }?.let { return@withContext DiskScanner.rootOf(it) }
        val root = settings.downloadDirectory.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@withContext null
        var dir = root
        val sub = pathResolver.resolve(settings, consoleId).subPath
        for (part in sub.split('/').filter { it.isNotBlank() }) {
            val child = DiskScanner.list(context, dir).firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) } ?: return@withContext null
            dir = DiskScanner.dirOf(dir, child)
        }
        dir
    }

    /**
     * Checks one file that was just downloaded against [consoleId]'s DAT; null when there is no DAT
     * for the console or the format is not in DATs. Only the DAT rows that can match the file are
     * read from the database, and the hashes land in the cache the full check uses.
     */
    suspend fun checkDownloaded(consoleId: String, name: String, uri: Uri, size: Long, lastModified: Long): DatCheck? = withContext(Dispatchers.IO) {
        if (dao.setOf(consoleId) == null) return@withContext null
        val cache = loadCache()
        val scanned = scan(DiskEntry(name, size, false, uri, "", lastModified), cache)
        saveCache(cache)
        // Nothing to look up (an unreadable ZIP, a format that is not hashed): say nothing rather than "not in the DAT".
        if (scanned.zipEntries.isNullOrEmpty() && scanned.sha1 == null && scanned.crc == null) return@withContext null
        val rows = buildList {
            scanned.zipEntries?.forEach { addAll(dao.byCrc(consoleId, it.crc.lowercase())) }
            scanned.sha1?.let { addAll(dao.bySha1(consoleId, it.lowercase())) }
            scanned.crc?.let { addAll(dao.byCrc(consoleId, it.lowercase())) }
        }
        val check = DatMatcher(rows.map { DatEntry(it.gameName, it.romName, it.size, it.crc, it.sha1) }).check(scanned)
        check.takeIf { it.status != DatStatus.SKIPPED }
    }

    /** Checks every file in [consoleId]'s folder (and one level of sub-folders) against its DAT. */
    suspend fun verify(consoleId: String): DatReport? = withContext(Dispatchers.IO) {
        val roms = dao.romsOf(consoleId)
        if (roms.isEmpty()) return@withContext null
        val matcher = DatMatcher(roms.map { DatEntry(it.gameName, it.romName, it.size, it.crc, it.sha1) })
        val dir = folderFor(consoleId) ?: return@withContext null
        val files = collect(dir, depth = 1)
        val cache = loadCache()
        val checks = ArrayList<Pair<DiskEntry, DatCheck>>(files.size)
        try {
            files.forEachIndexed { i, entry ->
                coroutineContext.ensureActive()
                _progress.value = DatProgress(consoleId, i, files.size, entry.name)
                val scanned = runCatching { scan(entry, cache) }.onFailure { Log.w(TAG, "Cannot read ${entry.name}: ${it.message}") }.getOrNull()
                checks += entry to (scanned?.let(matcher::check) ?: DatCheck(entry.name, DatStatus.SKIPPED))
            }
        } finally {
            _progress.value = null
            saveCache(cache)
        }
        DatReport(consoleId, checks, matcher.missing(checks.map { it.second }), matcher.gameCount).also { r -> _reports.update { it + (consoleId to r) } }
    }

    /** Gives [entry] the DAT's name; returns false when the storage refused (or the name is taken). */
    suspend fun rename(consoleId: String, entry: DiskEntry, check: DatCheck): Boolean = withContext(Dispatchers.IO) {
        val target = check.canonicalName?.let(DatMatcher::safeFileName) ?: return@withContext false
        val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, entry.uri, target) }
            .onFailure { Log.w(TAG, "Rename of ${entry.name} failed: ${it.message}") }.getOrNull() ?: return@withContext false
        _reports.update { all ->
            val report = all[consoleId] ?: return@update all
            all + (consoleId to report.copy(checks = report.checks.map { (e, c) ->
                if (e.uri == entry.uri) e.copy(name = target, uri = renamed) to c.copy(fileName = target, status = DatStatus.VERIFIED) else e to c
            }))
        }
        true
    }

    private fun collect(dir: DiskDir, depth: Int): List<DiskEntry> = DiskScanner.list(context, dir).flatMap { e ->
        if (!e.isDirectory) listOf(e) else if (depth > 0) collect(DiskScanner.dirOf(dir, e), depth - 1) else emptyList()
    }

    private suspend fun scan(entry: DiskEntry, cache: JsonObject): ScannedFile {
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        if (ext == "zip") {
            context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val inside = ZipDirectory.list(channel.size()) { position, length ->
                        val buffer = ByteBuffer.allocate(length)
                        var at = position
                        while (buffer.hasRemaining()) { val n = channel.read(buffer, at); if (n < 0) break; at += n }
                        buffer.array()
                    }
                    if (inside != null) return ScannedFile(entry.name, entry.size, zipEntries = inside)
                }
            }
            return ScannedFile(entry.name, entry.size, zipEntries = emptyList())
        }
        if (!DatMatcher.needsHash(entry.name)) return ScannedFile(entry.name, entry.size)
        val key = "${entry.uri}|${entry.size}|${entry.lastModified}"
        cache.get(key)?.asString?.split(',')?.takeIf { it.size == 2 }?.let { return ScannedFile(entry.name, entry.size, it[0], it[1]) }
        val crc = CRC32()
        val sha1 = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(entry.uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                sha1.update(buffer, 0, n)
            }
        } ?: error("Cannot open ${entry.name}")
        val crcHex = "%08x".format(crc.value)
        val shaHex = sha1.digest().joinToString("") { "%02x".format(it) }
        cache.addProperty(key, "$crcHex,$shaHex")
        return ScannedFile(entry.name, entry.size, crcHex, shaHex)
    }

    private val cacheFile get() = File(context.filesDir, "dat_hash_cache.json")

    private fun loadCache(): JsonObject = runCatching { JsonParser.parseString(cacheFile.readText()).asJsonObject }.getOrDefault(JsonObject())

    private fun saveCache(cache: JsonObject) {
        runCatching { cacheFile.writeText(cache.toString()) }
    }
}
