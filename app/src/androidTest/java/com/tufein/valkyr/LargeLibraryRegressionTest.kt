package com.tufein.valkyr

import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tufein.valkyr.data.local.DogmatixDatabase
import com.tufein.valkyr.data.local.entity.*
import com.tufein.valkyr.data.model.DownloadItemModel
import com.tufein.valkyr.data.model.DownloadStatus
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.util.QueueGlance
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Reproducible synthetic dataset; timings are observations, never a fragile pass threshold. */
class LargeLibraryRegressionTest {
    @Test fun tenAndFiftyThousandRowsPreserveSearchCollectionAndAllBatchTags() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, GamePackagesEntryPoint::class.java)
        val app = graph.packageAppSettings()
        val oldProfile = app.activeProfile.first()
        val databaseName = "large-library-${UUID.randomUUID()}.db"
        val selects = AtomicInteger()
        val db = Room.databaseBuilder(context, DogmatixDatabase::class.java, databaseName)
            .setQueryCallback({ sql, _ -> if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) selects.incrementAndGet() }, Executor { it.run() })
            .build()
        val repository = DownloadableFileRepository(db.downloadableFileDao(), graph.packageProfiles())
        val observations = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("dataset", "synthetic-v1: one console, two tags per row, 5000 collection members")
        try {
            app.setActiveProfile("")
            db.manufacturerDao().insertManufacturer(ManufacturerEntity("fixture", "Fixture"))
            db.consoleDao().insertConsole(ConsoleEntity("fixture", "Fixture", "fixture", "[]"))
            val collection = db.collectionDao().insert(CollectionEntity(name = "Fixture collection"))
            var inserted = 0
            for (count in listOf(10_000, 50_000)) {
                for (start in inserted until count step 1000) {
                    val rows = (start until minOf(count, start + 1000)).map { i ->
                        val number = i.toString().padStart(5, '0')
                        DownloadableFileEntity(id = i + 1L, name = "Fixture Game $number", fileName = "FixtureGame$number.gba",
                            consoleId = "fixture", downloadUrl = "https://fixture.invalid/$number.gba", fileSize = 1024,
                            fileExtension = "gba", sourceUrl = "https://fixture.invalid/", firstSeenAt = 1)
                    }
                    db.downloadableFileDao().insertFiles(rows)
                    db.downloadableFileDao().insertTags(rows.flatMap { listOf(FileTagEntity(it.id, "USA"), FileTagEntity(it.id, "English")) })
                    if (start < 5000) db.collectionDao().addItems(rows.map { CollectionItemEntity(collection, it.consoleId, it.fileName, 1) })
                }
                inserted = count
                assertEquals(count, db.downloadableFileDao().getFilesCount())
                val result = JSONObject()
                suspend fun measure(name: String, repeats: Int = 5, block: suspend () -> Unit) {
                    val timings = ArrayList<Double>()
                    repeat(repeats) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        block()
                        timings += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    result.put(name, JSONObject().put("firstMs", timings.first()).put("medianMs", timings.sorted()[timings.size / 2])
                        .put("maxMs", timings.max()).put("samples", timings))
                }
                measure("search50") {
                    val found = repository.searchFilesWithTags("Fixture Game 000", limit = 50)
                    assertEquals(50, found.size)
                    assertTrue(found.all { it.tags.toSet() == setOf("USA", "English") })
                }
                measure("collectionPage100") {
                    val page = repository.searchFilesWithTags("", collectionId = collection, limit = 100)
                    assertEquals(100, page.size)
                    assertTrue(page.all { it.file.fileName.substringAfter("FixtureGame").substringBefore('.').toInt() < 5000 })
                }
                val keys = (0 until 2000).map { "fixture" to "FixtureGame${it.toString().padStart(5, '0')}.gba" }
                measure("packageMatch2000") {
                    selects.set(0)
                    val matched = repository.exactMatchesBatch(keys)
                    val queryCount = selects.get()
                    assertEquals(2000, matched.size)
                    assertTrue(matched.values.all { it.single().tags.toSet() == setOf("USA", "English") })
                    assertEquals("Five bounded file batches and five tag batches", 10, queryCount)
                    result.put("packageMatchSelects", queryCount)
                }
                val allRows = db.downloadableFileDao().filesOf("fixture")
                val original = HashMap<Long, Set<String>>()
                measure("oldIndividualTagLookup", repeats = 1) {
                    selects.set(0)
                    allRows.forEach { original[it.id] = db.downloadableFileDao().tagsOf(it.id).toSet() }
                    result.put("oldTagSelects", selects.get())
                }
                measure("boundedTagLookup", repeats = 3) {
                    selects.set(0)
                    val grouped = allRows.map { it.id }.chunked(900).flatMap { db.downloadableFileDao().tagsOfFiles(it) }
                        .groupBy({ it.fileId }, { it.tag }).mapValues { it.value.toSet() }
                    val queryCount = selects.get()
                    assertEquals(original, grouped)
                    assertEquals((count + 899) / 900, queryCount)
                    result.put("boundedTagSelects", queryCount)
                }
                val queue = (0 until 2000).map { DownloadItemModel(name = "Fixture $it", fileName = "$it.gba", downloadSpeed = 0f,
                    progress = 0.5f, fileSize = 1024, downloadedBytes = 512, status = DownloadStatus.QUEUED) }
                measure("queueSummary2000") {
                    // The same summary used by the widget and secondary-screen dashboard.
                    withContext(Dispatchers.Main) {
                        val summary = QueueGlance.of(queue)
                        assertEquals(2000, summary.active)
                        assertEquals(50, summary.percent)
                    }
                }
                observations.put(count.toString(), result)
            }
            File(context.cacheDir, "large-library-observations.json").writeText(observations.toString(2))
            println("DOGMATIX_LARGE_LIBRARY=" + observations)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
            app.setActiveProfile(oldProfile)
        }
    }
}
