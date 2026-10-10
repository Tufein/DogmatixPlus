package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class GameReadyReport(val filesOk: Boolean, val discsOk: Boolean, val biosOk: Boolean,
    val emulatorOk: Boolean, val needsExtract: Boolean, val games: List<GameLaunch> = emptyList(),
    val gameOverride: String? = null, val consoleDefault: String? = null,
    val packageInspection: PackageInspection? = null, val emulatorChoiceNeeded: Boolean = false) {
    val ready: Boolean get() = filesOk && discsOk && biosOk && emulatorOk && !needsExtract
}

/** A read-only local check shared by individual games and offline collections; never launches apps. */
@Singleton
class GameReadinessService @Inject constructor(@param:ApplicationContext private val context: Context,
    private val library: LibraryIndexService, private val launcher: GameLaunchService, private val bios: BiosService) {
    suspend fun biosReport(): BiosReport = bios.check(true)
    suspend fun check(file: DownloadableFileEntity, verifyPackageHashes: Boolean = false,
        existingBios: BiosReport? = null): GameReadyReport = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val receipt = library.packageInspection(file, verifyPackageHashes)
        val plan = receipt?.files?.filter { GameArtifacts.safeLaunchPath(it.path) } ?: library.launchPlan(file)
        var readable = plan.isNotEmpty() && receipt?.complete != false
        for (part in plan) {
            coroutine.ensureActive()
            val ok = try { context.contentResolver.openInputStream(Uri.parse(part.uri))?.use { it.read() != -1 } == true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            if (!ok) readable = false
        }
        val sheetsOk = plan.filter { SheetParser.isSheet(it.name) }.all { part ->
            coroutine.ensureActive()
            try {
                val text = context.contentResolver.openInputStream(Uri.parse(part.uri))?.use {
                    val bytes = BoundedStreams.read(it, SetChecker.MAX_SHEET_BYTES.toInt() + 1)
                    kotlin.check(bytes.size <= SetChecker.MAX_SHEET_BYTES)
                    bytes.toString(Charsets.UTF_8)
                } ?: return@all false
                GameReadiness.referencesPresentAt(part.path, text, plan.map { it.path })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }
        val games = launcher.choices(file)
        val systems = BiosCatalog.systemsFor(listOf(file.consoleId)).map { it.name }.toSet()
        val report = if (systems.isEmpty()) null else existingBios ?: bios.check(true)
        val biosOk = systems.isEmpty() || report != null && systems.all { system ->
            report.results.firstOrNull { it.system.name == system }?.allGood == true
        }
        val override = launcher.gamePreferred(file.consoleId, file.fileName)
        val default = launcher.preferred(file.consoleId)
        val emulator = GameReadiness.emulatorState(games, override, default)
        GameReadyReport(readable, sheetsOk, biosOk, emulator.available, emulator.needsExtract, games,
            override, default, receipt, emulator.needsChoice)
    }
}
