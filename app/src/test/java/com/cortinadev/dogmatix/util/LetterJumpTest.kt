package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterJumpTest {
    private val names = listOf("007 Racing", "1942", "Aladdin", "Alone in the Dark", "Árbol", "Bomberman", "Bubble Bobble", "Contra", "Élan", "Zelda")

    @Test fun `groups ignore case accents and brackets`() {
        assertEquals('A', LetterJump.groupOf("Árbol"))
        assertEquals('A', LetterJump.groupOf("  alone"))
        assertEquals('E', LetterJump.groupOf("Élan"))
        assertEquals('#', LetterJump.groupOf("007 Racing"))
        assertEquals('#', LetterJump.groupOf(""))
        assertEquals('B', LetterJump.groupOf("[BIOS] bios"))
    }

    @Test fun `forward goes to the first entry of the next letter`() {
        assertEquals(2, LetterJump.target(names, 0, forward = true))   // # → A
        assertEquals(5, LetterJump.target(names, 3, forward = true))   // A → B
        assertEquals(7, LetterJump.target(names, 6, forward = true))   // B → C
    }

    @Test fun `forward stays at the last letter`() {
        assertEquals(9, LetterJump.target(names, 9, forward = true))
    }

    @Test fun `backward goes to the start of the current letter, then the previous one`() {
        assertEquals(2, LetterJump.target(names, 3, forward = false))  // inside A → start of A
        assertEquals(0, LetterJump.target(names, 2, forward = false))  // start of A → start of #
        assertEquals(5, LetterJump.target(names, 7, forward = false))  // start of C → start of B
        assertEquals(0, LetterJump.target(names, 0, forward = false))
    }

    @Test fun `empty list and out of range are safe`() {
        assertEquals(0, LetterJump.target(emptyList(), 5, true))
        assertEquals(9, LetterJump.target(names, 99, true))
    }

    @Test fun `step jumps a fixed number of rows`() {
        assertEquals(10, LetterJump.step(100, 0, true))
        assertEquals(0, LetterJump.step(100, 4, false))
        assertEquals(99, LetterJump.step(100, 95, true))
        assertEquals(0, LetterJump.step(0, 3, true))
    }
}
