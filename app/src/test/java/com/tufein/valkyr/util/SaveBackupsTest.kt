package com.tufein.valkyr.util

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveBackupsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun copy(path: String, takenAt: Long, kind: SaveKind = SaveKind.SAVE, batch: String = "b$takenAt") =
        SafetyCopy(kind, path, "$batch/${kind.apiPath}/$path", takenAt, 100)

    // ---- Retention -------------------------------------------------------------------------------

    @Test fun `the newest copies of each save stay and the rest is the excess`() {
        val copies = (1..8).map { copy("mGBA/Game.srm", now - it * day) }
        val excess = SaveBackups.excess(copies, 5)
        assertEquals(3, excess.size)
        assertEquals(listOf(6L, 7L, 8L), excess.map { (now - it.takenAt) / day }.sorted())
    }

    @Test fun `every save file has its own newest copies`() {
        val a = (1..4).map { copy("mGBA/A.srm", now - it * day) }
        val b = (1..2).map { copy("mGBA/B.srm", now - it * day) }
        val states = (1..4).map { copy("mGBA/A.srm", now - it * day, SaveKind.STATE) }
        val excess = SaveBackups.excess(a + b + states, 3)
        assertEquals(2, excess.size)
        assertTrue(excess.any { it.kind == SaveKind.SAVE && it.path == "mGBA/A.srm" })
        assertTrue(excess.any { it.kind == SaveKind.STATE })
    }

    @Test fun `paths are grouped without regard to case`() {
        val copies = listOf(copy("mGBA/Game.srm", 1), copy("mgba/game.srm", 2), copy("MGBA/GAME.SRM", 3))
        assertEquals(listOf(1L), SaveBackups.excess(copies, 2).map { it.takenAt })
    }

    @Test fun `nothing is in excess within the limit`() {
        assertTrue(SaveBackups.excess((1..5).map { copy("a.srm", it.toLong()) }, 5).isEmpty())
        assertTrue(SaveBackups.excess(emptyList(), 5).isEmpty())
    }

    @Test fun `copies of the same second are told apart by their folder`() {
        val copies = listOf(copy("a.srm", 10, batch = "x1"), copy("a.srm", 10, batch = "x2"), copy("a.srm", 10, batch = "x3"))
        assertEquals(listOf("x1/saves/a.srm"), SaveBackups.excess(copies, 2).map { it.relative })
    }

    @Test fun `the limit is clamped`() {
        assertEquals(1, SaveBackups.clampKeep(0))
        assertEquals(50, SaveBackups.clampKeep(1000))
        // A limit of 0 never empties a save of all its backups.
        val copies = (1..3).map { copy("a.srm", it.toLong()) }
        assertEquals(2, SaveBackups.excess(copies, 0).size)
    }

    @Test fun `housekeeping only takes old copies beyond the newest ones`() {
        val old = (40..45).map { copy("a.srm", now - it * day) }
        val fresh = (1..2).map { copy("a.srm", now - it * day) }
        val moved = SaveBackups.expired(fresh + old, 5, now)
        // 8 copies, 5 stay (2 fresh + 3 newest old), the 3 oldest go - all of them older than 30 days.
        assertEquals(3, moved.size)
        assertTrue(moved.all { now - it.takenAt > 30 * day })
        // A save with few copies loses none, however old.
        assertTrue(SaveBackups.expired(old.take(3), 5, now).isEmpty())
        // Copies beyond the limit that are still recent stay for the sync (only "Prune now" takes them).
        assertTrue(SaveBackups.expired((1..9).map { copy("b.srm", now - it * day) }, 5, now).isEmpty())
    }

    @Test fun `total bytes add up`() {
        assertEquals(300L, SaveBackups.totalBytes((1..3).map { copy("a.srm", it.toLong()) }))
    }

    @Test fun `the setting steps through its values`() {
        assertEquals(6, SaveBackups.nextKeep(5, 1))
        assertEquals(4, SaveBackups.nextKeep(5, -1))
        assertEquals(1, SaveBackups.nextKeep(1, -1))
        assertEquals(15, SaveBackups.nextKeep(10, 1))
        assertEquals(10, SaveBackups.nextKeep(15, -1))
        assertEquals(50, SaveBackups.nextKeep(50, 1))
        assertEquals(15, SaveBackups.nextKeep(12, 1))
    }

    // ---- The recovery folder ---------------------------------------------------------------------

    private val roots = mutableListOf<File>()
    private fun dirs(): Pair<File, File> {
        val base = Files.createTempDirectory("save-backups-test").toFile().also { roots += it }
        return File(base, "save-backups").also { it.mkdirs() } to File(base, "save-backups-trash")
    }
    @After fun cleanUp() { roots.forEach { it.deleteRecursively() } }

    private fun write(root: File, copy: SafetyCopy, text: String = "bytes", owner: Boolean = false): SafetyCopy {
        val f = File(root, copy.relative)
        f.parentFile.mkdirs()
        f.writeText(text)
        File(f.path + ".sha256").writeText("0".repeat(64))
        if (owner) File(root, copy.relative.substringBefore('/') + "/owner.json").writeText("{}")
        return copy.copy(size = text.length.toLong())
    }

    @Test fun `moving to the recovery folder keeps the relative path and the receipt`() {
        val (root, trash) = dirs()
        val c = write(root, copy("mGBA/Game.srm", 5, batch = "20261003-120000-aaa"))
        val moved = SafetyCopyTrash(root, trash).move(listOf(c), now = 1000)
        assertEquals(1, moved)
        assertFalse(File(root, c.relative).exists())
        assertFalse(File(root, c.relative + ".sha256").exists())
        assertTrue(File(trash, "0000000001000/${c.relative}").isFile)
        assertTrue(File(trash, "0000000001000/${c.relative}.sha256").isFile)
        // The emptied batch folder is gone.
        assertFalse(File(root, "20261003-120000-aaa").exists())
    }

    @Test fun `undo brings the last move back`() {
        val (root, trash) = dirs()
        val t = SafetyCopyTrash(root, trash)
        val a = write(root, copy("a.srm", 1, batch = "b1"), "A", owner = true)
        val b = write(root, copy("b.srm", 2, batch = "b2"), "B")
        t.move(listOf(a, b), now = 5)
        assertEquals(2, t.lastCount())
        assertEquals(2, t.undoLast())
        assertEquals("A", File(root, a.relative).readText())
        assertEquals("B", File(root, b.relative).readText())
        assertTrue(File(root, "b1/owner.json").isFile)
        assertEquals(0, t.lastCount())
        assertFalse(File(trash, "0000000000005").exists())
    }

    @Test fun `the owner note follows only when nothing else of the batch stays`() {
        val (root, trash) = dirs()
        val one = write(root, copy("a.srm", 1, batch = "shared"), "A", owner = true)
        val two = write(root, copy("b.srm", 2, batch = "shared"), "B")
        val t = SafetyCopyTrash(root, trash)
        t.move(listOf(one), now = 5)
        assertTrue(File(root, "shared/owner.json").isFile)
        assertTrue(File(root, two.relative).isFile)
        t.move(listOf(two), now = 6)
        assertFalse(File(root, "shared").exists())
        assertTrue(File(trash, "0000000000006/shared/owner.json").isFile)
    }

    @Test fun `undo never overwrites a file that is back in place`() {
        val (root, trash) = dirs()
        val t = SafetyCopyTrash(root, trash)
        val a = write(root, copy("a.srm", 1, batch = "b1"), "old")
        t.move(listOf(a), now = 5)
        write(root, a, "new")
        assertEquals(0, t.undoLast())
        assertEquals("new", File(root, a.relative).readText())
        assertTrue(File(trash, "0000000000005/${a.relative}").isFile)
    }

    @Test fun `a copy that is not there or lies outside is not moved`() {
        val (root, trash) = dirs()
        val ghost = copy("a.srm", 1)
        val escape = ghost.copy(relative = "../outside/saves/a.srm")
        File(root.parentFile, "outside/saves").mkdirs()
        File(root.parentFile, "outside/saves/a.srm").writeText("x")
        assertEquals(0, SafetyCopyTrash(root, trash).move(listOf(ghost, escape), now = 1))
        assertTrue(File(root.parentFile, "outside/saves/a.srm").isFile)
        assertFalse(File(trash, "0000000000001").exists())
    }

    @Test fun `old moves are purged for good and new ones stay`() {
        val (root, trash) = dirs()
        val t = SafetyCopyTrash(root, trash)
        t.move(listOf(write(root, copy("a.srm", 1, batch = "b1"))), now = 1_000)
        t.move(listOf(write(root, copy("b.srm", 2, batch = "b2"))), now = 1_000 + SaveBackups.TRASH_KEEP_MS)
        assertEquals(1, t.purge(now = 1_000 + SaveBackups.TRASH_KEEP_MS + 10))
        assertEquals(1, t.lastCount())
        assertEquals(0, t.purge(now = 1_000 + SaveBackups.TRASH_KEEP_MS + 20))
    }

    @Test fun `an empty move does nothing`() {
        val (root, trash) = dirs()
        assertEquals(0, SafetyCopyTrash(root, trash).move(emptyList()))
        assertFalse(trash.exists())
        assertEquals(0, SafetyCopyTrash(root, trash).undoLast())
    }
}
