package com.cortinadev.dogmatix

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.*
import com.cortinadev.dogmatix.ui.screens.tools.PackageTransferScreen
import com.cortinadev.dogmatix.ui.screens.tools.PackageTransferViewModel
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PackageTransferUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = EntryPointAccessors.fromApplication(context.applicationContext, GamePackagesEntryPoint::class.java)

    @OptIn(ExperimentalTestApi::class)
    @Test fun largeTextPreviewRequiresConfirmationControllerBCancelsThenVerifiedRestoreSucceeds() {
        val tree = DocumentsContract.buildTreeDocumentUri("com.tufein.dogmatixplus.test.storage", "root")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val test = InstrumentationRegistry.getInstrumentation().context
        test.grantUriPermission(context.packageName, tree, flags)
        val settings = graph.packagesSettings()
        val app = graph.packageAppSettings()
        val previousRoot = runBlocking { settings.downloadDirectory.first() }
        val previousCustom = runBlocking { settings.consoleDownloadDirectories.first() }
        val previousProfile = runBlocking { app.activeProfile.first() }
        val unique = UUID.randomUUID().toString()
        val folder = requireNotNull(StorageHelper.createDirectory(context, tree.toString(), "transfer-ui-$unique"))
        val file = DownloadableFileEntity(name = "Package UI fixture", fileName = "Demo-$unique.zip", consoleId = "transfer-ui-$unique",
            downloadUrl = "https://fixture.invalid/$unique.zip", sourceUrl = "https://fixture.invalid/")
        val packages = graph.gamePackages()
        val db = graph.packageDatabase()
        var model: PackageTransferViewModel? = null
        val receipt = File(context.filesDir, "game-packages/${GamePackages.receiptKey(packages.identity(file), folder.uri.toString())}.json")
        val marker = File(context.filesDir, "game-packages/${packages.identity(file)}.tracked")
        try {
            val imported = runBlocking {
                app.setActiveProfile("")
                previousCustom.keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
                settings.updateDownloadDirectory(folder.uri.toString())
                db.manufacturerDao().insertManufacturer(ManufacturerEntity(file.consoleId, "UI fixture"))
                db.consoleDao().insertConsole(ConsoleEntity(file.consoleId, "UI fixture", file.consoleId, "[]"))
                db.downloadableFileDao().insertFiles(listOf(file))
                StorageHelper.writeBytesSafely(context, folder, "Disc 1", "Track.bin", "test bytes".toByteArray())
                val record = packages.record(file, folder.uri.toString(), "", listOf("Disc 1/Track.bin"))
                val portable = GamePackageTransfer.decode(GamePackageTransfer.encode(listOf(record)))
                receipt.delete(); marker.delete()
                portable
            }
            compose.runOnIdle { model = PackageTransferViewModel(graph.packageTransfers(), graph.packagesLibrary(), app) }
            val viewModel = requireNotNull(model)
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) { DogmatixTheme { PackageTransferScreen(viewModel) } }
            }
            compose.waitUntil(10_000) { viewModel.snapshot.value != null }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("package-transfer-export").assertIsNotEnabled()
            compose.onNodeWithTag("package-transfer-import").assertIsEnabled()
            compose.runOnIdle { viewModel.imported.value = imported; viewModel.check(viewModel.snapshot.value!!.locations.single { it.uri == folder.uri.toString() }) }
            compose.waitUntil(10_000) { viewModel.preview.value?.ready == true && !viewModel.busy.value }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("package-transfer-restore"))
            compose.onNodeWithTag("package-transfer-restore").assertIsEnabled().performClick()
            compose.onNode(hasText(context.getString(R.string.package_transfer_confirm, 1, 1)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            assertFalse(receipt.exists())
            val cancel = compose.onNode(hasText(context.getString(R.string.package_transfer_cancel)) and hasAnyAncestor(isDialog()))
            cancel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused().performKeyInput { pressKey(Key.ButtonB) }
            compose.onNode(isDialog()).assertDoesNotExist()
            assertFalse(receipt.exists())
            compose.onNodeWithTag("package-transfer-restore").performClick()
            compose.onNode(hasText(context.getString(R.string.package_transfer_restore)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { viewModel.restored.value == 1 && !viewModel.busy.value }
            compose.runOnIdle {
                assertTrue(receipt.exists())
                assertNotNull(packages.recordFor(file, setOf(folder.uri.toString())))
            }
        } finally {
            model?.let { compose.runOnIdle { it.viewModelScope.cancel() } }
            listOf(receipt, marker).forEach { base -> listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() } }
            runBlocking {
                db.downloadableFileDao().deleteFilesByConsoleId(file.consoleId)
                db.consoleDao().deleteConsoleById(file.consoleId)
                db.manufacturerDao().deleteManufacturerById(file.consoleId)
                settings.consoleDownloadDirectories.first().keys.forEach { settings.updateConsoleDownloadDirectory(it, "") }
                previousCustom.forEach { (console, uri) -> settings.updateConsoleDownloadDirectory(console, uri) }
                settings.updateDownloadDirectory(previousRoot)
                app.setActiveProfile(previousProfile)
                graph.packagesLibrary().refresh()
            }
            folder.delete()
            test.revokeUriPermission(tree, flags)
        }
    }
}
