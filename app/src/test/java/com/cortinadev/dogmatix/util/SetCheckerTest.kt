package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetCheckerTest {
    private val cue = """
        FILE "Game (Track 1).bin" BINARY
          TRACK 01 MODE2/2352
            INDEX 01 00:00:00
        FILE "Game (Track 2).bin" BINARY
          TRACK 02 AUDIO
            INDEX 01 00:00:00
    """.trimIndent()

    @Test fun `parses the files of the three sheet types`() {
        assertEquals(listOf("Game (Track 1).bin", "Game (Track 2).bin"), SheetParser.cueFiles(cue))
        assertEquals(listOf("single.bin"), SheetParser.cueFiles("FILE single.bin BINARY"))
        assertEquals(listOf("track01.bin", "track02.raw", "disc one.bin"), SheetParser.gdiFiles("3\n1 0 4 2352 track01.bin 0\n2 450 0 2352 track02.raw 0\n3 45000 4 2352 \"disc one.bin\" 0"))
        assertEquals(listOf("Disc 1.cue", "Disc 2.cue"), SheetParser.m3uFiles("﻿#EXTM3U\nDisc 1.cue\n\n  Disc 2.cue  \nDisc 1.cue"))
        assertEquals("Game.cue", SheetParser.leafName("..\\Discs\\Game.cue"))
    }

    @Test fun `a complete set has no problems`() {
        val files = listOf(diskFile("Game.cue"), diskFile("Game (Track 1).bin"), diskFile("Game (Track 2).bin"))
        assertTrue(SetChecker.check(files) { cue }.isEmpty())
    }

    @Test fun `a cue with a missing track is reported with the names`() {
        val files = listOf(diskFile("Game.cue"), diskFile("Game (Track 1).bin"))
        val problems = SetChecker.check(files) { cue }
        assertEquals(1, problems.size)
        assertEquals(SetProblem.Kind.MISSING_TRACKS, problems[0].kind)
        assertEquals(listOf("Game (Track 2).bin"), problems[0].missing)
    }

    @Test fun `file names are compared without regard to case`() {
        val files = listOf(diskFile("Game.cue"), diskFile("GAME (TRACK 1).BIN"), diskFile("game (track 2).bin"))
        assertTrue(SetChecker.check(files) { cue }.isEmpty())
    }

    @Test fun `only the files of the same folder count`() {
        val files = listOf(diskFile("Game.cue", folder = "/a", dirId = "a"), diskFile("Game (Track 1).bin", folder = "/b", dirId = "b"), diskFile("Game (Track 2).bin", folder = "/b", dirId = "b"))
        assertEquals(1, SetChecker.check(files) { cue }.size)
    }

    @Test fun `a playlist with a deleted disc`() {
        val files = listOf(diskFile("Game.m3u"), diskFile("Game (Disc 1).cue"), diskFile("Game (Disc 1).bin"))
        val problems = SetChecker.check(files) { if (it.name.endsWith(".m3u")) "Game (Disc 1).cue\nGame (Disc 2).cue" else "FILE \"Game (Disc 1).bin\" BINARY" }
        assertEquals(SetProblem.Kind.MISSING_DISCS, problems.single().kind)
        assertEquals(listOf("Game (Disc 2).cue"), problems.single().missing)
    }

    @Test fun `entries in other folders are not judged`() {
        val files = listOf(diskFile("Game.m3u"))
        assertTrue(SetChecker.check(files) { "discs/Game (Disc 1).cue" }.isEmpty())
    }

    @Test fun `an empty or unreadable sheet is reported`() {
        val files = listOf(diskFile("Empty.cue"), diskFile("Locked.gdi"))
        val problems = SetChecker.check(files) { if (it.name == "Empty.cue") "REM nothing" else null }
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.kind == SetProblem.Kind.EMPTY_SHEET })
    }

    @Test fun `non sheets and oversized files are never read`() {
        val files = listOf(diskFile("Game.bin"), diskFile("Huge.cue", size = SetChecker.MAX_SHEET_BYTES + 1))
        assertTrue(SetChecker.check(files) { error("must not be read") }.isEmpty())
    }
}
