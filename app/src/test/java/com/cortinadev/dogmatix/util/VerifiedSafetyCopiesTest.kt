package com.cortinadev.dogmatix.util

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedSafetyCopiesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "save-backups")

    @Test fun `copies within the same second retain both versions and can be restored`() {
        val first = VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "mGBA/Game.srm", "first".toByteArray(), 1_700_000_000_000)
        val second = VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "mGBA/Game.srm", "second".toByteArray(), 1_700_000_000_000)
        assertNotEquals(first, second)
        assertEquals("first", VerifiedSafetyCopies.read(root, first.relativeTo(root).path, 100).toString(Charsets.UTF_8))
        assertEquals("second", VerifiedSafetyCopies.read(root, second.relativeTo(root).path, 100).toString(Charsets.UTF_8))
        assertTrue(File(first.path + ".sha256").isFile)
        val listed = root.walkTopDown().filter { it.isFile }.mapNotNull {
            CloudSaves.safetyCopy(it.relativeTo(root).invariantSeparatorsPath, it.length(), it.lastModified(), java.time.ZoneId.of("UTC"))
        }.toList()
        assertEquals(2, listed.size)
    }

    @Test fun `same-size corruption is detected before a restore can read the contents`() {
        val target = VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "Game.srm", "first".toByteArray())
        target.writeText("wrong")
        expectIntegrityFailure { VerifiedSafetyCopies.read(root, target.relativeTo(root).path, 100) }
    }

    @Test fun `a missing checksum of a new safety copy is not treated as a legacy copy`() {
        val target = VerifiedSafetyCopies.keep(root, SaveKind.STATE, "Game.state", "save".toByteArray())
        assertTrue(File(target.path + ".sha256").delete())
        expectIntegrityFailure { VerifiedSafetyCopies.read(root, target.relativeTo(root).path, 100) }
    }

    @Test fun `existing safety copies made before verification remain readable`() {
        val target = File(root, "20261003-120000-2/saves/Game.srm")
        target.parentFile!!.mkdirs()
        target.writeText("legacy save")
        assertEquals("legacy save", VerifiedSafetyCopies.read(root, target.relativeTo(root).path, 100).toString(Charsets.UTF_8))
    }

    @Test fun `a blocked backup folder fails without changing any existing file`() {
        root.writeText("a file blocks the directory")
        expectIoFailure { VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "Game.srm", "new".toByteArray()) }
        assertEquals("a file blocks the directory", root.readText())
    }

    @Test fun `traversal and oversized restoration are refused`() {
        expectIoFailure { VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "../Game.srm", "new".toByteArray()) }
        val target = VerifiedSafetyCopies.keep(root, SaveKind.SAVE, "Game.srm", "new".toByteArray())
        expectIoFailure { VerifiedSafetyCopies.read(root, target.relativeTo(root).path, 2) }
        expectIoFailure { VerifiedSafetyCopies.read(root, "../../outside.srm", 100) }
    }

    private fun expectIntegrityFailure(block: () -> Unit) {
        try { block(); fail("Corrupted copy was accepted") } catch (_: VerifiedSafetyCopies.IntegrityException) { }
    }
    private fun expectIoFailure(block: () -> Unit) {
        try { block(); fail("Unsafe operation was accepted") } catch (_: IOException) { }
    }
}
