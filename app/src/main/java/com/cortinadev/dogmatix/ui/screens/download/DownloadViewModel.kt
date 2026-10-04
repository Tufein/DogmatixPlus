package com.cortinadev.dogmatix.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.RommUploadService
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.util.QueueActions
import com.cortinadev.dogmatix.util.QueueEta
import com.cortinadev.dogmatix.util.StorageInsights
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.VerifyState
import com.cortinadev.dogmatix.util.WaitReason
import android.content.Context
import com.cortinadev.dogmatix.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import com.cortinadev.dogmatix.data.service.UploadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Statuses each action accepts, shared by the row buttons and the multi-selection bar. */
internal val DownloadStatus.canRetry: Boolean
    get() = this == DownloadStatus.COMPLETED || this == DownloadStatus.STOPPED ||
        this == DownloadStatus.FAILED || this == DownloadStatus.PAUSED

internal val DownloadStatus.canStop: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING ||
        this == DownloadStatus.UNZIPPING

/** Same set as [canRetry]: a running download is stopped first, never deleted outright. */
internal val DownloadStatus.canDelete: Boolean get() = canRetry

/** Download names looked up in the library per batch (see [DownloadViewModel.downloadDetails]). */
private const val DETAILS_BATCH = 400

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val fileRepository: DownloadableFileRepository,
    private val rommUploadService: RommUploadService,
    private val downloadService: DownloadService,
    libraryIndexService: LibraryIndexService,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Downloads held back by the schedule (Wi-Fi / charger / night) and why. */
    val waitingFiles: StateFlow<Set<String>> = downloadService.waitingFiles
    val waitingReasons: StateFlow<List<WaitReason>> = downloadService.gate.waiting
    /** Checksum check per finished download. */
    val verification: StateFlow<Map<String, VerifyState>> = downloadService.verification

    /** Lets everything that is waiting for the schedule start now. */
    fun startWaitingNow() = downloadService.gate.startNow()

    /** The user's hold on the queue: running downloads finish, nothing new starts. */
    val held: StateFlow<Boolean> = downloadService.gate.held
    fun setHeld(on: Boolean) = downloadService.gate.setHeld(on)

    /** What is left of the queue and how long it takes at the current speed. */
    val queueEta: StateFlow<QueueEta.Eta> = downloadService.downloads.map { list ->
        QueueEta.of(list.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }.map {
            QueueEta.Item((it.fileSize - it.downloadedBytes).coerceAtLeast(0), it.downloadSpeed, running = it.status == DownloadStatus.DOWNLOADING)
        })
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueEta.Eta(0, 0, null))

    /** Downloads waiting for a free slot, in the order they will start. */
    val queued: StateFlow<List<String>> = downloadService.queued
    fun moveUp(fileName: String) = downloadService.moveUp(fileName)
    fun moveDown(fileName: String) = downloadService.moveDown(fileName)
    fun moveToFront(fileName: String) = downloadService.moveToFront(fileName)

    /** Bytes the queue is short of the free space; 0 when it fits or the space is unknown. */
    val queueShortfall: StateFlow<Long> = combine(downloadService.downloads, libraryIndexService.freeBytes) { list, free ->
        val need = StorageInsights.queueNeed(
            list.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                .map { StorageInsights.QueueItem((it.fileSize - it.downloadedBytes).coerceAtLeast(0), StorageInsights.isExtractable(it.fileName.substringAfterLast('.', ""))) }
        )
        StorageInsights.shortfall(need, free) ?: 0L
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Opens a finished download in whichever app handles the file. */
    fun openDownload(context: Context, fileName: String) {
        viewModelScope.launch {
            val intent = downloadService.openIntentFor(fileName)
            val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
            if (!ok) ToastUtil.showInfo(context, context.getString(R.string.download_open_none))
        }
    }

    /** Name of the debrid service shown on QUEUED rows ("TorBox 40%"). */
    val debridLabel: StateFlow<String> = settingsRepository.debridProvider.map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val downloads: StateFlow<List<DownloadItemModel>> = repository.downloads

    /** Queued or downloading, for the tab badge: changes with the status, not with every progress tick. */
    val activeCount: StateFlow<Int> = downloads.map { list -> list.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED } }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** What the whole-queue buttons would act on. */
    val queueCounts: StateFlow<QueueActions.Counts> = downloads.map { QueueActions.counts(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueActions.Counts())

    // The whole-queue actions touch hundreds of rows: off the UI thread, so a big queue never freezes the screen.
    fun stopAll() {
        viewModelScope.launch(Dispatchers.Default) { QueueActions.stoppable(downloads.value).forEach { repository.cancelDownload(it) } }
    }

    /** Failed and stopped downloads go back in the queue as one batch (they continue from their partial file when they can). */
    fun retryFailed() {
        viewModelScope.launch(Dispatchers.Default) { downloadService.retryDownloads(QueueActions.retryable(downloads.value)) }
    }

    /** Removes the completed rows from the list; the files stay where they are. */
    fun clearFinished() {
        viewModelScope.launch(Dispatchers.Default) { QueueActions.clearable(downloads.value).forEach { repository.deleteDownload(it, false) } }
    }

    /** RomM upload state per download (see [RommUploadService]). */
    val uploads: StateFlow<Map<String, UploadState>> = rommUploadService.uploads

    fun retryUpload(fileName: String) = rommUploadService.retry(fileName)

    /** Looked-up details per file name; names the library does not know are in [detailsMissing]. */
    private val detailsCache = ConcurrentHashMap<String, DownloadableFileWithTags>()
    private val detailsMissing = ConcurrentHashMap.newKeySet<String>()

    /**
     * Indexed file + tags for each download, keyed by fileName, so the list can show what each one is.
     * New names are looked up a few hundred per query, off the UI thread, and the list fills in per
     * batch. One query per name (each a scan of the whole library table) after queueing a whole
     * console kept the database and the UI thread busy for minutes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadDetails: StateFlow<Map<String, DownloadableFileWithTags>> = downloads
        .map { list -> list.map { it.fileName } }
        .distinctUntilChanged()
        .transformLatest { names ->
            fun known() = names.mapNotNull { name -> detailsCache[name]?.let { name to it } }.toMap()
            val unknown = names.filter { !detailsCache.containsKey(it) && it !in detailsMissing }
            emit(known())
            for (chunk in unknown.chunked(DETAILS_BATCH)) {
                val found = fileRepository.findByFileNames(chunk) { downloadService.entityFor(it)?.consoleId }
                chunk.forEach { name -> found[name]?.let { detailsCache[name] = it } ?: detailsMissing.add(name) }
                emit(known())
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** File names ticked for a bulk action; empty = no selection mode. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    init {
        // Rows deleted elsewhere (or by us) must not linger in the selection.
        viewModelScope.launch {
            downloads.collect { list ->
                if (_selection.value.isNotEmpty()) {
                    val alive = list.mapTo(mutableSetOf()) { it.fileName }
                    _selection.value = _selection.value.intersect(alive)
                }
            }
        }
    }

    fun toggleSelection(fileName: String) {
        _selection.value = _selection.value.let { if (fileName in it) it - fileName else it + fileName }
    }

    /** Y on the list: tick everything, or clear it when everything is already ticked. */
    fun toggleSelectAll() {
        val all = downloads.value.mapTo(mutableSetOf()) { it.fileName }
        _selection.value = if (_selection.value.containsAll(all)) emptySet() else all
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selectedItems(): List<DownloadItemModel> =
        downloads.value.filter { it.fileName in _selection.value }

    fun retrySelected() = runOnSelection({ it.status.canRetry }) { repository.retryDownload(it.fileName) }

    fun stopSelected() = runOnSelection({ it.status.canStop }) { repository.cancelDownload(it.fileName) }

    /** Torrents while they transfer, web downloads also while queued (they keep their partial file). */
    fun pauseSelected() = runOnSelection({ item ->
        downloadDetails.value[item.fileName]?.let { QueueActions.canPause(item.status, it.file.isTorrent) } == true
    }) { repository.pauseDownload(it.fileName) }

    /**
     * Runs [action] over the ticked rows that [accepts] takes. The selection stays: the rows are
     * still there, their new status is visible, and the bar keeps the focus for a follow-up action.
     */
    private fun runOnSelection(
        accepts: (DownloadItemModel) -> Boolean,
        action: suspend (DownloadItemModel) -> Unit
    ) {
        val targets = selectedItems().filter(accepts)
        viewModelScope.launch { targets.forEach { action(it) } }
    }

    /** Deleting the selection asks about the files only when some of them finished. */
    fun deleteSelected() {
        val targets = selectedItems().filter { it.status.canDelete }
        if (targets.isEmpty()) return
        if (targets.any { it.status == DownloadStatus.COMPLETED }) {
            _showDeleteConfirmation.value = targets.map { it.fileName }
        } else {
            clearSelection()
            deleteDownloads(targets.map { it.fileName }, deleteFile = false)
        }
    }

    /** File names awaiting the "delete the file too?" answer (one row, or a whole selection). */
    private val _showDeleteConfirmation = MutableStateFlow<List<String>?>(null)
    val showDeleteConfirmation: StateFlow<List<String>?> = _showDeleteConfirmation.asStateFlow()

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            repository.cancelDownload(fileName)
        }
    }

    fun pauseDownload(fileName: String) {
        viewModelScope.launch { repository.pauseDownload(fileName) }
    }

    fun retryDownload(fileName: String) {
        viewModelScope.launch { 
            repository.retryDownload(fileName) 
        }
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        deleteDownloads(listOf(fileName), deleteFile)
    }

    private fun deleteDownloads(fileNames: List<String>, deleteFile: Boolean) {
        viewModelScope.launch {
            fileNames.forEach { repository.deleteDownload(it, deleteFile) }
        }
    }

    fun deleteDownloadWithConfirmation(fileName: String, isCompleted: Boolean) {
        if (isCompleted) {
            _showDeleteConfirmation.value = listOf(fileName)
        } else {
            deleteDownload(fileName, deleteFile = false)
        }
    }
    
    fun confirmDeleteKeepFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = false)
    }
    
    fun confirmDeleteRemoveFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = true)
    }
    
    fun cancelDeleteConfirmation() {
        _showDeleteConfirmation.value = null
    }
}
