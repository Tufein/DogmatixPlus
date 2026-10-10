package com.cortinadev.dogmatix

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.GameReadyReport
import com.cortinadev.dogmatix.data.service.OfflineReadyCollection
import com.cortinadev.dogmatix.data.service.OfflineReadyGame
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.tools.OfflineReadinessDialog
import com.cortinadev.dogmatix.ui.screens.tools.OfflineReadyUi
import com.cortinadev.dogmatix.ui.screens.tools.ReadinessRoute
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfflineReadinessUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeGameSnapshotShowsReadyCountAndExplicitLegacyHashLimit() {
        val report = OfflineReadyCollection(1, "Trip", 1,
            listOf(OfflineReadyGame("gba", "Game.gba", "Game", GameReadyReport(true, true, true, true, false))))
        var retries = 0
        compose.setContent { DogmatixTheme { OfflineReadinessDialog(OfflineReadyUi(1, false, report), {}, { retries++ }, {}) } }
        compose.onNodeWithText(context.getString(R.string.off28_summary, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.off28_ready)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.off28_legacy)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tools_refresh)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun failedChecksLinkToExactExistingRepairToolsAndGameReadiness() {
        val navigated = mutableListOf<String>()
        val report = OfflineReadyCollection(1, "Trip", 1,
            listOf(OfflineReadyGame("sony_playstation", "Game.zip", "Game", GameReadyReport(false, false, false, false, true))))
        compose.setContent { DogmatixTheme { OfflineReadinessDialog(OfflineReadyUi(1, false, report), {}, {}, navigated::add) } }
        listOf(R.string.ready25_missing, R.string.ready25_disc_problem, R.string.ready25_bios_problem,
            R.string.ready25_extract, R.string.ready25_title).forEach { id ->
            compose.onNodeWithText(context.getString(id)).performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf(NavRoutes.Downloads.route, NavRoutes.Sets.route, NavRoutes.Bios.route,
            NavRoutes.Files.route, ReadinessRoute.of("sony_playstation", "Game.zip")), navigated) }
    }

    @Test fun loadingCheckCanBeDismissedButCannotStartADuplicateRefresh() {
        var cancelled = false
        compose.setContent { DogmatixTheme { OfflineReadinessDialog(OfflineReadyUi(1), { cancelled = true }, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.off28_loading)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tools_refresh)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
