package com.tufein.valkyr.util

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three-way merge on its own: the manifest's base hash against the two sides. */
class SaveMergeTest {
    private val base = "aaaa"
    private val t0 = 1_700_000_000_000L

    private fun local(size: Long = 10, modified: Long = t0, path: String = "mGBA/Game.srm") = LocalSaveFile(SaveKind.SAVE, path, size, modified)
    private fun remote(hash: String? = base, size: Long = 10, at: Long = t0, id: Int = 1) =
        RemoteSaveFile(SaveKind.SAVE, id, 1, "Game.srm", "mGBA", Instant.ofEpochMilli(at).toString(), size, "/x", hash)
    private fun record(baseHash: String? = base, remoteHash: String? = base) =
        SaveSyncRecord(SaveKind.SAVE, "mGBA/Game.srm", 1, 1, Instant.ofEpochMilli(t0).toString(), 10, t0, 10, remoteHash, baseHash)

    // ---- Per side ------------------------------------------------------------------------------

    @Test fun `a file with the recorded size and time did not change and is not read`() {
        assertFalse(SaveMerge.needsHash(local(), record()))
        assertFalse(SaveMerge.localChanged(local(), record(), null))
    }

    @Test fun `a new file time with the base bytes is no change`() {
        val touched = local(modified = t0 + 60_000)
        assertTrue(SaveMerge.needsHash(touched, record()))
        assertFalse(SaveMerge.localChanged(touched, record(), base))
    }

    @Test fun `new bytes are a change whatever the time`() {
        assertTrue(SaveMerge.localChanged(local(modified = t0 + 5), record(), "bbbb"))
        assertTrue(SaveMerge.localChanged(local(size = 11), record(), "bbbb"))
    }

    @Test fun `without a hash read or a base the size and time rules decide`() {
        assertTrue(SaveMerge.localChanged(local(modified = t0 + 5), record(), null))
        assertTrue(SaveMerge.localChanged(local(modified = t0 + 5), record(baseHash = null), base))
    }

    @Test fun `a server time that moved with the base bytes is no change`() {
        val touched = remote(at = t0 + 90_000)
        assertTrue(SaveSyncPlanner.remoteChanged(touched, record()))
        assertFalse(SaveMerge.remoteChanged(touched, record()))
    }

    @Test fun `new server bytes are a change`() {
        assertTrue(SaveMerge.remoteChanged(remote(hash = "cccc", at = t0 + 90_000), record()))
        // The same time and size but another hash is caught by the record's own rules.
        assertTrue(SaveMerge.remoteChanged(remote(hash = "cccc"), record()))
    }

    @Test fun `a server without hashes is judged by size and time`() {
        assertTrue(SaveMerge.remoteChanged(remote(hash = null, at = t0 + 90_000), record(remoteHash = null)))
        assertFalse(SaveMerge.remoteChanged(remote(hash = null), record(remoteHash = null)))
    }

    @Test fun `hashes compare without case`() {
        assertTrue(SaveMerge.sameHash("ABCD", "abcd"))
        assertFalse(SaveMerge.sameHash(null, null))
        assertFalse(SaveMerge.sameHash("a", null))
    }

    // ---- The verdict ---------------------------------------------------------------------------

    @Test fun `verdict - nothing changed`() = assertEquals(SaveMerge.Verdict.UNCHANGED, SaveMerge.verdict(base, base, base, false, false))

    @Test fun `verdict - only the device changed pushes`() = assertEquals(SaveMerge.Verdict.PUSH, SaveMerge.verdict(base, "b", base, true, false))

    @Test fun `verdict - only the server changed pulls`() = assertEquals(SaveMerge.Verdict.PULL, SaveMerge.verdict(base, base, "c", false, true))

    @Test fun `verdict - both changed differently is a conflict`() = assertEquals(SaveMerge.Verdict.CONFLICT, SaveMerge.verdict(base, "b", "c", true, true))

    @Test fun `verdict - both changed to the same bytes converge`() = assertEquals(SaveMerge.Verdict.CONVERGED, SaveMerge.verdict(base, "b", "b", true, true))

    @Test fun `verdict - touched files with the base bytes are unchanged`() {
        assertEquals(SaveMerge.Verdict.UNCHANGED, SaveMerge.verdict(base, base, base, true, true))
        // One side touched only, the other really changed: the real change wins, no conflict.
        assertEquals(SaveMerge.Verdict.PUSH, SaveMerge.verdict(base, "b", base, true, true))
        assertEquals(SaveMerge.Verdict.PULL, SaveMerge.verdict(base, base, "c", true, true))
    }

    @Test fun `verdict - without a base the touched flags decide`() {
        assertEquals(SaveMerge.Verdict.CONFLICT, SaveMerge.verdict(null, "b", "c", true, true))
        assertEquals(SaveMerge.Verdict.PUSH, SaveMerge.verdict(null, "b", "c", true, false))
        assertEquals(SaveMerge.Verdict.PULL, SaveMerge.verdict(null, "b", "c", false, true))
        assertEquals(SaveMerge.Verdict.UNCHANGED, SaveMerge.verdict(null, null, null, false, false))
    }

    @Test fun `verdict - equal bytes without a base are recorded as converged`() =
        assertEquals(SaveMerge.Verdict.CONVERGED, SaveMerge.verdict(null, "b", "b", true, true))

    // ---- The options ---------------------------------------------------------------------------

    private fun newerServer() = SaveConflictInfo.of(local(modified = t0), remote(at = t0 + 60_000))
    private fun newerDevice() = SaveConflictInfo.of(local(modified = t0 + 60_000), remote(at = t0))

    @Test fun `the conflict info says which side is newer`() {
        assertEquals(NewerSide.SERVER, newerServer().newer)
        assertEquals(NewerSide.DEVICE, newerDevice().newer)
    }

    @Test fun `by default every difference is left to the user`() {
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(local(modified = t0), remote(at = t0 + 60_000), SaveSyncOptions()))
    }

    @Test fun `prefer newer server takes a clearly newer server save`() {
        val on = SaveSyncOptions(preferNewerServer = true)
        assertEquals(SaveMerge.OnDiffer.TAKE_SERVER, SaveMerge.onDiffer(local(modified = t0), remote(at = t0 + 60_000), on))
    }

    @Test fun `prefer newer server leaves a newer, equal or unknown device time to the user`() {
        val on = SaveSyncOptions(preferNewerServer = true)
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(local(modified = t0 + 60_000), remote(at = t0), on))
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(local(modified = t0), remote(at = t0 + 1_000), on))
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(local(modified = 0), remote(at = t0), on))
    }

    @Test fun `prefer newer server never decides for a card that holds many games`() {
        val card = LocalSaveFile(SaveKind.SAVE, "DuckStation/shared_card_1.mcd", 10, t0, layout = SaveFolderLayout.MEMCARDS, fixedRomId = 5)
        assertTrue(EmulatorSaveLayouts.isChosenGameOnly(card))
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(card, remote(at = t0 + 60_000), SaveSyncOptions(preferNewerServer = true)))
    }

    @Test fun `never overwrite turns taking the server into keeping both`() {
        val both = SaveSyncOptions(preferNewerServer = true, nonDestructive = true)
        assertEquals(SaveMerge.OnDiffer.KEEP_BOTH, SaveMerge.onDiffer(local(modified = t0), remote(at = t0 + 60_000), both))
        assertEquals(SaveMerge.OnDiffer.ASK, SaveMerge.onDiffer(local(modified = t0 + 60_000), remote(at = t0), both))
    }

    @Test fun `never overwrite replaces nothing and mirrors no deletion`() {
        val safe = SaveSyncOptions(nonDestructive = true)
        assertFalse(SaveMerge.replacesDevice(safe))
        assertFalse(SaveMerge.replacesServer(safe))
        assertFalse(SaveMerge.mirrorsDeletions(true, safe))
        assertTrue(SaveMerge.replacesDevice(SaveSyncOptions()))
        assertTrue(SaveMerge.replacesServer(SaveSyncOptions()))
        assertTrue(SaveMerge.mirrorsDeletions(true, SaveSyncOptions()))
        assertFalse(SaveMerge.mirrorsDeletions(false, SaveSyncOptions()))
    }

    // ---- Records -------------------------------------------------------------------------------

    @Test fun `keeping both notes both sides as seen and drops the base`() {
        val l = local(size = 12, modified = t0 + 7)
        val r = remote(hash = "cccc", size = 14, at = t0 + 9)
        val kept = SaveMerge.keepBothRecord(l, r)
        assertNull(kept.baseHash)
        assertFalse(SaveSyncPlanner.localChanged(l, kept))
        assertFalse(SaveSyncPlanner.remoteChanged(r, kept))
        assertEquals(r.id, kept.remoteId)
    }

    @Test fun `a refreshed record keeps its base and only follows the snapshots`() {
        val moved = SaveMerge.refreshed(record(), local(modified = t0 + 60_000), remote(at = t0 + 90_000))
        assertEquals(base, moved.baseHash)
        assertTrue(SaveMerge.needsRefresh(record(), local(modified = t0 + 60_000), remote()))
        assertFalse(SaveMerge.needsRefresh(record(), local(), remote()))
        assertNotNull(moved)
    }

    @Test fun `the planner uses the hashes - a touched device file is not a conflict`() {
        val l = local(modified = t0 + 60_000)
        val r = remote(hash = "cccc", at = t0 + 90_000)
        val withBase = SaveSyncPlanner.plan(listOf(l), listOf(r), listOf(record()), mapOf(SaveKind.SAVE to setOf("mGBA")), localHashes = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, l.path) to base))
        assertTrue(withBase.single() is SaveSyncAction.Download)
        val without = SaveSyncPlanner.plan(listOf(l), listOf(r), listOf(record()), mapOf(SaveKind.SAVE to setOf("mGBA")))
        assertTrue(without.single() is SaveSyncAction.Conflict)
    }

    @Test fun `the planner turns two changes to the same bytes into a compare`() {
        val l = local(modified = t0 + 60_000)
        val r = remote(hash = "bbbb", at = t0 + 90_000)
        val plan = SaveSyncPlanner.plan(listOf(l), listOf(r), listOf(record()), mapOf(SaveKind.SAVE to setOf("mGBA")), localHashes = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, l.path) to "bbbb"))
        assertTrue(plan.single() is SaveSyncAction.Compare)
    }

    @Test fun `the planner uploads when the server only touched its time`() {
        val l = local(size = 11, modified = t0 + 60_000)
        val r = remote(hash = base, at = t0 + 90_000)
        val plan = SaveSyncPlanner.plan(listOf(l), listOf(r), listOf(record()), mapOf(SaveKind.SAVE to setOf("mGBA")), localHashes = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, l.path) to "bbbb"))
        assertTrue(plan.single() is SaveSyncAction.Upload)
    }

    @Test fun `the settings are known to the settings backup`() {
        assertEquals("i", SettingSchema.expectedType(SaveMerge.KEY_BACKUP_KEEP))
        assertEquals("b", SettingSchema.expectedType(SaveMerge.KEY_NON_DESTRUCTIVE))
        assertEquals("b", SettingSchema.expectedType(SaveMerge.KEY_PREFER_SERVER))
        assertEquals(50, SettingSchema.sanitizeNumber(SaveMerge.KEY_BACKUP_KEEP, 500))
        assertEquals(1, SettingSchema.sanitizeNumber(SaveMerge.KEY_BACKUP_KEEP, 0))
        assertFalse(SettingSchema.compatible(SaveMerge.KEY_PREFER_SERVER, "true"))
    }
}
