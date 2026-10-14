package com.cortinadev.dogmatix.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** AetherSX2 / NetherSX2 and Dolphin memory cards (all shared) going through the ordinary save sync. */
class SharedCardEmulatorsSyncTest {
    private val ps2 = "AetherSX2 - NetherSX2 (standalone)"
    private val dolphin = "Dolphin (standalone)"
    private val roms = listOf(
        SaveSyncPlanner.RomCandidate(7, "Ico (USA).iso", "ps2", "ps2"),
        SaveSyncPlanner.RomCandidate(8, "Pikmin (USA).rvz", "ngc", "gc")
    )
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)

    /** A handheld with a NetherSX2 memcards folder and Dolphin's GC folder; [sharedRom] = the game chosen for their cards. */
    private inner class Device(val sharedRom: Int? = null) : SaveStore {
        val files = mutableMapOf<String, Pair<ByteArray, Long>>()
        val backups = mutableListOf<String>()
        val records = mutableMapOf<String, SaveSyncRecord>()
        val engine = SaveSyncEngine(server, this, clock = { clock.now }, syncDeletions = { true })
        fun put(path: String, content: String) { files[path] = content.toByteArray() to clock.tick() }
        fun text(path: String) = files[path]?.first?.toString(Charsets.UTF_8)
        fun sync() = runBlocking { engine.sync(records) }

        private fun layoutOf(path: String) = if (path.startsWith("$ps2/")) SaveFolderLayout.PS2_MEMCARDS else SaveFolderLayout.GC_MEMCARDS

        /** What the real scan lists: only the folder's own cards, and those only once a game was chosen. */
        private fun local(path: String): LocalSaveFile? {
            val (bytes, modified) = files[path] ?: return null
            val layout = layoutOf(path)
            if (EmulatorSaveLayouts.memoryCard(layout, path.substringAfterLast('/')) == null || sharedRom == null) return null
            return LocalSaveFile(
                SaveKind.SAVE, path, bytes.size.toLong(), modified,
                if (layout == SaveFolderLayout.PS2_MEMCARDS) setOf("ps2") else setOf("ngc", "gc"), layout, sharedRom
            )
        }
        override suspend fun list() = SaveStore.Listing(
            files.keys.mapNotNull(::local), mapOf(SaveKind.SAVE to setOf(ps2, dolphin)),
            noRootFolder = setOf(SaveKind.SAVE), holdSharedCards = if (sharedRom == null) setOf(ps2, dolphin) else emptySet()
        )
        override suspend fun read(file: LocalSaveFile) = files[file.path]?.first ?: throw IOException("gone")
        override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile {
            if (EmulatorSaveLayouts.memoryCard(layoutOf(path), path.substringAfterLast('/')) == null) throw IOException("not a card")
            if (sharedRom == null) throw IOException("shared cards are not synced here")
            files[path] = bytes to clock.tick()
            return requireNotNull(local(path))
        }
        override suspend fun backup(file: LocalSaveFile) { backups += file.path }
        override suspend fun delete(file: LocalSaveFile) { throw IOException("never deleted") }
    }

    @Test fun cardsGoWithTheChosenGameAndReachAnotherHandheld() {
        val a = Device(sharedRom = 7)
        a.put("$ps2/Mcd001.ps2", "ps2 card")
        a.put("$dolphin/MemoryCardA.USA.raw", "gc card")
        // Not cards: Dolphin's console settings and a save of its GCI folder never travel.
        a.put("$dolphin/SRAM.raw", "settings")
        a.put("$dolphin/01-GALE-SuperSmashBros.gci", "one save")
        val (up, _) = a.sync()
        assertEquals(2, up.uploaded)
        assertEquals(0, server.searches)
        assertEquals(setOf("Mcd001.ps2", "MemoryCardA.USA.raw"), server.stored.map { it.name }.toSet())
        assertTrue(server.stored.all { it.romId == 7 })
        assertEquals(setOf(ps2, dolphin), server.stored.map { it.emulator }.toSet())

        // A handheld that opted in with the same game and has no card yet gets them.
        val b = Device(sharedRom = 7)
        assertEquals(2, b.sync().first.downloaded)
        assertEquals("ps2 card", b.text("$ps2/Mcd001.ps2"))
        assertEquals("gc card", b.text("$dolphin/MemoryCardA.USA.raw"))
    }

    @Test fun aHandheldThatDidNotOptInNeitherSendsNorGetsCards() {
        val a = Device(sharedRom = 7)
        a.put("$ps2/Mcd001.ps2", "server copy")
        a.sync()
        val other = Device()
        other.put("$ps2/Mcd001.ps2", "its own")
        val (result, conflicts) = other.sync()
        assertEquals(0, result.uploaded + result.downloaded)
        assertTrue(conflicts.isEmpty())
        assertEquals("its own", other.text("$ps2/Mcd001.ps2"))
        assertEquals("server copy", server.text(SaveKind.SAVE, "Mcd001.ps2"))
        val empty = Device()
        assertEquals(0, empty.sync().first.downloaded)
        assertNull(empty.text("$ps2/Mcd001.ps2"))
    }

    @Test fun aNewerCardOnTheServerNeverReplacesTheHandheldsCardWithoutAsking() {
        val a = Device(sharedRom = 7)
        val b = Device(sharedRom = 7)
        a.put("$dolphin/MemoryCardA.USA.raw", "v1")
        a.sync()
        b.sync()
        a.put("$dolphin/MemoryCardA.USA.raw", "v2")
        a.sync()
        val (result, conflicts) = b.sync()
        assertEquals(0, result.downloaded)
        assertEquals("$dolphin/MemoryCardA.USA.raw", conflicts.single().local.path)
        assertEquals("v1", b.text("$dolphin/MemoryCardA.USA.raw"))
        runBlocking { b.engine.resolve(conflicts.single(), keepDevice = false, records = b.records) }
        assertEquals("v2", b.text("$dolphin/MemoryCardA.USA.raw"))
        assertEquals(listOf("$dolphin/MemoryCardA.USA.raw"), b.backups)
    }

    @Test fun aCardOnBothSidesThatNeverSyncedIsCompared() {
        val a = Device(sharedRom = 7)
        a.put("$ps2/Mcd001.ps2", "played on A")
        a.sync()
        // The emulator made a fresh card on B before its first sync: B chooses, nothing is overwritten.
        val b = Device(sharedRom = 7)
        b.put("$ps2/Mcd001.ps2", "fresh card")
        val (_, conflicts) = b.sync()
        assertEquals(1, conflicts.size)
        assertEquals("fresh card", b.text("$ps2/Mcd001.ps2"))
        // The same bytes on both sides are simply recorded.
        val c = Device(sharedRom = 7)
        c.put("$ps2/Mcd001.ps2", "played on A")
        val (same, none) = c.sync()
        assertTrue(none.isEmpty())
        assertEquals(1, same.unchanged)
    }

    @Test fun cardsAreNeverDeletedOnEitherSide() {
        val a = Device(sharedRom = 7)
        a.put("$ps2/Mcd001.ps2", "card")
        a.sync()
        a.files.clear()
        a.sync()
        assertEquals(1, server.stored.size)
        val b = Device(sharedRom = 7)
        b.put("$dolphin/MemoryCardB.EUR.raw", "card")
        b.sync()
        server.remove(SaveKind.SAVE, "MemoryCardB.EUR.raw")
        val (result, _) = b.sync()
        assertEquals(0, result.deletedOnDevice)
        assertEquals("card", b.text("$dolphin/MemoryCardB.EUR.raw"))
    }

    /** The planner on sizes alone: a 64 MB PS2 card (69,206,016 bytes) is treated like any shared card. */
    @Test fun plannerTreatsLargeCardsLikeAnySharedCard() {
        val big = 69_206_016L
        val card = LocalSaveFile(SaveKind.SAVE, "$ps2/Mcd001.ps2", big, 2_000, setOf("ps2"), SaveFolderLayout.PS2_MEMCARDS, 7)
        val remote = RemoteSaveFile(SaveKind.SAVE, 5, 7, "Mcd001.ps2", ps2, "2026-10-02T10:00:00+00:00", big, "/x", "aa")
        val record = SaveSyncRecord(SaveKind.SAVE, card.path, 7, 5, "2026-10-01T10:00:00+00:00", big, 2_000, big, "bb")
        val folders = mapOf(SaveKind.SAVE to setOf(ps2))
        fun plan(local: LocalSaveFile? = card, remoteFile: RemoteSaveFile? = remote, records: List<SaveSyncRecord> = listOf(record), hold: Set<String> = emptySet()) =
            SaveSyncPlanner.plan(listOfNotNull(local), listOfNotNull(remoteFile), records, folders, syncDeletions = true, noRootFolder = setOf(SaveKind.SAVE), holdSharedCards = hold)

        // Only the server changed: the user decides, it is never downloaded over the card.
        assertEquals(listOf(SaveSyncAction.Conflict(card, remote)), plan())
        // Only the device changed: it goes up to the chosen game.
        val changed = card.copy(modified = 3_000)
        val unchangedRemote = remote.copy(updatedAt = record.remoteUpdatedAt, contentHash = record.remoteHash)
        assertEquals(listOf(SaveSyncAction.Upload(changed, 7, unchangedRemote)), plan(changed, unchangedRemote))
        // Both exist and were never synced: compared by bytes.
        assertEquals(listOf(SaveSyncAction.Compare(card, remote)), plan(records = emptyList()))
        // Gone from the device: never deleted on the server (a card holds many games' saves); it comes back into the empty place.
        assertEquals(listOf(SaveSyncAction.Download(unchangedRemote, card.path, null)), plan(local = null, remoteFile = unchangedRemote))
        // Gone from the server: never deleted on the device, it goes up again.
        assertEquals(listOf(SaveSyncAction.Upload(card, 7, null)), plan(remoteFile = null))
        // A folder whose cards are not synced never gets the server's.
        assertEquals(emptyList<SaveSyncAction>(), plan(local = null, records = emptyList(), hold = setOf(ps2)))
    }

    @Test fun presetsForTheNewEmulators() {
        val nether = EmulatorSaveFolders.presets.single { it.emulatorId == "nethersx2" }
        assertEquals(SaveFolderLayout.PS2_MEMCARDS, nether.layout)
        assertEquals(ps2, nether.label)
        assertTrue(nether.platforms.contains("ps2"))
        assertTrue(nether.folderHint.endsWith("memcards"))
        val dol = EmulatorSaveFolders.presets.single { it.emulatorId == "dolphin" }
        assertEquals(SaveFolderLayout.GC_MEMCARDS, dol.layout)
        assertEquals(dolphin, dol.label)
        assertTrue(dol.platforms.containsAll(setOf("ngc", "gc", "gamecube")))
        assertTrue(dol.folderHint.endsWith("GC"))
        assertTrue(dol.isFor("dolphin_mmjr"))
        // Each emulator has one preset, and every label is one plain folder name RomM takes as emulator.
        val ids = EmulatorSaveFolders.presets.mapNotNull { it.emulatorId }
        assertEquals(ids.distinct(), ids)
        EmulatorSaveFolders.presets.forEach { assertEquals(it.label, EmulatorSaveFolders.cleanLabel(it.label)) }
        // Their folders are found for their consoles only: GameCube, not Wii.
        val gcFolder = EmulatorSaveFolder(dolphin, dol.platforms, "content://gc", SaveFolderLayout.GC_MEMCARDS)
        assertEquals(listOf(gcFolder), EmulatorSaveFolders.foldersFor("dolphin", "nintendo_gamecube", listOf(gcFolder)))
        assertEquals(listOf(gcFolder), EmulatorSaveFolders.foldersFor("dolphin_mmjr", "nintendo_gamecube", listOf(gcFolder)))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.foldersFor("dolphin", "nintendo_wii", listOf(gcFolder)))
        val ps2Folder = EmulatorSaveFolder("$ps2 2", nether.platforms, "content://ps2", SaveFolderLayout.PS2_MEMCARDS)
        assertEquals("nethersx2", EmulatorSaveFolders.presetOf(ps2Folder)?.emulatorId)
        assertEquals(listOf(ps2Folder), EmulatorSaveFolders.foldersFor("nethersx2", "sony_playstation_2", listOf(ps2Folder)))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.foldersFor("nethersx2", "sony_playstation", listOf(ps2Folder)))
    }

    @Test fun wholeFilesAreReadIntoOneArrayOfTheirSize() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        fun read(input: InputStream, expected: Long, max: Long = 1_000_000) =
            BoundedStreams.readAll(input, expected, max, tooLarge = { IOException("too large") }, changed = { IOException("changed") })
        assertArrayEquals(data, read(ByteArrayInputStream(data), data.size.toLong()))
        // Unknown size (a provider that reports 0): read to the end, still bounded.
        assertArrayEquals(data, read(ByteArrayInputStream(data), 0))
        assertArrayEquals(ByteArray(0), read(ByteArrayInputStream(ByteArray(0)), 0))
        fun fails(message: String, block: () -> Unit) {
            try { block(); fail("no failure") } catch (e: IOException) { assertEquals(message, e.message) }
        }
        fails("changed") { read(ByteArrayInputStream(data), data.size + 1L) }
        fails("changed") { read(ByteArrayInputStream(data), data.size - 1L) }
        fails("too large") { read(ByteArrayInputStream(data), data.size.toLong(), max = 1000) }
        fails("too large") { read(ByteArrayInputStream(data), 0, max = 1000) }
        // A stream that hands out a few bytes at a time is read whole.
        val trickle = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= data.size) return -1
                val n = minOf(len, 7, data.size - at)
                System.arraycopy(data, at, b, off, n); at += n
                return n
            }
        }
        assertArrayEquals(data, read(trickle, data.size.toLong()))
    }
}
