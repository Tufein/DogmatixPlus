package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GameJournalTest {
    private val key = JournalKey("parent", "gba", "Game.gba")
    private val image = JournalAttachment("image", JournalAttachmentKind.SCREENSHOT, "Screenshot.png", "image/png", "content://screenshots/document/one")
    @Test fun backupRestoresNotesButNeverReusesForeignDeviceUris() {
        val entry = JournalEntry(key, "Chapter 3: ♥", listOf(image), 123)
        assertEquals(entry, GameJournal.decode(GameJournal.encode(listOf(entry))).single())
        val imported = GameJournal.decode(GameJournal.encode(listOf(entry)), importing = true).single()
        assertEquals(entry.note, imported.note)
        assertEquals("Screenshot.png", imported.attachments.single().name)
        assertNull(imported.attachments.single().uri)
    }
    @Test fun sameGameRemainsSeparateForEveryProfileAndConsole() {
        val entries = listOf(JournalEntry(key, "parent"), JournalEntry(key.copy(profileId = "child"), "child"), JournalEntry(key.copy(consoleId = "nes"), "nes"))
        assertEquals(entries, GameJournal.decode(GameJournal.encode(entries)))
    }
    @Test fun conflictingRestoredNotesRemainAvailableAlongsideLocalWork() {
        val current = JournalEntry(key, "local note", listOf(image), 123)
        val imported = JournalEntry(key, "backup note", modifiedAt = 456)
        val merged = GameJournal.merge(listOf(current), listOf(imported)).single()
        assertEquals("local note", merged.note)
        assertEquals(listOf("backup note"), merged.recoveredNotes)
        assertEquals(listOf(image), merged.attachments)
        assertEquals(456L, merged.modifiedAt)
        assertEquals(merged, GameJournal.merge(listOf(merged), listOf(imported)).single())
    }
    @Test fun attachmentAndAlternativeLimitsRejectMergeWithoutTruncatingEitherSide() {
        val current = JournalEntry(key, "local", recoveredNotes = listOf("a", "b", "c", "d"))
        assertTrue(runCatching { GameJournal.merge(listOf(current), listOf(JournalEntry(key, "new backup"))) }.isFailure)
        assertEquals(4, current.recoveredNotes.size)
    }
    @Test fun restoredDifferentDescriptionWithSameIdIsRetainedAndImportIsIdempotent() {
        val original = JournalEntry(key, attachments = listOf(image))
        val restored = JournalEntry(key, attachments = listOf(image.copy(name = "Older screenshot.png", uri = null)))
        val merged = GameJournal.merge(listOf(original), listOf(restored)).single()
        assertEquals(listOf("Screenshot.png", "Older screenshot.png"), merged.attachments.map { it.name })
        assertEquals(merged, GameJournal.merge(listOf(merged), listOf(restored)).single())
    }
    @Test fun notesAndAttachmentCollectionsAreBoundedBeforeWriting() {
        assertTrue(runCatching { GameJournal.encode(listOf(JournalEntry(key, "x".repeat(GameJournal.MAX_NOTE + 1)))) }.isFailure)
        assertTrue(runCatching { GameJournal.encode(listOf(JournalEntry(key, attachments = (0..GameJournal.MAX_ATTACHMENTS).map { image.copy(id = it.toString()) }))) }.isFailure)
    }
    @Test fun localPathsWebLinksAndMalformedContentUrisAreNotAcceptedAsAttachments() {
        listOf("file:///storage/save.png", "https://example.invalid/image.png", "content:///image.png", "content://provider/one#fragment").forEach {
            assertFalse(it, GameJournal.validContentUri(it))
        }
        assertTrue(GameJournal.validContentUri("content://provider/document/one%20two"))
    }
    @Test fun invalidAttachmentMimeOrDuplicateIdentityIsRejected() {
        assertTrue(runCatching { GameJournal.encode(listOf(JournalEntry(key, attachments = listOf(image.copy(mime = "text/html"))))) }.isFailure)
        assertTrue(runCatching { GameJournal.encode(listOf(JournalEntry(key, attachments = listOf(image, image)))) }.isFailure)
    }
    @Test fun malformedBackupNeverBecomesAnEmptyJournal() {
        listOf("{}", "{\"version\":\"1\",\"entries\":[]}", "{\"version\":1,\"entries\":[{\"profile\":3}]}").forEach { raw ->
            assertTrue(runCatching { GameJournal.decode(JsonParser.parseString(raw)) }.isFailure)
        }
    }
    @Test fun failedAtomicReplacementRetainsThePreviousNotes() {
        val dir = Files.createTempDirectory("journal-test").toFile()
        try {
            val file = dir.resolve("journal.json")
            GameJournalStore(file).write(listOf(JournalEntry(key, "precious notes")))
            val before = file.readBytes()
            assertTrue(runCatching { GameJournalStore(file) { _, _ -> false }.write(listOf(JournalEntry(key, "replacement"))) }.isFailure)
            assertArrayEquals(before, file.readBytes())
            assertEquals("precious notes", GameJournalStore(file).read().single().note)
            assertEquals(listOf("journal.json"), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }
    @Test fun unreadableJournalIsReportedAndNeverSilentlyOverwritten() {
        val dir = Files.createTempDirectory("journal-corrupt").toFile()
        try {
            val file = dir.resolve("journal.json").apply { writeText("broken precious data") }
            assertTrue(runCatching { GameJournalStore(file).read() }.isFailure)
            assertEquals("broken precious data", file.readText())
        } finally { dir.deleteRecursively() }
    }
}
