package com.tufein.valkyr.util

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DuckStation memory cards and PPSSPP save folders going through the ordinary save sync. */
class MemcardSavedataSyncTest {
    private val duck = "DuckStation (standalone)"
    private val ppsspp = "PPSSPP (standalone)"
    private val roms = listOf(
        SaveSyncPlanner.RomCandidate(1, "Crash Bandicoot (USA).chd", "psx", "psx"),
        SaveSyncPlanner.RomCandidate(2, "Metal Gear Solid (USA) [SLUS-00594].chd", "psx", "psx"),
        SaveSyncPlanner.RomCandidate(3, "Grand Theft Auto - Liberty City Stories (USA) (ULUS-10041).iso", "psp", "psp"),
        SaveSyncPlanner.RomCandidate(4, "Ape Escape (USA).chd", "psx", "psx")
    )
    private val clock = TestClock()
    private val server = FakeSaveServer(clock, roms)

    /** A handheld with a DuckStation memcards folder and a PPSSPP SAVEDATA folder. */
    private inner class Device(val sharedRom: Int? = null) : SaveStore {
        val files = mutableMapOf<String, Pair<ByteArray, Long>>()
        val backups = mutableListOf<String>()
        val records = mutableMapOf<String, SaveSyncRecord>()
        val engine = SaveSyncEngine(server, this, clock = { clock.now }, syncDeletions = { true })
        fun put(path: String, content: String) { files[path] = content.toByteArray() to clock.tick() }
        fun text(path: String) = files[path]?.first?.toString(Charsets.UTF_8)
        fun sync() = runBlocking { engine.sync(records) }

        private fun local(path: String): LocalSaveFile? {
            val (bytes, modified) = files[path] ?: return null
            val pack = path.startsWith("$ppsspp/")
            val name = path.substringAfterLast('/')
            if (!pack && EmulatorSaveLayouts.isSharedCard(name) && sharedRom == null) return null
            return LocalSaveFile(
                SaveKind.SAVE, path, bytes.size.toLong(), modified, if (pack) setOf("psp") else setOf("psx"),
                if (pack) SaveFolderLayout.SAVEDATA else SaveFolderLayout.MEMCARDS,
                if (!pack && EmulatorSaveLayouts.isSharedCard(name)) sharedRom else null
            )
        }
        override suspend fun list() = SaveStore.Listing(
            files.keys.mapNotNull(::local), mapOf(SaveKind.SAVE to setOf(duck, ppsspp)),
            noRootFolder = setOf(SaveKind.SAVE), holdSharedCards = if (sharedRom == null) setOf(duck) else emptySet()
        )
        override suspend fun read(file: LocalSaveFile) = files[file.path]?.first ?: throw IOException("gone")
        override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile {
            if (EmulatorSaveLayouts.isSharedCard(path.substringAfterLast('/')) && sharedRom == null) throw IOException("shared cards are not synced here")
            files[path] = bytes to clock.tick()
            return requireNotNull(local(path))
        }
        override suspend fun backup(file: LocalSaveFile) { backups += file.path }
        override suspend fun delete(file: LocalSaveFile) { throw IOException("never deleted") }
    }

    @Test fun perGameCardsAndSaveFoldersTravelBetweenHandhelds() {
        val a = Device()
        val b = Device()
        a.put("$duck/Crash Bandicoot_1.mcd", "crash card")
        a.put("$duck/SLUS-00594_1.mcd", "mgs card")
        a.put("$ppsspp/ULUS10041DATA00.savedata.zip", "gta pack")
        val (up, _) = a.sync()
        assertEquals(3, up.uploaded)
        assertEquals(setOf(1, 2, 3), server.stored.map { it.romId }.toSet())
        assertTrue(server.stored.all { it.emulator == duck || it.emulator == ppsspp })

        val (down, _) = b.sync()
        assertEquals(3, down.downloaded)
        assertEquals("crash card", b.text("$duck/Crash Bandicoot_1.mcd"))
        assertEquals("gta pack", b.text("$ppsspp/ULUS10041DATA00.savedata.zip"))
    }

    @Test fun savesNoGameFitsAreListedAndLeftAlone() {
        val a = Device()
        a.put("$ppsspp/UCUS98711DATA.savedata.zip", "unknown game")
        a.put("$ppsspp/SYSTEMDATA.savedata.zip", "no id")
        val (result, _) = a.sync()
        assertEquals(0, result.uploaded)
        assertEquals(2, result.notMatched)
        assertEquals(setOf("$ppsspp/UCUS98711DATA.savedata.zip", "$ppsspp/SYSTEMDATA.savedata.zip"), result.unmatched.toSet())
        assertEquals("unknown game", a.text("$ppsspp/UCUS98711DATA.savedata.zip"))
    }

    @Test fun sharedCardsOnlyTravelWhereTheUserChoseAGame() {
        val opted = Device(sharedRom = 4)
        opted.put("$duck/shared_card_1.mcd", "many games")
        assertEquals(1, opted.sync().first.uploaded)
        assertEquals(4, server.stored.single().romId)

        // A handheld that did not opt in keeps its own shared card and never gets the server's.
        val other = Device()
        other.put("$duck/shared_card_1.mcd", "its own")
        val (result, conflicts) = other.sync()
        assertEquals(0, result.downloaded + result.uploaded)
        assertTrue(conflicts.isEmpty())
        assertEquals("its own", other.text("$duck/shared_card_1.mcd"))
        // Nor does it get one when it has none.
        val empty = Device()
        assertEquals(0, empty.sync().first.downloaded)
        assertNull(empty.text("$duck/shared_card_1.mcd"))
    }

    @Test fun aNewerSharedCardOnTheServerIsAConflictNotADownload() {
        val a = Device(sharedRom = 4)
        val b = Device(sharedRom = 4)
        a.put("$duck/shared_card_1.mcd", "v1")
        a.sync()
        assertEquals(1, b.sync().first.downloaded)
        assertEquals("v1", b.text("$duck/shared_card_1.mcd"))
        a.put("$duck/shared_card_1.mcd", "v2")
        a.sync()
        // Only the server changed for B, but the card holds many games: B decides.
        val (result, conflicts) = b.sync()
        assertEquals(0, result.downloaded)
        assertEquals(1, conflicts.size)
        assertEquals("v1", b.text("$duck/shared_card_1.mcd"))
        runBlocking { b.engine.resolve(conflicts.single(), keepDevice = false, records = b.records) }
        assertEquals("v2", b.text("$duck/shared_card_1.mcd"))
        assertEquals(listOf("$duck/shared_card_1.mcd"), b.backups)
    }

    @Test fun aCardOrFolderGoneFromTheDeviceIsNeverDeletedOnTheServer() {
        val a = Device()
        a.put("$duck/Crash Bandicoot_1.mcd", "crash card")
        a.put("$ppsspp/ULUS10041DATA00.savedata.zip", "gta pack")
        a.sync()
        assertEquals(2, server.stored.size)
        a.files.clear()
        a.sync()
        assertEquals(2, server.stored.size)
    }

    @Test fun cardsAndFoldersAreNeverDeletedFromTheDeviceBySync() {
        val a = Device()
        a.put("$duck/Crash Bandicoot_1.mcd", "crash card")
        a.sync()
        server.remove(SaveKind.SAVE, "Crash Bandicoot_1.mcd")
        // Deletion sync is on, yet the card stays and goes up again.
        val (result, _) = a.sync()
        assertEquals(0, result.deletedOnDevice)
        assertEquals("crash card", a.text("$duck/Crash Bandicoot_1.mcd"))
        assertEquals(1, result.uploaded)
    }

    @Test fun plannerHoldsBackServerSharedCardsOfFoldersThatDidNotOptIn() {
        val remotes = listOf(
            RemoteSaveFile(SaveKind.SAVE, 1, 4, "shared_card_1.mcd", duck, "2026-10-01T10:00:00+00:00", 1, "/a"),
            RemoteSaveFile(SaveKind.SAVE, 2, 1, "Crash Bandicoot_1.mcd", duck, "2026-10-01T10:00:00+00:00", 1, "/b")
        )
        val actions = SaveSyncPlanner.plan(emptyList(), remotes, emptyList(), mapOf(SaveKind.SAVE to setOf(duck)), noRootFolder = setOf(SaveKind.SAVE), holdSharedCards = setOf(duck))
        assertEquals(listOf("$duck/Crash Bandicoot_1.mcd"), actions.map { (it as SaveSyncAction.Download).path })
    }

    @Test fun foldersRememberTheirLayoutAndSharedCardChoice() {
        val folder = EmulatorSaveFolder(duck, setOf("psx"), "content://tree/duck", SaveFolderLayout.MEMCARDS, 4, "Ape Escape")
        val psp = EmulatorSaveFolder(ppsspp, setOf("psp"), "content://tree/psp", SaveFolderLayout.SAVEDATA)
        assertEquals(listOf(folder, psp), EmulatorSaveFolders.fromJson(EmulatorSaveFolders.toJson(listOf(folder, psp))))
        // Folders written before 3.1 read as plain files; a layout from a newer version is skipped, not misread.
        assertEquals(SaveFolderLayout.FILES, EmulatorSaveFolders.fromJson("""[{"label":"DraStic","uri":"x"}]""").single().layout)
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.fromJson("""[{"label":"X","uri":"x","layout":"FUTURE"}]"""))
        // A shared-card game only means something for memory cards.
        assertNull(EmulatorSaveFolders.fromJson("""[{"label":"P","uri":"x","layout":"SAVEDATA","sharedCardsRomId":3}]""").single().sharedCardsRomId)
        // PS2 and GameCube card folders keep theirs.
        val ps2 = EmulatorSaveFolder("AetherSX2 - NetherSX2 (standalone)", setOf("ps2"), "content://tree/ps2", SaveFolderLayout.PS2_MEMCARDS, 9, "Ico")
        val gc = EmulatorSaveFolder("Dolphin (standalone)", setOf("gc", "ngc"), "content://tree/gc", SaveFolderLayout.GC_MEMCARDS)
        assertEquals(listOf(ps2, gc), EmulatorSaveFolders.fromJson(EmulatorSaveFolders.toJson(listOf(ps2, gc))))
    }

    @Test fun presetsTellWhichEmulatorAFolderIsFor() {
        val duckFolder = EmulatorSaveFolder("DuckStation (standalone) 2", setOf("psx", "ps"), "content://d", SaveFolderLayout.MEMCARDS)
        assertEquals("duckstation", EmulatorSaveFolders.presetOf(duckFolder)?.emulatorId)
        assertEquals(listOf(duckFolder), EmulatorSaveFolders.foldersFor("duckstation", "sony_playstation", listOf(duckFolder)))
        assertEquals(emptyList<EmulatorSaveFolder>(), EmulatorSaveFolders.foldersFor("duckstation", "nintendo_snes", listOf(duckFolder)))
        assertNull(EmulatorSaveFolders.presetOf(EmulatorSaveFolder("Something else", emptySet(), "x")))
        assertEquals(SaveFolderLayout.SAVEDATA, EmulatorSaveFolders.presets.single { it.emulatorId == "ppsspp" }.layout)
        assertTrue(EmulatorSaveFolders.platformFits("sony_playstation", setOf("psx")))
        assertTrue(EmulatorSaveFolders.platformFits("nintendo_ds", setOf("nds")))
        assertTrue(EmulatorSaveFolders.platformFits("psp", setOf("psp")))
        assertTrue(!EmulatorSaveFolders.platformFits("nintendo_ds", setOf("psx", "ps")))
    }

    @Test fun handoffTakesSaveFoldersButNeverSharedCards() {
        assertTrue(SaveHandoff.isInGameSave("ULUS10041DATA00.savedata.zip"))
        assertTrue(SaveHandoff.isInGameSave("Crash Bandicoot_1.mcd"))
        assertTrue(!SaveHandoff.isInGameSave("shared_card_1.mcd"))
        assertTrue(!SaveHandoff.isInGameSave("archive.zip"))
    }
}
