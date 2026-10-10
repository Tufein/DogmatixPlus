package com.cortinadev.dogmatix

import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.util.RomsetCatalog
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RomsetMergeRegressionTest {
    @Test fun optionalSourcesMergeIdempotentlyWithoutChangingDisabledSourcesNamesOrIndexedGames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RoadmapSourcesEntryPoint::class.java)
        val db = graph.database()
        val maker = "romset_${UUID.randomUUID().toString().replace("-", "")}" 
        val entry = RomsetCatalog.Entry("${maker}_test", maker, "Catalog maker", "test", "Catalog name", "CAT", listOf("cat"), "Atari - 2600", 1)
        val custom = UrlEntry("https://example.test/own/", ContentType.GAME, enabled = false)
        val initial = db.downloadableFileDao().getFilesCount()
        try {
            db.manufacturerDao().insertManufacturer(ManufacturerEntity(maker, "My manufacturer"))
            db.consoleDao().insertConsole(ConsoleEntity(entry.id, "My console", maker, SourcesJson.serializeUrlEntries(listOf(custom)), shortName = "OWN"))
            assertEquals(mapOf(entry.id to setOf(entry.url)), graph.sources().mergeRomsets(listOf(entry)))
            val after = db.consoleDao().getConsoleById(entry.id)!!
            assertEquals("My console", after.name)
            assertEquals("OWN", after.shortName)
            assertEquals(custom, SourcesJson.parseUrlEntries(after.urls).first())
            val disabled = SourcesJson.parseUrlEntries(after.urls).map { it.copy(enabled = false) }
            db.consoleDao().updateConsole(after.copy(urls = SourcesJson.serializeUrlEntries(disabled)))
            assertTrue(graph.sources().mergeRomsets(listOf(entry)).isEmpty())
            assertEquals(disabled, SourcesJson.parseUrlEntries(db.consoleDao().getConsoleById(entry.id)!!.urls))
            assertEquals(initial, db.downloadableFileDao().getFilesCount())
        } finally {
            db.consoleDao().deleteConsoleById(entry.id)
            db.manufacturerDao().deleteManufacturerById(maker)
        }
    }
}
