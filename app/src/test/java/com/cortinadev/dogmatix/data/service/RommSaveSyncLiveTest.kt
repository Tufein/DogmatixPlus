package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FakeSaveStore
import com.cortinadev.dogmatix.util.SaveKind
import com.cortinadev.dogmatix.util.SaveServer
import com.cortinadev.dogmatix.util.SaveSyncEngine
import com.cortinadev.dogmatix.util.SaveSyncRecord
import com.cortinadev.dogmatix.util.RemoteSaveFile
import com.cortinadev.dogmatix.util.TestClock
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The save sync against a real RomM server, with the app's own [RommClient]. Skipped unless
 * `ROMM_TEST_URL` and `ROMM_TEST_TOKEN` (an `rmm_…` client token with assets and roms scopes)
 * are set; the server needs ROMs named `Pokemon Emerald (USA).gba` (platform gba) and
 * `Super Mario World (USA).sfc` (snes). It writes saves and states to that account.
 */
class RommSaveSyncLiveTest {

    private val url = System.getenv("ROMM_TEST_URL").orEmpty()
    private val token = System.getenv("ROMM_TEST_TOKEN").orEmpty()
    private val save = System.getenv("ROMM_TEST_SAVE") ?: "Pokemon Emerald (USA).srm"
    private val state = System.getenv("ROMM_TEST_STATE") ?: "Super Mario World (USA).state.auto"

    private fun client(): RommClient {
        val settings = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SettingsRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "getRommUrl" -> flowOf(url)
                "getRommToken" -> flowOf(token)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SettingsRepository
        return RommClient(settings)
    }

    private fun server(client: RommClient) = object : SaveServer {
        override suspend fun list(kind: SaveKind) = client.saves(kind)
        override suspend fun download(save: RemoteSaveFile) = client.downloadSave(save, 1L shl 26)
        override suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray) =
            client.uploadSave(kind, romId, fileName, emulator, bytes)
        override suspend fun searchRoms(term: String) = client.searchRoms(term)
        override suspend fun delete(save: RemoteSaveFile) = client.deleteSave(save)
    }

    @Test
    fun `two devices share progress through RomM`() = runBlocking {
        assumeTrue(url.isNotBlank() && token.isNotBlank())
        val stamp = System.currentTimeMillis().toString()
        val clock = TestClock(System.currentTimeMillis())
        val server = server(client())

        val a = FakeSaveStore(clock)
        val aRecords = mutableMapOf<String, SaveSyncRecord>()
        a.put(SaveKind.SAVE, "mGBA/$save", "badge 1 $stamp")
        a.put(SaveKind.STATE, state, "world 2 $stamp")
        val engineA = SaveSyncEngine(server, a)
        val (up, leftovers) = engineA.sync(aRecords)
        assertEquals(up.toString(), 0, up.failed + up.notMatched)
        // An earlier run left its own copies on the server: this device's win.
        leftovers.forEach { engineA.resolve(it, keepDevice = true, records = aRecords) }
        // Times RomM answers an upload with must match the ones it lists afterwards.
        val again = engineA.sync(aRecords).first
        assertEquals(again.toString(), 0, again.uploaded + again.downloaded + again.conflicts)

        val b = FakeSaveStore(clock)
        b.folders[SaveKind.SAVE] = mutableSetOf("mGBA")
        val bRecords = mutableMapOf<String, SaveSyncRecord>()
        val engineB = SaveSyncEngine(server, b)
        val down = engineB.sync(bRecords).first
        assertEquals(down.toString(), 0, down.failed)
        assertEquals("badge 1 $stamp", b.text(SaveKind.SAVE, "mGBA/$save"))
        assertEquals("world 2 $stamp", b.text(SaveKind.STATE, state))

        Thread.sleep(1_100)   // RomM keeps times to the second
        b.put(SaveKind.SAVE, "mGBA/$save", "badge 2 $stamp")
        assertEquals(1, engineB.sync(bRecords).first.uploaded)
        val back = engineA.sync(aRecords).first
        assertEquals(back.toString(), 1, back.downloaded)
        assertEquals("badge 2 $stamp", a.text(SaveKind.SAVE, "mGBA/$save"))

        Thread.sleep(1_100)
        a.put(SaveKind.SAVE, "mGBA/$save", "a 3 $stamp")
        b.put(SaveKind.SAVE, "mGBA/$save", "b 3 $stamp")
        engineB.sync(bRecords)
        val (clash, conflicts) = engineA.sync(aRecords)
        assertEquals(clash.toString(), 1, clash.conflicts)
        engineA.resolve(conflicts.single(), keepDevice = true, records = aRecords)
        Thread.sleep(1_100)
        assertEquals(1, engineB.sync(bRecords).first.downloaded)
        assertEquals("a 3 $stamp", b.text(SaveKind.SAVE, "mGBA/$save"))
    }
}
