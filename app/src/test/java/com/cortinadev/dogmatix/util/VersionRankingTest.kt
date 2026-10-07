package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRankingTest {
    private val english = VersionPreferences.defaultFor(setOf("EN"), null, null)
    private fun of(names: List<String>, fixed: String?) =
        VersionRanking.of(names, { VersionPicker.Candidate(it, it) }, english, fixed)

    @Test fun `the pick comes first and is the same as every other picker`() {
        val names = listOf("G (Japan).gba", "G (Europe).gba", "G (USA).gba")
        val r = of(names, null)
        assertEquals("G (USA).gba", r.pick)
        assertEquals(VersionPreference.pick(names.map { VersionPicker.Candidate(it, it) }, english, null)!!.id, r.pick)
        assertEquals(listOf("G (USA).gba", "G (Europe).gba", "G (Japan).gba"), r.rows.map { it.item })
        assertTrue(r.rows.first().isTop && r.rows.first().isPick)
        assertNull(r.rows.first().whyNot)
        assertTrue(r.rows.drop(1).all { it.whyNot != null })
    }

    @Test fun `a fixed version is the pick and the best ranked one says so`() {
        val r = of(listOf("G (Japan).gba", "G (USA).gba"), "G (Japan).gba")
        assertEquals("G (Japan).gba", r.pick)
        assertEquals(listOf("G (Japan).gba", "G (USA).gba"), r.rows.map { it.item })
        assertTrue(r.rows[0].isFixed && !r.rows[0].isTop)
        assertTrue(r.rows[1].isTop && !r.rows[1].isPick)
    }

    @Test fun `one version has no pick`() {
        val r = of(listOf("G (USA).gba"), null)
        assertNull(r.pick)
        assertEquals(1, r.rows.size)
    }

    @Test fun `two rows with the same file name stay two rows`() {
        val r = of(listOf("G (USA).gba", "G (USA).gba"), null)
        assertEquals(2, r.rows.size)
        assertEquals(1, r.rows.count { it.isPick })
    }

    @Test fun `a decoded filename stays marked as fixed in the displayed ranking`() {
        val r = of(listOf("G (Japan).gba", "G (USA).gba"), "G%20%28Japan%29.gba")
        assertEquals("G (Japan).gba", r.pick)
        assertTrue(r.rows.first().isFixed && r.rows.first().isPick)
    }
}
