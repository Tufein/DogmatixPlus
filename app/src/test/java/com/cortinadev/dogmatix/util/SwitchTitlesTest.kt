package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchTitlesTest {
    @Test fun `title ids give the kind, the base game and the update version`() {
        val base = SwitchTitles.parse("The Legend of Zelda BotW [01007EF00011E000][v0].nsp")!!
        assertEquals(SwitchTitles.Kind.BASE, base.kind)
        assertEquals("01007EF00011E000", base.baseId)
        val update = SwitchTitles.parse("The Legend of Zelda BotW [01007EF00011E800][v786432].nsp")!!
        assertEquals(SwitchTitles.Kind.UPDATE, update.kind)
        assertEquals("01007EF00011E000", update.baseId)
        assertEquals(12L, update.release)
        val dlc = SwitchTitles.parse("Zelda BotW Expansion Pass (01007ef00011f001) (v0).nsz")!!
        assertEquals(SwitchTitles.Kind.DLC, dlc.kind)
        assertEquals("01007EF00011E000", dlc.baseId)
    }

    @Test fun `names without a title id or with a strange one are not switch titles`() {
        assertNull(SwitchTitles.parse("Super Mario Odyssey (USA).xci"))
        assertNull(SwitchTitles.parse("Game [0100000000010400].nsp"))
        assertTrue(SwitchTitles.isSwitchFile("a.XCZ"))
        assertFalse(SwitchTitles.isSwitchFile("a.zip"))
    }

    @Test fun `analysis finds the newer update and the missing dlc of games on disk`() {
        val rows = listOf(
            "Game A [0100AAAA00000000][v0].nsp", "Game A [0100AAAA00000800][v131072].nsp", "Game A [0100AAAA00000800][v65536].nsp",
            "Game A DLC 1 [0100AAAA00001001][v0].nsp", "Game A DLC 2 [0100AAAA00001002][v0].nsp",
            "Game B [0100BBBB00000000][v0].nsp", "Game B [0100BBBB00000800][v65536].nsp"
        )
        val owned = listOf("game a [0100aaaa00000000][v0].nsp", "game a [0100aaaa00000800][v65536].nsp", "game a dlc 1 [0100aaaa00001001][v0]")
        val result = SwitchTitles.analyse(rows, { it }, owned)
        assertEquals(1, result.size)
        val a = result.single()
        assertTrue(a.updateAvailable)
        assertEquals(65536L, a.ownedUpdate)
        assertEquals("Game A [0100AAAA00000800][v131072].nsp", a.newestUpdate!!.first)
        assertEquals(listOf("Game A DLC 2 [0100AAAA00001002][v0].nsp"), a.missingDlc)
        assertEquals(2, a.dlcInLibrary)
        assertEquals(1, a.dlcOwned)
        assertEquals(2, SwitchTitles.analyse(rows, { it }, owned, onlyOwned = false).size)
    }

    @Test fun `a game with the newest update and all dlc has nothing to do`() {
        val rows = listOf("G [0100CCCC00000000][v0].nsp", "G [0100CCCC00000800][v65536].nsp")
        val owned = listOf("G [0100CCCC00000000][v0].nsp", "G [0100CCCC00000800][v65536].nsp")
        assertFalse(SwitchTitles.analyse(rows, { it }, owned).single().hasWork)
    }
}
