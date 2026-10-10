package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.ui.screens.tools.FileExplorerScreen
import com.cortinadev.dogmatix.ui.screens.tools.FileExplorerViewModel
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Recovery is exercised through the actual folder selection, confirmation and ViewModel. */
class ExplorerRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, ExplorerRecoveryEntryPoint::class.java)

    private fun fixture(block: (DocumentFile, FileExplorerViewModel) -> Unit) {
        val rootTree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val settings = graph.explorer27SettingsRepository()
        val previousDownloadDir = runBlocking { settings.downloadDirectory.first() }
        testContext.grantUriPermission(context.packageName, rootTree, flags)
        val folder = requireNotNull(StorageHelper.createDirectory(context, rootTree.toString(), "explorer-recovery-${UUID.randomUUID()}"))
        val folderTree = DocumentsContract.buildTreeDocumentUri(rootTree.authority, DocumentsContract.getDocumentId(folder.uri))
        testContext.grantUriPermission(context.packageName, folderTree, flags)
        var viewModel: FileExplorerViewModel? = null
        try {
            runBlocking { settings.updateDownloadDirectory(folderTree.toString()) }
            StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "precious old save".toByteArray())
            context.contentResolver.call(rootTree, "fixture:fail_commit", "Game.srm", null)
            assertTrue(runCatching { StorageHelper.writeBytesSafely(context, folder, "", "Game.srm", "replacement save".toByteArray()) }.isFailure)
            context.contentResolver.call(rootTree, "fixture:clear_faults", null, null)
            assertNull(folder.findFile("Game.srm"))
            assertEquals(1, StorageHelper.pendingRecoveries(context, folder).size)
            compose.runOnIdle {
                viewModel = FileExplorerViewModel(context, settings, graph.explorer27Extractor(), graph.explorer27VerifiedCopy(),
                    graph.explorer27Trash(), graph.explorer27Library(), graph.explorer27MoveGate(), graph.recovery28AppSettings())
            }
            val model = requireNotNull(viewModel)
            compose.setContent { DogmatixTheme { FileExplorerScreen(model) } }
            compose.waitUntil(10_000) { model.state.value.roots.isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.files_root_downloads)).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.recoveries.size == 1 }
            block(folder, model)
        } finally {
            viewModel?.let { model -> compose.runOnIdle { model.viewModelScope.cancel() } }
            runBlocking { settings.updateDownloadDirectory(previousDownloadDir) }
            context.contentResolver.call(rootTree, "fixture:clear_faults", null, null)
            folder.delete()
            testContext.revokeUriPermission(folderTree, flags)
            testContext.revokeUriPermission(rootTree, flags)
        }
    }

    @Test fun failedReplacementCanBeRecoveredThroughTheExplorerConfirmation() = fixture { folder, model ->
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.recovery27_available, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.recovery27_hint)).assertIsDisplayed()
        compose.onNodeWithText("Game.srm").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.recovery27_confirm, "Game.srm")).assertIsDisplayed()
        // Opening the confirmation itself never changes the folder.
        assertNull(folder.findFile("Game.srm"))
        assertEquals(1, StorageHelper.pendingRecoveries(context, folder).size)
        compose.onNode(hasText(context.getString(R.string.recovery27_title)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { model.state.value.busy == null && !model.state.value.loading && model.state.value.recoveries.isEmpty() }
        assertEquals("precious old save", StorageHelper.readText(context, folder.findFile("Game.srm")!!))
        assertTrue(StorageHelper.pendingRecoveries(context, folder).isEmpty())
        compose.onNodeWithText(context.getString(R.string.recovery27_hint)).assertDoesNotExist()
        compose.onNodeWithText("Game.srm").assertIsDisplayed()
    }

    @Test fun explorerRecoveryRefusesToReplaceADifferentNewerSave() = fixture { folder, model ->
        val current = requireNotNull(folder.createFile("application/octet-stream", "Game.srm"))
        context.contentResolver.openOutputStream(current.uri, "wt")!!.use { it.write("newer precious save".toByteArray()) }
        val recovery = model.state.value.recoveries.single()
        compose.runOnIdle { model.recover(recovery) }
        compose.waitUntil(10_000) { model.state.value.busy == null && !model.state.value.loading }
        assertEquals("newer precious save", StorageHelper.readText(context, current))
        assertEquals(1, StorageHelper.pendingRecoveries(context, folder).size)
        assertEquals("precious old save", StorageHelper.readText(context, folder.findFile(recovery.backupName)!!))
        compose.onNodeWithText(context.getString(R.string.recovery27_hint)).assertIsDisplayed()
    }
}
