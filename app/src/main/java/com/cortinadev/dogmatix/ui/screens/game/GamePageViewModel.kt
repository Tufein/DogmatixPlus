package com.cortinadev.dogmatix.ui.screens.game

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.CollectionWithCount
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.repository.CollectionsRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.data.service.RommLibraryService
import com.cortinadev.dogmatix.data.service.SourceTrackService
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import com.cortinadev.dogmatix.ui.screens.home.GameDetailsLoader
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.RommMarks
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the page stands: finding the library row, showing it, or the row is gone (re-indexed away). */
enum class GamePagePhase { LOADING, READY, MISSING }

/**
 * 8.0: the full-screen game page. Finds the library row behind `consoleId` / `fileName` and fills a
 * [DetailsState] with the same [GameDetailsLoader] the library's details card uses; the actions
 * (download, favourite, collections, remove) go to the same services as in the library.
 */
@HiltViewModel
class GamePageViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val loader: GameDetailsLoader,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val libraryIndex: LibraryIndexService,
    private val favourites: FavouritesRepository,
    private val collectionsRepository: CollectionsRepository,
    private val sourceTrack: SourceTrackService,
    rommLibrary: RommLibraryService,
    retroAchievements: RetroAchievementsService,
    val gameLauncher: com.cortinadev.dogmatix.data.service.GameLaunchService,
    private val versionPreference: com.cortinadev.dogmatix.data.service.VersionPreferenceService,
    datService: com.cortinadev.dogmatix.data.service.DatService,
    private val profiles: com.cortinadev.dogmatix.data.service.ProfileService
) : ViewModel() {

    private val _phase = MutableStateFlow(GamePagePhase.LOADING)
    val phase: StateFlow<GamePagePhase> = _phase.asStateFlow()

    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()

    private var key: Pair<String, String>? = null
    private var job: Job? = null

    /** Shows [fileName] of [consoleId]; a repeat call for the same game does nothing (recomposition, rotation). */
    fun load(consoleId: String, fileName: String) {
        if (key == consoleId to fileName) return
        key = consoleId to fileName
        preferredJob?.cancel()
        preferredJob = viewModelScope.launch { versionPreference.observe(consoleId, fileName).collect { _preferred.value = it } }
        job?.cancel()
        _phase.value = GamePagePhase.LOADING
        _details.value = null
        job = viewModelScope.launch {
            // findByFileNames may fall back to another console's row with the same name: only this console's counts.
            val item = runCatching { repository.findByFileNames(listOf(fileName)) { consoleId }[fileName] }.getOrNull()
                ?.takeIf { it.file.consoleId == consoleId && profiles.current().allows(it.file.consoleId, it.tags) }
            if (item == null) { _phase.value = GamePagePhase.MISSING; return@launch }
            _details.value = DetailsState(item, loading = true)
            _phase.value = GamePagePhase.READY
            loader.load(item, index = null) { change ->
                _details.value?.takeIf { it.item == item }?.let { _details.value = change(it) }
            }
        }
    }

    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys
    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val favouriteKeys: StateFlow<Set<String>> = favourites.keys
    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean = favourites.toggle(item.file)

    val rommKeys: StateFlow<Set<String>> = rommLibrary.keys
    private val rommBase: StateFlow<String> = settingsRepository.rommUrl.map { it.trim().trimEnd('/') }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun isOnRomm(file: DownloadableFileEntity, keys: Set<String>): Boolean {
        val base = rommBase.value
        return (keys.isNotEmpty() || base.isNotEmpty()) && RommMarks.isOnServer(keys, file.consoleId, file.fileName, file.downloadUrl, base)
    }

    /**
     * Whether the Progress tab has something to show: a RetroAchievements account or a RomM server
     * (play status, rating and cloud saves live there). The sections themselves still hide when the
     * game is unknown to them.
     */
    val progressAvailable: StateFlow<Boolean> = combine(retroAchievements.accountReady, rommBase) { ra, romm ->
        ra == true || romm.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val collections: StateFlow<List<CollectionWithCount>> = collectionsRepository.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Puts the page's game in or out of collection [id]. */
    suspend fun toggleCollection(item: DownloadableFileWithTags, id: Long): Boolean {
        val inIt = collectionsRepository.toggle(id, item.file)
        _details.value?.takeIf { it.item == item }?.let { _details.value = it.copy(collectionIds = collectionsRepository.collectionsOf(item.file)) }
        return inIt
    }

    suspend fun createCollectionWith(item: DownloadableFileWithTags, name: String): Boolean {
        val id = collectionsRepository.create(name) ?: return false
        if (id !in collectionsRepository.collectionsOf(item.file)) toggleCollection(item, id)
        return true
    }

    /** Queues [file] (from the source with the best track record); false when the download folder is missing. */
    suspend fun download(file: DownloadableFileEntity, context: Context, condition: DownloadCondition? = null): Boolean {
        val directory = settingsRepository.downloadDirectory.first()
        if (directory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return false
        }
        if (!StorageHelper.isValidUri(context, directory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return false
        }
        downloadService.startDownload(sourceTrack.pickBest(file, sameNameOnly = true), condition)
        return true
    }

    val datReports = datService.reports
    val languages = settingsRepository.favoriteLanguages.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _preferred = MutableStateFlow<String?>(null)
    val preferred = _preferred.asStateFlow()
    private var preferredJob: Job? = null

    suspend fun removalPlan(item: DownloadableFileWithTags) = libraryIndex.removalPlan(item.file)
    suspend fun remove(plan: List<com.cortinadev.dogmatix.data.service.RemovalFile>, title: String) = libraryIndex.deletePlan(plan, title)
    suspend fun setPreferred(item: DownloadableFileWithTags, pinned: Boolean) {
        versionPreference.set(item.file.consoleId, item.file.fileName, if (pinned) item.file.fileName else null)
        _preferred.value = if (pinned) item.file.fileName else null
        val current = _details.value ?: return
        val candidates = current.versions.map { com.cortinadev.dogmatix.util.VersionPicker.Candidate(it.file.fileName, it.file.fileName, it.tags, it.file.fileSize) }
        val languages = settingsRepository.favoriteLanguages.first()
        val best = com.cortinadev.dogmatix.util.VersionPreference.pick(candidates, com.cortinadev.dogmatix.util.VersionPicker.regionPreference(languages), languages, _preferred.value)?.id
        _details.value = current.copy(bestFileName = best, best = current.versions.firstOrNull { it.file.fileName == best })
    }

    /** Removes the game from the download folder (the screen confirms first). */
    suspend fun deleteOwned(item: DownloadableFileWithTags): Boolean = libraryIndex.deleteOwned(item.file)
}
