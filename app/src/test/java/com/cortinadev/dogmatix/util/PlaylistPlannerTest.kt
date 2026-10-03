package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPlannerTest {
    @Test fun `splits the disc number off a name`() {
        assertEquals("Final Fantasy VII (USA)" to 2, PlaylistPlanner.split("Final Fantasy VII (USA) (Disc 2)"))
        assertEquals("Game" to 3, PlaylistPlanner.split("Game (Disc 3 of 4)"))
        assertEquals("Game" to 2, PlaylistPlanner.split("Game [CD II]"))
        assertEquals("Game" to 1, PlaylistPlanner.split("Game - Disc 1"))
        assertNull(PlaylistPlanner.split("Game (USA)"))
        assertNull(PlaylistPlanner.split("(Disc 1)"))
    }

    @Test fun `plans one playlist for a game with two discs`() {
        val files = listOf(
            diskFile("FF7 (USA) (Disc 1).cue"), diskFile("FF7 (USA) (Disc 1).bin"),
            diskFile("FF7 (USA) (Disc 2).cue"), diskFile("FF7 (USA) (Disc 2).bin"),
            diskFile("FF7 (USA) (Disc 3).cue"), diskFile("FF7 (USA) (Disc 3).bin")
        )
        val plan = PlaylistPlanner.plan(files).single()
        assertEquals("FF7 (USA).m3u", plan.fileName)
        assertEquals(listOf("FF7 (USA) (Disc 1).cue", "FF7 (USA) (Disc 2).cue", "FF7 (USA) (Disc 3).cue"), plan.discs)
        assertEquals("FF7 (USA) (Disc 1).cue\nFF7 (USA) (Disc 2).cue\nFF7 (USA) (Disc 3).cue\n", plan.content)
        assertEquals("content://t/dir//ROMs/psx", plan.dirUri)
    }

    @Test fun `single image discs are listed through the image`() {
        val files = listOf(diskFile("Game (Disc 2).chd"), diskFile("Game (Disc 1).chd"))
        assertEquals(listOf("Game (Disc 1).chd", "Game (Disc 2).chd"), PlaylistPlanner.plan(files).single().discs)
    }

    @Test fun `a sheet wins over its tracks and a bare bin is not a disc`() {
        val files = listOf(diskFile("G (Disc 1).cue"), diskFile("G (Disc 1).iso"), diskFile("G (Disc 2).bin"))
        assertTrue(PlaylistPlanner.plan(files).isEmpty())   // disc 2 has only a bin: only one listable disc
    }

    @Test fun `games with a playlist already, or with one disc, are left alone`() {
        assertTrue(PlaylistPlanner.plan(listOf(diskFile("G (Disc 1).cue"), diskFile("G (Disc 2).cue"), diskFile("g.M3U"))).isEmpty())
        assertTrue(PlaylistPlanner.plan(listOf(diskFile("G (Disc 1).cue"))).isEmpty())
    }

    @Test fun `discs in different folders are not joined`() {
        val files = listOf(diskFile("G (Disc 1).cue", folder = "/a", dirId = "a"), diskFile("G (Disc 2).cue", folder = "/b", dirId = "b"))
        assertTrue(PlaylistPlanner.plan(files).isEmpty())
    }

    @Test fun `different games in one folder get their own playlists`() {
        val files = listOf(
            diskFile("A (Disc 1).chd"), diskFile("A (Disc 2).chd"),
            diskFile("B (Disc 1).chd"), diskFile("B (Disc 2).chd"), diskFile("Solo.chd")
        )
        assertEquals(listOf("A.m3u", "B.m3u"), PlaylistPlanner.plan(files).map { it.fileName })
    }
}
