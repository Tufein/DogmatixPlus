package com.cortinadev.dogmatix

import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.DownloadProgressTracker
import com.cortinadev.dogmatix.data.service.NotificationActionEntryPoint
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadQueue
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bulk regressions run against Android's main looper and real Room, without an external server. */
class BulkDownloadRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bulkStartStopAndRetryRemainResponsiveAndDoNotDuplicateRows() = runBlocking {
        val downloads = EntryPointAccessors.fromApplication(
            context.applicationContext, NotificationActionEntryPoint::class.java
        ).downloads()
        val prefix = "bulk-regression-${UUID.randomUUID()}"
        val files = (0 until BATCH_SIZE).map { file("$prefix-$it.gba") }
        val names = files.map { it.fileName }
        val wanted = names.toSet()
        val heldBefore = downloads.gate.held.value
        // Hold remains in force when stopping clears the individual time conditions. No test
        // download is allowed to reach an actual server or the user's download directory.
        downloads.gate.setHeld(true)
        eventually { downloads.gate.held.value }
        val tomorrow = DownloadCondition(ConditionKind.AT_TIME, atMillis = System.currentTimeMillis() + 86_400_000L)
        try {
            bulkAction {
                downloads.startDownloads(files, tomorrow)
                downloads.startDownloads(files, tomorrow) // A repeated bulk tap must be harmless.
            }
            eventually {
                val rows = downloads.downloads.value.filter { it.fileName in wanted }
                rows.size == BATCH_SIZE && downloads.itemConditions.value.keys.containsAll(wanted)
            }
            assertEquals(BATCH_SIZE, downloads.downloads.value.count { it.fileName in wanted })
            assertTrue(names.none(downloads::isTransferring))
            // Release the per-item schedule but retain the global hold. Reaching that hold
            // also confirms source selection and the initial history insertion have finished,
            // so test cleanup cannot race an outstanding initial upsert.
            bulkAction { downloads.setCondition(names, null) }
            eventually { downloads.waitingFiles.value.any { it in wanted } }
            assertTrue(names.none(downloads::isTransferring))

            bulkAction { names.forEach(downloads::cancelDownload) }
            eventually {
                downloads.downloads.value.filter { it.fileName in wanted }.let { rows ->
                    rows.size == BATCH_SIZE && rows.all { it.status == DownloadStatus.STOPPED }
                }
            }
            eventually { downloads.itemConditions.value.keys.none { it in wanted } }

            var retried = 0
            bulkAction {
                // The UI can select duplicate names and a no-longer-existing history entry.
                // Each real download is restarted exactly once.
                retried = downloads.retryDownloads(names + names.take(20) + "$prefix-missing.gba")
            }
            assertEquals(BATCH_SIZE, retried)
            assertEquals(BATCH_SIZE, downloads.downloads.value.count { it.fileName in wanted })
            assertTrue(downloads.downloads.value.filter { it.fileName in wanted }.all { !it.isFinished })
            assertTrue(names.none(downloads::isTransferring))
            // Another retry while the rows are already active must do no work.
            bulkAction { assertEquals(0, downloads.retryDownloads(names)) }
            bulkAction { names.forEach(downloads::cancelDownload) }
            eventually { downloads.downloads.value.filter { it.fileName in wanted }.all { it.status == DownloadStatus.STOPPED } }
        } finally {
            bulkAction { names.forEach { downloads.deleteDownload(it, deleteFile = false) } }
            eventually { downloads.downloads.value.none { it.fileName in wanted } }
            downloads.gate.setHeld(heldBefore)
            eventually { downloads.gate.held.value == heldBefore }
        }
    }

    @Test fun immediatePauseAndStopThenResumeKeepTheLatestBulkGeneration() = runBlocking {
        val downloads = EntryPointAccessors.fromApplication(
            context.applicationContext, NotificationActionEntryPoint::class.java
        ).downloads()
        val prefix = "generation-regression-${UUID.randomUUID()}"
        val files = (0 until BATCH_SIZE).map { file("$prefix-$it.gba") }
        val names = files.map { it.fileName }
        val wanted = names.toSet()
        val selected = names.take(50)
        val selectedNames = selected.toSet()
        val heldBefore = downloads.gate.held.value
        downloads.gate.setHeld(true)
        eventually { downloads.gate.held.value }
        val tomorrow = DownloadCondition(ConditionKind.AT_TIME, atMillis = System.currentTimeMillis() + 86_400_000L)
        try {
            withTimeout(30_000) {
                coroutineScope {
                    // Start watching before publication, so Stop can race the registration
                    // of the bulk jobs rather than waiting for startDownloads to return.
                    val stopAndResume = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        downloads.downloads.first { rows -> rows.count { it.fileName in wanted } == BATCH_SIZE }
                        names.forEach(downloads::cancelDownload)
                        assertEquals(BATCH_SIZE, downloads.retryDownloads(names))
                    }
                    val start = launch(Dispatchers.Default) { downloads.startDownloads(files, tomorrow) }
                    stopAndResume.await()
                    start.join()
                }
            }
            eventually(timeoutMillis = 30_000) {
                storedStatuses(wanted).let { rows ->
                    rows.size == BATCH_SIZE && rows.values.all { it == DownloadStatus.DOWNLOADING.name }
                }
            }
            repeat(20) { round ->
                bulkAction {
                    // Resume before cancellation callbacks have settled. A callback from
                    // an older job must not stop or pause its replacement.
                    selected.forEach { name ->
                        if (round % 2 == 0) downloads.pauseDownload(name) else downloads.cancelDownload(name)
                    }
                    assertEquals(selected.size, downloads.retryDownloads(selected))
                }
                assertTrue(downloads.downloads.value.filter { it.fileName in wanted }.all { it.status == DownloadStatus.DOWNLOADING })
            }
            // These replacement jobs can join the queue only after their predecessors and
            // cleanup have finished; the other 450 downloads hold all available slots.
            eventually { downloads.queued.value.containsAll(selected) }
            assertTrue(names.none(downloads::isTransferring))
            mainAction { }
            bulkAction { selected.forEach { downloads.pauseDownload(it) } }
            eventually {
                downloads.downloads.value.filter { it.fileName in wanted }.all { row ->
                    row.status == if (row.fileName in selectedNames) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING
                } && selected.none(downloads::isPausing) && downloads.queued.value.none { it in selectedNames }
            }
            eventually(timeoutMillis = 30_000) {
                storedStatuses(wanted).let { rows ->
                    rows.size == BATCH_SIZE && rows.all { (name, status) ->
                        status == if (name in selectedNames) DownloadStatus.PAUSED.name else DownloadStatus.DOWNLOADING.name
                    }
                }
            }
            bulkAction { names.forEach(downloads::cancelDownload) }
            eventually {
                downloads.downloads.value.filter { it.fileName in wanted }.all { it.status == DownloadStatus.STOPPED } &&
                    downloads.queued.value.none { it in wanted } && downloads.waitingFiles.value.none { it in wanted }
            }
            eventually(timeoutMillis = 30_000) {
                storedStatuses(wanted).let { rows ->
                    rows.size == BATCH_SIZE && rows.values.all { it == DownloadStatus.STOPPED.name }
                }
            }
        } finally {
            bulkAction { names.forEach { downloads.deleteDownload(it, deleteFile = false) } }
            eventually { downloads.downloads.value.none { it.fileName in wanted } }
            eventually { storedStatuses(wanted).isEmpty() }
            downloads.gate.setHeld(heldBefore)
            eventually { downloads.gate.held.value == heldBefore }
        }
    }

    @Test fun concurrentBulkProgressKeepsCompletionAndLatestHistoryStatus() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val dao = database.downloadHistoryDao()
        val tracker = DownloadProgressTracker(dao)
        val files = (0 until BATCH_SIZE).map { file("progress-$it.gba") }
        val initial = files.map { item(it) }
        dao.upsertAll(files.zip(initial) { file, item -> DownloadHistoryEntity.from(file, item) })
        tracker.addDownloads(initial)
        try {
            coroutineScope {
                // Several independent transfer workers finish while Room status writes are
                // outstanding. A slower old write must never replace COMPLETED on disk.
                files.chunked(25).forEach { chunk ->
                    launch(Dispatchers.Default) {
                        chunk.forEach { file ->
                            tracker.updateDownloadProgress(file.fileName, 0.5f, 2f, file.fileSize / 2)
                            tracker.updateDownloadStatus(file.fileName, DownloadStatus.COPYING)
                            tracker.updateDownloadStatus(file.fileName, DownloadStatus.UNZIPPING)
                            tracker.updateDownloadProgress(file.fileName, 1f, 0f, file.fileSize)
                            tracker.updateDownloadStatus(file.fileName, DownloadStatus.COMPLETED)
                        }
                    }
                }
            }
            assertEquals(BATCH_SIZE, tracker.downloads.value.size)
            tracker.downloads.value.forEach { row ->
                assertEquals(DownloadStatus.COMPLETED, row.status)
                assertEquals(1f, row.progress, 0f)
                assertEquals(row.fileSize, row.downloadedBytes)
                assertNotNull(row.finishedAt)
            }
            eventually(timeoutMillis = 30_000) {
                dao.getAll().let { rows ->
                    rows.size == BATCH_SIZE && rows.all { it.status == DownloadStatus.COMPLETED.name && it.finishedAt != null }
                }
            }
            // Recreating the visible history after a process restart must keep completion,
            // rather than changing an older persisted in-flight status into STOPPED.
            val restored = DownloadProgressTracker(dao)
            restored.restore(dao.getAll().map { it.toItem() })
            assertEquals(BATCH_SIZE, restored.downloads.value.count { it.status == DownloadStatus.COMPLETED })
            assertFalse(restored.hasActiveDownloads())
        } finally {
            database.close()
        }
    }

    @Test fun bulkRetryDiscardsOldProgressButPreservesActiveRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        try {
            val tracker = DownloadProgressTracker(database.downloadHistoryDao())
            val files = (0 until BATCH_SIZE).map { file("retry-$it.gba") }
            val initial = files.mapIndexed { index, file ->
                item(file).copy(
                    status = if (index % 2 == 0) DownloadStatus.FAILED else DownloadStatus.DOWNLOADING,
                    progress = 0.5f, downloadedBytes = file.fileSize / 2,
                    finishedAt = if (index % 2 == 0) 1234L else null
                )
            }
            tracker.addDownloads(initial)
            // A retry occurs before the batched old progress has reached the UI.
            initial.filter { it.isFinished }.forEach { tracker.updateDownloadProgress(it.fileName, 0.75f, 3f, 3072) }
            mainAction {
                assertEquals(BATCH_SIZE / 2, tracker.resetDownloadsForRetry(files.map { it.fileName }).size)
            }
            tracker.flushProgress()
            val activeBefore = initial.filter { !it.isFinished }.associateBy { it.fileName }
            tracker.downloads.value.forEach { row ->
                if (row.fileName in activeBefore) {
                    assertEquals(activeBefore.getValue(row.fileName), row)
                } else {
                    assertEquals(DownloadStatus.DOWNLOADING, row.status)
                    assertEquals(0f, row.progress, 0f)
                    assertEquals(0L, row.downloadedBytes)
                    assertEquals(null, row.finishedAt)
                }
            }
        } finally {
            database.close()
        }
    }

    @Test fun lateTorrentObservationsCannotOverwriteWorkerOrStoppedStates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java).build()
        val dao = database.downloadHistoryDao()
        try {
            val tracker = DownloadProgressTracker(dao)
            val protected = listOf(
                DownloadStatus.COPYING, DownloadStatus.UNZIPPING, DownloadStatus.PAUSED,
                DownloadStatus.STOPPED, DownloadStatus.FAILED, DownloadStatus.COMPLETED
            )
            val files = protected.map { file("late-${it.name}.gba") }
            val active = file("late-active.gba")
            val initial = files.zip(protected) { file, status ->
                item(file).copy(
                    status = status, progress = if (status == DownloadStatus.COMPLETED) 1f else 0.5f,
                    downloadedBytes = if (status == DownloadStatus.COMPLETED) file.fileSize else file.fileSize / 2,
                    finishedAt = if (status in setOf(
                        DownloadStatus.STOPPED, DownloadStatus.FAILED, DownloadStatus.COMPLETED
                    )) 1234L else null
                )
            } + item(active)
            dao.upsertAll((files + active).zip(initial) { file, item -> DownloadHistoryEntity.from(file, item) })
            tracker.addDownloads(initial)
            val nativeStates = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
            // The worker is copying when a delayed native "finished" / "failed" callback arrives.
            val copying = files.first().fileName
            tracker.updateDownloadProgress(copying, 0.75f, 3f, 3072)
            files.forEach { file ->
                tracker.updateDownloadStatus(file.fileName, DownloadStatus.COMPLETED, allowedFrom = nativeStates)
                tracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED, allowedFrom = nativeStates)
            }
            tracker.flushProgress()
            val actual = tracker.downloads.value.associateBy { it.fileName }
            initial.dropLast(1).forEach { expected ->
                val row = actual.getValue(expected.fileName)
                assertEquals(expected.status, row.status)
                assertEquals(expected.finishedAt, row.finishedAt)
            }
            // Rejecting a stale status must also leave the legitimate copying progress intact.
            assertEquals(0.75f, actual.getValue(copying).progress, 0f)
            assertEquals(3072L, actual.getValue(copying).downloadedBytes)
            tracker.updateDownloadProgress(active.fileName, 1f, 0f, active.fileSize)
            tracker.updateDownloadStatus(active.fileName, DownloadStatus.COMPLETED, allowedFrom = nativeStates)
            eventually { dao.getByFileName(active.fileName)?.status == DownloadStatus.COMPLETED.name }
            val stored = dao.getAll().associateBy { it.fileName }
            initial.dropLast(1).forEach { expected ->
                assertEquals(expected.status.name, stored.getValue(expected.fileName).status)
            }
        } finally {
            database.close()
        }
    }

    @Test fun cancellingHundredsOfWaitersLeavesTheMainLooperAndQueueUsable() = runBlocking {
        val queue = DownloadQueue(1)
        queue.acquire("initial")
        val running = AtomicInteger()
        val served = ArrayList<Int>()
        coroutineScope {
            val waiterScope = this
            val jobs = withContext(Dispatchers.Main) {
                (0 until BATCH_SIZE).map { index ->
                    waiterScope.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                        queue.acquire("queued-$index")
                        try {
                            assertEquals("A queue slot was granted twice", 1, running.incrementAndGet())
                            served += index
                        } finally {
                            running.decrementAndGet()
                            queue.release()
                        }
                    }
                }
            }
            try {
                eventually { queue.waiting.value.size == BATCH_SIZE }
                // This main-looper message must run while all 500 downloads are waiting,
                // before any queue slot is released.
                mainAction { assertTrue(served.isEmpty()) }
                withContext(Dispatchers.Main) { jobs.take(200).forEach { it.cancelAndJoin() } }
                eventually { queue.waiting.value.size == BATCH_SIZE - 200 }
                mainAction { queue.moveToFront("queued-${BATCH_SIZE - 1}") }
                assertEquals("queued-${BATCH_SIZE - 1}", queue.waiting.value.first())
                queue.release()
                withTimeout(10_000) { jobs.joinAll() }
                assertEquals(BATCH_SIZE - 200, served.size)
                assertEquals(BATCH_SIZE - 1, served.first())
                assertEquals((200 until BATCH_SIZE).toSet(), served.toSet())
                eventually { queue.waiting.value.isEmpty() }
                // A cancellation or racing grant must not permanently consume a slot.
                withTimeout(2_000) { queue.acquire("after-bulk") }
                queue.release()
                assertEquals(0, running.get())
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    /** Read the app's committed history; this connection never writes or changes its schema. */
    private fun storedStatuses(fileNames: Set<String>): Map<String, String> {
        // DatabaseModule's persistent Room database, also used when the process restarts.
        val path = context.getDatabasePath("dogmatix_db")
        if (!path.exists()) return emptyMap()
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val placeholders = fileNames.joinToString(",") { "?" }
            database.query(
                "download_history", arrayOf("fileName", "status"), "fileName IN ($placeholders)",
                fileNames.toTypedArray(), null, null, null
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
        }
    }

    private fun file(name: String) = DownloadableFileEntity(
        name = name, fileName = name, consoleId = "bulk-regression",
        downloadUrl = "http://127.0.0.1:1/$name", fileSize = 4096, fileExtension = "gba"
    )

    private fun item(file: DownloadableFileEntity) = DownloadItemModel(
        name = file.name, fileName = file.fileName, downloadSpeed = 0f, progress = 0f,
        fileSize = file.fileSize, status = DownloadStatus.DOWNLOADING, startedAt = 1000L
    )

    private suspend fun eventually(timeoutMillis: Long = 15_000, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!predicate()) delay(25)
        }
    }

    /** Matches the UI's worker dispatch while checking the main looper during the bulk action. */
    private suspend fun bulkAction(action: () -> Unit) = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val worker = async(Dispatchers.Default) {
            started.complete(Unit)
            action()
        }
        withTimeout(5_000) { started.await() }
        mainAction { }
        withTimeout(15_000) { worker.await() }
    }

    /** Bound both dispatch latency and the synchronous UI work, well below Android's ANR timeout. */
    private suspend fun mainAction(action: () -> Unit) {
        val result = CompletableDeferred<Unit>()
        Handler(Looper.getMainLooper()).post {
            val started = SystemClock.elapsedRealtime()
            try {
                action()
                assertTrue("Bulk action blocked the main thread", SystemClock.elapsedRealtime() - started < 2_000)
                result.complete(Unit)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        withTimeout(5_000) { result.await() }
    }

    private companion object { const val BATCH_SIZE = 500 }
}
