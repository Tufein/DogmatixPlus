package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSlotsTest {
    private fun state(path: String, modified: Long = 1000, size: Long = 50, kind: SaveKind = SaveKind.STATE, layout: SaveFolderLayout = SaveFolderLayout.FILES) =
        LocalSaveFile(kind, path, size, modified, layout = layout)

    @Test fun `retroarch slot names are parsed`() {
        assertEquals(0, SaveSlots.parse(state("Game (USA).state"))?.index)
        assertEquals(3, SaveSlots.parse(state("Game (USA).state3"))?.index)
        assertEquals(12, SaveSlots.parse(state("snes9x/Game.state12"))?.index)
        val auto = SaveSlots.parse(state("Game (USA).state.auto"))
        assertNotNull(auto)
        assertTrue(auto!!.auto)
        assertEquals("Game (USA)", auto.base)
        assertEquals("snes9x/Game", SaveSlots.parse(state("snes9x/Game.state12"))?.base)
    }

    @Test fun `only plain states are slots`() {
        assertNull(SaveSlots.parse(state("Game.srm")))
        assertNull(SaveSlots.parse(state("Game.state", kind = SaveKind.SAVE)))
        assertNull(SaveSlots.parse(state("Game.state", layout = SaveFolderLayout.SAVEDATA)))
        assertNull(SaveSlots.parse(state("Game.state1000")))
        assertNull(SaveSlots.parse(state(".state")))
        assertNull(SaveSlots.parse(state("Game.state1.png")))
    }

    @Test fun `a game with several slots becomes one group and a lone state does not`() {
        val files = listOf(
            state("A.state"), state("A.state2"), state("A.state.auto"), state("A.state10"),
            state("B.state1"),
            state("C/D.state1"), state("C/D.state2"), state("C/E.state1")
        )
        val groups = SaveSlots.groups(files)
        assertEquals(listOf("A", "C/D"), groups.map { it.base })
        assertEquals(listOf(0, 2, 10, null), groups.first().slots.map { it.index })
    }

    @Test fun `groups ignore case and sort by name`() {
        val groups = SaveSlots.groups(listOf(state("b.state1"), state("B.state2"), state("a.state1"), state("A.state3")))
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.map { it.base.lowercase() })
    }

    @Test fun `targets are the other numbered slots and the first free one`() {
        val group = SaveSlots.groups(listOf(state("A.state"), state("A.state1"), state("A.state3"), state("A.state.auto"))).single()
        val source = group.slots.first { it.index == 1 }
        val targets = SaveSlots.targets(group, source)
        assertEquals(listOf(0, 2, 3), targets.map { it.index })
        assertEquals("A.state", targets.first { it.index == 0 }.path)
        assertNotNull(targets.first { it.index == 0 }.existing)
        assertEquals("A.state2", targets.first { it.index == 2 }.path)
        assertNull(targets.first { it.index == 2 }.existing)
    }

    @Test fun `the auto slot is a source but never a target`() {
        val group = SaveSlots.groups(listOf(state("A.state1"), state("A.state.auto"))).single()
        val auto = group.slots.first { it.auto }
        assertEquals(listOf(0, 1), SaveSlots.targets(group, auto).map { it.index })
        assertTrue(SaveSlots.targets(group, auto).none { it.path.endsWith("auto") })
        assertTrue(SaveSlots.targets(group, group.slots.first { !it.auto }).none { it.index == null })
    }

    @Test fun `an existing slot keeps its own file name even with a leading zero style`() {
        val group = SaveSlots.groups(listOf(state("A.state0"), state("A.state1"))).single()
        val target = SaveSlots.targets(group, group.slots.first { it.index == 1 }).single { it.index == 0 }
        assertEquals("A.state0", target.path)
    }

    @Test fun `the plan names what is replaced`() {
        val group = SaveSlots.groups(listOf(state("A.state1", 1000, 10), state("A.state2", 2000, 20))).single()
        val source = group.slots.first { it.index == 1 }
        val replace = SaveSlots.plan(source, SaveSlots.targets(group, source).first { it.index == 2 })
        assertEquals("A.state2", replace?.targetPath)
        assertEquals(2000L, replace?.replaces?.modified)
        val fresh = SaveSlots.plan(source, SaveSlots.targets(group, source).first { it.index == 0 })
        assertEquals("A.state", fresh?.targetPath)
        assertNull(fresh?.replaces)
    }

    @Test fun `a slot is never copied onto itself`() {
        val group = SaveSlots.groups(listOf(state("A.state1"), state("A.state2"))).single()
        val source = group.slots.first()
        assertNull(SaveSlots.plan(source, SaveSlots.Target(source.index!!, source.file.path.uppercase(), source.file)))
    }

    @Test fun `the first free number skips used ones and runs out`() {
        val files = (0..99).map { state(SaveSlots.pathOf("A", it)) }
        val full = SaveSlots.groups(files).single()
        assertNull(full.firstFree)
        assertEquals(98, SaveSlots.groups(files.filterNot { it.path == "A.state98" }).single().firstFree)
        assertEquals(1, SaveSlots.groups(listOf(state("A.state"), state("A.state2"))).single().firstFree)
    }

    @Test fun `slot paths follow retroarch's naming`() {
        assertEquals("Game.state", SaveSlots.pathOf("Game", 0))
        assertEquals("Game.state7", SaveSlots.pathOf("Game", 7))
    }

    @Test fun `the service only writes numbered slots of the same game`() {
        assertTrue(SaveSlots.sameGame("snes/Game.state1", "snes/Game.state4"))
        assertTrue(SaveSlots.sameGame("snes/Game.state.auto", "snes/Game.state"))
        assertFalse(SaveSlots.sameGame("snes/Game.state1", "snes/Game.state.auto"))
        assertFalse(SaveSlots.sameGame("snes/Game.state1", "gba/Game.state2"))
        assertFalse(SaveSlots.sameGame("snes/Game.state1", "snes/Other.state2"))
        assertFalse(SaveSlots.sameGame("snes/Game.srm", "snes/Game.state2"))
        assertTrue(SaveSlots.sameGame("Game.STATE1", "game.state2"))
    }
}
