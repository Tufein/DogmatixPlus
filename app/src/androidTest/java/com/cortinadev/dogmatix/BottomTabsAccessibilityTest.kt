package com.cortinadev.dogmatix

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.ui.components.BottomTabs
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class BottomTabsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowFrenchNavigationKeepsFullLargeTextLabelsVisibleAndClickable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val french = context.createConfigurationContext(config)
        var selected: NavRoutes? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides french,
                LocalConfiguration provides config,
                LocalDensity provides Density(density, 1.6f)
            ) {
                DogmatixTheme {
                    Box(Modifier.width(360.dp)) { BottomTabs(NavRoutes.Home.route, 12) { selected = it } }
                }
            }
        }
        NavRoutes.tabs.forEach { tab ->
            val label = french.getString(tab.labelRes)
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertFalse("Full $label must fit vertically at 160% text size", layout.didOverflowHeight)
            // Paragraph width includes unused space; check the rendered lines inside the rounded node.
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { line ->
                assertFalse("$label must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("Full $label must fit horizontally: right=${layout.getLineRight(line)}, node=${layout.size.width}", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            }
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val buttonBounds = compose.onNode(hasClickAction() and hasText(label)).fetchSemanticsNode().boundsInRoot
            assertTrue("$label must remain inside its touch target", textBounds.top >= buttonBounds.top && textBounds.bottom <= buttonBounds.bottom)
        }
        compose.onNode(hasClickAction() and hasText(french.getString(NavRoutes.Downloads.labelRes))).performClick()
        compose.runOnIdle { assertEquals(NavRoutes.Downloads, selected) }
    }
}
