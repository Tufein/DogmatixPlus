package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.util.DownloadLogEntry
import com.cortinadev.dogmatix.util.DownloadStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one line per finished download in `files/download_log.txt` for the statistics screen; the
 * Downloads list itself can be cleared, the log is not.
 */
@Singleton
class DownloadLog @Inject constructor(
    @param:ApplicationContext context: Context,
    downloadService: DownloadService
) {
    private val file = File(context.filesDir, "download_log.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            downloadService.finished.collect { name ->
                val entity = downloadService.entityFor(name) ?: return@collect
                val size = entity.fileSize.takeIf { it > 0 }
                    ?: downloadService.downloads.value.firstOrNull { it.fileName == name }?.fileSize ?: 0L
                append(DownloadLogEntry(System.currentTimeMillis(), entity.consoleId, size))
            }
        }
    }

    @Synchronized
    private fun append(entry: DownloadLogEntry) {
        runCatching { file.appendText(DownloadStats.line(entry) + "\n") }
    }

    suspend fun entries(): List<DownloadLogEntry> = withContext(Dispatchers.IO) {
        runCatching { DownloadStats.parse(file.readText()) }.getOrDefault(emptyList())
    }
}
