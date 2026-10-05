package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.Checksums
import com.cortinadev.dogmatix.util.HashAlgo
import com.cortinadev.dogmatix.util.RommFirmware
import com.cortinadev.dogmatix.util.RommFirmwareMatcher
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommFirmwareService"
/** No catalogue BIOS comes near this (a PS2 BIOS is 4 MB); a bigger file is not what we want. */
private const val MAX_FIRMWARE_BYTES = 64L * 1024 * 1024

/** Why a run could not start. */
enum class FirmwareProblem { NOT_SET_UP, NO_FOLDER, NO_WRITE_ACCESS, NOTHING_MISSING, SERVER }

/** Why one file did not come. */
enum class FirmwareFailure { WRONG_DUMP, CORRUPT, TOO_LARGE, DOWNLOAD, WRITE }

/** The outcome of "Fetch missing BIOS from RomM". */
data class FirmwareFetchReport(
    /** Set when nothing was attempted. */
    val problem: FirmwareProblem? = null,
    val problemKind: RommErrorKind? = null,
    /** Catalogue files that were missing when the run started. */
    val missing: Int = 0,
    /** Written and verified: (system, path in the BIOS folder). */
    val fetched: List<Pair<String, String>> = emptyList(),
    /** Found on the server but not kept: (system, path, why). */
    val failed: List<Triple<String, String, FirmwareFailure>> = emptyList(),
    /** Missing here and not found on the server: (system, path, required). */
    val notOnServer: List<Triple<String, String, Boolean>> = emptyList(),
    val finishedAt: Long = 0L
)

data class FirmwareFetchState(
    val running: Boolean = false,
    /** Files handled / to handle in the current run. */
    val done: Int = 0,
    val total: Int = 0,
    val report: FirmwareFetchReport? = null
)

/**
 * Fills the BIOS folder from the RomM server (5.0, BIOS tool): for every catalogue file the
 * check finds missing, a firmware file RomM keeps (`GET /api/firmware`) with the same MD5 — or
 * the same name, when no checksum is known — is downloaded, its MD5 checked, and written into
 * the BIOS folder through SAF (temporary file, then renamed, as save sync writes saves). Nothing
 * that is already in the folder is replaced.
 */
@Singleton
class RommFirmwareService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val biosService: BiosService,
    private val rommClient: RommClient
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(FirmwareFetchState())
    val state: StateFlow<FirmwareFetchState> = _state.asStateFlow()

    /** Fetches what is missing for the systems the BIOS screen shows ([allSystems] like its toggle). */
    suspend fun fetchMissing(allSystems: Boolean): FirmwareFetchReport = withContext(Dispatchers.IO) {
        lock.withLock {
            _state.value = FirmwareFetchState(running = true)
            val report = try {
                run(allSystems)
            } catch (e: CancellationException) {
                _state.value = FirmwareFetchState(running = false, report = _state.value.report)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Firmware fetch failed: ${e.javaClass.simpleName}")
                FirmwareFetchReport(problem = FirmwareProblem.SERVER, problemKind = rommErrorKind(e), finishedAt = System.currentTimeMillis())
            }
            _state.value = FirmwareFetchState(running = false, done = _state.value.total, total = _state.value.total, report = report)
            report
        }
    }

    private suspend fun run(allSystems: Boolean): FirmwareFetchReport {
        fun stop(problem: FirmwareProblem, kind: RommErrorKind? = null, missing: Int = 0) =
            FirmwareFetchReport(problem = problem, problemKind = kind, missing = missing, finishedAt = System.currentTimeMillis())

        if (rommClient.configuredBaseUrl().isEmpty() || settingsRepository.rommToken.first().isBlank()) return stop(FirmwareProblem.NOT_SET_UP)
        val folderUri = appSettings.biosDir.first()
        if (folderUri.isBlank()) return stop(FirmwareProblem.NO_FOLDER)
        val root = StorageHelper.getDocumentFile(context, folderUri)?.takeIf { runCatching { it.exists() }.getOrDefault(false) }
            ?: return stop(FirmwareProblem.NO_FOLDER)
        // The folder may have been picked for reading only (4.x asked for read access).
        if (!runCatching { root.canWrite() }.getOrDefault(false)) return stop(FirmwareProblem.NO_WRITE_ACCESS)

        val check = biosService.check(allSystems)
        val wanted = RommFirmwareMatcher.missing(check.results)
        if (wanted.isEmpty()) return stop(FirmwareProblem.NOTHING_MISSING)

        val systems = check.results.map { it.system }.filter { s -> wanted.any { it.system == s.name } }
        val platforms = runCatching { rommClient.platforms() }.getOrDefault(emptyList())
            .map { RommFirmwareMatcher.PlatformRef(it.id, it.slug, it.fsSlug, it.name) }
        val bySystem = RommFirmwareMatcher.platformsBySystem(systems, settingsRepository.rommPlatformMap.first(), platforms)

        // The firmware of every platform that belongs to a system with a gap.
        val firmware = ArrayList<RommFirmware>()
        var lastError: Exception? = null
        var listed = 0
        for (platformId in bySystem.values.flatten().toSortedSet()) {
            try {
                firmware += rommClient.firmware(platformId)
                listed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "No firmware list for platform $platformId: ${e.javaClass.simpleName}")
                // A token without the firmware scope fails the same way everywhere: stop asking.
                if (rommErrorKind(e) == RommErrorKind.AUTH || rommErrorKind(e) == RommErrorKind.FORBIDDEN) break
            }
        }
        val authProblem = lastError?.let { rommErrorKind(it) }?.takeIf { it == RommErrorKind.AUTH || it == RommErrorKind.FORBIDDEN }
        if (authProblem != null && listed == 0) return stop(FirmwareProblem.SERVER, authProblem, wanted.size)

        var result = RommFirmwareMatcher.match(wanted, firmware, bySystem)
        // Known dumps are the same bytes on any platform: one listing of everything finds those
        // on platforms nobody mapped (or when the server could not be asked per platform).
        if (result.notOnServer.any { it.md5.isNotEmpty() }) {
            val everything = runCatching { rommClient.firmware(null) }.getOrNull()
            if (everything != null) {
                // extra.notOnServer still holds the leftovers without a hash; a path is written once.
                val extra = RommFirmwareMatcher.matchByHashOnly(result.notOnServer, everything)
                val taken = result.matches.mapTo(HashSet()) { it.targetPath.lowercase() }
                result = RommFirmwareMatcher.Result(result.matches + extra.matches.filter { taken.add(it.targetPath.lowercase()) }, extra.notOnServer)
            } else if (firmware.isEmpty() && listed == 0) {
                lastError?.let { return stop(FirmwareProblem.SERVER, rommErrorKind(it), wanted.size) }
            }
        }

        _state.value = FirmwareFetchState(running = true, done = 0, total = result.matches.size)
        val fetched = ArrayList<Pair<String, String>>()
        val failed = ArrayList<Triple<String, String, FirmwareFailure>>()
        val downloads = HashMap<Int, ByteArray>()
        for ((index, match) in result.matches.withIndex()) {
            val outcome = fetchOne(root, match, downloads)
            if (outcome == null) fetched += match.wanted.system to match.targetPath
            else failed += Triple(match.wanted.system, match.targetPath, outcome)
            _state.value = FirmwareFetchState(running = true, done = index + 1, total = result.matches.size)
        }
        return FirmwareFetchReport(
            missing = wanted.size,
            fetched = fetched,
            failed = failed,
            notOnServer = result.notOnServer.map { Triple(it.system, it.path, it.required) },
            finishedAt = System.currentTimeMillis()
        )
    }

    /** Downloads, verifies and writes one match; null when it worked, else why not. */
    private suspend fun fetchOne(root: DocumentFile, match: RommFirmwareMatcher.Match, downloads: MutableMap<Int, ByteArray>): FirmwareFailure? {
        val fw = match.firmware
        if (fw.sizeBytes > MAX_FIRMWARE_BYTES) return FirmwareFailure.TOO_LARGE
        val bytes = downloads[fw.id] ?: try {
            rommClient.downloadFirmware(fw, MAX_FIRMWARE_BYTES).also { downloads[fw.id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of firmware ${fw.id} failed: ${e.javaClass.simpleName}")
            return if (e.message?.contains("too large", ignoreCase = true) == true) FirmwareFailure.TOO_LARGE else FirmwareFailure.DOWNLOAD
        }
        val md5 = Checksums.hexOf(ByteArrayInputStream(bytes), HashAlgo.MD5)
        when (RommFirmwareMatcher.verify(match, md5, bytes.size.toLong())) {
            RommFirmwareMatcher.Verdict.OK -> Unit
            RommFirmwareMatcher.Verdict.WRONG_DUMP -> return FirmwareFailure.WRONG_DUMP
            else -> return FirmwareFailure.CORRUPT
        }
        return try {
            val dir = folder(root, match.targetFolder) ?: return FirmwareFailure.WRITE
            // Never replace a file that is there after all (another name case, written meanwhile).
            if (findIgnoringCase(dir, match.targetName) != null) return FirmwareFailure.WRITE
            val written = StorageHelper.writeBytesSafely(context, dir, "", match.targetName, bytes)
            // Read it back: some providers accept a write and keep less.
            val check = context.contentResolver.openInputStream(written.uri)?.use { Checksums.hexOf(it, HashAlgo.MD5) }
            if (check == null || !check.equals(md5, ignoreCase = true)) {
                runCatching { written.delete() }
                FirmwareFailure.WRITE
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not write ${match.targetName}: ${e.javaClass.simpleName}")
            FirmwareFailure.WRITE
        }
    }

    /** The sub-folder [path] of [root] (`dc`), found without regard to case or created. */
    private fun folder(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val existing = findIgnoringCase(current, part)
            current = when {
                existing == null -> current.createDirectory(part) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }

    private fun findIgnoringCase(dir: DocumentFile, name: String): DocumentFile? =
        runCatching { dir.listFiles().firstOrNull { it.name?.equals(name, ignoreCase = true) == true } }.getOrNull()

    /** RomM URL and token are set (the card hides otherwise). */
    suspend fun isConfigured(): Boolean =
        rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank()
}
