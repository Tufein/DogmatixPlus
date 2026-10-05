package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DatDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DatSetEntity
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.CollectionBasis
import com.cortinadev.dogmatix.util.CollectionGoals
import com.cortinadev.dogmatix.util.CollectionTally
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.ConsoleProgress
import com.cortinadev.dogmatix.util.LibraryKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionGoals"

/** Every console's completion as last worked out. */
data class CollectionState(val loading: Boolean = true, val rows: List<ConsoleProgress> = emptyList())

/**
 * How complete each console's collection is (6.0). Measured against the imported DAT when there is
 * one (exactly, when the DAT check ran; by file names otherwise), else against what the sources
 * list. Worked out on Dispatchers.Default / IO from the on-disk index (no disk walk), kept in memory
 * and only worked out again when the index, the DATs, the consoles or the sources change. Only
 * counts are kept; a console's missing titles are read again when it is opened.
 */
@OptIn(FlowPreview::class)
@Singleton
class CollectionGoalsService @Inject constructor(
    private val consoleRepository: ConsoleRepository,
    private val fileDao: DownloadableFileDao,
    private val datDao: DatDao,
    private val dat: DatService,
    private val libraryIndex: LibraryIndexService,
    private val rescan: RescanStateHolder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(CollectionState())
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    @Volatile private var started = false

    /** DAT game names per console, with the import time they were read at. */
    private val datNames = HashMap<String, Pair<Long, List<String>>>()

    /** Starts watching (once). Safe to call from every view model. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                libraryIndex.ownedKeys, dat.sets, dat.reports, consoleRepository.getAllConsoles(), rescan.lastRescanTime
            ) { _, _, _, _, _ -> Unit }
                .debounce(700)
                .collect {
                    val rows = try { computeAll() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w(TAG, "Collection not worked out: ${e.message}"); null
                    }
                    if (rows != null) _state.value = CollectionState(loading = false, rows = rows)
                    else _state.value = _state.value.copy(loading = false)
                }
        }
    }

    private suspend fun computeAll(): List<ConsoleProgress> = withContext(Dispatchers.IO) {
        val consoles = consoleRepository.getAllConsoles().first()
        val counts = fileDao.countsByConsole().associate { it.consoleId to it.count }
        val sets = dat.sets.first().associateBy { it.consoleId }
        val keys = libraryIndex.ownedKeys.value
        val byScope by lazy { CollectionGoals.namesByScope(keys) }
        val out = ArrayList<ConsoleProgress>(consoles.size)
        for (console in consoles) {
            val set = sets[console.id]
            val indexed = counts[console.id] ?: 0
            if (set == null && indexed == 0) continue
            val (tally, basis) = tallyFor(console, set, indexed, keys, { byScope })
            if (tally.total > 0) out += ConsoleProgress(console.id, ConsoleFormatter.getConsoleDisplayName(console.id), tally.owned, tally.total, basis)
        }
        out
    }

    /** The titles console [consoleId] is missing, A-Z (all of them; the screen pages). */
    suspend fun missing(consoleId: String): List<String> = withContext(Dispatchers.IO) {
        val console = consoleRepository.getAllConsoles().first().firstOrNull { it.id == consoleId } ?: return@withContext emptyList()
        val set = datDao.setOf(consoleId)
        val keys = libraryIndex.ownedKeys.value
        tallyFor(console, set, indexed = 1, keys = keys, byScope = { CollectionGoals.namesByScope(keys) }).first.missing
    }

    private suspend fun tallyFor(
        console: ConsoleEntity,
        set: DatSetEntity?,
        indexed: Int,
        keys: Set<String>,
        byScope: () -> Map<String, Set<String>>
    ): Pair<CollectionTally, CollectionBasis> {
        val scopes = LibraryKeys.scopesFor(console.id)
        if (set != null) {
            val report = dat.reports.value[console.id]
            if (report != null) return CollectionGoals.reportTally(report.gameCount, report.missing) to CollectionBasis.DAT_VERIFIED
            val names = synchronized(datNames) { datNames[console.id]?.takeIf { it.first == set.importedAt }?.second }
                ?: datDao.romsOf(console.id).map { it.gameName }.distinct().also { synchronized(datNames) { datNames[console.id] = set.importedAt to it } }
            val owned = CollectionGoals.ownedNames(scopes, byScope())
            return CollectionGoals.datTally(names, owned) to CollectionBasis.DAT_NAMES
        }
        if (indexed == 0) return CollectionTally(0, 0, emptyList()) to CollectionBasis.SOURCES
        val files = fileDao.fileNamesFor(console.id)
        return CollectionGoals.sourceTally(files) { CollectionGoals.isOwned(scopes, it, keys) } to CollectionBasis.SOURCES
    }
}
