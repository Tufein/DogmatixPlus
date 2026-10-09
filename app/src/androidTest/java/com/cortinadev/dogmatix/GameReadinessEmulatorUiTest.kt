package com.cortinadev.dogmatix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.GameHandler
import com.cortinadev.dogmatix.data.service.GameLaunch
import com.cortinadev.dogmatix.ui.screens.tools.ReadinessGameEntry
import com.cortinadev.dogmatix.ui.screens.tools.ReadyUi
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.GameLaunchKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GameReadinessEmulatorUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val duck = GameHandler(GameLaunchKeys.catalogue("duckstation", packageName = "com.github.stenzek.duckstation"),
        "DuckStation", "com.github.stenzek.duckstation")
    private val epsxe = GameHandler(GameLaunchKeys.catalogue("epsxe", packageName = "com.epsxe.ePSXe"),
        "ePSXe", "com.epsxe.ePSXe")
    private val cue = GameLaunch("content://test/cue", "Game.cue", listOf(duck))
    private val chd = GameLaunch("content://test/chd", "Game.chd", listOf(duck, epsxe))

    private fun rowText(game: GameLaunch, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(game.uri))

    @Composable
    private fun Entries(ui: ReadyUi, onTest: (GameLaunch, GameHandler) -> Unit) {
        DogmatixTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ui.games.forEach { game ->
                    Column(Modifier.testTag(game.uri)) {
                        ReadinessGameEntry(game, ui.effective(game), enabled = true,
                            onSelect = {}, onTest = { onTest(game, it) })
                    }
                }
            }
        }
    }

    @Test fun effectiveChoiceWarningSelectionAndTestActionMatchEachEntry() {
        val tested = mutableListOf<Pair<String, String>>()
        val ui = ReadyUi(loading = false, games = listOf(cue, chd), gameOverride = epsxe.key, consoleDefault = duck.key)
        compose.setContent { Entries(ui) { game, handler -> tested += game.uri to handler.key } }

        compose.onNode(rowText(cue, context.getString(R.string.play26_effective, duck.label))).assertIsDisplayed()
        compose.onNode(rowText(cue, context.getString(R.string.play26_missing))).assertIsDisplayed()
        compose.onNode(rowText(cue, duck.label)).assertIsSelected()
        compose.onNode(rowText(chd, context.getString(R.string.play26_effective, epsxe.label))).performScrollTo().assertIsDisplayed()
        compose.onNode(rowText(chd, context.getString(R.string.play26_missing))).assertDoesNotExist()
        compose.onNode(rowText(chd, duck.label)).assertIsNotSelected()
        compose.onNode(rowText(chd, epsxe.label)).assertIsSelected()

        val testLabel = context.getString(R.string.play26_test_effective)
        compose.onNode(rowText(cue, testLabel)).performScrollTo().performClick()
        compose.onNode(rowText(chd, testLabel)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(cue.uri to duck.key, chd.uri to epsxe.key), tested) }
    }

    @Test fun missingChoiceOnFirstEntryDoesNotHideTheEffectiveTestForAnotherEntry() {
        val tested = mutableListOf<Pair<String, String>>()
        val ui = ReadyUi(loading = false, games = listOf(cue, chd), gameOverride = epsxe.key)
        compose.setContent { Entries(ui) { game, handler -> tested += game.uri to handler.key } }

        val testLabel = context.getString(R.string.play26_test_effective)
        compose.onNode(rowText(cue, testLabel)).assertDoesNotExist()
        compose.onNode(rowText(cue, duck.label)).assertIsNotSelected()
        compose.onNode(rowText(chd, epsxe.label)).assertIsSelected()
        compose.onNode(rowText(chd, testLabel)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(chd.uri to epsxe.key), tested) }
    }
}
