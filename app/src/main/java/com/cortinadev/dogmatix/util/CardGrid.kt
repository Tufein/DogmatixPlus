package com.cortinadev.dogmatix.util

/**
 * Lays the rows of the 5.0 settings cards (Settings, RomM, Save sync) out on a lazy grid of
 * [columns] columns, so that the cells of one group, drawn side by side, read as one card.
 *
 * Every cell knows which edges of its card it sits on (rounded corners and a border are drawn
 * only there) and whether a hairline separates it from the line above. A group's optional header
 * spans the whole width on top of its card; when the last line of a group is not full, its last
 * row stretches over the free columns, so the card keeps straight edges.
 *
 * The same cells drive the LB / RB gamepad hops: [hop] moves between the columns of one line,
 * [groupJump] to the first row of the previous / next group.
 */
object CardGrid {

    enum class Kind { HEADER, ITEM }

    /** One group: an optional header and [items] rows. Empty groups are left out unless [keepIfEmpty]. */
    data class Section(val header: Boolean, val items: Int, val keepIfEmpty: Boolean = false)

    data class Cell(
        /** Index of the group in the list given to [layout]. */
        val section: Int,
        val kind: Kind,
        /** Index of the row inside its group; -1 for a header. */
        val item: Int,
        /** First column the cell occupies (0 = start). */
        val column: Int,
        /** Columns the cell covers. */
        val span: Int,
        val top: Boolean,
        val bottom: Boolean,
        val start: Boolean,
        val end: Boolean,
        /** A hairline above the cell: every line of rows except a headerless card's first one. */
        val divider: Boolean
    )

    fun layout(sections: List<Section>, columns: Int): List<Cell> {
        val cols = columns.coerceAtLeast(1)
        val cells = ArrayList<Cell>()
        sections.forEachIndexed { s, section ->
            val items = section.items.coerceAtLeast(0)
            if (items == 0 && !section.keepIfEmpty) return@forEachIndexed
            if (section.header) {
                cells += Cell(s, Kind.HEADER, -1, 0, cols, top = true, bottom = items == 0, start = true, end = true, divider = false)
            }
            val lines = (items + cols - 1) / cols
            for (i in 0 until items) {
                val line = i / cols
                val column = i % cols
                val last = i == items - 1
                // The last row of a short last line takes the free columns too.
                val span = if (last) cols - column else 1
                cells += Cell(
                    section = s,
                    kind = Kind.ITEM,
                    item = i,
                    column = column,
                    span = span,
                    top = !section.header && line == 0,
                    bottom = line == lines - 1,
                    start = column == 0,
                    end = column + span >= cols,
                    divider = section.header || line > 0
                )
            }
        }
        return cells
    }

    /**
     * LB / RB in a two-column card: the row in the other column of the same line, or null when
     * there is none (a stretched last row, a header, another group). With nothing focused yet
     * ([current] null) the first row of the grid.
     */
    fun hop(cells: List<Cell>, current: Int?): Int? {
        if (current == null || current !in cells.indices) return cells.indexOfFirst { it.kind == Kind.ITEM }.takeIf { it >= 0 }
        val here = cells[current]
        if (here.kind != Kind.ITEM) return null
        val target = if (here.column == 0) current + 1 else current - 1
        val there = cells.getOrNull(target) ?: return null
        return target.takeIf { there.kind == Kind.ITEM && there.section == here.section && there.column != here.column }
    }

    /**
     * LB / RB in one column: the header and the first row of the next ([forward]) or previous
     * group with a header, as a pair (scroll to, focus). Going back from the first group stays
     * on it. Null when there is no such group.
     */
    fun groupJump(cells: List<Cell>, current: Int?, forward: Boolean): Pair<Int, Int>? {
        val headers = cells.indices.filter { cells[it].kind == Kind.HEADER }
        val focused = current?.takeIf { it in cells.indices && cells[it].kind == Kind.ITEM }
        val here = focused?.let { c -> headers.lastOrNull { it < c } } ?: -1
        val header = if (forward) headers.firstOrNull { it > here } else headers.lastOrNull { it < here } ?: headers.firstOrNull()
        header ?: return null
        val row = (header + 1 until cells.size).firstOrNull { cells[it].section == cells[header].section && cells[it].kind == Kind.ITEM }
            ?: return null
        return header to row
    }
}
