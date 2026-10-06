package com.cortinadev.dogmatix.ui.screens.settings

import com.cortinadev.dogmatix.util.CardGrid
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLinesTest {

    @Test
    fun twoColumnsGroupRowsInPairsAndHeadersAlone() {
        // A headerless card of 1 row, a card with header and 3 rows, a card with header and 2 rows.
        val cells = CardGrid.layout(
            listOf(CardGrid.Section(false, 1), CardGrid.Section(true, 3), CardGrid.Section(true, 2)),
            columns = 2
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6, 7)),
            settingsLines(cells)
        )
        assertEquals(3, settingsItemOf(cells, 4))
        assertEquals(4, settingsItemOf(cells, 4, hasTitle = true))
    }

    @Test
    fun oneColumnIsOneCellPerLine() {
        val cells = CardGrid.layout(listOf(CardGrid.Section(true, 2)), columns = 1)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), settingsLines(cells))
    }
}
