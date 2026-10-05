package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.DatDao
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.RaApi
import com.cortinadev.dogmatix.util.RaErrorKind
import com.cortinadev.dogmatix.util.RaGame
import com.cortinadev.dogmatix.util.RaGameProgress
import com.cortinadev.dogmatix.util.RaUserSummary
import com.cortinadev.dogmatix.util.RetroAchievements
import com.cortinadev.dogmatix.util.StorageHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    // ---- 5.0: the user's profile and per-game progress (RA web API), cached in memory ----
    // RA asks API users to be gentle: answers are kept for a few minutes, a failure is not retried
    // for a while (longer for a refused key or rate limiting), and only one RA call runs at a time.

    /** Why an RA call failed. Carries a kind and the HTTP status only: never the URL, which holds the key. */
    class RaApiException(val kind: RaErrorKind, val httpCode: Int = 0) :
        IOException("RetroAchievements: $kind" + if (httpCode > 0) " (HTTP $httpCode)" else "")

    /** How the user summary stands: [updatedAt] of the last success, [error] of the last attempt. */
    data class SummaryStatus(
        val loading: Boolean = false,
        val error: RaErrorKind? = null,
        val updatedAt: Long = 0L,
        val failedAt: Long = 0L
    )

    private val onlineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val netMutex = Mutex()

    private val _accountReady = MutableStateFlow<Boolean?>(null)
    /** True when an RA name and web API key are set; null until the settings have been read. */
    val accountReady: StateFlow<Boolean?> = _accountReady.asStateFlow()

    private val _summary = MutableStateFlow<RaUserSummary?>(null)
    /** The user's RA profile (points, rank, recently played), or null until fetched. See [refreshSummary]. */
    val summary: StateFlow<RaUserSummary?> = _summary.asStateFlow()
    private val _summaryStatus = MutableStateFlow(SummaryStatus())
    val summaryStatus: StateFlow<SummaryStatus> = _summaryStatus.asStateFlow()

    private class Cached<T>(val value: T, val at: Long)
    private class LruMap<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    /** Game id → progress (null = RA does not know the game). Guards [progressFailures] too. */
    private val progressCache = LruMap<Int, Cached<RaGameProgress?>>(48)
    private val progressFailures = HashMap<Int, Pair<RaApiException, Long>>()
    /** Title matches of library rows, so the details dialog does not parse RA's game list each time. */
    private val lookupMemo = LruMap<String, Pair<RaGame, Boolean>?>(96)
    /** Hash of the last name + key seen, to notice a change without keeping the key twice. */
    @Volatile private var credentialsHash: Int? = null

    init {
        onlineScope.launch {
            try {
                combine(appSettings.raUser, appSettings.raKey) { user, key -> user.trim() to key.trim() }
                    .distinctUntilChanged()
                    .collect { creds ->
                        val (user, key) = creds
                        val hash = (user + "\n" + key).hashCode()
                        val previous = credentialsHash
                        credentialsHash = hash
                        // Another account (or none): what was fetched belongs to the old one.
                        if (previous != null && previous != hash) clearOnline()
                        _accountReady.value = user.isNotEmpty() && key.isNotEmpty()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The settings could not be read (a damaged preferences file): treat RA as not set up.
                _accountReady.value = false
            }
        }
    }

    /**
     * Fetches the user's summary unless the one in [summary] is under 5 minutes old or the last
     * attempt failed moments ago; [force] (a "Refresh" press) skips both checks. Network and parse
     * failures do not throw: the outcome is in [summary] (kept when a refresh fails) and [summaryStatus].
     */
    suspend fun refreshSummary(force: Boolean = false): RaUserSummary? = withContext(Dispatchers.IO) {
        netMutex.withLock {
            val now = System.currentTimeMillis()
            val status = _summaryStatus.value
            val current = _summary.value
            val lastError = status.error
            if (!force && current != null && lastError == null && now - status.updatedAt < SUMMARY_TTL_MS) return@withLock current
            if (!force && lastError != null && now - status.failedAt < backoffMs(lastError)) return@withLock current
            val user = appSettings.raUser.first().trim()
            val key = appSettings.raKey.first().trim()
            if (user.isEmpty() || key.isEmpty()) {
                _summary.value = null
                _summaryStatus.value = SummaryStatus(error = RaErrorKind.NO_ACCOUNT, failedAt = now)
                return@withLock null
            }
            _summaryStatus.value = status.copy(loading = true)
            try {
                val body = fetch(RaApi.userSummaryUrl(user, key, RECENT_GAMES, RECENT_UNLOCKS))
                val parsed = RaApi.parseUserSummary(body, user) ?: throw RaApiException(RaApi.unreadableKind(body))
                _summary.value = parsed
                _summaryStatus.value = SummaryStatus(updatedAt = System.currentTimeMillis())
                parsed
            } catch (e: CancellationException) {
                _summaryStatus.value = status.copy(loading = false)
                throw e
            } catch (e: Exception) {
                _summaryStatus.value = status.copy(loading = false, error = kindOf(e), failedAt = System.currentTimeMillis())
                current
            }
        }
    }

    /** [refreshSummary] in the service's own scope, so leaving a screen does not cut it off. */
    fun requestSummary(force: Boolean = false) {
        onlineScope.launch {
            try {
                refreshSummary(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // refreshSummary reports its own failures; this only guards the settings read.
                _summaryStatus.update { it.copy(loading = false, error = kindOf(e), failedAt = System.currentTimeMillis()) }
            }
        }
    }

    /** The last progress fetched for a game, however old (shown at once while a fresh copy loads). */
    fun cachedProgress(gameId: Int): RaGameProgress? = synchronized(progressCache) { progressCache[gameId]?.value }

    /**
     * The user's progress in RA game [gameId]: from memory when under 5 minutes old, else fetched.
     * Null when RA does not know the game. Throws [NoCredentialsException] and [RaApiException]
     * (within a short back-off the last failure again, without calling RA, unless [force]).
     */
    suspend fun gameProgress(gameId: Int, force: Boolean = false): RaGameProgress? = withContext(Dispatchers.IO) {
        if (gameId <= 0) return@withContext null
        if (!force) {
            freshProgress(gameId)?.let { return@withContext it.value }
            recentFailure(gameId)?.let { throw it }
        }
        netMutex.withLock {
            // Another caller may have fetched it while this one waited.
            if (!force) freshProgress(gameId)?.let { return@withLock it.value }
            val user = appSettings.raUser.first().trim()
            val key = appSettings.raKey.first().trim()
            if (user.isEmpty() || key.isEmpty()) throw NoCredentialsException()
            try {
                val body = fetch(RaApi.gameProgressUrl(gameId, user, key))
                val parsed = RaApi.parseGameProgress(body)
                if (parsed == null) {
                    val kind = RaApi.unreadableKind(body)
                    if (kind != RaErrorKind.NOT_FOUND) throw RaApiException(kind)
                }
                synchronized(progressCache) {
                    progressCache[gameId] = Cached(parsed, System.currentTimeMillis())
                    progressFailures.remove(gameId)
                }
                parsed
            } catch (e: RaApiException) {
                synchronized(progressCache) { progressFailures[gameId] = e to System.currentTimeMillis() }
                throw e
            }
        }
    }

    /**
     * The RA game of a library row and whether it matched by hash: the hash marks first, then a
     * title match in RA's cached game list (see [lookup]), remembered for the session.
     */
    suspend fun resolveGame(consoleId: String, fileName: String, title: String = ""): Pair<RaGame, Boolean>? {
        _marks.value.gameFor(consoleId, fileName)?.let { return it to true }
        val ra = RetroAchievements.consoleFor(consoleId) ?: return null
        val stamp = withContext(Dispatchers.IO) { File(dir, "games_${ra.id}.json").lastModified() }
        if (stamp == 0L) return null
        val name = title.ifBlank { fileName.substringBeforeLast('.') }
        val memoKey = "$consoleId|$fileName|$name|$stamp"
        synchronized(lookupMemo) { if (lookupMemo.containsKey(memoKey)) return lookupMemo[memoKey] }
        val found = try {
            lookup(consoleId, fileName, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        synchronized(lookupMemo) { lookupMemo[memoKey] = found }
        return found
    }

    private fun freshProgress(gameId: Int): Cached<RaGameProgress?>? =
        synchronized(progressCache) { progressCache[gameId] }?.takeIf { System.currentTimeMillis() - it.at < GAME_TTL_MS }

    private fun recentFailure(gameId: Int): RaApiException? =
        synchronized(progressCache) { progressFailures[gameId] }
            ?.takeIf { System.currentTimeMillis() - it.second < backoffMs(it.first.kind) }?.first

    private fun clearOnline() {
        synchronized(progressCache) {
            progressCache.clear()
            progressFailures.clear()
        }
        _summary.value = null
        _summaryStatus.value = SummaryStatus()
    }

    /** One GET to the RA API. Failures become [RaApiException]s that say nothing about the URL. */
    private fun fetch(url: String): String {
        val response = try {
            JsonHttp.request("GET", url, connectTimeoutMs = 15_000, readTimeoutMs = 30_000)
        } catch (e: IOException) {
            throw RaApiException(RaErrorKind.OFFLINE)
        } catch (e: RuntimeException) {
            throw RaApiException(RaErrorKind.BAD_RESPONSE)
        }
        if (!response.ok) throw RaApiException(RaApi.errorKindFor(response.code, response.body), response.code)
        return response.body
    }

    private fun kindOf(e: Throwable): RaErrorKind = when (e) {
        is RaApiException -> e.kind
        is NoCredentialsException -> RaErrorKind.NO_ACCOUNT
        is IOException -> RaErrorKind.OFFLINE
        else -> RaErrorKind.BAD_RESPONSE
    }

    private fun backoffMs(kind: RaErrorKind): Long = when (kind) {
        RaErrorKind.OFFLINE -> 30_000L
        RaErrorKind.RATE_LIMITED -> 5 * 60_000L
        RaErrorKind.BAD_KEY, RaErrorKind.NOT_FOUND, RaErrorKind.NO_ACCOUNT -> 10 * 60_000L
        RaErrorKind.SERVER, RaErrorKind.BAD_RESPONSE -> 60_000L
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000
        const val MAX_ROM_BYTES = 64L * 1024 * 1024
        const val SUMMARY_TTL_MS = 5 * 60_000L
        const val GAME_TTL_MS = 5 * 60_000L
        const val RECENT_GAMES = 5
        const val RECENT_UNLOCKS = 5
    }
}
