package com.cortinadev.dogmatix.ui.screens.next

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.ForYouResult
import com.cortinadev.dogmatix.data.service.ForYouService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.ProfileService
import com.cortinadev.dogmatix.data.service.SourceTrackService
import com.cortinadev.dogmatix.data.service.UpNextService
import com.cortinadev.dogmatix.util.ForYou
import com.cortinadev.dogmatix.util.LibraryKeys
import com.cortinadev.dogmatix.util.LibraryRestrictions
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.UpNext
import com.cortinadev.dogmatix.util.UpNextEntry
import com.cortinadev.dogmatix.util.UpNextState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class UpNextTab { UP_NEXT, FOR_YOU }

/**
 * One game of the list as the screen draws it. [file] is the library row (null once no source
 * lists the game); [owned] = on the device, so the row offers Play (the game page) instead of Download.
 */
data class UpNextRow(
    val entry: UpNextEntry,
    val file: DownloadableFileEntity?,
    val owned: Boolean,
    val downloading: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
) {
    val key: String get() = entry.key
}

/** The "For you" section: being worked out, or the result (an empty list says there was nothing to go on). */
data class ForYouUi(val loading: Boolean = true, val result: ForYouResult? = null)

/** What a Download from the list came to; the screen says it in words. */
enum class UpNextDownload { STARTED, ALREADY, NO_FOLDER, FOLDER_GONE, NOT_LISTED }

/**
 * The Up next screen (3.0.0): the active profile's list ([UpNextService]) joined with the library
 * rows, what is on the device and what is downloading; and "For you" ([ForYouService]), worked out
 * only once that tab is opened. Play is the game page's own (the screen opens the page); Download
 * goes to the same [DownloadService] entry as the game page, with the source that has the best
 * track record, and waits for the download conditions the user set like any other download.
 */
@HiltViewModel
class UpNextViewModel @Inject constructor(
    private val service: UpNextService,
    private val forYouService: ForYouService,
    private val fileDao: DownloadableFileDao,
    private val libraryIndex: LibraryIndexService,
    private val downloadService: DownloadService,
    private val sourceTrack: SourceTrackService,
    private val settingsRepository: SettingsRepository,
    private val profiles: ProfileService
) : ViewModel() {

    private val _tab = MutableStateFlow(UpNextTab.UP_NEXT)
    val tab: StateFlow<UpNextTab> = _tab.asStateFlow()

    /** Library rows (with their tags) of the games on the list, by key; refreshed when the list or the profile changes. */
    private data class Lookup(val rows: Map<String, Pair<DownloadableFileEntity, List<String>>>, val restrictions: LibraryRestrictions)
    private val lookup = MutableStateFlow<Lookup?>(null)

    private val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.mapTo(HashSet()) { it.fileName } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            combine(service.entries, profiles.restrictions) { list, _ -> list }.collect { list ->
                if (list == null) return@collect
                lookup.value = Lookup(rowsOf(list), profiles.current())
            }
        }
        // The list changed (added from a suggestion, a game page): the suggestions leave out what is on it.
        viewModelScope.launch { service.keys.drop(1).collect { forYouService.invalidate() } }
    }

    private suspend fun rowsOf(list: List<UpNextEntry>): Map<String, Pair<DownloadableFileEntity, List<String>>> = withContext(Dispatchers.IO) {
        try {
            val wanted = list.mapTo(HashSet()) { it.key }
            val files = list.map { it.fileName }.distinct().chunked(400).flatMap { fileDao.filesByFileNames(it) }
                .filter { UpNext.key(it.consoleId, it.fileName) in wanted }
                .associateBy { UpNext.key(it.consoleId, it.fileName) }
            val tags = files.values.map { it.id }.chunked(400).flatMap { fileDao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
            files.mapValues { (_, f) -> f to tags[f.id].orEmpty() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyMap() }
    }

    /** The rows the profile may see: a game on a console or with a tag it hides is left out (it stays on the list). */
    val rows: StateFlow<List<UpNextRow>?> = combine(service.entries, lookup, libraryIndex.ownedKeys, activeDownloads) { list, look, keys, active ->
        if (list == null || look == null) return@combine null
        val visible = list.filter { e ->
            val row = look.rows[e.key]
            look.restrictions.allows(e.consoleId, row?.second.orEmpty())
        }
        visible.map { e ->
            val file = look.rows[e.key]?.first
            UpNextRow(
                entry = e,
                file = file,
                owned = if (file != null) libraryIndex.isOwned(file, keys) else LibraryKeys.isOwned(e.consoleId, e.fileName, keys),
                downloading = e.fileName in active,
                canMoveUp = UpNext.canMove(visible, e.key, -1),
                canMoveDown = UpNext.canMove(visible, e.key, 1)
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val upNextKeys: StateFlow<Set<String>> = service.keys

    private val _forYou = MutableStateFlow(ForYouUi(loading = false))
    val forYou: StateFlow<ForYouUi> = _forYou.asStateFlow()
    private var forYouJob: Job? = null

    fun setTab(tab: UpNextTab) {
        _tab.value = tab
        if (tab == UpNextTab.FOR_YOU && _forYou.value.result == null && forYouJob?.isActive != true) loadForYou(force = false)
    }

    fun stepTab(delta: Int) {
        val all = UpNextTab.entries
        setTab(all[((all.indexOf(_tab.value) + delta) % all.size + all.size) % all.size])
    }

    /** Works the suggestions out (the kept ones while fresh, unless [force]). */
    fun loadForYou(force: Boolean) {
        forYouJob?.cancel()
        _forYou.value = _forYou.value.copy(loading = true)
        forYouJob = viewModelScope.launch {
            val result = try { forYouService.suggestions(force) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            _forYou.value = ForYouUi(loading = false, result = result ?: ForYouResult(emptyList(), System.currentTimeMillis(), 0))
        }
    }

    // ---- Edits ---------------------------------------------------------------------------------

    /** Runs an edit; [onDone] gets false when the list could not be saved. */
    private fun edit(onDone: (Boolean) -> Unit = {}, block: suspend () -> Boolean) {
        viewModelScope.launch { onDone(block()) }
    }

    fun move(key: String, delta: Int, onDone: (Boolean) -> Unit = {}) = edit(onDone) { service.move(key, delta) }
    fun setState(key: String, state: UpNextState, onDone: (Boolean) -> Unit = {}) = edit(onDone) { service.setState(key, state) }
    fun remove(key: String, onDone: (Boolean) -> Unit = {}) = edit(onDone) { service.remove(key) }
    fun clearFinished(onDone: (Boolean) -> Unit = {}) = edit(onDone) { service.clearFinished() }

    /** Puts a suggestion on the list (its shown version). */
    fun add(suggestion: ForYou.Suggestion, onDone: (Boolean) -> Unit = {}) = edit(onDone) {
        val row = suggestion.row
        service.add(row.consoleId, row.fileName, row.name.substringBeforeLast('.').ifBlank { row.name })
    }

    /**
     * Queues [row]'s game like the game page's Download: the download folder must be set and
     * reachable, the source with the best track record is used, and the download waits for the
     * conditions set in Settings.
     */
    fun download(row: UpNextRow, context: Context, onResult: (UpNextDownload) -> Unit) {
        val file = row.file ?: return onResult(UpNextDownload.NOT_LISTED)
        if (row.downloading || file.fileName in activeDownloads.value) return onResult(UpNextDownload.ALREADY)
        viewModelScope.launch {
            val directory = settingsRepository.downloadDirectory.first()
            val outcome = when {
                directory.isEmpty() -> UpNextDownload.NO_FOLDER
                !withContext(Dispatchers.IO) { StorageHelper.isValidUri(context, directory) } -> UpNextDownload.FOLDER_GONE
                else -> {
                    downloadService.startDownload(sourceTrack.pickBest(file, sameNameOnly = true))
                    UpNextDownload.STARTED
                }
            }
            onResult(outcome)
        }
    }
}
