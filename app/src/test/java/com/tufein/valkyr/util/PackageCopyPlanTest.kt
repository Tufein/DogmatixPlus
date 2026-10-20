package com.tufein.valkyr.util

import com.tufein.valkyr.util.PackageCopyPlan.Decision
import com.tufein.valkyr.util.PackageCopyPlan.Existing
import com.tufein.valkyr.util.PackageCopyPlan.FileState
import com.tufein.valkyr.util.PackageCopyPlan.Part
import com.tufein.valkyr.util.PackageCopyPlan.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageCopyPlanTest {
    private fun part(path: String, bytes: Long = 100, sha: String = path.hashCode().toString().padStart(64, '0').takeLast(64)) =
        Part(path, bytes, sha, "content://src/$path")

    private val discGame = Source("a", "Game (USA).zip", "psx", "psx", listOf(
        part("Game (USA)/Game (USA).cue", 120), part("Game (USA)/Game (USA) (Track 1).bin", 5000),
        part("Game (USA)/Game (USA) (Track 2).bin", 3000)
    ))
    private val looseDiscs = Source("b", "Saga.7z", "psx", "psx", listOf(
        part("Saga.m3u", 40), part("Saga (Disc 1).chd", 9000), part("Saga (Disc 2).chd", 9000)
    ))
    private val single = Source("c", "Mario.zip", "snes", "snes", listOf(part("Mario.sfc", 1024)))

    private fun dest(vararg entries: Pair<String, Existing>): (String) -> Existing? {
        val map = entries.associate { ArchivePlan.portableKey(it.first) to it.second }
        return { map[ArchivePlan.portableKey(it)] }
    }

    @Test fun emptyDestinationCopiesEveryFileWhereItIsOnTheDevice() {
        val plan = PackageCopyPlan.plan(listOf(discGame, single), dest(), freeBytes = 10L shl 30)
        assertEquals(listOf("psx/Game (USA)/Game (USA).cue", "psx/Game (USA)/Game (USA) (Track 1).bin",
            "psx/Game (USA)/Game (USA) (Track 2).bin", "snes/Mario.sfc"), plan.files.map { it.target })
        assertEquals(120L + 5000 + 3000 + 1024, plan.bytesToCopy)
        assertTrue(plan.games.all { it.decision == Decision.COPY })
        assertEquals(true, plan.enoughSpace)
        assertTrue(plan.canStart)
    }

    @Test fun aGameWhoseFilesOnlyDifferInLetterCaseIsLeftOutWhole() {
        val clash = Source("d", "Twins.zip", "gba", "gba", listOf(part("Game.gba", 10), part("game.GBA", 20)))
        val plan = PackageCopyPlan.plan(listOf(clash, single), dest(), freeBytes = 10L shl 30)
        val game = plan.games.single { it.key == "d" }
        assertEquals(Decision.SKIPPED, game.decision)
        assertEquals(listOf("gba/Game.gba", "gba/game.GBA"), game.conflicts)
        assertEquals(listOf("snes/Mario.sfc"), plan.files.map { it.target })
    }

    @Test fun savesNeverTravelAndAreListed() {
        val withSaves = discGame.copy(parts = discGame.parts + part("Game (USA)/Game (USA).srm", 8192) + part("Game (USA)/Game (USA).state3", 50_000))
        val plan = PackageCopyPlan.plan(listOf(withSaves), dest())
        assertEquals(3, plan.files.size)
        assertEquals(listOf("psx/Game (USA)/Game (USA).srm", "psx/Game (USA)/Game (USA).state3"), plan.saves)
        val onlySaves = Source("s", "Saves", "gba", "gba", listOf(part("Pokemon.sav")))
        assertEquals(Decision.ONLY_SAVES, PackageCopyPlan.plan(listOf(onlySaves), dest()).games.single().decision)
    }

    @Test fun saveDetection() {
        listOf("a.srm", "A.SAV", "x/Game.state", "Game.state.auto", "Game.state12", "Game.ss1", "card.mcd", "Game.dsv",
            "saves/anything.bin", "Game/States/slot.bin").forEach { assertTrue(it, PackageCopyPlan.isSaveOrState(it)) }
        listOf("Game.bin", "Game.cue", "Game.chd", "Game.iso", "Game.m3u", "Game (USA).zip", "Saturn.bin", "statesman.bin")
            .forEach { assertFalse(it, PackageCopyPlan.isSaveOrState(it)) }
    }

    @Test fun sameNameAndSizeIsPresentNotACopyAndNothingIsReplaced() {
        val plan = PackageCopyPlan.plan(listOf(single), dest("snes/Mario.sfc" to Existing(false, 1024)))
        assertEquals(FileState.PRESENT, plan.files.single().state)
        assertEquals(0L, plan.bytesToCopy)
        assertEquals(1024L, plan.bytesPresent)
    }

    @Test fun clashSkipsTheWholeGameByDefault() {
        val plan = PackageCopyPlan.plan(listOf(discGame, single), dest("psx/Game (USA)/Game (USA) (Track 2).bin" to Existing(false, 1)))
        val game = plan.games.first()
        assertEquals(Decision.SKIPPED, game.decision)
        assertEquals(listOf("psx/Game (USA)/Game (USA) (Track 2).bin"), game.conflicts)
        // Not even its other files are copied: a game is never left half there.
        assertEquals(listOf("snes/Mario.sfc"), plan.files.map { it.target })
    }

    @Test fun renameMovesTheGamesOwnFolder() {
        val plan = PackageCopyPlan.plan(listOf(discGame), dest(
            "psx/Game (USA)" to Existing(true, 0),
            "psx/Game (USA)/Game (USA).cue" to Existing(false, 7)
        ), CopyConflictChoice.RENAME)
        val game = plan.games.single()
        assertEquals(Decision.RENAMED, game.decision)
        assertEquals("psx/Game (USA) (2)", game.root)
        assertTrue(plan.files.all { it.target.startsWith("psx/Game (USA) (2)/") && it.state == FileState.NEW })
        // The file names inside stay the same, so the cue still finds its tracks.
        assertEquals(discGame.parts.map { it.path.substringAfterLast('/') }, plan.files.map { it.target.substringAfterLast('/') })
    }

    @Test fun renameSkipsNamesThatAreTakenToo() {
        val plan = PackageCopyPlan.plan(listOf(single), dest(
            "snes/Mario.sfc" to Existing(false, 1), "snes/Mario (2).sfc" to Existing(false, 1024)
        ), CopyConflictChoice.RENAME)
        assertEquals("snes/Mario (3).sfc", plan.files.single().target)
    }

    @Test fun renameOfLooseFilesGivesThemAFolderSoReferencesStayValid() {
        val plan = PackageCopyPlan.plan(listOf(looseDiscs), dest("psx/Saga.m3u" to Existing(false, 99)), CopyConflictChoice.RENAME)
        assertEquals(listOf("psx/Saga (2)/Saga.m3u", "psx/Saga (2)/Saga (Disc 1).chd", "psx/Saga (2)/Saga (Disc 2).chd"), plan.files.map { it.target })
    }

    @Test fun sharedFileIsListedAndCopiedOnce() {
        val bios = part("bios/scph1001.bin", 524_288, sha = "a".repeat(64))
        val one = Source("1", "One.zip", "psx", "psx", listOf(part("One.chd", 10), bios))
        val two = Source("2", "Two.zip", "psx", "psx", listOf(part("Two.chd", 10), bios))
        val plan = PackageCopyPlan.plan(listOf(one, two), dest())
        assertEquals(3, plan.files.size)
        val shared = plan.files.single { it.shared }
        assertEquals(listOf("One.zip", "Two.zip"), shared.games)
        assertEquals(1, plan.sharedFiles)
        assertEquals(20L + 524_288, plan.bytesToCopy)
    }

    @Test fun twoSelectedGamesWithDifferentFilesOfOneNameClash() {
        val one = Source("1", "One", "psx", "psx", listOf(part("readme.chd", 10, sha = "1".repeat(64))))
        val two = Source("2", "Two", "psx", "psx", listOf(part("readme.chd", 10, sha = "2".repeat(64))))
        val plan = PackageCopyPlan.plan(listOf(one, two), dest())
        assertEquals(listOf(Decision.COPY, Decision.SKIPPED), plan.games.map { it.decision })
        val renamed = PackageCopyPlan.plan(listOf(one, two), dest(), CopyConflictChoice.RENAME)
        assertEquals(listOf("psx/readme.chd", "psx/readme (2).chd"), renamed.files.map { it.target })
    }

    @Test fun aFileWhereAFolderShouldBeIsAClash() {
        val plan = PackageCopyPlan.plan(listOf(discGame), dest("psx/Game (USA)" to Existing(false, 5)))
        assertEquals(Decision.SKIPPED, plan.games.single().decision)
    }

    @Test fun matchesCaseInsensitivelyLikeAMemoryCard() {
        val plan = PackageCopyPlan.plan(listOf(single), dest("SNES/mario.SFC" to Existing(false, 1)))
        assertEquals(Decision.SKIPPED, plan.games.single().decision)
    }

    @Test fun notEnoughSpaceBlocksTheStartAndUnknownSpaceDoesNot() {
        val tight = PackageCopyPlan.plan(listOf(discGame), dest(), freeBytes = 8120L + PackageCopyPlan.MARGIN_BYTES - 1)
        assertEquals(false, tight.enoughSpace)
        assertFalse(tight.canStart)
        val unknown = PackageCopyPlan.plan(listOf(discGame), dest(), freeBytes = null)
        assertNull(unknown.enoughSpace)
        assertTrue(unknown.canStart)
    }

    @Test fun emptyGameFolderKeepsFilesAtTheDestinationRoot() {
        val plan = PackageCopyPlan.plan(listOf(single.copy(folder = "")), dest())
        assertEquals("Mario.sfc", plan.files.single().target)
    }

    @Test fun folderNamesAreSafeForMemoryCards() {
        assertEquals("Game_ Part 1", PackageCopyPlan.folderName("Game_ Part 1.7z"))
        assertEquals("What Now", PackageCopyPlan.folderName("What? Now.zip"))
        assertEquals("Game", PackageCopyPlan.folderName("???.zip"))
    }
}
