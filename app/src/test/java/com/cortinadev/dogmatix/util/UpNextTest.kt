package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {

    private fun e(name: String, state: UpNextState = UpNextState.QUEUED, changed: Long = 0L) =
        UpNextEntry("snes", "$name.zip", name, addedAt = 1L, state = state, changedAt = changed)

    private fun names(list: List<UpNextEntry>) = list.map { it.title }

    @Test
    fun `add goes after the open games and never twice`() {
        var list = listOf(e("A"), e("Done", UpNextState.FINISHED, 5L))
        list = UpNext.add(list, e("B"))
        assertEquals(listOf("A", "B", "Done"), names(list))
        assertEquals(list, UpNext.add(list, e("B")))
        // A finished game added again stays where it is (already on the list).
        assertEquals(list, UpNext.add(list, e("Done")))
    }

    @Test
    fun `a full list drops its oldest finished game, or refuses`() {
        val open = (1 until UpNext.MAX_ENTRIES).map { e("G$it") }
        val full = open + e("Old", UpNextState.FINISHED, 1L)
        val added = UpNext.add(full, e("New"))
        assertEquals(UpNext.MAX_ENTRIES, added.size)
        assertFalse(added.any { it.title == "Old" })
        assertTrue(added.any { it.title == "New" })
        val allOpen = (0 until UpNext.MAX_ENTRIES).map { e("H$it") }
        assertEquals(allOpen, UpNext.add(allOpen, e("New")))
    }

    @Test
    fun `move shifts among the open games only`() {
        val list = listOf(e("A"), e("B"), e("C"), e("Done", UpNextState.FINISHED, 3L))
        assertEquals(listOf("B", "A", "C", "Done"), names(UpNext.move(list, UpNext.key("snes", "B.zip"), -1)))
        assertEquals(listOf("A", "B", "C", "Done"), names(UpNext.move(list, UpNext.key("snes", "C.zip"), 1)))
        assertEquals(listOf("A", "B", "C", "Done"), names(UpNext.move(list, UpNext.key("snes", "Done.zip"), -1)))
        assertTrue(UpNext.canMove(list, UpNext.key("snes", "B.zip"), 1))
        assertFalse(UpNext.canMove(list, UpNext.key("snes", "A.zip"), -1))
        assertFalse(UpNext.canMove(list, UpNext.key("snes", "C.zip"), 1))
        assertFalse(UpNext.canMove(list, UpNext.key("snes", "Done.zip"), -1))
    }

    @Test
    fun `starting moves to the top, finishing sinks, queued again rejoins the open ones`() {
        val list = listOf(e("A"), e("B"), e("C"))
        val started = UpNext.setState(list, UpNext.key("snes", "C.zip"), UpNextState.STARTED, 10L)
        assertEquals(listOf("C", "A", "B"), names(started))
        assertEquals(10L, started.first().changedAt)
        val finished = UpNext.setState(started, UpNext.key("snes", "C.zip"), UpNextState.FINISHED, 20L)
        assertEquals(listOf("A", "B", "C"), names(finished))
        val again = UpNext.setState(finished + e("D", UpNextState.FINISHED, 30L), UpNext.key("snes", "C.zip"), UpNextState.QUEUED, 40L)
        assertEquals(listOf("A", "B", "C", "D"), names(again))
        assertEquals(0L, again[2].changedAt)
        assertEquals(listOf("A", "B"), names(UpNext.clearFinished(finished)))
        assertEquals(listOf("A"), names(UpNext.open(finished, 1)))
    }

    @Test
    fun `finished games show newest first`() {
        val list = listOf(e("Old", UpNextState.FINISHED, 1L), e("A"), e("New", UpNextState.FINISHED, 9L))
        assertEquals(listOf("A", "New", "Old"), names(UpNext.normalized(list)))
    }

    @Test
    fun `json round trip skips broken and duplicate entries`() {
        val list = listOf(e("A"), e("B", UpNextState.STARTED, 4L), e("C", UpNextState.FINISHED, 7L))
        assertEquals(list, UpNext.fromJson(UpNext.toJson(list)))
        val broken = """[{"console":"snes","file":"A.zip","title":"A","added":1,"state":"WEIRD"},{"file":"x"},3,
            {"console":"snes","file":"A.zip","title":"A again","added":2}]"""
        val read = UpNext.fromJson(broken)
        assertEquals(1, read.size)
        assertEquals(UpNextState.QUEUED, read.single().state)
        assertEquals(emptyList<UpNextEntry>(), UpNext.fromJson("not json"))
        assertEquals(emptyList<UpNextEntry>(), UpNext.fromJson(null))
    }

    @Test
    fun `profile file names are safe and distinct`() {
        assertEquals("upnext_default.json", UpNext.fileNameFor(""))
        assertEquals("upnext_p_kid1.json", UpNext.fileNameFor("kid1"))
        val odd = UpNext.fileNameFor("../x")
        assertFalse(odd.contains('/'))
        assertNotEquals(UpNext.fileNameFor("a/b"), UpNext.fileNameFor("a.b"))
    }
}
