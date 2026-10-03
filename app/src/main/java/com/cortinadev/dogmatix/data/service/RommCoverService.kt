package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CoverPlanner
import com.cortinadev.dogmatix.util.CoverSource
import com.cortinadev.dogmatix.util.DuplicateFinder
import com.cortinadev.dogmatix.util.SaveSyncPlanner
import com.cortinadev.dogmatix.util.StorageHelper
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
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommCoverService"
/// Covers are small; anything bigger is not one.
private const val MAX_COVER_BYTES = 12L * 1024 * 1024

data class CoverRunState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Outcome of the last run: fetched / failed counts, or why it could not run. */
    val fetched: Int? = null,
    val failed: Int = 0,
    val problem: Problem? = null
) {
    enum class Problem { NO_ESDE_FOLDER, NO_PLATFORMS, ESDE_NOT_WRITABLE, FAILED }
}

/**
 * Brings the cover art of games RomM knows into ES-DE: for every game that is on this device, has
 * a cover on the server and has none yet in ES-DE's `downloaded_media/<system>/covers`, the image
 * is downloaded and written there. Existing covers are never replaced. Needs the ES-DE folder
 * (Settings → ES-DE) and the console → platform mapping (Settings → RomM).
 */
@Singleton
class RommCoverService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val libraryScanService: LibraryScanService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CoverRunState())
    val state: StateFlow<CoverRunState> = _state.asStateFlow()

    fun start() {
        if (_state.value.running) return
        scope.launch { run() }
    }

    private suspend fun run() {
        _state.value = CoverRunState(running = true)
        try {
            val esdeUri = settingsRepository.esdeDirectory.first()
            val esdeDir = esdeUri.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
            val platformMap = settingsRepository.rommPlatformMap.first()
            when {
                esdeUri.isBlank() -> return finish(problem = CoverRunState.Problem.NO_ESDE_FOLDER)
                platformMap.isEmpty() -> return finish(problem = CoverRunState.Problem.NO_PLATFORMS)
                esdeDir == null || !esdeDir.isDirectory || !esdeDir.canWrite() -> return finish(problem = CoverRunState.Problem.ESDE_NOT_WRITABLE)
            }
            esdeDir!!

            // What is on the device, per ES-DE system (= the console's folder name).
            val disk = libraryScanService.scan()
            val systemConsole = mutableMapOf<String, String>()
            val deviceGames = mutableMapOf<String, MutableSet<String>>()
            DuplicateFinder.entries(disk.files).forEach { entry ->
                val consoleId = entry.consoleId ?: return@forEach
                if (consoleId !in platformMap) return@forEach
                val system = CoverPlanner.systemOf(entry.files.first()) ?: return@forEach
                systemConsole.putIfAbsent(system, consoleId)
                deviceGames.getOrPut(system) { linkedSetOf() } += entry.baseName
            }

            // What the server has covers for.
            val server = mutableMapOf<String, List<CoverSource>>()
            for ((system, consoleId) in systemConsole) {
                val platformId = platformMap[consoleId] ?: continue
                server[system] = rommClient.roms(platformId).map { CoverSource(SaveSyncPlanner.romStem(it.fsName), it.coverPath) }
            }

            // What ES-DE already shows.
            val existing = deviceGames.keys.associateWith { system ->
                StorageHelper.findFile(esdeDir, "downloaded_media/$system/covers")
                    ?.takeIf { it.isDirectory }
                    ?.listFiles()?.mapNotNull { it.name?.substringBeforeLast('.', "")?.lowercase() }?.toSet().orEmpty()
            }

            val jobs = CoverPlanner.plan(deviceGames, server, existing)
            _state.update { it.copy(total = jobs.size) }
            var fetched = 0
            var failed = 0
            val headers = rommClient.downloadHeaders()
            val base = rommClient.configuredBaseUrl()
            for (job in jobs) {
                val ok = runCatching {
                    val bytes = JsonHttp.download(CoverPlanner.coverUrl(base, job.coverPath), headers, MAX_COVER_BYTES)
                    val mime = if (job.extension == "jpg" || job.extension == "jpeg") "image/jpeg" else "image/png"
                    val doc = withContext(Dispatchers.IO) {
                        StorageHelper.createFile(context, esdeUri, "downloaded_media/${job.system}/covers", job.fileName, mime, overwrite = false)
                    } ?: error("could not create ${job.fileName}")
                    StorageHelper.getOutputStream(context, doc)?.use { it.write(bytes) } ?: error("could not write ${job.fileName}")
                }.onFailure { Log.w(TAG, "Cover ${job.fileName} failed: ${it.message}") }.isSuccess
                if (ok) fetched++ else failed++
                _state.update { it.copy(done = it.done + 1) }
            }
            _state.value = CoverRunState(running = false, done = jobs.size, total = jobs.size, fetched = fetched, failed = failed)
        } catch (e: Exception) {
            Log.w(TAG, "Cover run failed", e)
            finish(problem = CoverRunState.Problem.FAILED)
        }
    }

    private fun finish(problem: CoverRunState.Problem) {
        _state.value = CoverRunState(running = false, problem = problem)
    }
}
