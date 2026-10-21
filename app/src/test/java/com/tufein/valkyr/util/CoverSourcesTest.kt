package com.tufein.valkyr.util

import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.CoverSources.Cached
import com.tufein.valkyr.util.CoverSources.Chain
import com.tufein.valkyr.util.CoverSources.Plan
import com.tufein.valkyr.util.CoverSources.Source
import com.tufein.valkyr.util.CoverSources.Tally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSourcesTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val all = setOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA)

    @Test fun `the saved order is cleaned and completed`() {
        assertEquals(CoverSources.DEFAULT_ORDER, CoverSources.orderOf(null))
        assertEquals(CoverSources.DEFAULT_ORDER, CoverSources.orderOf("nonsense,,"))
        assertEquals(listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.METADATA), CoverSources.orderOf("screenscraper,libretro,metadata"))
        // RomM never moves; a missing source comes back at its default place; duplicates go.
        assertEquals(listOf(Source.LIBRETRO, Source.METADATA, Source.SCREENSCRAPER), CoverSources.orderOf("romm,metadata,screenscraper,metadata"))
        assertEquals(listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA), CoverSources.orderOf("metadata"))
        val order = listOf(Source.METADATA, Source.LIBRETRO, Source.SCREENSCRAPER)
        assertEquals(order, CoverSources.orderOf(CoverSources.encodeOrder(order)))
    }

    @Test fun `moving works on the value and stops at the ends`() {
        val order = CoverSources.DEFAULT_ORDER
        assertEquals(listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.METADATA), CoverSources.move(order, Source.SCREENSCRAPER, -1))
        assertEquals(listOf(Source.LIBRETRO, Source.METADATA, Source.SCREENSCRAPER), CoverSources.move(order, Source.SCREENSCRAPER, 1))
        assertEquals(order, CoverSources.move(order, Source.LIBRETRO, -1))
        assertEquals(order, CoverSources.move(order, Source.METADATA, 5))
        assertEquals(order, CoverSources.move(order, Source.ROMM, 1))
    }

    @Test fun `romm is always first and switched off sources drop out`() {
        val chain = Chain(order = listOf(Source.METADATA, Source.LIBRETRO, Source.SCREENSCRAPER), disabled = setOf(Source.LIBRETRO))
        assertEquals(listOf(Source.ROMM, Source.METADATA, Source.SCREENSCRAPER), chain.active)
        assertEquals(setOf(Source.SCREENSCRAPER), CoverSources.disabledOf(setOf("screenscraper", "bogus")))
    }

    @Test fun `no row asks every usable source in order`() {
        val chain = Chain(order = listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.METADATA))
        assertEquals(Plan.Ask(listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.METADATA)), CoverSources.plan(chain, all, null, now))
        // ScreenScraper without credentials (or paused) is simply not asked.
        assertEquals(Plan.Ask(listOf(Source.LIBRETRO, Source.METADATA)), CoverSources.plan(chain, all - Source.SCREENSCRAPER, null, now))
    }

    @Test fun `a fresh cover is used unless its source was switched off`() {
        val row = Cached("https://img/x.png", "libretro", now - day)
        assertEquals(Plan.Use("https://img/x.png"), CoverSources.plan(Chain(), all, row, now))
        val off = Chain(disabled = setOf(Source.LIBRETRO))
        assertEquals(Plan.Ask(listOf(Source.SCREENSCRAPER, Source.METADATA)), CoverSources.plan(off, all, row, now))
        // An old cover is looked up again.
        val old = row.copy(fetchedAt = now - 40 * day)
        assertTrue(CoverSources.plan(Chain(), all, old, now) is Plan.Ask)
        // A row with an unknown tag is kept.
        assertEquals(Plan.Use("u"), CoverSources.plan(Chain(), all, Cached("u", "something", now), now))
    }

    @Test fun `a fresh miss only asks the sources that have not answered yet`() {
        val miss = Cached("", CoverSources.missRecord(setOf(Source.LIBRETRO, Source.METADATA)), now - day)
        // ScreenScraper was not usable then: it is asked now that it is.
        assertEquals(Plan.Ask(listOf(Source.SCREENSCRAPER), setOf(Source.LIBRETRO, Source.METADATA), now - day), CoverSources.plan(Chain(), all, miss, now))
        // Still not usable: nothing new to ask.
        assertEquals(Plan.KnownMiss, CoverSources.plan(Chain(), all - Source.SCREENSCRAPER, miss, now))
        // Old miss: everyone again.
        assertEquals(Plan.Ask(listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA)), CoverSources.plan(Chain(), all, miss.copy(fetchedAt = now - 4 * day), now))
    }

    @Test fun `a miss from before 4_3 counts as libretro and metadata`() {
        assertEquals(setOf(Source.LIBRETRO, Source.METADATA), CoverSources.triedBy(""))
        assertNull(CoverSources.triedBy("libretro"))
        assertEquals(setOf(Source.SCREENSCRAPER), CoverSources.triedBy("miss:screenscraper"))
        assertEquals(Plan.Ask(listOf(Source.SCREENSCRAPER), setOf(Source.LIBRETRO, Source.METADATA), now), CoverSources.plan(Chain(), all, Cached("", "", now), now))
    }

    @Test fun `a found cover is stored with its source`() {
        val ask = Plan.Ask(listOf(Source.LIBRETRO, Source.SCREENSCRAPER))
        val s = CoverSources.settle(listOf(Source.LIBRETRO to Answer.Miss, Source.SCREENSCRAPER to Answer.Found("https://ss/x")), ask, now)
        assertEquals("https://ss/x", s.url)
        assertEquals("screenscraper", s.record)
        assertEquals(now, s.fetchedAt)
        assertTrue(s.remember)
    }

    @Test fun `an unavailable source is never stored as a miss`() {
        val ask = Plan.Ask(listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA))
        val s = CoverSources.settle(listOf(Source.LIBRETRO to Answer.Miss, Source.SCREENSCRAPER to Answer.Unavailable, Source.METADATA to Answer.Miss), ask, now)
        assertNull(s.url)
        assertEquals("miss:libretro+metadata", s.record)
        assertFalse(s.remember)
        // Offline: libretro could not answer either, and the local metadata miss alone is not stored.
        val offline = CoverSources.settle(listOf(Source.LIBRETRO to Answer.Unavailable, Source.METADATA to Answer.Miss), ask, now)
        assertNull(offline.record)
        assertFalse(offline.remember)
    }

    @Test fun `asking a new source keeps the old answers and their time`() {
        val ask = Plan.Ask(listOf(Source.SCREENSCRAPER), setOf(Source.LIBRETRO, Source.METADATA), now - day)
        val s = CoverSources.settle(listOf(Source.SCREENSCRAPER to Answer.Miss), ask, now)
        assertEquals("miss:libretro+screenscraper+metadata", s.record)
        assertEquals(now - day, s.fetchedAt)
        assertTrue(s.remember)
    }

    @Test fun `daily tallies start again on a new day and survive text`() {
        val t = Tally().count(10, Tally.Kind.HIT).count(10, Tally.Kind.HIT).count(10, Tally.Kind.MISS).count(10, Tally.Kind.ERROR)
        assertEquals(Tally(10, 2, 1, 1), t)
        assertEquals(Tally(11, 0, 1, 0), t.count(11, Tally.Kind.MISS))
        assertEquals(Tally(12), t.on(12))
        assertEquals(t, Tally.decode(t.encode()))
        assertEquals(Tally(), Tally.decode("broken"))
        assertEquals(Tally(), Tally.decode("1:-2:0:0"))
        assertEquals(Tally.Kind.ERROR, CoverSources.answerKind(Answer.Unavailable))
    }

    @Test fun `the settings are typed for backups`() {
        assertEquals("s", SettingSchema.expectedType(CoverSources.ORDER_KEY))
        assertEquals("ss", SettingSchema.expectedType(CoverSources.DISABLED_KEY))
        assertEquals("b", SettingSchema.expectedType(CoverSources.NON_BOX_KEY))
        assertEquals("s", SettingSchema.expectedType(ScreenScraper.DEV_ID_KEY))
    }
}
