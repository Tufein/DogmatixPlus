package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Preserved archive directories stay owned and their descriptors receive child read grants. */
class NestedLibraryRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, NestedLibraryEntryPoint::class.java)
    private suspend fun fixture(block: suspend (DocumentFile) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val test = InstrumentationRegistry.getInstrumentation().context
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        test.grantUriPermission(context.packageName, tree, flags)
        val directory = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "nested-library-${UUID.randomUUID()}"))
        val settings = graph.nestedLibrarySettings()
        val previousRoot = settings.downloadDirectory.first()
        val previousCustom = settings.consoleDownloadDirectories.first()
        try {
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            previousCustom.keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            settings.updateDownloadDirectory(directory.uri.toString())
            block(directory)
        } finally {
            settings.consoleDownloadDirectories.first().keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
            previousCustom.forEach { (console, uri) -> settings.updateConsoleDownloadDirectory(console, uri) }
            settings.updateDownloadDirectory(previousRoot)
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            directory.delete()
            test.revokeUriPermission(tree, flags)
        }
    }
    private fun row(name: String, console: String = "nintendo_gameboy_advance") = DownloadableFileEntity(
        consoleId = console, name = "Game", fileName = name, downloadUrl = "https://example.invalid/$name", fileExtension = name.substringAfterLast('.'))
    private fun write(root: DocumentFile, path: String, text: String): DocumentFile = StorageHelper.writeBytesSafely(
        context, root, path.substringBeforeLast('/', ""), path.substringAfterLast('/'), text.toByteArray())

    @Test fun deepConsoleArchiveStaysOwnedAfterFullRefreshAndItsGameCanBeFound() = runBlocking {
        fixture { root ->
            val document = write(root, "gba/Game/Disc 1/Game.cue", "FILE \"Track.bin\" BINARY")
            write(root, "gba/Game/Disc 1/Track.bin", "track")
            graph.nestedLibrary().refresh()
            assertTrue(graph.nestedLibrary().isOwned(row("Game.zip")))
            assertFalse(graph.nestedLibrary().isOwned(row("Game.zip", "nintendo_super_nintendo_entertainment_system")))
            val plan = graph.nestedLibrary().launchPlan(row("Game.zip"))
            assertEquals(setOf("Game.cue", "Track.bin"), plan.map { it.name }.toSet())
            assertTrue(plan.any { it.uri == document.uri.toString() })
        }
    }
    @Test fun unnamedArchiveFolderUnderFlatRootIsOwnedWithoutCrossingKnownConsoleScopes() = runBlocking {
        fixture { root ->
            write(root, "Game/Game.gba", "flat-root-game")
            write(root, "snes/OnlySnes.sfc", "snes-game")
            graph.nestedLibrary().refresh()
            assertTrue(graph.nestedLibrary().isOwned(row("Game.zip")))
            assertFalse(graph.nestedLibrary().isOwned(row("OnlySnes.zip")))
            assertTrue(graph.nestedLibrary().isOwned(row("OnlySnes.zip", "nintendo_super_nintendo_entertainment_system")))
            assertEquals(listOf("Game.gba"), graph.nestedLibrary().launchPlan(row("Game.zip")).map { it.name })
        }
    }
    @Test fun nestedPlaylistDiscAndTrackAreIncludedInActualLaunchGrants() = runBlocking {
        fixture { root ->
            val playlist = write(root, "gba/Game.m3u", "Disc 1/Disc1.cue\nDisc 2/Disc2.cue")
            val cue1 = write(root, "gba/Disc 1/Disc1.cue", "FILE \"Track.bin\" BINARY")
            val track1 = write(root, "gba/Disc 1/Track.bin", "first-track")
            val cue2 = write(root, "gba/Disc 2/Disc2.cue", "FILE \"Track.bin\" BINARY")
            val track2 = write(root, "gba/Disc 2/Track.bin", "second-track")
            write(root, "snes/Game.cue", "FILE \"Secret.bin\" BINARY")
            write(root, "snes/Secret.bin", "another-console")
            val file = row("Game.zip")
            val plan = graph.nestedLibrary().launchPlan(file)
            assertEquals(setOf(playlist, cue1, track1, cue2, track2).map { it.uri.toString() }.toSet(), plan.map { it.uri }.toSet())
            val choice = graph.nestedLibraryLaunches().choices(file).first { it.uri == playlist.uri.toString() }
            assertEquals(setOf(cue1, track1, cue2, track2).map { it.uri.toString() }.toSet(), choice.siblings.toSet())
        }
    }
    @Test fun nestedDescriptorCannotGrantParentPathsSavesOrUnknownFormats() = runBlocking {
        fixture { root ->
            write(root, "gba/Game.m3u", "Disc/Disc.cue\n../snes/Other.iso\nGame.srm\nprivate.key")
            write(root, "gba/Disc/Disc.cue", "FILE \"Track.bin\" BINARY")
            write(root, "gba/Disc/Track.bin", "track")
            write(root, "gba/Game.srm", "save")
            write(root, "gba/private.key", "key")
            write(root, "snes/Other.iso", "other-game")
            assertEquals(setOf("Game.m3u", "Disc.cue", "Track.bin"), graph.nestedLibrary().launchPlan(row("Game.zip")).map { it.name }.toSet())
        }
    }
}
