package com.cortinadev.dogmatix.util

import java.text.Normalizer

/**
 * Jumping through a long, name-sorted list by first letter (◀ ▶ on a gamepad): to the first entry
 * of the next letter, or back to the first entry of the current / previous letter. Digits and
 * symbols form one group in front of "A".
 */
object LetterJump {

    /** Group a name belongs to: its first letter without accent, upper case, or `#`. */
    fun groupOf(name: String): Char {
        val folded = Normalizer.normalize(name.trim().trimStart('(', '[', '"', '\'', '.', '!', '¡', '¿'), Normalizer.Form.NFD)
        val first = folded.firstOrNull { !it.isWhitespace() } ?: return '#'
        return if (first.isLetter()) first.uppercaseChar() else '#'
    }

    /**
     * Index to move to from [current] in a list sorted by name. [forward] goes to the first entry
     * of the next group; backwards goes to the start of the current group, or of the previous one
     * when already at the start. Stays put at either end.
     */
    fun target(names: List<String>, current: Int, forward: Boolean): Int {
        if (names.isEmpty()) return 0
        val index = current.coerceIn(0, names.lastIndex)
        val group = groupOf(names[index])
        return if (forward) {
            var i = index + 1
            while (i < names.size && groupOf(names[i]) == group) i++
            if (i >= names.size) index else i
        } else {
            var start = index
            while (start > 0 && groupOf(names[start - 1]) == group) start--
            if (start < index) start
            else if (start == 0) 0
            else {
                val previous = groupOf(names[start - 1])
                var i = start - 1
                while (i > 0 && groupOf(names[i - 1]) == previous) i--
                i
            }
        }
    }

    /** For lists not sorted by name (by size): a fixed step instead of a letter. */
    fun step(size: Int, current: Int, forward: Boolean, by: Int = 10): Int =
        if (size == 0) 0 else (current + if (forward) by else -by).coerceIn(0, size - 1)
}
