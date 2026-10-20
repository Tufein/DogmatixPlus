package com.cortinadev.dogmatix.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Dolphin's GCI folders and Wii saves as opt-in bundles: names, contents, safety and the sync's decisions. */
class DolphinSaveBundlesTest {
    private val dolphin = "Dolphin (standalone)"

    // ---- Names ---------------------------------------------------------------------------------

    @Test fun bundleNamesRoundTripAndNothingElseIsABundle() {
        DolphinSaveBundles.all(gci = true, wii = true, wiiChannels = true).forEach {
            assertEquals(it, DolphinSaveBundles.parse(it.fileName))
        }
        assertEquals("GCI-USA-A.gcifolder.zip", DolphinSaveBundles.gci("USA", 'A').fileName)
        assertEquals("GC/EUR/Card B", DolphinSaveBundles.gci("EUR", 'B').devicePath)
        assertEquals("Wii-00010000.wiisaves.zip", DolphinSaveBundles.wii(DolphinSaveBundles.WII_GAMES).fileName)
        assertEquals("Wii/title/00010004", DolphinSaveBundles.wii(DolphinSaveBundles.WII_DISC_CHANNELS).devicePath)
        assertEquals(DolphinSaveBundles.gci("JAP", 'B'), DolphinSaveBundles.parse("gci-jap-b.GCIFOLDER.zip"))
        listOf(
            "MemoryCardA.USA.raw", "ULUS10041DATA00.savedata.zip", "GCI-KOR-A.gcifolder.zip", "GCI-USA-C.gcifolder.zip",
            "Wii-00010001.wiisaves.zip", "Wii-00010002.wiisaves.zip", "01-GALE-Name.gci", "GCI-USA-A.zip"
        ).forEach { assertNull(it, DolphinSaveBundles.parse(it)) }
        assertEquals(6, DolphinSaveBundles.all(gci = true, wii = false, wiiChannels = true).size)
        assertEquals(1, DolphinSaveBundles.all(gci = false, wii = true, wiiChannels = false).size)
        assertEquals(0, DolphinSaveBundles.all(gci = false, wii = false, wiiChannels = true).size)
    }

    @Test fun bundlesHoldSeveralSavesAndAreNoSharedCards() {
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("$dolphin/GCI-USA-A.gcifolder.zip"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("$dolphin/Wii-00010000.wiisaves.zip"))
        // Not shared cards: the cards' own choice (sharedCardsRomId) never holds or releases them.
        assertFalse(EmulatorSaveLayouts.isSharedCard("GCI-USA-A.gcifolder.zip"))
        val local = LocalSaveFile(SaveKind.SAVE, "$dolphin/GCI-USA-A.gcifolder.zip", 10, 1, layout = SaveFolderLayout.GC_MEMCARDS, fixedRomId = 8)
        assertTrue(DolphinSaveBundles.isBundle(local))
        assertTrue(EmulatorSaveLayouts.isChosenGameOnly(local))
        assertFalse(DolphinSaveBundles.isBundle(local.copy(layout = SaveFolderLayout.FILES)))
        // Never handed over with one game.
        assertFalse(SaveHandoff.isInGameSave("GCI-USA-A.gcifolder.zip"))
    }

    @Test fun gciAndWiiNamesTellTheGame() {
        assertEquals(DolphinSaveBundles.GciName("01", "GALE", "SuperSmashBros0110290334"), DolphinSaveBundles.gciName("01-GALE-SuperSmashBros0110290334.gci"))
        assertNull(DolphinSaveBundles.gciName("MemoryCardA.USA.raw"))
        assertTrue(DolphinSaveBundles.isGciFile("8P-GZLE-gczelda.gci"))
        assertFalse(DolphinSaveBundles.isGciFile(".dogmatix-write-1.part"))
        assertFalse(DolphinSaveBundles.isGciFile("notes.txt"))
        assertEquals("RSBE", DolphinSaveBundles.wiiGameId("52534245"))
        assertNull(DolphinSaveBundles.wiiGameId("0000000g"))
        assertNull(DolphinSaveBundles.wiiGameId("00000001"))
        assertTrue(DolphinSaveBundles.isWiiTitle("534d4e45"))
        assertFalse(DolphinSaveBundles.isWiiTitle("data"))
        assertEquals("USA", DolphinSaveBundles.pick(listOf("EUR", "USA"), "USA"))
        assertEquals("usa", DolphinSaveBundles.pick(listOf("usa"), "USA"))
        assertNull(DolphinSaveBundles.pick(listOf("EUR"), "USA"))
    }

    // ---- What a bundle may hold ------------------------------------------------------------------

    @Test fun onlySavesOfTheBundlesOwnShapeAreAccepted() {
        val gci = DolphinSaveBundles.gci("USA", 'A')
        val wii = DolphinSaveBundles.wii(DolphinSaveBundles.WII_GAMES)
        DolphinSaveBundles.checkEntries(gci, listOf("01-GALE-SuperSmashBros.gci", "8P-GZLE-gczelda.gci"))
        DolphinSaveBundles.checkEntries(wii, listOf("52534245/data/RSBE.dat", "534d4e45/data/sub/folder/file.bin"))
        val refused = mapOf(
            gci to listOf("../01-GALE-x.gci", "/01-GALE-x.gci", "sub/01-GALE-x.gci", "01-GALE-x.raw", ".hidden.gci", "a\\b.gci", "C:x.gci", "x.gci "),
            wii to listOf(
                "52534245/content/title.tmd", "52534245/data/../../sys/x", "52534245/data", "52534245/x/data/y",
                "shared2/sys/SYSCONF", "sys/uid.sys", "ticket/00010000/x.tik", "5253424/data/x", "52534245/data/.x",
                "52534245\\data\\x", "/52534245/data/x", "52534245/data/a/b/c/d/e/f/g"
            )
        )
        refused.forEach { (bundle, paths) ->
            paths.forEach { path ->
                try { DolphinSaveBundles.checkEntries(bundle, listOf(path)); fail("accepted $path") } catch (_: IOException) { }
            }
        }
        // Two names a FAT card or a case-insensitive storage would merge.
        try { DolphinSaveBundles.checkEntries(gci, listOf("01-GALE-a.gci", "01-gale-A.gci")); fail("collision accepted") } catch (_: IOException) { }
        try { DolphinSaveBundles.checkEntries(gci, List(DolphinSaveBundles.MAX_ENTRIES + 1) { "01-GALE-$it.gci" }); fail("too many") } catch (_: IOException) { }
    }

    @Test fun bundlesPackLikeSaveFoldersAndTakeMoreFiles() {
        val files = List(600) { "52534245/data/f$it.bin" to "save $it".toByteArray() }
        val packed = SavePack.pack(files, DolphinSaveBundles.MAX_ENTRIES)
        assertArrayEquals(packed, SavePack.pack(files.reversed(), DolphinSaveBundles.MAX_ENTRIES))
        assertEquals(packed.size.toLong(), SavePack.packedSize(files.map { it.first to it.second.size.toLong() }))
        val back = SavePack.unpack(packed, DolphinSaveBundles.MAX_BYTES, DolphinSaveBundles.MAX_ENTRIES)
        assertEquals(600, back.size)
        DolphinSaveBundles.checkEntries(DolphinSaveBundles.wii(DolphinSaveBundles.WII_GAMES), back.map { it.first })
        // A PPSSPP save folder keeps its own, smaller limit.
        try { SavePack.unpack(packed, DolphinSaveBundles.MAX_BYTES); fail("entry cap ignored") } catch (_: IOException) { }
        try { SavePack.unpack(packed, 100, DolphinSaveBundles.MAX_ENTRIES); fail("size cap ignored") } catch (_: IOException) { }
    }

    @Test fun aServerZipWithATraversalIsRefusedBeforeAnythingIsWritten() {
        fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
        }.toByteArray()
        try { SavePack.unpack(zip("../01-GALE-x.gci"), DolphinSaveBundles.MAX_BYTES, DolphinSaveBundles.MAX_ENTRIES); fail("traversal unpacked") } catch (_: Exception) { }
        val wii = DolphinSaveBundles.wii(DolphinSaveBundles.WII_GAMES)
        val gci = DolphinSaveBundles.gci("USA", 'A')
        // Names SavePack alone would clean up quietly (a backslash, a colon) are refused for bundles.
        listOf("52534245\\data\\x", "52534245/data/a:b", "/52534245/data/x", "52534245/content/00000000.app", "52534245/data/../../../sys/x").forEach { name ->
            try { DolphinSaveBundles.unpack(wii, zip(name)); fail("accepted $name") } catch (_: Exception) { }
        }
        try { DolphinSaveBundles.unpack(gci, zip("../01-GALE-x.gci")); fail("traversal accepted") } catch (_: Exception) { }
        assertEquals(listOf("52534245/data/x"), DolphinSaveBundles.unpack(wii, zip("52534245/data/x")).map { it.first })
        assertEquals(listOf("01-GALE-x.gci"), DolphinSaveBundles.unpack(gci, zip("01-GALE-x.gci")).map { it.first })
    }

    // ---- Settings --------------------------------------------------------------------------------

    @Test fun dolphinFoldersRememberTheirBundleChoices() {
        val folder = EmulatorSaveFolder(
            dolphin, setOf("ngc"), "content://tree/dolphin", SaveFolderLayout.GC_MEMCARDS,
            sharedCardsRomId = 8, sharedCardsRomName = "Pikmin", gciRomId = 9, gciRomName = "F-Zero GX",
            wiiRomId = 10, wiiRomName = "Wii Sports", wiiChannels = true
        )
        val back = EmulatorSaveFolders.fromJson(EmulatorSaveFolders.toJson(listOf(folder))).single()
        assertEquals(folder, back)
        assertEquals(8, back.dolphinBundles.size)
        assertEquals(9, back.romIdOf(DolphinSaveBundles.gci("USA", 'A')))
        assertEquals(10, back.romIdOf(DolphinSaveBundles.wii(DolphinSaveBundles.WII_DISC_CHANNELS)))
        // Off: nothing of it is kept, and channels need Wii saves on.
        val off = EmulatorSaveFolders.fromJson(EmulatorSaveFolders.toJson(listOf(folder.copy(gciRomId = null, wiiRomId = null)))).single()
        assertNull(off.gciRomId); assertEquals("", off.gciRomName); assertFalse(off.wiiChannels)
        assertTrue(off.dolphinBundles.isEmpty())
        assertNull(off.romIdOf(DolphinSaveBundles.gci("USA", 'A')))
        // Another emulator's folder never carries Dolphin bundles, whatever its JSON says.
        val json = """[{"label":"PPSSPP (standalone)","platforms":["psp"],"uri":"content://x","layout":"SAVEDATA","gciRomId":3,"wiiRomId":4,"wiiChannels":true}]"""
        val other = EmulatorSaveFolders.fromJson(json).single()
        assertNull(other.gciRomId); assertNull(other.wiiRomId); assertTrue(other.dolphinBundles.isEmpty())
        // Malformed values are dropped, not fatal.
        val odd = """[{"label":"$dolphin","platforms":[],"uri":"content://x","layout":"GC_MEMCARDS","gciRomId":"x","wiiRomId":-1,"wiiRomName":5}]"""
        val parsed = EmulatorSaveFolders.fromJson(odd).single()
        assertNull(parsed.gciRomId); assertNull(parsed.wiiRomId)
    }

    // ---- Planner ---------------------------------------------------------------------------------

    private fun remote(id: Int, name: String, updated: String = "2026-10-01T10:00:00Z", emulator: String? = dolphin) =
        RemoteSaveFile(SaveKind.SAVE, id, 8, name, emulator, updated, 100, "/x/$id", "abc")

    private fun local(name: String, size: Long = 100, modified: Long = 1) =
        LocalSaveFile(SaveKind.SAVE, "$dolphin/$name", size, modified, setOf("ngc"), SaveFolderLayout.GC_MEMCARDS, 8)

    @Test fun serverBundlesTheDeviceDoesNotTakeStayOnTheServer() {
        val gci = "GCI-USA-A.gcifolder.zip"
        val wii = "Wii-00010000.wiisaves.zip"
        val top = mapOf(SaveKind.SAVE to setOf(dolphin))
        val none = SaveSyncPlanner.plan(emptyList(), listOf(remote(1, gci), remote(2, wii)), emptyList(), top, noRootFolder = setOf(SaveKind.SAVE))
        assertTrue(none.isEmpty())
        val taken = SaveSyncPlanner.plan(
            emptyList(), listOf(remote(1, gci), remote(2, wii), remote(3, gci, emulator = null)), emptyList(), top,
            noRootFolder = setOf(SaveKind.SAVE), syncedBundles = setOf("$dolphin/$gci".uppercase())
        )
        assertEquals(listOf("$dolphin/$gci"), taken.filterIsInstance<SaveSyncAction.Download>().map { it.path })
        assertEquals(1, taken.size)
    }

    @Test fun aNewerServerBundleIsAConflictAndABundleIsNeverDeleted() {
        val name = "GCI-USA-A.gcifolder.zip"
        val record = SaveSyncRecord(SaveKind.SAVE, "$dolphin/$name", 8, 1, "2026-10-01T10:00:00Z", 100, 1, 100, "abc")
        val top = mapOf(SaveKind.SAVE to setOf(dolphin))
        val synced = setOf("$dolphin/$name")
        val newer = SaveSyncPlanner.plan(listOf(local(name)), listOf(remote(1, name, "2026-10-02T10:00:00Z")), listOf(record), top, true, syncedBundles = synced)
        assertTrue(newer.single() is SaveSyncAction.Conflict)
        // Gone from the device: the server's bundle is not removed, it comes back down.
        val gone = SaveSyncPlanner.plan(emptyList(), listOf(remote(1, name)), listOf(record), top, true, syncedBundles = synced)
        assertTrue(gone.none { it is SaveSyncAction.DeleteRemote })
        assertTrue(gone.single() is SaveSyncAction.Download)
        // Gone from the server: the device's bundle is not removed, it goes back up.
        val serverGone = SaveSyncPlanner.plan(listOf(local(name)), emptyList(), listOf(record), top, true, syncedBundles = synced)
        assertTrue(serverGone.single() is SaveSyncAction.Upload)
    }

    // ---- Through the engine ----------------------------------------------------------------------

    private val clock = TestClock()
    private val server = FakeSaveServer(clock, listOf(SaveSyncPlanner.RomCandidate(8, "Pikmin (USA).rvz", "ngc", "gc")))

    /** A handheld whose Dolphin folder takes the bundles of [taken] (by file name), kept with game 8. */
    private inner class Device(val taken: Set<String>) : SaveStore {
        val files = mutableMapOf<String, Pair<ByteArray, Long>>()
        val backups = mutableListOf<String>()
        val records = mutableMapOf<String, SaveSyncRecord>()
        val engine = SaveSyncEngine(server, this, clock = { clock.now }, syncDeletions = { true })
        fun put(name: String, content: String) { files["$dolphin/$name"] = content.toByteArray() to clock.tick() }
        fun text(name: String) = files["$dolphin/$name"]?.first?.toString(Charsets.UTF_8)
        fun sync() = runBlocking { engine.sync(records) }
        private fun local(path: String): LocalSaveFile? {
            val (bytes, modified) = files[path] ?: return null
            if (path.substringAfterLast('/') !in taken) return null
            return LocalSaveFile(SaveKind.SAVE, path, bytes.size.toLong(), modified, setOf("ngc"), SaveFolderLayout.GC_MEMCARDS, 8)
        }
        override suspend fun list() = SaveStore.Listing(
            files.keys.mapNotNull(::local), mapOf(SaveKind.SAVE to setOf(dolphin)), noRootFolder = setOf(SaveKind.SAVE),
            holdSharedCards = setOf(dolphin), syncedBundles = taken.map { "$dolphin/$it" }.toSet(), problems = listOf("GC/EUR/Card A was not synced: too large")
        )
        override suspend fun read(file: LocalSaveFile) = files[file.path]?.first ?: throw IOException("gone")
        override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile {
            if (path.substringAfterLast('/') !in taken) throw IOException("not taken here")
            files[path] = bytes to clock.tick()
            return requireNotNull(local(path))
        }
        override suspend fun backup(file: LocalSaveFile) { backups += file.path }
        override suspend fun delete(file: LocalSaveFile) { throw IOException("never deleted") }
    }

    @Test fun bundlesGoWithTheChosenGameAndReachAnotherHandheldThatTakesThem() {
        val gci = "GCI-USA-A.gcifolder.zip"
        val wii = "Wii-00010000.wiisaves.zip"
        val a = Device(setOf(gci, wii))
        a.put(gci, "gci bundle")
        a.put(wii, "wii bundle")
        val (up, _) = a.sync()
        assertEquals(2, up.uploaded)
        assertEquals(0, server.searches)
        assertTrue(server.stored.all { it.romId == 8 && it.emulator == dolphin })
        // The listing's problems are shown with the result.
        assertEquals("GC/EUR/Card A was not synced: too large", up.errors.first())

        // Takes only the GCI folders: the Wii bundle stays on the server.
        val b = Device(setOf(gci))
        assertEquals(1, b.sync().first.downloaded)
        assertEquals("gci bundle", b.text(gci))
        assertNull(b.text(wii))

        // A newer bundle never replaces B's folder without asking; keeping the server's backs B's up first.
        a.put(gci, "gci bundle v2")
        a.sync()
        val (result, conflicts) = b.sync()
        assertEquals(0, result.downloaded)
        assertEquals("gci bundle", b.text(gci))
        runBlocking { b.engine.resolve(conflicts.single(), keepDevice = false, records = b.records) }
        assertEquals("gci bundle v2", b.text(gci))
        assertEquals(listOf("$dolphin/$gci"), b.backups)

        // Gone from a handheld: never deleted on the server.
        a.files.clear()
        a.sync()
        assertEquals(2, server.stored.size)
    }
}
