package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.CardGrid.Kind
import com.cortinadev.dogmatix.util.CardGrid.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardGridTest {
    // A headless group of one row (Tools), then two groups with headers: 3 rows and 4 rows.
    private val sections = listOf(Section(header = false, items = 1), Section(header = true, items = 3), Section(header = true, items = 4))

    @Test fun `one column puts every row on its own line`() {
        val cells = CardGrid.layout(sections, columns = 1)
        assertEquals(1 + 4 + 5, cells.size)
        assertTrue(cells.all { it.span == 1 && it.start && it.end })
        val tools = cells[0]
        assertTrue(tools.top && tools.bottom && !tools.divider)
        val header = cells[1]
        assertEquals(Kind.HEADER, header.kind)
        assertTrue(header.top && !header.bottom)
        assertTrue(cells[2].divider && !cells[2].top)
        assertTrue(cells[4].bottom)
        assertFalse(cells[3].bottom)
    }

    @Test fun `two columns pair the rows and stretch an odd last row`() {
        val cells = CardGrid.layout(sections, columns = 2)
        // Tools alone: stretched over both columns, a whole card on its own.
        assertEquals(2, cells[0].span)
        assertTrue(cells[0].start && cells[0].end && cells[0].top && cells[0].bottom)
        // Group of 3: header, a full line, then a stretched last row.
        assertEquals(Kind.HEADER, cells[1].kind)
        assertEquals(2, cells[1].span)
        val (left, right, last) = Triple(cells[2], cells[3], cells[4])
        assertTrue(left.start && !left.end && left.column == 0)
        assertTrue(!right.start && right.end && right.column == 1)
        assertFalse(left.bottom)
        assertEquals(2, last.span)
        assertTrue(last.bottom && last.start && last.end && last.divider)
        // Group of 4: two full lines, the second one at the bottom.
        val group = cells.filter { it.section == 2 && it.kind == Kind.ITEM }
        assertEquals(listOf(false, false, true, true), group.map { it.bottom })
        assertTrue(group.all { it.span == 1 })
    }

    @Test fun `empty groups are left out unless kept`() {
        val cells = CardGrid.layout(listOf(Section(true, 0), Section(true, 2), Section(true, 0, keepIfEmpty = true)), columns = 1)
        assertEquals(listOf(1, 1, 1, 2), cells.map { it.section })
        assertTrue(cells.last().kind == Kind.HEADER && cells.last().bottom)
    }

    @Test fun `hop moves between the columns of one line only`() {
        val cells = CardGrid.layout(sections, columns = 2)
        assertEquals(3, CardGrid.hop(cells, 2))
        assertEquals(2, CardGrid.hop(cells, 3))
        assertNull(CardGrid.hop(cells, 4))        // stretched last row: no partner
        assertNull(CardGrid.hop(cells, 1))        // a header
        assertNull(CardGrid.hop(cells, 0))        // Tools: the next cell is a header
        assertEquals(0, CardGrid.hop(cells, null)) // nothing focused: the first row
    }

    @Test fun `group jump goes to the first row of the next or previous group`() {
        val cells = CardGrid.layout(sections, columns = 1)
        // Header indices: 1 and 5.
        assertEquals(1 to 2, CardGrid.groupJump(cells, 0, forward = true))
        assertEquals(5 to 6, CardGrid.groupJump(cells, 3, forward = true))
        assertNull(CardGrid.groupJump(cells, 7, forward = true))
        assertEquals(1 to 2, CardGrid.groupJump(cells, 7, forward = false))
        assertEquals(1 to 2, CardGrid.groupJump(cells, 3, forward = false)) // first group stays
        assertEquals(1 to 2, CardGrid.groupJump(cells, null, forward = true))
    }
}
