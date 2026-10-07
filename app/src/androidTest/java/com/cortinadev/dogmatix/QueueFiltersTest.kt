package com.cortinadev.dogmatix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.ui.screens.download.QueueFilters
import com.cortinadev.dogmatix.ui.screens.download.QueueNoMatches
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.DownloadQueueFilter
import com.cortinadev.dogmatix.util.QueueFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real controls on a handheld-width surface: search, horizontal filter access and scoped selection. */
class QueueFiltersTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows = listOf(
        row("Mario", DownloadStatus.FAILED),
        row("Sonic", DownloadStatus.DOWNLOADING),
        row("Zelda", DownloadStatus.COMPLETED)
    )

    @Test fun searchButtonAcceptsInputAndClearRestoresTheWholeList() {
        val criteria = mutableStateOf(DownloadQueueFilter.Criteria())
        compose.setContent {
            DogmatixTheme {
                val view = DownloadQueueFilter.project(rows, criteria.value, emptySet())
                Column(Modifier.width(400.dp)) {
                    QueueFilters(view, criteria.value, false,
                        onSearch = { criteria.value = criteria.value.copy(query = it) },
                        onFilter = { criteria.value = criteria.value.copy(status = it) },
                        onClear = { criteria.value = DownloadQueueFilter.Criteria() }, onSelectShown = {})
                    view.rows.forEach { Text(it.name) }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.queue23_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("mario")
        compose.onNodeWithText("Mario").assertIsDisplayed()
        compose.onNodeWithText("Sonic").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.queue23_showing, 1, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.queue23_clear_filters)).performClick()
        compose.onNodeWithText("Sonic").assertIsDisplayed()
        compose.onNodeWithText("Zelda").assertIsDisplayed()
    }

    @Test fun offscreenStatusChipCanBeReachedAndSelectShownExcludesHiddenRows() {
        val criteria = mutableStateOf(DownloadQueueFilter.Criteria())
        val selected = mutableStateOf<Set<String>>(emptySet())
        compose.setContent {
            DogmatixTheme {
                val view = DownloadQueueFilter.project(rows, criteria.value, emptySet())
                Column(Modifier.width(400.dp)) {
                    QueueFilters(view, criteria.value, view.visibleNames.isNotEmpty() && selected.value.containsAll(view.visibleNames),
                        onSearch = { selected.value = emptySet(); criteria.value = criteria.value.copy(query = it) },
                        onFilter = { selected.value = emptySet(); criteria.value = criteria.value.copy(status = it) },
                        onClear = { selected.value = emptySet(); criteria.value = DownloadQueueFilter.Criteria() },
                        onSelectShown = { selected.value = DownloadQueueFilter.toggleVisibleSelection(selected.value, view.visibleNames) })
                    view.rows.forEach { Text(it.name) }
                }
            }
        }
        val problems = context.getString(R.string.queue23_problems) + " · 1"
        compose.onNodeWithText(problems).performScrollTo().performClick()
        compose.onNodeWithText("Mario").assertIsDisplayed()
        compose.onNodeWithText("Sonic").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.queue23_select_shown)).performClick()
        compose.runOnIdle { assertEquals(setOf("Mario.zip"), selected.value) }
        compose.onNodeWithText(context.getString(R.string.queue23_clear_filters)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selected.value.isEmpty()) }
        compose.onNodeWithText("Sonic").assertIsDisplayed()
    }

    @Test fun noMatchesOffersAWorkingReset() {
        val criteria = mutableStateOf(DownloadQueueFilter.Criteria("missing"))
        compose.setContent {
            DogmatixTheme {
                val view = DownloadQueueFilter.project(rows, criteria.value, emptySet())
                Column(Modifier.width(400.dp)) {
                    if (view.rows.isEmpty()) QueueNoMatches(onClear = { criteria.value = DownloadQueueFilter.Criteria() })
                    else view.rows.forEach { Text(it.name) }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.queue23_no_matches)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.queue23_clear_filters)).performClick()
        compose.onNodeWithText("Mario").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.queue23_no_matches)).assertDoesNotExist()
    }

    private fun row(name: String, status: DownloadStatus) =
        DownloadItemModel(name, "$name.zip", 0f, 0f, 1L, status = status)
}
