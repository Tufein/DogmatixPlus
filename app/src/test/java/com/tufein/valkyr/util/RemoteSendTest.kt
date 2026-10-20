package com.tufein.valkyr.util

import com.tufein.valkyr.util.PackageCopyPlan.Part
import com.tufein.valkyr.util.PackageCopyPlan.Source
import com.tufein.valkyr.util.RemoteSend.Check
import com.tufein.valkyr.util.RemoteSend.Outcome
import com.tufein.valkyr.util.RemoteSend.RommDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSendTest {
    private fun part(path: String, bytes: Long = 100) = Part(path, bytes, "0".repeat(64), "content://src/$path")
    private val cueBin = Source("a", "Game (USA).zip", "psx", "psx", listOf(part("Game (USA).cue", 120), part("Game (USA).bin", 5000)))
    private val chd = Source("b", "Other.chd", "psx", "psx", listOf(part("Other.chd", 7000)))
    private val folder = Source("c", "Folder.zip", "psx", "psx", listOf(part("Folder/Folder.iso", 7000)))
    private val platforms = mapOf("psx" to 7)

    private fun plan(vararg sources: Source, server: Map<String, Long> = emptyMap(), choice: CopyConflictChoice = CopyConflictChoice.SKIP) =
        RemoteSend.rommPlan(sources.toList(), { platforms[it] }, { server }, choice)

    @Test fun severalFilesOrFoldersArePackedSingleLooseFilesAreNot() {
        val games = plan(cueBin, chd, folder)
        assertEquals(listOf("Game (USA).zip", "Other.chd", "Folder.zip"), games.map { it.name })
        assertEquals(listOf(true, false, true), games.map { it.zipped })
        val entries = cueBin.parts.map { StoredZip.Entry(it.path, it.bytes, 0) }
        assertEquals(StoredZip.size(entries), games.first().bytes)
        assertEquals(7000L, games[1].bytes)
        assertTrue(games.all { it.decision == RommDecision.SEND })
    }

    @Test fun namesThatOnlyDifferInCaseArePacked() {
        assertTrue(RemoteSend.needsZip(listOf("a.bin", "A.BIN")))
        assertFalse(RemoteSend.needsZip(listOf("a.bin")))
    }

    @Test fun sameNameAndSizeOnTheServerIsCheckedNotSentAgain() {
        assertEquals(RommDecision.PRESENT, plan(chd, server = mapOf("other.chd" to 7000L)).single().decision)
    }

    @Test fun anotherFileOfTheNameIsSkippedOrRenamedNeverReplaced() {
        assertEquals(RommDecision.SKIPPED, plan(chd, server = mapOf("other.chd" to 1L)).single().decision)
        val renamed = plan(chd, server = mapOf("other.chd" to 1L, "other (2).chd" to 5L), choice = CopyConflictChoice.RENAME).single()
        assertEquals(RommDecision.RENAMED, renamed.decision)
        assertEquals("Other (3).chd", renamed.name)
        assertTrue(renamed.sends)
    }

    @Test fun twoSelectedGamesOfOneNameDoNotOverwriteEachOther() {
        val twin = chd.copy(key = "z")
        assertEquals(listOf(RommDecision.SEND, RommDecision.SKIPPED), plan(chd, twin).map { it.decision })
    }

    @Test fun unmappedConsoleOnlySavesAndTooLarge() {
        val snes = Source("s", "Mario.sfc", "snes", "snes", listOf(part("Mario.sfc")))
        assertEquals(RommDecision.NO_PLATFORM, plan(snes).single().decision)
        val saves = Source("v", "Saves", "psx", "psx", listOf(part("Game.srm")))
        assertEquals(RommDecision.ONLY_SAVES, plan(saves).single().decision)
        val huge = Source("h", "Huge.7z", "psx", "psx", listOf(part("Huge.cue", 100), part("Huge.bin", 4_500_000_000L)))
        assertEquals(RommDecision.TOO_LARGE, plan(huge).single().decision)
    }

    @Test fun savesAreLeftOutOfTheZip() {
        val withSave = cueBin.copy(parts = cueBin.parts + part("Game (USA).srm", 8192))
        val game = plan(withSave).single()
        assertEquals(2, game.parts.size)
        assertEquals(listOf("Game (USA).srm"), game.saves)
    }

    @Test fun nasPathsKeepTheFolders() {
        assertEquals(listOf("games", "psx", "Game (USA)", "Track 1.bin"), RemoteSend.davSegments("psx/Game (USA)/Track 1.bin"))
        assertEquals(".Game.bin.dogmatix-part", RemoteSend.tempName("Game.bin"))
    }

    @Test fun checkingWhatArrived() {
        val sha = "ab".repeat(32)
        assertEquals(Check.HASH_OK, RemoteSend.check(10, sha, 10, sha.uppercase()))
        assertEquals(Check.SIZE_OK, RemoteSend.check(10, sha, 10, null))
        assertEquals(Check.MISSING, RemoteSend.check(10, sha, null, null))
        assertEquals(Check.SIZE_DIFFERS, RemoteSend.check(10, sha, 9, sha))
        assertEquals(Check.HASH_DIFFERS, RemoteSend.check(10, sha, 10, "cd".repeat(32)))
    }

    @Test fun rommChecksumOnlyCountsWhenItMatches() {
        val sha1 = "1".repeat(40)
        assertEquals(true, RemoteSend.rommHashMatches("sha1:$sha1", sha1, null, null))
        assertEquals(false, RemoteSend.rommHashMatches("sha1:${"2".repeat(40)}", sha1, null, null))
        assertEquals(true, RemoteSend.rommHashMatches("crc32:0000abcd", sha1, "x", "0000ABCD"))
        assertNull(RemoteSend.rommHashMatches(null, sha1, null, null))
        assertNull(RemoteSend.rommHashMatches("md5:${"3".repeat(32)}", sha1, null, null))
    }

    @Test fun gameOutcomeIsTheWorstPartAndRetryIsOnlyOfferedWhereItHelps() {
        assertEquals(Outcome.SENT_SIZE, RemoteSend.gameOutcome(listOf(Outcome.SENT_HASH, Outcome.SENT_SIZE, Outcome.PRESENT_HASH)))
        assertEquals(Outcome.FAILED, RemoteSend.gameOutcome(listOf(Outcome.SENT_HASH, Outcome.FAILED, Outcome.CONFLICT)))
        assertTrue(RemoteSend.isDone(Outcome.PRESENT_SIZE))
        assertFalse(RemoteSend.isDone(Outcome.WAITING))
        assertTrue(RemoteSend.canRetry(Outcome.FAILED))
        assertFalse(RemoteSend.canRetry(Outcome.CONFLICT))
        assertFalse(RemoteSend.canRetry(Outcome.CHANGED))
    }
}
