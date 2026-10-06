package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class RecoverySafetyTest {
    @Test fun sameSizeDifferentContentIsRejected() {
        val a = VerifiedCopy.hash("abcd".byteInputStream())
        val b = VerifiedCopy.hash("wxyz".byteInputStream())
        assertNotEquals(a, b)
        assertThrows(IOException::class.java) { VerifiedCopy.requireSame(a, b) }
    }
    @Test fun reReadingCopyDetectsCorruption() {
        val output = ByteArrayOutputStream()
        val hash = VerifiedCopy.transfer("a game".byteInputStream(), output)
        assertEquals(hash, VerifiedCopy.hash(output.toByteArray().inputStream()))
        assertThrows(IOException::class.java) { VerifiedCopy.requireSame(hash, VerifiedCopy.hash("b game".byteInputStream())) }
    }
    @Test fun interruptedTransferClosesBothStreams() {
        var inputClosed = false; var outputClosed = false
        val input = object : ByteArrayInputStream(ByteArray(600_000)) { override fun close() { inputClosed = true; super.close() } }
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        var checks = 0
        assertThrows(IOException::class.java) { VerifiedCopy.transfer(input, output, check = { if (++checks == 2) throw IOException("interrupted") }) }
        assertTrue(inputClosed); assertTrue(outputClosed)
        assertTrue(output.size() < 600_000)
    }
    @Test fun saveAndMetadataWithMatchingBaseAreNeverGameArtifacts() {
        listOf("Game.srm", "Game.sav", "Game.state", "Game.txt", "Game.xml", "Game.png").forEach {
            assertFalse(it, GameRemoval.matches(it, "Game.zip"))
        }
        assertTrue(GameRemoval.matches("Game.gba", "Game.zip"))
        assertTrue(GameRemoval.matches("Game.zip", "Game.zip"))
    }
    @Test fun nonArchiveRequestsDoNotTakeAnotherRomVersion() {
        assertFalse(GameRemoval.matches("Game.gba", "Game.gbc"))
        assertFalse(GameRemoval.matches("Game (Rev 2).gba", "Game.zip"))
    }
    @Test fun descriptorReferencesCannotLeaveTheirFolderOrIncludeSaves() {
        listOf("../track.bin", "sub/track.bin", "sub\\track.bin", "/track.bin", "C:track.bin", "game.srm").forEach {
            assertFalse(GameRemoval.safeReference(it))
        }
        assertTrue(GameRemoval.safeReference("Game (Track 2).bin"))
    }
    @Test fun preferredVersionWinsRankingAndMissingPreferenceFallsBack() {
        val a = VersionPicker.Candidate("a", "Game (USA).gba")
        val b = VersionPicker.Candidate("b", "Game (Japan).gba")
        assertEquals(b, VersionPreference.pick(listOf(a,b), listOf("USA", "Japan"), setOf("EN"), "b"))
        assertEquals(a, VersionPreference.pick(listOf(a,b), listOf("USA", "Japan"), setOf("EN"), "gone"))
    }
    @Test fun preferenceSeparatesConsolesSequelsAndDiscs() {
        assertEquals(VersionPreference.key("gba", "Game (USA).gba"), VersionPreference.key("gba", "Game (Europe) (Rev 2).gba"))
        assertNotEquals(VersionPreference.key("gba", "Game.gba"), VersionPreference.key("snes", "Game.sfc"))
        assertNotEquals(VersionPreference.key("psx", "Game (Disc 1).cue"), VersionPreference.key("psx", "Game (Disc 2).cue"))
        assertNotEquals(VersionPreference.key("gba", "Game.gba"), VersionPreference.key("gba", "Game 2.gba"))
    }
    @Test fun bulkPlanRespectsPinnedVersion() {
        val a = BulkCandidate(1, "gba", "game", "Game (USA).gba", 10, emptyList(), false, false)
        val b = a.copy(id=2, fileName="Game (Japan).gba")
        val plan = BulkPlanner.plan(listOf(a,b), true, listOf("USA", "Japan"), setOf("EN"), null, preferredVersion={ b.fileName })
        assertEquals(listOf(b), plan.chosen)
    }
}
