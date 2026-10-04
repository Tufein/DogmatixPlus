package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.DatDao
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.RaGame
import com.cortinadev.dogmatix.util.RetroAchievements
import com.cortinadev.dogmatix.util.StorageHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Per console: which games (by file name without extension, lower-case) RetroAchievements supports. */
data class RaMarks(val byConsole: Map<String, Map<String, RaGame>> = emptyMap()) {
    fun gameFor(consoleId: String, fileName: String): RaGame? = byConsole[consoleId]?.get(stem(fileName))
    companion object { fun stem(fileName: String) = fileName.substringBeforeLast('.').lowercase() }
}

/**
 * RetroAchievements for the library. With the user's RA name and web API key it fetches, per
 * console, RA's list of games with achievements and the ROM hashes each accepts (cached for a
 * week in `files/ra/`). A game is marked supported when its hash is in that list:
 *  - for files on the device, by hashing them the way RA does (cartridge systems; see [RetroAchievements]);
 *  - for games only in the sources, through an imported No-Intro DAT, whose MD5 per ROM is RA's hash.
 * Results are kept in `files/ra/marks.json`, so the badges are there at once on the next start.
 */
@Singleton
class RetroAchievementsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val datDao: DatDao
) {
    private val dir = File(context.filesDir, "ra").apply { mkdirs() }
    private val marksFile = File(dir, "marks.json")

    private val _marks = MutableStateFlow(RaMarks())
    val marks: StateFlow<RaMarks> = _marks.asStateFlow()

    init {
        // The marks file is read off the main thread; the badges appear once it is in.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val stored = loadMarks()
            _marks.update { current -> RaMarks(stored.byConsole + current.byConsole) }
        }
    }

    data class ConsoleCheck(val consoleId: String, val raName: String, val raGames: Int, val onDevice: Int, val supportedOnDevice: Int, val fromDat: Int)

    class NoCredentialsException : Exception()

    suspend fun configured(): Boolean = appSettings.raUser.first().isNotBlank() && appSettings.raKey.first().isNotBlank()

    /** RA's game list for a console: from the cache when it is under a week old, else fetched. */
    suspend fun gameList(raConsoleId: Int, refresh: Boolean = false): List<RaGame> = withContext(Dispatchers.IO) {
        val cache = File(dir, "games_$raConsoleId.json")
        if (!refresh && cache.exists() && System.currentTimeMillis() - cache.lastModified() < WEEK_MS) {
            return@withContext RetroAchievements.parseGameList(cache.readText())
        }
        val user = appSettings.raUser.first(); val key = appSettings.raKey.first()
        if (user.isBlank() || key.isBlank()) throw NoCredentialsException()
        val text = JsonHttp.download(RetroAchievements.gameListUrl(raConsoleId, user, key), maxBytes = 30L * 1024 * 1024, readTimeoutMs = 120_000).toString(Charsets.UTF_8)
        val games = RetroAchievements.parseGameList(text)
        if (games.isNotEmpty() || text.trim().startsWith("[")) cache.writeText(text)
        games
    }

    /**
     * Checks one console: hashes the games in its folder (files up to 64 MB, ZIPs with one ROM
     * inside) and matches the imported DAT, then stores the marks. Throws [NoCredentialsException].
     */
    suspend fun check(consoleId: String, refresh: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }): ConsoleCheck? = withContext(Dispatchers.IO) {
        val ra = RetroAchievements.consoleFor(consoleId) ?: return@withContext null
        val games = gameList(ra.id, refresh)
        val byHash = HashMap<String, RaGame>()
        games.forEach { g -> g.hashes.forEach { byHash[it] = g } }
        val found = HashMap<String, RaGame>()

        // 1. Files on the device.
        val base = settingsRepository.consoleDownloadDirectories.first()[consoleId] ?: settingsRepository.downloadDirectory.first()
        val subPath = runCatching { pathResolver.resolve(settingsRepository, consoleId).subPath }.getOrDefault("")
        val folder = base.takeIf { it.isNotBlank() }?.let { StorageHelper.createDirectory(context, it, subPath) }
        val files = folder?.listFiles()?.filter { it.isFile && it.length() in 1..MAX_ROM_BYTES }.orEmpty()
        var supported = 0
        files.forEachIndexed { i, f ->
            onProgress(i + 1, files.size)
            val bytes = runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }.getOrNull() ?: return@forEachIndexed
            val rom = if (f.name.orEmpty().endsWith(".zip", true)) singleZipEntry(bytes) ?: return@forEachIndexed else bytes
            val game = byHash[RetroAchievements.hash(rom, ra.rule)] ?: return@forEachIndexed
            found[RaMarks.stem(f.name.orEmpty())] = game
            supported++
        }

        // 2. Games known through an imported DAT (No-Intro lists the MD5 RA uses).
        var fromDat = 0
        runCatching { datDao.romsOf(consoleId) }.getOrDefault(emptyList()).forEach { rom ->
            val game = rom.md5?.lowercase()?.let { byHash[it] } ?: return@forEach
            if (found.putIfAbsent(rom.gameName.lowercase(), game) == null) fromDat++
            found.putIfAbsent(RaMarks.stem(rom.romName), game)
        }

        _marks.update { RaMarks(it.byConsole + (consoleId to found)) }
        saveMarks()
        ConsoleCheck(consoleId, ra.name, games.size, files.size, supported, fromDat)
    }

    /** RA game for a library row: by hash when checked, else a title match (`probably`). */
    suspend fun lookup(consoleId: String, fileName: String, title: String): Pair<RaGame, Boolean>? {
        _marks.value.gameFor(consoleId, fileName)?.let { return it to true }
        val ra = RetroAchievements.consoleFor(consoleId) ?: return null
        val cache = File(dir, "games_${ra.id}.json").takeIf { it.exists() } ?: return null
        val clean = GameTitleCleaner.clean(title)
        return withContext(Dispatchers.IO) { RetroAchievements.parseGameList(cache.readText()) }
            .firstOrNull { GameTitleCleaner.matches(clean, it.title) }?.let { it to false }
    }

    private fun singleZipEntry(zip: ByteArray): ByteArray? = runCatching {
        ZipInputStream(zip.inputStream()).use { z ->
            val entries = generateSequence { z.nextEntry }.filter { !it.isDirectory }
            val first = entries.firstOrNull() ?: return null
            val data = z.readBytes()
            if (z.nextEntry != null) null else data.takeIf { first.name.isNotEmpty() }
        }
    }.getOrNull()

    private fun loadMarks(): RaMarks = runCatching {
        val root = JsonParser.parseString(marksFile.readText()).asJsonObject
        RaMarks(root.entrySet().associate { (console, games) ->
            console to games.asJsonObject.entrySet().associate { (stem, g) ->
                val o = g.asJsonObject
                stem to RaGame(o.get("id").asInt, o.get("title").asString, o.get("n").asInt, emptySet())
            }
        })
    }.getOrElse { RaMarks() }

    private fun saveMarks() {
        runCatching {
            val root = JsonObject()
            _marks.value.byConsole.forEach { (console, games) ->
                root.add(console, JsonObject().apply {
                    games.forEach { (stem, g) -> add(stem, JsonObject().apply { addProperty("id", g.id); addProperty("title", g.title); addProperty("n", g.achievements) }) }
                })
            }
            marksFile.writeText(root.toString())
        }
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000
        const val MAX_ROM_BYTES = 64L * 1024 * 1024
    }
}
