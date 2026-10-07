package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.ActionEntry
import com.cortinadev.dogmatix.util.ActionKind
import com.cortinadev.dogmatix.util.ActionReason
import com.cortinadev.dogmatix.util.DownloadRunTally
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes finished and failed downloads to the action history without flooding it (see
 * [DownloadRunTally]): a run lasts while the queue has anything queued or running; its first lines
 * are written one by one, the rest is summed up when the queue goes quiet, or every half hour for
 * a long queue. It only watches [DownloadService]; the download code is not touched.
 * Created at app start (DogmatixApplication) so it sees every download.
 */
@Singleton
class DownloadActionRecorder @Inject constructor(
    private val downloadService: DownloadService,
    private val actionLog: ActionLogService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tally = DownloadRunTally()
    private val lastStatus = HashMap<String, DownloadStatus>()

    init {
        scope.launch {
            downloadService.finished.collect { name ->
                val entity = downloadService.entityFor(name)
                val size = entity?.fileSize?.takeIf { it > 0 }
                    ?: downloadService.downloads.value.firstOrNull { it.fileName == name }?.fileSize ?: 0L
                note(ActionEntry("", 0L, ActionKind.DOWNLOADED, entity?.name ?: name.substringBeforeLast('.'), consoleId = entity?.consoleId, fileName = name, bytes = size))
            }
        }
        scope.launch {
            // A download that turns FAILED (once per failure; an automatic retry that fails again is a new one).
            downloadService.downloads.collect { items ->
                val failed = ArrayList<ActionEntry>()
                synchronized(lastStatus) {
                    val seen = HashSet<String>()
                    for (item in items) {
                        seen += item.fileName
                        if (item.status == DownloadStatus.FAILED && lastStatus[item.fileName] != DownloadStatus.FAILED && item.fileName in lastStatus) {
                            val entity = downloadService.entityFor(item.fileName)
                            failed += ActionEntry(
                                "", 0L, ActionKind.DOWNLOAD_FAILED, entity?.name ?: item.name, consoleId = entity?.consoleId, fileName = item.fileName,
                                reason = item.failure?.category?.let { ActionReason.DOWNLOAD_PREFIX + it.name }
                            )
                        }
                        lastStatus[item.fileName] = item.status
                    }
                    lastStatus.keys.retainAll(seen)
                }
                failed.forEach(::note)
            }
        }
        scope.launch {
            // The run ends once the queue has stayed empty of queued and running downloads for a moment.
            downloadService.anyActive.distinctUntilChanged().collectLatest { active ->
                if (!active) {
                    delay(QUIET_MS)
                    write(synchronized(tally) { tally.endRun(System.currentTimeMillis(), actionLog::newId) })
                }
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                write(synchronized(tally) { tally.tick(System.currentTimeMillis(), actionLog::newId) })
            }
        }
    }

    private fun note(entry: ActionEntry) {
        runCatching {
            val now = synchronized(tally) { tally.accept(entry, System.currentTimeMillis()) }
            if (now) actionLog.record(entry)
        }
    }

    private fun write(lines: List<ActionEntry>) = lines.forEach(actionLog::record)

    private companion object {
        const val QUIET_MS = 5_000L
        const val TICK_MS = 60_000L
    }
}
