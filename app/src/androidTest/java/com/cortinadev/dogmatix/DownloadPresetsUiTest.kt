package com.cortinadev.dogmatix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.ui.screens.settings.DownloadPresetEditor
import com.cortinadev.dogmatix.util.DownloadPreset
import com.cortinadev.dogmatix.util.DownloadPresetOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DownloadPresetsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun largeTextEditorRequiresNameAndSavesTheScheduleWithoutChangingSpeedUnits() {
        var saved: DownloadPreset? = null
        val options = DownloadPresetOptions(limitSpeed = 750f, concurrentDownloads = 2,
            nightOnly = true, nightStart = 1320, nightEnd = 360)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme { DownloadPresetEditor(DownloadPreset("custom", "", options),
                    onSave = { saved = it }, onDismiss = {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.dialog_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.presets26_name)).performScrollTo().performTextReplacement(" Evening ")
        compose.onNodeWithText(context.getString(R.string.dialog_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Evening", saved?.name); assertEquals(options, saved?.options) }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun controllerBClosesTheEditorWithoutApplyingIt() {
        var closed = false
        var saved = false
        compose.setContent { MaterialTheme {
            DownloadPresetEditor(DownloadPreset("custom", "Evening", DownloadPresetOptions()),
                onSave = { saved = true }, onDismiss = { closed = true })
        } }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(context.getString(R.string.dialog_cancel))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
            // Route B through this dialog's focused root, independent of native window timing.
            .performKeyInput { pressKey(Key.ButtonB) }
        compose.runOnIdle { assertTrue(closed); assertFalse(saved) }
    }
}
