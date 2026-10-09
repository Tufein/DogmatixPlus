package com.cortinadev.dogmatix

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.GameHandler
import com.cortinadev.dogmatix.data.service.GameLaunch
import com.cortinadev.dogmatix.ui.screens.game.GameEmulatorChoiceDialog
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.GameLaunchKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GameEmulatorChoiceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val standard = GameHandler(GameLaunchKeys.catalogue("ppsspp", packageName = "org.ppsspp.ppsspp"), "PPSSPP", "org.ppsspp.ppsspp")
    private val gold = GameHandler(GameLaunchKeys.catalogue("ppsspp", packageName = "org.ppsspp.ppssppgold"), "PPSSPP Gold", "org.ppsspp.ppssppgold")

    @Test fun choosingGameVariantAndReturningToConsoleChangesTheEffectiveChoice() {
        val own = mutableStateOf<String?>(null)
        val console = standard.key
        compose.setContent { DogmatixTheme {
            GameEmulatorChoiceDialog(listOf(GameLaunch("content://test/game", "Game.iso", listOf(standard, gold))),
                own.value, console, onPick = { own.value = it }, onDismiss = {})
        } }
        compose.onNodeWithText(context.getString(R.string.play26_effective, standard.label)).assertIsDisplayed()
        compose.onNodeWithText(gold.label).performClick()
        compose.onNodeWithText(context.getString(R.string.play26_effective, gold.label)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.play26_use_console)).performClick()
        compose.onNodeWithText(context.getString(R.string.play26_effective, standard.label)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, own.value); assertEquals(standard.key, console) }
    }

    @Test fun anUnavailableGameVariantExplainsTheConsoleFallback() {
        compose.setContent { DogmatixTheme {
            GameEmulatorChoiceDialog(listOf(GameLaunch("content://test/game", "Game.iso", listOf(standard))),
                gold.key, standard.key, onPick = {}, onDismiss = {})
        } }
        compose.onNodeWithText(context.getString(R.string.play26_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.play26_effective, standard.label)).assertIsDisplayed()
    }
}
