package com.cortinadev.dogmatix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.ui.screens.tools.SmartRuleDialog
import com.cortinadev.dogmatix.util.SmartCollectionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CollectionRulesUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun largeTextDialogRejectsReversedYearsAndSavesValidRules() {
        var saved: SmartCollectionRule? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme { SmartRuleDialog("Smart", SmartCollectionRule(), emptyList(),
                    onSave = { _, rule -> saved = rule }, onDismiss = {}) }
            }
        }
        val start = context.getString(R.string.smart25_from)
        val end = context.getString(R.string.smart25_to)
        compose.onNode(hasText(start)).performScrollTo().performTextReplacement("2005")
        compose.onNode(hasText(end)).performScrollTo().performTextReplacement("2000")
        compose.onNodeWithText(context.getString(R.string.dialog_save)).assertIsNotEnabled()
        compose.onNode(hasText(start)).performScrollTo().performTextReplacement("1990")
        compose.onNodeWithText(context.getString(R.string.dialog_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1990, saved?.fromYear); assertEquals(2000, saved?.toYear) }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun controllerBClosesTheRulesDialogWithoutSaving() {
        var closed = false
        var saved = false
        compose.setContent { MaterialTheme {
            SmartRuleDialog("Smart", SmartCollectionRule(), emptyList(), onSave = { _, _ -> saved = true }, onDismiss = { closed = true })
        } }
        compose.waitForIdle()
        // D-pad input switches Android out of touch mode, like using a real controller.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.dialog_cancel))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            // Route B through this dialog's focused root, independent of native window timing.
            .performKeyInput { pressKey(Key.ButtonB) }
        compose.runOnIdle { assertTrue(closed); assertFalse(saved) }
    }
}
