package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionExportTest {
    private val labels = ExportLabels("My games", "Console", "Game", "Files", "Size", "Folder", "Total", "Made on")
    private val games = listOf(
        ExportGame("SNES", "Zelda, A Link \"to\" the Past", 1, 1_048_576, "/ROMs/snes"),
        ExportGame("GBA", "<b>Tetris</b>", 2, 2048, "/ROMs/gba"),
        ExportGame("GBA", "Advance Wars", 1, 4_194_304, "/ROMs/gba")
    )

    @Test fun `csv is sorted, quoted and starts with a BOM`() {
        val lines = CollectionExport.csv(games, labels).removePrefix("﻿").trim().lines()
        assertEquals("Console,Game,Files,Size,Folder", lines[0])
        assertEquals("GBA,<b>Tetris</b>,2,2048,/ROMs/gba", lines[1])
        assertEquals("GBA,Advance Wars,1,4194304,/ROMs/gba", lines[2])
        assertEquals("SNES,\"Zelda, A Link \"\"to\"\" the Past\",1,1048576,/ROMs/snes", lines[3])
    }

    @Test fun `formula looking names cannot run in a spreadsheet`() {
        assertEquals("'=SUM(A1)", CollectionExport.cell("=SUM(A1)"))
        assertEquals("'@cmd", CollectionExport.cell("@cmd"))
        assertEquals("plain", CollectionExport.cell("plain"))
    }

    @Test fun `html escapes names and groups per console`() {
        val html = CollectionExport.html(games, labels, "2026-10-03")
        assertTrue(html.contains("&lt;b&gt;Tetris&lt;/b&gt;"))
        assertFalse(html.contains("<b>Tetris</b>"))
        assertTrue(html.contains("Zelda, A Link &quot;to&quot; the Past"))
        assertTrue(html.indexOf("GBA") < html.indexOf("SNES"))
        assertTrue(html.contains("Total: 3"))
        assertFalse(html.contains("<script"))
    }

    @Test fun `sizes are readable`() {
        assertEquals("512 B", CollectionExport.humanSize(512))
        assertEquals("2 KB", CollectionExport.humanSize(2048))
        assertEquals("4.0 MB", CollectionExport.humanSize(4_194_304))
        assertEquals("1.5 GB", CollectionExport.humanSize(1_610_612_736))
    }
}
