package com.cortinadev.dogmatix

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.ArchiveExtractorService
import com.cortinadev.dogmatix.util.ArchiveSafetyException
import com.cortinadev.dogmatix.util.DownloadExtractionException
import com.cortinadev.dogmatix.util.StorageHelper
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real archive streams and SAF operations; every failure must keep the original archive. */
class ArchiveSafetyRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(block: suspend (Uri, DocumentFile) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        test.grantUriPermission(context.packageName, tree, flags)
        val name = "archive-safety-${UUID.randomUUID()}"
        try {
            val directory = StorageHelper.createDirectory(context, tree.toString(), name)!!
            block(tree, directory)
        } finally {
            for (method in listOf("fixture:fail_read", "fixture:fail_write", "fixture:fail_commit")) {
                context.contentResolver.call(tree, method, null, null)
            }
            StorageHelper.getDocumentFile(context, tree.toString())?.findFile(name)?.delete()
            test.revokeUriPermission(tree, flags)
        }
    }
    private suspend fun archive(vararg entries: Pair<String, String>, block: suspend (File) -> Unit) {
        val zip = File(context.cacheDir, "archive-safety-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                for ((name, body) in entries) {
                    val bytes = body.toByteArray()
                    val entry = ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry()
                }
            }
            block(zip)
        } finally { zip.delete() }
    }
    private suspend fun expectFailure(zip: File, dest: DocumentFile, path: String = ""): Throwable {
        try {
            ArchiveExtractorService().extractArchiveFile(context, zip, dest.uri, path, failOnError = true)
            error("Incomplete extraction must not report success")
        } catch (error: DownloadExtractionException) {
            assertTrue("The downloaded archive must survive", zip.exists())
            return error
        } catch (error: com.cortinadev.dogmatix.util.StorageException) {
            assertTrue(zip.exists())
            return error
        }
    }
    private fun reason(error: Throwable): ArchiveSafetyException.Reason? = generateSequence(error) { it.cause }.filterIsInstance<ArchiveSafetyException>().firstOrNull()?.reason

    @Test fun sameBasenameInDifferentDiscsKeepsBothContents() = runBlocking {
        fixture { _, dest -> archive("Disc 1/Track.bin" to "first-disc", "Disc 2/Track.bin" to "second-disc") { zip ->
            assertEquals(listOf("Disc 1/Track.bin", "Disc 2/Track.bin"), ArchiveExtractorService().extractArchiveFile(context, zip, dest.uri, failOnError = true))
            assertEquals("first-disc", StorageHelper.readText(context, StorageHelper.findFile(dest, "Disc 1/Track.bin")!!))
            assertEquals("second-disc", StorageHelper.readText(context, StorageHelper.findFile(dest, "Disc 2/Track.bin")!!))
            assertNull(dest.findFile("Track.bin"))
        } }
    }
    @Test fun consoleSubpathContainsTheFilesAndDoesNotWriteIntoItsParent() = runBlocking {
        fixture { tree, dest -> archive("Game.gba" to "console-game") { zip ->
            assertEquals(listOf("Game.gba"), ArchiveExtractorService().extractArchiveFile(context, zip, dest.uri, "gba", failOnError = true))
            assertNull(dest.findFile("Game.gba"))
            val console = dest.findFile("gba")!!
            assertTrue(console.isDirectory)
            assertEquals("console-game", StorageHelper.readText(context, console.findFile("Game.gba")!!))
            assertEquals(listOf("gba"), dest.listFiles().map { it.name })
            assertNull(StorageHelper.getDocumentFile(context, tree.toString())!!.findFile("Game.gba"))
        } }
    }
    @Test fun selectedChildDocumentUriRemainsTheExtractionDestination() = runBlocking {
        fixture { tree, dest ->
            val selected = dest.createDirectory("selected")!!
            archive("Selected.gba" to "selected-game") { zip ->
                assertEquals(listOf("Selected.gba"), ArchiveExtractorService().extractArchiveFile(context, zip, selected.uri, failOnError = true))
                assertEquals("selected-game", StorageHelper.readText(context, selected.findFile("Selected.gba")!!))
                assertNull(dest.findFile("Selected.gba"))
                assertNull(StorageHelper.getDocumentFile(context, tree.toString())!!.findFile("Selected.gba"))
                assertEquals(listOf("selected"), dest.listFiles().map { it.name })
            }
        }
    }
    @Test fun vanishedSelectedChildDocumentCannotWriteIntoItsGrantedParent() = runBlocking {
        fixture { tree, dest ->
            val selected = dest.createDirectory("selected")!!
            val selectedUri = selected.uri
            assertTrue(selected.delete())
            archive("Selected.gba" to "must-not-leak") { zip ->
                try {
                    ArchiveExtractorService().extractArchiveFile(context, zip, selectedUri, failOnError = true)
                    fail("Missing selected child must reject extraction")
                } catch (_: Exception) {
                    assertTrue(zip.exists())
                    assertTrue(dest.listFiles().isEmpty())
                    assertNull(StorageHelper.getDocumentFile(context, tree.toString())!!.findFile("Selected.gba"))
                }
            }
        }
    }
    @Test fun traversalIsRejectedBeforeAnyDestinationFileIsCreated() = runBlocking {
        fixture { _, dest -> archive("ok.bin" to "good", "../outside.bin" to "bad") { zip ->
            assertEquals(ArchiveSafetyException.Reason.UNSAFE_PATH, reason(expectFailure(zip, dest)))
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
    @Test fun sanitizedNamesCannotSilentlyOverwriteEachOther() = runBlocking {
        fixture { _, dest -> archive("Game?.bin" to "first", "Game*.bin" to "second") { zip ->
            assertEquals(ArchiveSafetyException.Reason.NAME_COLLISION, reason(expectFailure(zip, dest)))
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
    @Test fun unavailableConsoleDirectoryNeverFallsBackToRoot() = runBlocking {
        fixture { _, dest ->
            StorageHelper.writeBytesSafely(context, dest, "", "blocked", "existing-file".toByteArray())
            archive("Game.gba" to "game") { zip ->
                assertEquals(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE, reason(expectFailure(zip, dest, "blocked")))
                assertNull(dest.findFile("Game.gba"))
                assertEquals("existing-file", StorageHelper.readText(context, dest.findFile("blocked")!!))
            }
        }
    }
    @Test fun conflictInTheLastEntryPreventsAllDestinationWrites() = runBlocking {
        fixture { _, dest ->
            StorageHelper.writeBytesSafely(context, dest, "", "Last.bin", "original".toByteArray())
            archive("First.bin" to "first", "Last.bin" to "different") { zip ->
                assertEquals(ArchiveSafetyException.Reason.DESTINATION_CONFLICT, reason(expectFailure(zip, dest)))
                assertNull(dest.findFile("First.bin"))
                assertEquals("original", StorageHelper.readText(context, dest.findFile("Last.bin")!!))
                assertEquals(listOf("Last.bin"), dest.listFiles().map { it.name })
            }
        }
    }
    @Test fun identicalFilesAreReusedSoInterruptedExtractionsCanBeRetried() = runBlocking {
        fixture { _, dest ->
            val original = StorageHelper.writeBytesSafely(context, dest, "Disc", "First.bin", "same".toByteArray())
            archive("Disc/First.bin" to "same", "Disc/Second.bin" to "second") { zip ->
                assertEquals(listOf("Disc/First.bin", "Disc/Second.bin"), ArchiveExtractorService().extractArchiveFile(context, zip, dest.uri, failOnError = true))
                assertEquals(original.uri, StorageHelper.findFile(dest, "Disc/First.bin")!!.uri)
                assertEquals("same", StorageHelper.readText(context, original))
                assertEquals("second", StorageHelper.readText(context, StorageHelper.findFile(dest, "Disc/Second.bin")!!))
            }
        }
    }
    @Test fun latePublishFailureRollsBackEarlierNewFiles() = runBlocking {
        fixture { tree, dest -> archive("First.bin" to "first", "Last.bin" to "last") { zip ->
            context.contentResolver.call(tree, "fixture:fail_commit", "Last.bin", null)
            expectFailure(zip, dest)
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
    @Test fun stagingWriteFailureCannotPublishTheFirstFile() = runBlocking {
        fixture { tree, dest -> archive("First.bin" to "first", "Last.bin" to "last") { zip ->
            context.contentResolver.call(tree, "fixture:fail_write", ".dogmatix-extract-*", null)
            expectFailure(zip, dest)
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
    @Test fun corruptedEntryNeverCopiesOtherSuccessfulEntriesToStorage() = runBlocking {
        fixture { _, dest -> archive("First.bin" to "first-data", "Last.bin" to "unique-corrupted-payload") { zip ->
            val bytes = zip.readBytes()
            val pattern = "unique-corrupted-payload".toByteArray()
            val offset = bytes.indices.first { index -> index + pattern.size <= bytes.size && pattern.indices.all { bytes[index + it] == pattern[it] } }
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            zip.writeBytes(bytes)
            expectFailure(zip, dest)
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
    @Test fun cancellationDuringCacheExtractionCannotPublishFiles() = runBlocking {
        fixture { _, dest -> archive("First.bin" to "first", "Last.bin" to "last") { zip ->
            val task = launch {
                val job = currentCoroutineContext()[Job]!!
                ArchiveExtractorService().extractArchiveFile(context, zip, dest.uri, failOnError = true, onProgress = { job.cancel() })
            }
            task.join()
            assertTrue(task.isCancelled)
            assertTrue(zip.exists())
            assertTrue(dest.listFiles().isEmpty())
        } }
    }
}
