package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.ui.screens.tools.RecoveryScreen
import com.cortinadev.dogmatix.ui.screens.tools.RecoveryViewModel
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecoveryHubUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, ExplorerRecoveryEntryPoint::class.java)

    @Test fun recoveryCentreShowsSourceAndTargetAndOnlyRestoresAfterConfirmation() {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        test.grantUriPermission(context.packageName, tree, flags)
        val settings = graph.explorer27SettingsRepository()
        val appSettings = graph.recovery28AppSettings()
        val previousDirectory = runBlocking { settings.downloadDirectory.first() }
        val previousProfile = runBlocking { appSettings.activeProfile.first() }
        val folder = StorageHelper.createDirectory(context, tree.toString(), "recovery-ui-${UUID.randomUUID()}")!!
        val name = "Recovery-${UUID.randomUUID()}.gba"
        var model: RecoveryViewModel? = null
        try {
            runBlocking { appSettings.setActiveProfile(""); settings.updateDownloadDirectory(folder.uri.toString()) }
            StorageHelper.writeBytesSafely(context, folder, "", name, "protected original".toByteArray())
            context.contentResolver.call(tree, "fixture:fail_commit", name, null)
            assertTrue(runCatching { StorageHelper.writeBytesSafely(context, folder, "", name, "replacement".toByteArray()) }.isFailure)
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            val receipt = StorageHelper.pendingRecoveries(context, folder).single()
            compose.runOnIdle {
                model = RecoveryViewModel(graph.recovery28History(), graph.explorer27Trash(), graph.recovery28Mover(),
                    graph.explorer27Library(), graph.explorer27VerifiedCopy(), graph.explorer27MoveGate(), settings,
                    graph.recovery28SmartSettings(), appSettings, graph.recovery28Hub(), graph.recovery28Downloads(),
                    graph.recovery28SaveSync(), context)
            }
            val viewModel = requireNotNull(model)
            compose.setContent { DogmatixTheme { RecoveryScreen(viewModel) } }
            compose.waitUntil(30_000) { viewModel.loaded.value && viewModel.snapshot.value.replacements.any { it.recovery.id == receipt.id } }
            val card = "recovery-replacement-${receipt.id}"
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(card))
            compose.onNode(hasText(context.getString(R.string.road28_recovery_preview)) and hasClickAction() and hasAnyAncestor(hasTestTag(card))).performClick()
            compose.waitUntil(10_000) { viewModel.confirmation.value != null }
            val entry = viewModel.snapshot.value.replacements.first { it.recovery.id == receipt.id }
            compose.onNode(hasText(context.getString(R.string.road28_recovery_from, entry.folderLabel)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasText(context.getString(R.string.road28_recovery_to, "${entry.folderLabel}/$name")) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            assertNull(folder.findFile(name))
            assertEquals(1, StorageHelper.pendingRecoveries(context, folder).size)
            compose.onNode(hasText(context.getString(R.string.recovery_restore)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(30_000) { !viewModel.busy.value && viewModel.confirmation.value == null && viewModel.snapshot.value.replacements.none { it.recovery.id == receipt.id } }
            assertEquals("protected original", StorageHelper.readText(context, folder.findFile(name)!!))
            assertTrue(StorageHelper.pendingRecoveries(context, folder).isEmpty())
        } finally {
            model?.let { compose.runOnIdle { it.viewModelScope.cancel() } }
            context.contentResolver.call(tree, "fixture:clear_faults", null, null)
            runBlocking { settings.updateDownloadDirectory(previousDirectory); appSettings.setActiveProfile(previousProfile) }
            folder.delete()
            test.revokeUriPermission(tree, flags)
        }
    }
}
