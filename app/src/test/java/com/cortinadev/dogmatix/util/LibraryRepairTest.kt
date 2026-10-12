package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepairTest {
    private val now = 10L * 24 * 60 * 60 * 1000
    private val old = now - 3L * 60 * 60 * 1000

    private fun file(name: String, size: Long, console: String? = "psx", dir: String = "psx", mod: Long = old) = DiskFile(
        scope = dir, consoleId = console, folder = dir, name = name, size = size, uri = "uri:$dir/$name", dirId = dir,
        dirUri = "dir:$dir", rootId = "r", lastModified = mod
    )

    private fun kinds(input: RepairInput) = LibraryRepair.check(input.copy(now = now)).findings.map { it.kind }

    @Test fun emptyGameFileIsFoundButNotOutsideAConsoleFolder() {
        assertTrue(RepairKind.EMPTY_FILE in kinds(RepairInput(listOf(file("Game.chd", 0)))))
        assertFalse(RepairKind.EMPTY_FILE in kinds(RepairInput(listOf(file("Game.chd", 0, console = null, dir = "")))))
    }

    @Test fun foreignPartsOfOtherAppsAreOnlyListedInsideConsoleFolders() {
        val loose = file("Movie.mkv.crdownload", 5_000, console = null, dir = "")
        assertFalse(RepairKind.FOREIGN_PART in kinds(RepairInput(listOf(loose))))
        val inFolder = file("Game.chd.crdownload", 5_000)
        assertTrue(RepairKind.FOREIGN_PART in kinds(RepairInput(listOf(inFolder))))
        // A generic temporary file is not a download part.
        assertFalse(RepairKind.FOREIGN_PART in kinds(RepairInput(listOf(file("notes.tmp", 10)))))
    }

    @Test fun freshForeignPartIsLeftAlone() {
        assertFalse(RepairKind.FOREIGN_PART in kinds(RepairInput(listOf(file("Game.chd.crdownload", 5_000, mod = now - 1_000)))))
    }

    @Test fun playlistIsNotJudgedWhileASheetInItsFolderWasNotRead() {
        val m3u = file("Game.m3u", 40)
        val cue1 = file("Game (Disc 1).cue", 100)
        val cue2 = file("Game (Disc 2).cue", 100)
        val files = listOf(m3u, cue1, cue2)
        val text = "Game (Disc 1).cue\nGame (Disc 2).cue\n"
        // Both sheets read: complete set, nothing to report.
        val all = RepairInput(files, sheets = mapOf(m3u.fileId to text, cue1.fileId to "", cue2.fileId to ""))
        assertFalse(RepairKind.DEAD_PLAYLIST in kinds(all))
        // One sheet was not read (over the cap): its disc must not count as missing.
        val partial = RepairInput(files, sheets = mapOf(m3u.fileId to text, cue1.fileId to ""))
        val found = kinds(partial)
        assertFalse(RepairKind.DEAD_PLAYLIST in found)
        assertFalse(RepairKind.MISSING_DISCS in found)
    }

    @Test fun trashFixesNeverTouchLooseFiles() {
        val result = LibraryRepair.check(RepairInput(listOf(file("Movie.mkv.crdownload", 5_000, console = null, dir = "")), now = now))
        assertEquals(emptyList<RepairFinding>(), result.findings)
    }
}
