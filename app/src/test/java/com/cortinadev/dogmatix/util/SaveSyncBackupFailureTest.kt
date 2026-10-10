package com.cortinadev.dogmatix.util

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveSyncBackupFailureTest {
    private val name = "Pokemon Emerald (USA).srm"
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, listOf(SaveSyncPlanner.RomCandidate(1, "Pokemon Emerald (USA).gba", "gba", "gba")))
    private val store = FakeSaveStore(clock)
    private val records = mutableMapOf<String, SaveSyncRecord>()

    private fun establish() = runBlocking {
        store.put(SaveKind.SAVE, name, "precious device save")
        SaveSyncEngine(server, store).sync(records)
        assertEquals(1, records.size)
    }

    private fun failingStore(failure: Exception = IOException("Safety copy could not be kept")) = object : SaveStore by store {
        override suspend fun backup(file: LocalSaveFile) { throw failure }
    }

    @Test fun `local deletion stops when its safety copy cannot be made`() = runBlocking {
        establish()
        val before = records.toMap()
        server.remove(SaveKind.SAVE, name)
        val result = SaveSyncEngine(server, failingStore(), syncDeletions = { true }).sync(records).first
        assertEquals(1, result.failed)
        assertEquals(0, result.deletedOnDevice)
        assertEquals("precious device save", store.text(SaveKind.SAVE, name))
        assertEquals(before, records)
        assertTrue(result.errors.single().contains("Safety copy"))
    }

    @Test fun `a newer server save cannot replace the device save without a safety copy`() = runBlocking {
        establish()
        val before = records.toMap()
        server.put(SaveKind.SAVE, 1, name, "newer server save")
        val result = SaveSyncEngine(server, failingStore()).sync(records).first
        assertEquals(1, result.failed)
        assertEquals(0, result.downloaded)
        assertEquals("precious device save", store.text(SaveKind.SAVE, name))
        assertEquals(before, records)
    }

    @Test fun `cancellation during backup is propagated and never deletes the save`() = runBlocking {
        establish()
        server.remove(SaveKind.SAVE, name)
        try {
            SaveSyncEngine(server, failingStore(CancellationException("Stopped")), syncDeletions = { true }).sync(records)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals("precious device save", store.text(SaveKind.SAVE, name))
            assertEquals(1, records.size)
        }
    }
}
