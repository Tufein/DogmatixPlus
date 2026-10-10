package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.GamePackageService
import com.cortinadev.dogmatix.util.GamePackages
import com.cortinadev.dogmatix.util.StorageHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class GamePackageStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, GamePackagesEntryPoint::class.java)
    private suspend fun withLibraryRoots(block: suspend () -> Unit) {
        val settings = graph.packagesSettings()
        val oldRoot = settings.downloadDirectory.first()
        val oldCustom = settings.consoleDownloadDirectories.first()
        try {
            oldCustom.keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            block()
        } finally {
            settings.consoleDownloadDirectories.first().keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            oldCustom.forEach { (console, uri) -> settings.updateConsoleDownloadDirectory(console, uri) }
            settings.updateDownloadDirectory(oldRoot)
            graph.packagesLibrary().refresh()
        }
    }
    private suspend fun fixture(block: suspend (DocumentFile, DownloadableFileEntity, GamePackageService) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        test.grantUriPermission(context.packageName, tree, flags)
        val root = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "package-${UUID.randomUUID()}"))
        val file = DownloadableFileEntity(name = "Game", fileName = "DifferentArchive.zip", consoleId = "sony_playstation",
            downloadUrl = "https://package.invalid/${UUID.randomUUID()}.zip", sourceUrl = "https://package.invalid/")
        val service = GamePackageService(context)
        try {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            block(root, file, service)
        } finally {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            val receipt = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), root.uri.toString())}.json")
            listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).forEach { it.delete() }
            val marker = File(context.filesDir, "game-packages/${service.identity(file)}.tracked")
            listOf(marker, File(marker.path + ".bak"), File(marker.path + ".new")).forEach { it.delete() }
            root.delete()
            test.revokeUriPermission(tree, flags)
        }
    }
    private fun write(root: DocumentFile, path: String, value: String): DocumentFile = StorageHelper.writeBytesSafely(context,
        root, path.substringBeforeLast('/', ""), path.substringAfterLast('/'), value.toByteArray())

    @Test fun independentServiceAfterRestartResolvesExactNestedFilesAndRestoredQueueIdentity() = runBlocking {
        fixture { root, file, service ->
            write(root, "psx/Disc 1/Game.cue", "FILE \"Track.bin\" BINARY")
            write(root, "psx/Disc 1/Track.bin", "first-track")
            write(root, "psx/Disc 2/Game.cue", "FILE \"Track.bin\" BINARY")
            write(root, "psx/Disc 2/Track.bin", "second-track")
            val paths = listOf("Disc 1/Game.cue", "Disc 1/Track.bin", "Disc 2/Game.cue", "Disc 2/Track.bin")
            val original = service.record(file, root.uri.toString(), "psx", paths)
            val fresh = GamePackageService(context)
            val restored = DownloadHistoryEntity(file.fileName, file.name, file.consoleId, file.downloadUrl,
                10, "zip", null, null, "COMPLETED", 1, 2).toEntity()
            val receipt = requireNotNull(fresh.recordFor(restored, setOf(root.uri.toString())))
            assertEquals(original, receipt)
            val inspection = fresh.inspect(receipt, true)
            assertTrue(inspection.complete)
            assertEquals(paths.toSet(), inspection.files.map { it.path }.toSet())
            assertEquals(11L, receipt.parts.first { it.path == "Disc 1/Track.bin" }.bytes)
            assertNull(fresh.recordFor(file.copy(downloadUrl = "https://another.invalid/Game.zip"), setOf(root.uri.toString())))
            assertNull(fresh.recordFor(file.copy(consoleId = "sega_saturn"), setOf(root.uri.toString())))
            assertTrue(fresh.records(setOf("content://another/tree/root")).isEmpty())
        }
    }

    @Test fun sameSizeChangesAndReadFailuresAreReportedWithoutWritingAnyGameBytes() = runBlocking {
        fixture { root, file, service ->
            val original = write(root, "Game.gba", "first-track")
            val receipt = service.record(file, root.uri.toString(), "", listOf("Game.gba"))
            context.contentResolver.openOutputStream(original.uri, "wt")!!.use { it.write("other-track".toByteArray()) }
            assertTrue(service.inspect(receipt).complete)
            assertEquals(listOf("Game.gba"), service.inspect(receipt, true).changed)
            assertEquals("other-track", StorageHelper.readText(context, original))
            context.contentResolver.call(tree, "fixture:fail_read", "Game.gba", null)
            assertEquals(listOf("Game.gba"), service.inspect(receipt, true).changed)
            context.contentResolver.call(tree, "fixture:fail_read", null, null)
            assertEquals("other-track", StorageHelper.readText(context, original))
        }
    }

    @Test fun temporarilyMissingRootDoesNotDiscardDurableReceipt() = runBlocking {
        fixture { root, file, service ->
            write(root, "Disc/Game.chd", "disc")
            val receipt = service.record(file, root.uri.toString(), "", listOf("Disc/Game.chd"))
            assertTrue(root.delete())
            val fresh = GamePackageService(context)
            assertEquals(receipt, fresh.recordFor(file, setOf(root.uri.toString())))
            val inspection = fresh.inspect(receipt, true)
            assertFalse(inspection.complete)
            assertEquals(listOf("Disc/Game.chd"), inspection.missing)
            assertEquals(receipt, fresh.recordFor(file, setOf(root.uri.toString())))
        }
    }

    @Test fun generatedPlaylistUpdatesHashesAndOnlyJoinsItsOriginalPackageScope() = runBlocking {
        fixture { root, file, service ->
            write(root, "psx/Disc/Game.chd", "disc")
            service.record(file, root.uri.toString(), "psx", listOf("Disc/Game.chd"))
            write(root, "psx/Game.m3u", "Disc/Game.chd")
            write(root, "psx/Unrelated.chd", "another game")
            val updated = service.includeGenerated(file, root.uri.toString(), "psx", listOf("Game.m3u"))
            assertEquals(setOf("Disc/Game.chd", "Game.m3u"), updated.parts.map { it.path }.toSet())
            assertTrue(GamePackageService(context).inspect(updated, true).complete)
            assertTrue(runCatching { service.includeGenerated(file, root.uri.toString(), "another", listOf("Game.m3u")) }.isFailure)
            assertEquals(updated, service.recordFor(file, setOf(root.uri.toString())))
        }
    }

    @Test fun invalidOrUnavailablePathsCannotReplaceAnExistingReceipt() = runBlocking {
        fixture { root, file, service ->
            write(root, "Game.gba", "game")
            val receipt = service.record(file, root.uri.toString(), "", listOf("Game.gba"))
            listOf(listOf("../Game.gba"), listOf("Missing.gba"), listOf("Game.gba", "game.gba")).forEach { paths ->
                assertTrue(runCatching { service.record(file, root.uri.toString(), "", paths) }.isFailure)
                assertEquals(receipt, service.recordFor(file, setOf(root.uri.toString())))
            }
            assertTrue(runCatching { service.record(file.copy(downloadUrl = "relative/Game.zip"), root.uri.toString(), "", listOf("Game.gba")) }.isFailure)
            assertEquals("game", StorageHelper.readText(context, root.findFile("Game.gba")!!))
        }
    }

    @Test fun exactSourceReceiptSurvivesFullLibraryRefreshAndNeverWidensRemoval() = runBlocking {
        fixture { root, file, service -> withLibraryRoots {
            val paths = listOf("Disc 1/Game.cue", "Disc 1/Track.bin", "Disc 2/Game.cue", "Disc 2/Track.bin")
            write(root, paths[0], "FILE \"Track.bin\" BINARY")
            write(root, paths[1], "first-track")
            write(root, paths[2], "FILE \"Track.bin\" BINARY")
            write(root, paths[3], "second-track")
            service.record(file, root.uri.toString(), "", paths)
            graph.packagesSettings().updateDownloadDirectory(root.uri.toString())
            graph.packagesLibrary().refresh()
            assertTrue(graph.packagesLibrary().isOwned(file))
            assertFalse(graph.packagesLibrary().isOwned(file.copy(downloadUrl = "https://another.invalid/Game.zip")))
            assertEquals(paths.toSet(), graph.packagesLibrary().launchPlan(file).map { it.path }.toSet())
            assertTrue(graph.packagesLibrary().removalPlan(file).isEmpty())
            val ready = graph.packageReadiness().check(file, true)
            assertTrue(ready.filesOk)
            assertTrue(ready.discsOk)
            assertTrue(ready.packageInspection!!.hashesChecked)
            assertTrue(ready.packageInspection!!.complete)
        } }
    }

    @Test fun anotherConsolesCustomRootCannotActivateAnOldPackageReceipt() = runBlocking {
        fixture { root, file, service -> withLibraryRoots {
            write(root, "Local.chd", "disc")
            service.record(file, root.uri.toString(), "", listOf("Local.chd"))
            graph.packagesSettings().updateDownloadDirectory("")
            graph.packagesSettings().updateConsoleDownloadDirectory("sega_saturn", root.uri.toString())
            graph.packagesLibrary().refresh()
            assertFalse(graph.packagesLibrary().isOwned(file))
            assertNull(graph.packagesLibrary().packageInspection(file))
            assertNotNull(service.recordFor(file, setOf(root.uri.toString())))
        } }
    }

    @Test fun exactLookupIgnoresAnUnrelatedCorruptReceiptAndRecoversAnInterruptedAtomicWrite() = runBlocking {
        fixture { root, file, service ->
            write(root, "Game.gba", "game")
            val original = service.record(file, root.uri.toString(), "", listOf("Game.gba"))
            val folder = File(context.filesDir, "game-packages")
            val unrelated = File(folder, GamePackages.receiptKey(UUID.randomUUID().toString(), root.uri.toString()) + ".json")
            val expected = File(folder, GamePackages.receiptKey(service.identity(file), root.uri.toString()) + ".json")
            try {
                unrelated.writeText("{broken private receipt")
                File(expected.path + ".bak").writeBytes(GamePackages.encode(original))
                expected.writeText("{interrupted write")
                assertEquals(original, GamePackageService(context).recordFor(file, setOf(root.uri.toString())))
                assertTrue(GamePackageService(context).inspect(original, true).complete)
                assertTrue(unrelated.exists())
            } finally { unrelated.delete() }
        }
    }

    @Test fun batchInspectionsKeepCaseSensitiveDocumentFoldersSeparate() = runBlocking {
        fixture { root, file, service ->
            val upper = write(root, "Games/Game.gba", "upper-game")
            val lower = write(root, "games/Game.gba", "lower-game")
            val one = service.record(file, root.uri.toString(), "Games", listOf("Game.gba"))
            val secondFile = file.copy(downloadUrl = file.downloadUrl + "?other")
            try {
                val two = service.record(secondFile, root.uri.toString(), "games", listOf("Game.gba"))
                val inspected = service.inspectBatch(listOf(one, two))
                assertTrue(inspected.all { it.complete })
                assertEquals(upper.uri.toString(), inspected[0].files.single().uri)
                assertEquals(lower.uri.toString(), inspected[1].files.single().uri)
            } finally {
                val target = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(secondFile), root.uri.toString())}.json")
                listOf(target, File(target.path + ".bak"), File(target.path + ".new")).forEach { it.delete() }
                val marker = File(context.filesDir, "game-packages/${service.identity(secondFile)}.tracked")
                listOf(marker, File(marker.path + ".bak"), File(marker.path + ".new")).forEach { it.delete() }
            }
        }
    }

    @Test fun trackedGameNeverFallsBackToSameNamedFilesAfterReceiptLossOrRootChange() = runBlocking {
        fixture { root, file, service -> withLibraryRoots {
            write(root, file.fileName, "original bytes")
            service.record(file, root.uri.toString(), "", listOf(file.fileName))
            val settings = graph.packagesSettings()
            val library = graph.packagesLibrary()
            settings.updateDownloadDirectory(root.uri.toString())
            library.refresh()
            assertTrue(library.isOwned(file))
            assertTrue(library.launchPlan(file).isNotEmpty())
            val receipt = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), root.uri.toString())}.json")
            receipt.writeText("{corrupt receipt")
            library.refresh()
            assertFalse(library.isOwned(file))
            assertTrue(library.launchPlan(file).isEmpty())
            receipt.delete()
            val other = requireNotNull(root.createDirectory("different-root"))
            write(other, file.fileName, "unrelated game")
            settings.updateDownloadDirectory(other.uri.toString())
            library.refresh()
            assertFalse(library.isOwned(file))
            assertTrue(library.launchPlan(file).isEmpty())
        } }
    }
    @Test fun trackingMarkerSurvivesMissingAndCorruptReceiptsButGrantsNoFiles() = runBlocking {
        fixture { root, file, service ->
            assertFalse(service.hasRecordedIdentity(file, setOf(root.uri.toString())))
            write(root, "Game.gba", "game")
            service.record(file, root.uri.toString(), "", listOf("Game.gba"))
            val target = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), root.uri.toString())}.json")
            target.writeText("{corrupt receipt")
            assertTrue(GamePackageService(context).hasRecordedIdentity(file, setOf(root.uri.toString())))
            assertNull(GamePackageService(context).recordFor(file, setOf(root.uri.toString())))
            assertTrue(target.delete())
            assertTrue(GamePackageService(context).hasRecordedIdentity(file, emptySet()))
            assertNull(GamePackageService(context).recordFor(file, setOf(root.uri.toString())))
            assertFalse(service.hasRecordedIdentity(file.copy(downloadUrl = "https://another.invalid/Game.zip"), setOf(root.uri.toString())))
        }
    }

    @Test fun existingReceiptEvenWithoutMarkerPreventsLegacyFallbackAndCorruptBackupGrantsNothing() = runBlocking {
        fixture { root, file, service ->
            val target = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), root.uri.toString())}.json")
            target.parentFile!!.mkdirs()
            val backup = File(target.path + ".bak")
            backup.writeText("{interrupted corrupt write")
            assertTrue(service.hasRecordedIdentity(file, setOf(root.uri.toString())))
            assertNull(service.recordFor(file, setOf(root.uri.toString())))
            assertTrue(service.hasRecordedIdentity(file, setOf(root.uri.toString())))
        }
    }

    @Test fun verifiedRebaseRetainsOriginalAndMovesNestedPackageIdentityToNewRoot() = runBlocking {
        fixture { root, file, service ->
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            try {
                write(source, "psx/Disc/Track.bin", "disc bytes")
                write(target, "psx/Disc/Track.bin", "disc bytes")
                val original = service.record(file, source.uri.toString(), "psx", listOf("Disc/Track.bin"))
                val rebased = service.rebase(original, target.uri.toString(), "psx")
                assertEquals(original, service.recordFor(file, setOf(source.uri.toString())))
                assertEquals(rebased, GamePackageService(context).recordFor(file, setOf(target.uri.toString())))
                assertTrue(service.inspect(rebased, true).complete)
                assertEquals(rebased, service.rebase(original, target.uri.toString(), "psx"))
            } finally { listOf(source, target).forEach { directory -> removeReceipt(service, file, directory.uri.toString()) } }
        }
    }

    @Test fun changedOrMissingRebaseTargetNeverOverwritesAnExistingReceipt() = runBlocking {
        fixture { root, file, service ->
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            try {
                write(source, "Game.gba", "original")
                write(target, "Game.gba", "modified")
                val original = service.record(file, source.uri.toString(), "", listOf("Game.gba"))
                val existing = service.record(file, target.uri.toString(), "", listOf("Game.gba"))
                assertTrue(runCatching { service.rebase(original, target.uri.toString(), "") }.isFailure)
                assertEquals(existing, service.recordFor(file, setOf(target.uri.toString())))
                assertEquals("modified", StorageHelper.readText(context, target.findFile("Game.gba")!!))
                target.findFile("Game.gba")!!.delete()
                assertTrue(runCatching { service.rebase(original, target.uri.toString(), "") }.isFailure)
                assertEquals(existing, service.recordFor(file, setOf(target.uri.toString())))
            } finally { listOf(source, target).forEach { directory -> removeReceipt(service, file, directory.uri.toString()) } }
        }
    }

    @Test fun smartMovePathMappingRebasesOnlySelectedConsoleAndKeepsRelativeDiscLayout() = runBlocking {
        fixture { root, file, service ->
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            val other = file.copy(consoleId = "sega_saturn", downloadUrl = file.downloadUrl + "?saturn")
            try {
                val cue = write(source, "psx/Game/Disc.cue", "FILE \"Track.bin\" BINARY")
                val track = write(source, "psx/Game/Track.bin", "track")
                write(source, "saturn/Other.chd", "other disc")
                write(target, "Game/Disc.cue", "FILE \"Track.bin\" BINARY")
                write(target, "Game/Track.bin", "track")
                val original = service.record(file, source.uri.toString(), "psx/Game", listOf("Disc.cue", "Track.bin"))
                service.record(other, source.uri.toString(), "saturn", listOf("Other.chd"))
                assertEquals(1, service.rebaseMovedFiles(setOf(source.uri.toString()),
                    mapOf(cue.uri.toString() to "Game/Disc.cue", track.uri.toString() to "Game/Track.bin"), target.uri.toString(), consoleId = file.consoleId))
                val rebased = requireNotNull(service.recordFor(file, setOf(target.uri.toString())))
                assertEquals("Game", rebased.subPath)
                assertTrue(service.inspect(rebased, true).complete)
                assertEquals(original, service.recordFor(file, setOf(source.uri.toString())))
                assertNull(service.recordFor(other, setOf(target.uri.toString())))
            } finally {
                listOf(source, target).forEach { directory ->
                    removeReceipt(service, file, directory.uri.toString())
                    removeReceipt(service, other, directory.uri.toString())
                }
                File(context.filesDir, "game-packages/${service.identity(other)}.tracked").delete()
            }
        }
    }

    @Test fun partialPackageMoveRefusesBeforeWritingTargetOwnership() = runBlocking {
        fixture { root, file, service ->
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            try {
                val one = write(source, "Disc 1/Game.chd", "first")
                write(source, "Disc 2/Game.chd", "second")
                write(target, "Disc 1/Game.chd", "first")
                service.record(file, source.uri.toString(), "", listOf("Disc 1/Game.chd", "Disc 2/Game.chd"))
                assertTrue(runCatching { service.rebaseMovedFiles(setOf(source.uri.toString()),
                    mapOf(one.uri.toString() to "Disc 1/Game.chd"), target.uri.toString()) }.isFailure)
                assertNull(service.recordFor(file, setOf(target.uri.toString())))
                assertEquals("first", StorageHelper.readText(context, one))
            } finally { listOf(source, target).forEach { directory -> removeReceipt(service, file, directory.uri.toString()) } }
        }
    }

    @Test fun actualLibraryMoveCommitsNewRootWithCompletePackageAndPreservesOriginalReceipt() = runBlocking {
        fixture { root, file, service -> withLibraryRoots {
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            try {
                write(source, "psx/Disc/Game.cue", "FILE \"Track.bin\" BINARY")
                write(source, "psx/Disc/Track.bin", "track")
                val original = service.record(file, source.uri.toString(), "psx", listOf("Disc/Game.cue", "Disc/Track.bin"))
                graph.packagesSettings().updateDownloadDirectory(source.uri.toString())
                val mover = graph.packageMover()
                mover.dismiss()
                mover.start(target.uri.toString())
                val outcome = withTimeout(30_000) { mover.state.first { it.finished || it.problem != null } }
                assertTrue(outcome.toString(), outcome.finished)
                assertEquals(0, outcome.failed)
                assertEquals(target.uri.toString(), graph.packagesSettings().downloadDirectory.first())
                val newReceipt = requireNotNull(service.recordFor(file, setOf(target.uri.toString())))
                assertTrue(service.inspect(newReceipt, true).complete)
                assertEquals(original, service.recordFor(file, setOf(source.uri.toString())))
                assertNull(StorageHelper.findFile(source, "psx/Disc/Track.bin"))
                graph.packagesLibrary().refresh()
                assertTrue(graph.packagesLibrary().isOwned(file))
                assertEquals(2, graph.packagesLibrary().launchPlan(file).size)
            } finally { listOf(source, target).forEach { directory -> removeReceipt(service, file, directory.uri.toString()) } }
        } }
    }

    @Test fun sameHashTargetWithDifferentRecordedLayoutStillPreservesCurrentReceipt() = runBlocking {
        fixture { root, file, service ->
            val source = requireNotNull(root.createDirectory("source"))
            val target = requireNotNull(root.createDirectory("target"))
            try {
                write(source, "Game.gba", "same bytes")
                write(target, "new/Game.gba", "same bytes")
                write(target, "current/Game.gba", "same bytes")
                val original = service.record(file, source.uri.toString(), "", listOf("Game.gba"))
                val current = service.record(file, target.uri.toString(), "current", listOf("Game.gba"))
                assertTrue(runCatching { service.rebase(original, target.uri.toString(), "new") }.isFailure)
                assertEquals(current, service.recordFor(file, setOf(target.uri.toString())))
                val receiptFile = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), target.uri.toString())}.json")
                receiptFile.writeText("{corrupt receipt")
                assertTrue(runCatching { service.rebase(original, target.uri.toString(), "new") }.isFailure)
                assertEquals("{corrupt receipt", receiptFile.readText())
                assertTrue(service.hasRecordedIdentity(file, setOf(target.uri.toString())))
            } finally { listOf(source, target).forEach { directory -> removeReceipt(service, file, directory.uri.toString()) } }
        }
    }

    private fun removeReceipt(service: GamePackageService, file: DownloadableFileEntity, root: String) {
        val receipt = File(context.filesDir, "game-packages/${GamePackages.receiptKey(service.identity(file), root)}.json")
        listOf(receipt, File(receipt.path + ".bak"), File(receipt.path + ".new")).forEach { it.delete() }
    }
}
