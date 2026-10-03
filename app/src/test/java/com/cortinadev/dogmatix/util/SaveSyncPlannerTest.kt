package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomCandidate
import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncPlannerTest {

    private fun local(path: String, size: Long = 100, modified: Long = 1_000, kind: SaveKind = SaveKind.SAVE) =
        LocalSaveFile(kind, path, size, modified)

    private fun remote(id: Int, name: String, romId: Int = 10, updatedAt: String = "2026-10-01T10:00:00+00:00", emulator: String? = null, kind: SaveKind = SaveKind.SAVE) =
        RemoteSaveFile(kind, id, romId, name, emulator, updatedAt, 100, "/api/saves/$id/content")

    private fun record(path: String, remoteId: Int, romId: Int = 10, updatedAt: String = "2026-10-01T10:00:00+00:00", size: Long = 100, modified: Long = 1_000) =
        SaveSyncRecord(SaveKind.SAVE, path, romId, remoteId, updatedAt, size, modified)

    @Test
    fun `new device file is uploaded, new server file is downloaded`() {
        val actions = SaveSyncPlanner.plan(
            locals = listOf(local("Zelda (USA).srm")),
            remotes = listOf(remote(1, "Metroid (USA).srm")),
            records = emptyList()
        )
        assertEquals(2, actions.size)
        val upload = actions.filterIsInstance<SaveSyncAction.Upload>().single()
        assertEquals("Zelda (USA).srm", upload.local.path)
        assertNull(upload.romId)
        val download = actions.filterIsInstance<SaveSyncAction.Download>().single()
        assertEquals("Metroid (USA).srm", download.path)
        assertNull(download.replacing)
    }

    @Test
    fun `first meeting of both sides compares contents`() {
        val actions = SaveSyncPlanner.plan(listOf(local("Zelda.srm")), listOf(remote(1, "zelda.SRM")), emptyList())
        assertTrue(actions.single() is SaveSyncAction.Compare)
    }

    @Test
    fun `three-way decisions against the last sync`() {
        val r = remote(1, "Zelda.srm")
        fun decide(local: LocalSaveFile, remote: RemoteSaveFile) =
            SaveSyncPlanner.plan(listOf(local), listOf(remote), listOf(record("Zelda.srm", 1))).single()

        assertTrue(decide(local("Zelda.srm"), r) is SaveSyncAction.InSync)
        val up = decide(local("Zelda.srm", modified = 2_000), r)
        assertTrue(up is SaveSyncAction.Upload)
        assertEquals(10, (up as SaveSyncAction.Upload).romId)
        val down = decide(local("Zelda.srm"), r.copy(updatedAt = "2026-10-02T10:00:00+00:00"))
        assertTrue(down is SaveSyncAction.Download)
        assertEquals("Zelda.srm", (down as SaveSyncAction.Download).path)
        assertTrue(decide(local("Zelda.srm", size = 50), r.copy(updatedAt = "2026-10-02T10:00:00+00:00")) is SaveSyncAction.Conflict)
    }

    @Test
    fun `record keeps the pair when RomM renamed the upload`() {
        val actions = SaveSyncPlanner.plan(
            listOf(local("Who's: There.srm")),
            listOf(remote(7, "Who's- There.srm")),
            listOf(record("Who's: There.srm", 7))
        )
        assertTrue(actions.single() is SaveSyncAction.InSync)
    }

    @Test
    fun `server copy deleted - the device file goes up again for the same game`() {
        val action = SaveSyncPlanner.plan(listOf(local("Zelda.srm")), emptyList(), listOf(record("Zelda.srm", 1, romId = 42))).single()
        assertEquals(42, (action as SaveSyncAction.Upload).romId)
    }

    @Test
    fun `same name for two games - the emulator folder decides`() {
        val gb = remote(1, "Tetris.srm", romId = 1, emulator = "gambatte")
        val nes = remote(2, "Tetris.srm", romId = 2, emulator = "nestopia")
        val actions = SaveSyncPlanner.plan(listOf(local("nestopia/Tetris.srm")), listOf(gb, nes), emptyList(), mapOf(SaveKind.SAVE to setOf("nestopia")))
        val compare = actions.filterIsInstance<SaveSyncAction.Compare>().single()
        assertEquals(2, compare.remote.id)
        // The Game Boy one has no gambatte folder here, so it lands in the root.
        assertEquals("Tetris.srm", actions.filterIsInstance<SaveSyncAction.Download>().single().path)
    }

    @Test
    fun `same name for two games without a deciding folder is left alone`() {
        val actions = SaveSyncPlanner.plan(
            listOf(local("Tetris.srm")),
            listOf(remote(1, "Tetris.srm", romId = 1, emulator = "a"), remote(2, "Tetris.srm", romId = 2, emulator = "b")),
            emptyList()
        )
        assertTrue(actions.any { it is SaveSyncAction.Ambiguous })
        // Neither server file may be written over the device file.
        assertEquals(2, actions.count { it is SaveSyncAction.Blocked })
        assertFalse(actions.any { it is SaveSyncAction.Download })
    }

    @Test
    fun `downloads go into an existing emulator folder`() {
        val action = SaveSyncPlanner.plan(emptyList(), listOf(remote(1, "Zelda.srm", emulator = "MGBA")), emptyList(), mapOf(SaveKind.SAVE to setOf("mGBA"))).single()
        assertEquals("mGBA/Zelda.srm", (action as SaveSyncAction.Download).path)
    }

    @Test
    fun `saves and states never pair with each other`() {
        val actions = SaveSyncPlanner.plan(
            listOf(local("Zelda.state", kind = SaveKind.STATE)),
            listOf(remote(1, "Zelda.state", kind = SaveKind.SAVE)),
            emptyList()
        )
        assertEquals(1, actions.count { it is SaveSyncAction.Upload })
        assertEquals(1, actions.count { it is SaveSyncAction.Download })
    }

    @Test
    fun `file names that are not progress are ignored`() {
        assertTrue(SaveSyncPlanner.isSyncable("Zelda.srm"))
        assertTrue(SaveSyncPlanner.isSyncable("Zelda.state.auto"))
        assertFalse(SaveSyncPlanner.isSyncable("Zelda.state.png"))
        assertFalse(SaveSyncPlanner.isSyncable(".nomedia"))
        assertFalse(SaveSyncPlanner.isSyncable("Zelda.srm.tmp"))
    }

    @Test
    fun `kind of a file in a shared folder`() {
        assertEquals(SaveKind.STATE, SaveSyncPlanner.kindOf("Zelda.state"))
        assertEquals(SaveKind.STATE, SaveSyncPlanner.kindOf("Zelda.state12"))
        assertEquals(SaveKind.STATE, SaveSyncPlanner.kindOf("Zelda.STATE.auto"))
        assertEquals(SaveKind.SAVE, SaveSyncPlanner.kindOf("Zelda.srm"))
        assertEquals(SaveKind.SAVE, SaveSyncPlanner.kindOf("Statesman.sav"))
    }

    @Test
    fun `stems and rom stems`() {
        assertEquals(listOf("Zelda (USA).state", "Zelda (USA)"), SaveSyncPlanner.stems("Zelda (USA).state.auto"))
        assertEquals("Dr. Mario (USA)", SaveSyncPlanner.romStem("Dr. Mario (USA).nes"))
        assertEquals("Dr. Mario", SaveSyncPlanner.romStem("Dr. Mario"))
        assertEquals("Final Fantasy VII", SaveSyncPlanner.romStem("Final Fantasy VII"))
    }

    @Test
    fun `rom matching by name, with the platform folder breaking ties`() {
        val roms = listOf(
            RomCandidate(1, "Dr. Mario (USA).nes", "nes", "nes"),
            RomCandidate(2, "Dr. Mario (USA).zip", "gb", "gb"),
            RomCandidate(3, "Dr. Mario 64 (USA).z64", "n64", "n64")
        )
        assertEquals(RomMatch.Ambiguous, SaveSyncPlanner.matchRom(local("Dr. Mario (USA).srm"), roms))
        assertEquals(RomMatch.Found(2), SaveSyncPlanner.matchRom(local("gb/Dr. Mario (USA).srm"), roms))
        assertEquals(RomMatch.Found(3), SaveSyncPlanner.matchRom(local("Dr. Mario 64 (USA).state.auto", kind = SaveKind.STATE), roms))
        assertEquals(RomMatch.NotFound, SaveSyncPlanner.matchRom(local("Dr. Mario (Europe).srm"), roms))
    }

    @Test
    fun `search term keeps only title words`() {
        assertEquals("Dr Mario", SaveSyncPlanner.searchTerm("Dr. Mario (USA) [!].srm"))
        assertEquals("Pokemon Emerald", SaveSyncPlanner.searchTerm("Pokemon Emerald (USA).state.auto"))
        assertEquals("Link s Awakening DX v1", SaveSyncPlanner.searchTerm("Link's Awakening DX|v1.srm"))
    }

    @Test
    fun `search terms for old and new RomM search`() {
        assertEquals(listOf("Dr Mario", "Dr. Mario", "Mario"), SaveSyncPlanner.searchTerms("Dr. Mario (World).state.auto"))
        assertEquals(listOf("Tetris"), SaveSyncPlanner.searchTerms("Tetris (USA).srm"))
    }

    @Test
    fun `emulator folder name is only sent when RomM accepts it`() {
        assertEquals("mGBA", SaveSyncPlanner.emulatorFor(local("mGBA/Zelda.srm")))
        assertNull(SaveSyncPlanner.emulatorFor(local("Zelda.srm")))
        assertNull(SaveSyncPlanner.emulatorFor(local("a:b/Zelda.srm")))
    }

    @Test
    fun `only the newest server version per name`() {
        val old = remote(1, "Zelda.srm", updatedAt = "2026-10-01T10:00:00+00:00")
        val new = remote(2, "zelda.srm", updatedAt = "2026-10-02T09:00:00.123456+02:00")
        val other = remote(3, "Zelda.srm", romId = 11)
        assertEquals(setOf(2, 3), SaveSyncPlanner.latestPerName(listOf(old, new, other)).map { it.id }.toSet())
    }

    @Test
    fun `upload answer and listing of one version are the same time`() {
        assertTrue(SaveSyncPlanner.sameTime("2026-10-03T08:11:34.419482+00:00", "2026-10-03T08:11:34+00:00"))
        assertFalse(SaveSyncPlanner.sameTime("2026-10-03T08:11:34.419482+00:00", "2026-10-03T08:11:35+00:00"))
        // An upload recorded from the answer is not mistaken for a server change on the next sync.
        val action = SaveSyncPlanner.plan(
            listOf(local("Zelda.srm")),
            listOf(remote(1, "Zelda.srm", updatedAt = "2026-10-03T08:11:34+00:00")),
            listOf(record("Zelda.srm", 1, updatedAt = "2026-10-03T08:11:34.419482+00:00"))
        ).single()
        assertTrue(action is SaveSyncAction.InSync)
    }

    @Test
    fun `a server change in the same second shows through size or hash`() {
        val r = remote(1, "Zelda.srm").copy(contentHash = "bbb")
        val sameSecond = record("Zelda.srm", 1).copy(remoteSize = 100, remoteHash = "aaa")
        assertTrue(SaveSyncPlanner.plan(listOf(local("Zelda.srm")), listOf(r), listOf(sameSecond)).single() is SaveSyncAction.Download)
        val resized = record("Zelda.srm", 1).copy(remoteSize = 99)
        assertTrue(SaveSyncPlanner.plan(listOf(local("Zelda.srm")), listOf(remote(1, "Zelda.srm")), listOf(resized)).single() is SaveSyncAction.Download)
        val same = record("Zelda.srm", 1).copy(remoteSize = 100, remoteHash = "BBB")
        assertTrue(SaveSyncPlanner.plan(listOf(local("Zelda.srm")), listOf(r), listOf(same)).single() is SaveSyncAction.InSync)
    }

    @Test
    fun `timestamps in RomM formats`() {
        assertEquals(0L, SaveSyncPlanner.epochMillis("1970-01-01T00:00:00+00:00"))
        assertEquals(0L, SaveSyncPlanner.epochMillis("1970-01-01T00:00:00Z"))
        assertEquals(0L, SaveSyncPlanner.epochMillis("1970-01-01T00:00:00"))
        assertNull(SaveSyncPlanner.epochMillis("not a date"))
    }
}
