package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.*
import com.cortinadev.dogmatix.data.service.GamePackageService
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room, DataStore and document-provider tests; fixtures never use personal folders. */
class PackageTransferRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, GamePackagesEntryPoint::class.java)
    private val packages get() = graph.gamePackages()
    private val transfer get() = graph.packageTransfers()

    private suspend fun fixture(block: suspend (DocumentFile, DownloadableFileEntity) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        test.grantUriPermission(context.packageName, tree, flags)
        val unique = UUID.randomUUID().toString()
        val root = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "transfer-$unique"))
        val db = graph.packageDatabase()
        val settings = graph.packagesSettings()
        val app = graph.packageAppSettings()
        val oldRoot = settings.downloadDirectory.first()
        val oldCustom = settings.consoleDownloadDirectories.first()
        val oldProfile = app.activeProfile.first()
        val oldProfiles = app.profiles.first()
        val file = DownloadableFileEntity(name = "Transfer fixture", fileName = "Package-$unique.zip", consoleId = "transfer-$unique",
            downloadUrl = "https://package.invalid/$unique.zip", sourceUrl = "https://package.invalid/")
        try {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            app.setActiveProfile("")
            oldCustom.keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            settings.updateDownloadDirectory(root.uri.toString())
            db.manufacturerDao().insertManufacturer(ManufacturerEntity(file.consoleId, "Transfer fixture"))
            db.consoleDao().insertConsole(ConsoleEntity(file.consoleId, "Transfer fixture", file.consoleId, "[]"))
            db.downloadableFileDao().insertFiles(listOf(file))
            block(root, file)
        } finally {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            // Delete only this fixture's identities, including its second-source restriction case.
            val identities = listOf(packages.identity(file), packages.identity(file.copy(downloadUrl = file.downloadUrl + "?other")))
            val ownNames = identities.map { "$it.tracked" } + identities.map { "${GamePackages.receiptKey(it, root.uri.toString())}.json" }
            File(context.filesDir, "game-packages").listFiles().orEmpty().filter { candidate ->
                candidate.name.removeSuffix(".bak").removeSuffix(".new") in ownNames ||
                    runCatching { GamePackages.decode(candidate.readBytes()).consoleId == file.consoleId }.getOrDefault(false)
            }.forEach { it.delete() }
            db.downloadableFileDao().deleteFilesByConsoleId(file.consoleId)
            db.consoleDao().deleteConsoleById(file.consoleId)
            db.manufacturerDao().deleteManufacturerById(file.consoleId)
            settings.consoleDownloadDirectories.first().keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            oldCustom.forEach { (console, uri) -> settings.updateConsoleDownloadDirectory(console, uri) }
            settings.updateDownloadDirectory(oldRoot)
            app.setProfiles(oldProfiles)
            app.setActiveProfile(oldProfile)
            graph.packagesLibrary().refresh()
            root.delete()
            test.revokeUriPermission(tree, flags)
        }
    }

    private fun write(root: DocumentFile, path: String, value: String) = StorageHelper.writeBytesSafely(context,
        root, path.substringBeforeLast('/', ""), path.substringAfterLast('/'), value.toByteArray())
    private fun receipt(record: GamePackages.Manifest) = File(context.filesDir,
        "game-packages/${GamePackages.receiptKey(record.sourceIdentity, record.rootUri)}.json")
    private fun forget(record: GamePackages.Manifest) {
        val target = receipt(record)
        val marker = File(context.filesDir, "game-packages/${record.sourceIdentity}.tracked")
        listOf(target, marker).forEach { base -> listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() } }
    }
    private suspend fun mustFail(block: suspend () -> Unit) {
        val exception = try { block(); null } catch (expected: Exception) { expected }
        assertNotNull("Unsafe or stale action must fail", exception)
    }
    private suspend fun portable(record: GamePackages.Manifest) = GamePackageTransfer.decode(GamePackageTransfer.encode(listOf(record)))

    @Test fun exportRestoreAfterReinstallKeepsNestedFilesAndIsIdempotent() = runBlocking {
        fixture { root, file ->
            write(root, "psx/Disc 1/Game.cue", "FILE \"Track.bin\" BINARY")
            val track = write(root, "psx/Disc 1/Track.bin", "track bytes")
            val record = packages.record(file, root.uri.toString(), "psx", listOf("Disc 1/Game.cue", "Disc 1/Track.bin"))
            val selected = transfer.snapshot()
            assertEquals(listOf(record), selected.records)
            val export = requireNotNull(root.createFile("application/json", "packages.json"))
            transfer.export(selected, export.uri)
            val imported = transfer.read(export.uri)
            assertEquals(GamePackageTransfer.PORTABLE_ROOT, imported.single().rootUri)
            forget(record) // Simulate the receipt/marker loss of a reinstall, retaining copied game files.
            val checked = transfer.preview(imported, selected.locations.single { it.uri == root.uri.toString() })
            assertTrue(checked.ready)
            assertEquals(1, transfer.restore(checked))
            val afterRestart = GamePackageService(context).recordFor(file, setOf(root.uri.toString()))
            assertEquals(record, afterRestart)
            assertTrue(GamePackageService(context).inspect(requireNotNull(afterRestart), true).complete)
            val again = transfer.preview(imported, checked.location)
            assertEquals(0, transfer.restore(again))
            assertEquals("track bytes", context.contentResolver.openInputStream(track.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun sameLengthMutationAfterPreviewCannotRegisterFiles() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "before")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val imported = portable(record)
            forget(record)
            val location = transfer.snapshot().locations.single { it.uri == root.uri.toString() }
            val checked = transfer.preview(imported, location)
            assertTrue(checked.ready)
            val changed = write(root, "Game.bin", "after!")
            mustFail { transfer.restore(checked) }
            assertFalse(receipt(record).exists())
            assertFalse(packages.hasRecordedIdentity(file, setOf(root.uri.toString())))
            assertEquals(listOf("Game.bin"), transfer.preview(imported, location).checks.single().changed)
            assertEquals("after!", context.contentResolver.openInputStream(changed.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun multipleRootsRestoreOnlyCompleteVerifiedPackagesAndLeaveOtherRootUnclaimed() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "first-game")
            val first = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val other = requireNotNull(root.createDirectory("Other root"))
            write(other, "Game.bin", "secondgame")
            val secondFile = file.copy(downloadUrl = file.downloadUrl + "?other")
            graph.packageDatabase().downloadableFileDao().insertFiles(listOf(secondFile))
            val second = packages.record(secondFile, other.uri.toString(), "", listOf("Game.bin"))
            graph.packagesSettings().updateConsoleDownloadDirectory(file.consoleId, other.uri.toString())
            val snapshot = transfer.snapshot()
            assertEquals(2, snapshot.records.size)
            val imported = GamePackageTransfer.decode(GamePackageTransfer.encode(snapshot.records))
            forget(first); forget(second)
            val checked = transfer.preview(imported, snapshot.locations.single { it.uri == root.uri.toString() })
            assertFalse(checked.ready)
            assertEquals(1, checked.checks.count { it.ready })
            assertEquals(1, transfer.restore(checked.copy(checks = checked.checks.filter { it.ready })))
            assertNotNull(packages.recordFor(file, setOf(root.uri.toString())))
            assertNull(packages.recordFor(secondFile, setOf(root.uri.toString(), other.uri.toString())))
            val checkedOther = transfer.preview(imported, snapshot.locations.single { it.uri == other.uri.toString() })
            assertEquals(1, checkedOther.checks.count { it.ready })
            assertEquals(1, transfer.restore(checkedOther.copy(checks = checkedOther.checks.filter { it.ready })))
            assertEquals(second, packages.recordFor(secondFile, setOf(other.uri.toString())))
            assertEquals("first-game", StorageHelper.readText(context, root.findFile("Game.bin")!!))
            assertEquals("secondgame", StorageHelper.readText(context, other.findFile("Game.bin")!!))
        }
    }

    @Test fun wrongConfiguredFolderAndUnconfiguredLocationNeverRestore() = runBlocking {
        fixture { root, file ->
            write(root, "Nested/Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "Nested", listOf("Game.bin"))
            val imported = portable(record)
            forget(record)
            val original = transfer.snapshot().locations.single { it.uri == root.uri.toString() }
            val other = requireNotNull(root.createDirectory("Wrong folder"))
            graph.packagesSettings().updateDownloadDirectory(other.uri.toString())
            mustFail { transfer.preview(imported, original) }
            val wrong = transfer.snapshot().locations.single { it.uri == other.uri.toString() }
            val checked = transfer.preview(imported, wrong)
            assertFalse(checked.ready)
            assertEquals(listOf("Game.bin"), checked.checks.single().missing)
            mustFail { transfer.restore(checked) }
            assertFalse(receipt(record).exists())
            assertNotNull(root.findFile("Nested")?.findFile("Game.bin"))
        }
    }

    @Test fun corruptExistingReceiptIsProtectedAndFailedReadsBlockRestore() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val imported = portable(record)
            val location = transfer.snapshot().locations.single { it.uri == root.uri.toString() }
            val target = receipt(record)
            target.writeText("{broken private receipt")
            val checked = transfer.preview(imported, location)
            assertFalse(checked.ready)
            assertFalse(checked.checks.single().existingCompatible)
            mustFail { transfer.restore(checked) }
            assertEquals("{broken private receipt", target.readText())
            forget(record)
            context.contentResolver.call(tree, "fixture:fail_read", "Game.bin", null)
            assertFalse(transfer.preview(imported, location).ready)
            assertFalse(target.exists())
        }
    }

    @Test fun sourceMismatchCannotClaimSameNamedFileAndDoesNotReadItsBytes() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val imported = portable(record)
            forget(record)
            val dao = graph.packageDatabase().downloadableFileDao()
            dao.deleteFilesByConsoleId(file.consoleId)
            dao.insertFiles(listOf(file.copy(downloadUrl = file.downloadUrl + "?other")))
            context.contentResolver.call(tree, "fixture:fail_read", "Game.bin", null)
            val checked = transfer.preview(imported, transfer.snapshot().locations.single { it.uri == root.uri.toString() })
            assertFalse(checked.ready)
            assertFalse(checked.checks.single().sourceFound)
            mustFail { transfer.restore(checked) }
            assertFalse(receipt(record).exists())
        }
    }

    @Test fun profileChangeAndNewRestrictionsInvalidatePreviewAndExport() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val imported = portable(record)
            val oldSnapshot = transfer.snapshot()
            val output = requireNotNull(root.createFile("application/json", "stale.json"))
            forget(record)
            val checked = transfer.preview(imported, oldSnapshot.locations.single { it.uri == root.uri.toString() })
            val app = graph.packageAppSettings()
            app.setProfiles(Profiles.toJson(listOf(Profile("transfer-child", "Fixture", hiddenConsoles = setOf(file.consoleId)))))
            app.setActiveProfile("transfer-child")
            mustFail { transfer.restore(checked) }
            mustFail { transfer.export(oldSnapshot, output.uri) }
            mustFail { transfer.preview(imported, checked.location) }
            assertFalse(receipt(record).exists())
            assertEquals(0L, output.length())
        }
    }

    @Test fun duplicateSourceRowsCannotBypassHiddenTagsDuringExport() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val dao = graph.packageDatabase().downloadableFileDao()
            val second = file.copy(downloadUrl = file.downloadUrl + "?other", sourceUrl = "https://hidden.invalid/")
            val secondId = dao.insertAll(listOf(second)).single()
            dao.insertTags(listOf(FileTagEntity(secondId, "Adult")))
            val app = graph.packageAppSettings()
            app.setProfiles(Profiles.toJson(listOf(Profile("transfer-child", "Fixture", hiddenTags = setOf("adult")))))
            app.setActiveProfile("transfer-child")
            assertTrue(transfer.snapshot().records.isEmpty())
            val rows = graph.packageFiles().exactMatchesBatch(listOf(file.consoleId to file.fileName))[file.consoleId to file.fileName]!!
            assertEquals(2, rows.size)
            assertTrue(rows.any { "Adult" in it.tags })
        }
    }

    @Test fun changingRestrictionsWithinSameProfileInvalidatesReadyPreview() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val imported = portable(record)
            forget(record)
            val app = graph.packageAppSettings()
            app.setProfiles(Profiles.toJson(listOf(Profile("transfer-parent", "Fixture"))))
            app.setActiveProfile("transfer-parent")
            val checked = transfer.preview(imported, transfer.snapshot().locations.single { it.uri == root.uri.toString() })
            assertTrue(checked.ready)
            app.setProfiles(Profiles.toJson(listOf(Profile("transfer-parent", "Fixture", hiddenConsoles = setOf(file.consoleId)))))
            mustFail { transfer.restore(checked) }
            assertFalse(receipt(record).exists())
        }
    }

    @Test fun failedReceiptWriteRollsBackNewBatchWithoutTouchingGameFiles() = runBlocking {
        fixture { root, file ->
            val game = write(root, "Game.bin", "game")
            val record = packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val secondFile = file.copy(downloadUrl = file.downloadUrl + "?other")
            val second = packages.record(secondFile, root.uri.toString(), "", listOf("Game.bin"))
            forget(record); forget(second)
            val blocked = File(receipt(second).path + ".new")
            assertTrue(blocked.mkdirs())
            // A nonempty directory prevents AtomicFile from opening/removing this staging path.
            val obstruction = File(blocked, "obstruction").apply { writeText("fixture") }
            try {
                mustFail { packages.importVerified(listOf(record, second)) }
                assertFalse(receipt(record).exists())
                assertFalse(receipt(second).exists())
                assertFalse(packages.hasRecordedIdentity(file, setOf(root.uri.toString())))
                assertFalse(packages.hasRecordedIdentity(secondFile, setOf(root.uri.toString())))
                assertEquals("game", context.contentResolver.openInputStream(game.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally { obstruction.delete(); blocked.delete() }
        }
    }

    @Test fun exportRequiresSuccessfulProviderReadback() = runBlocking {
        fixture { root, file ->
            write(root, "Game.bin", "game")
            packages.record(file, root.uri.toString(), "", listOf("Game.bin"))
            val selected = transfer.snapshot()
            val output = requireNotNull(root.createFile("application/json", "failed-readback.json"))
            context.contentResolver.call(tree, "fixture:fail_read", "failed-readback.json", null)
            mustFail { transfer.export(selected, output.uri) }
            assertEquals(1, transfer.snapshot().records.size)
        }
    }
}
