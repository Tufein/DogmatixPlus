package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class SaveHandoffTest {
    @Test fun oversizedHandoffSaveIsRejectedBeforePlanningOrReading() {
        SaveHandoff.requireBoundedSave(SaveHandoff.MAX_SAVE_BYTES)
        val failure = runCatching { SaveHandoff.requireBoundedSave(SaveHandoff.MAX_SAVE_BYTES + 1) }.exceptionOrNull()
        assertTrue(failure is SaveHandoff.SaveTooLargeException)
        assertTrue(failure!!.message!!.contains("16 MiB"))
    }
    private val local = LocalSaveFile(SaveKind.SAVE, "mGBA/Game.srm", 4, 123)
    private val remote = RemoteSaveFile(SaveKind.SAVE, 1, 10, "Game.srm", "mGBA", "2026-10-10T10:00:00Z", 4, "/saves/1")
    private val listing = SaveStore.Listing(listOf(local), mapOf(SaveKind.SAVE to setOf("mGBA")))
    private fun plan(localHash: String, remoteHash: String, records: List<SaveSyncRecord> = emptyList()) = SaveHandoff.plan(listing, listOf(remote), records, mapOf(local.path to localHash), mapOf(remote.id to remoteHash)).single()
    private fun preview(file: SaveHandoffFile = plan("same", "same")) = SaveHandoffPreview(JournalKey("parent", "gba", "Game.gba"), 10, "folders-and-server", 1000, listOf(file))

    @Test fun matchingBytesAllowVerificationButNeverClaimVerifiedBackupBeforeTransfer() {
        val preview = preview()
        assertTrue(preview.canTransfer)
        assertFalse(preview.ready)
        assertTrue(preview.copy(backupVerified = true).ready)
    }
    @Test fun unsynchronizedDifferentBytesAreAConflictEvenIfClocksLookEqual() {
        assertEquals(SaveHandoffDirection.CONFLICT, plan("new", "old").direction)
        assertFalse(preview(plan("new", "old")).canTransfer)
    }
    @Test fun matchingRecordedTimestampsDoNotHideChangedBytes() {
        val record = SaveSyncEngine.record(local, remote)
        assertEquals(SaveHandoffDirection.CONFLICT, plan("changed invisibly", "old", listOf(record)).direction)
    }
    @Test fun equalBytesResolveClockBasedConflictsWithoutPickingASide() {
        val record = SaveSyncEngine.record(local.copy(modified = 1), remote.copy(updatedAt = "2026-10-09T10:00:00Z"))
        assertEquals(SaveHandoffDirection.IDENTICAL, plan("equal bytes", "equal bytes", listOf(record)).direction)
    }
    @Test fun onlyTheChangedDeviceSideIsPlannedForUpload() {
        val record = SaveSyncEngine.record(local.copy(modified = 1), remote)
        assertEquals(SaveHandoffDirection.UPLOAD, plan("new", "old", listOf(record)).direction)
    }
    @Test fun onlyTheChangedServerSideIsPlannedForDownload() {
        val record = SaveSyncEngine.record(local, remote.copy(updatedAt = "2026-10-09T10:00:00Z"))
        assertEquals(SaveHandoffDirection.DOWNLOAD, plan("old", "new", listOf(record)).direction)
    }
    @Test fun noDeletionIsEverPlannedForMissingDeviceSave() {
        val rows = SaveHandoff.plan(listing.copy(files = emptyList()), listOf(remote), listOf(SaveSyncEngine.record(local, remote)), emptyMap(), mapOf(remote.id to "server"))
        assertEquals(SaveHandoffDirection.DOWNLOAD, rows.single().direction)
        assertEquals("mGBA/Game.srm", rows.single().path)
    }
    @Test fun emulatorStatesAndUnknownFormatsCannotEnterThePortableSavePlan() {
        listOf("Game.state", "Game.state1", "Game.dss", "Game.dst", "Game.bin", "config.json").forEach { assertFalse(it, SaveHandoff.isInGameSave(it)) }
        assertTrue(SaveHandoff.isInGameSave("Game.DSV"))
        assertTrue(runCatching { SaveHandoff.plan(listing.copy(files = listOf(local.copy(kind = SaveKind.STATE, path = "Game.state"))), emptyList(), emptyList(), mapOf("Game.state" to "hash"), emptyMap()) }.isFailure)
    }
    @Test fun stalePreviewBlocksNewGameProfileFolderAndByteChanges() {
        val before = preview()
        listOf(before.copy(key = before.key.copy(profileId = "child")), before.copy(configuration = "other-folder"),
            before.copy(files = listOf(before.files.single().copy(localSha256 = "changed"))), before.copy(romId = 11)).forEach { after ->
            assertTrue(runCatching { SaveHandoff.requireFresh(before, after, 1100) }.isFailure)
        }
        SaveHandoff.requireFresh(before, before.copy(createdAt = 1100), 1100)
    }
    @Test fun expiredOrFuturePreviewIsNotUsable() {
        val before = preview()
        assertTrue(runCatching { SaveHandoff.requireFresh(before, before, 999) }.isFailure)
        assertTrue(runCatching { SaveHandoff.requireFresh(before, before, before.createdAt + SaveHandoff.PREVIEW_TTL_MS + 1) }.isFailure)
    }
    @Test fun emptyOrBlockedPreviewNeverBecomesReady() {
        assertFalse(preview().copy(files = emptyList(), backupVerified = true).ready)
        assertFalse(preview(preview().files.single().copy(direction = SaveHandoffDirection.BLOCKED)).copy(backupVerified = true).ready)
    }
}
