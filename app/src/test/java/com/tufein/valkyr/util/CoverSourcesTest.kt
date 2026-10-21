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

    private val keyed = listOf(Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB)
    private val everything = all + keyed

    @Test fun `the saved order is cleaned and completed`() {
        assertEquals(CoverSources.DEFAULT_ORDER, CoverSources.orderOf(null))
        assertEquals(CoverSources.DEFAULT_ORDER, CoverSources.orderOf("nonsense,,"))
        assertEquals(listOf(Source.SCREENSCRAPER, Source.LIBRETRO) + keyed + Source.METADATA, CoverSources.orderOf("screenscraper,libretro,metadata"))
        // RomM never moves; a missing source comes back at its default place; duplicates go.
        assertEquals(listOf(Source.LIBRETRO, Source.METADATA) + keyed + Source.SCREENSCRAPER, CoverSources.orderOf("romm,metadata,screenscraper,metadata"))
        assertEquals(CoverSources.DEFAULT_ORDER, CoverSources.orderOf("metadata"))
        val order = listOf(Source.METADATA, Source.IGDB, Source.LIBRETRO, Source.STEAMGRIDDB, Source.SCREENSCRAPER, Source.THEGAMESDB)
        assertEquals(order, CoverSources.orderOf(CoverSources.encodeOrder(order)))
    }

    @Test fun `a 4_3 order gets the new sources after screenscraper and before the metadata image`() {
        assertEquals(
            listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA),
            CoverSources.orderOf("libretro,screenscraper,metadata")
        )
        // The user's own order of the old sources is kept.
        assertEquals(
            listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA),
            CoverSources.orderOf("screenscraper,libretro,metadata")
        )
        assertEquals(
            listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA),
            CoverSources.DEFAULT_ORDER
        )
    }

    @Test fun `moving works on the value and stops at the ends`() {
        val order = CoverSources.DEFAULT_ORDER
        assertEquals(listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA), CoverSources.move(order, Source.SCREENSCRAPER, -1))
        assertEquals(listOf(Source.LIBRETRO, Source.THEGAMESDB, Source.SCREENSCRAPER, Source.STEAMGRIDDB, Source.IGDB, Source.METADATA), CoverSources.move(order, Source.SCREENSCRAPER, 1))
        assertEquals(order, CoverSources.move(order, Source.LIBRETRO, -1))
        assertEquals(order, CoverSources.move(order, Source.METADATA, 5))
        assertEquals(order, CoverSources.move(order, Source.ROMM, 1))
    }

    @Test fun `the new sources start switched off until a switch was ever saved`() {
        assertEquals(setOf(Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB), CoverSources.disabledOf(null))
        // A saved (even empty) set is the user's own: nothing is added to it.
        assertEquals(emptySet<Source>(), CoverSources.disabledOf(emptySet()))
        assertEquals(setOf(Source.LIBRETRO), CoverSources.disabledOf(setOf("libretro")))
        val fresh = Chain(disabled = CoverSources.disabledOf(null))
        assertEquals(listOf(Source.ROMM, Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA), fresh.active)
        assertTrue(Source.IGDB.needsKey && Source.THEGAMESDB.needsKey && Source.STEAMGRIDDB.needsKey && !Source.LIBRETRO.needsKey)
    }

    @Test fun `the longer chain is asked in order and stops at the first cover`() {
        val chain = Chain(order = listOf(Source.THEGAMESDB, Source.LIBRETRO, Source.IGDB, Source.STEAMGRIDDB, Source.SCREENSCRAPER, Source.METADATA))
        val ask = CoverSources.plan(chain, everything, null, now) as Plan.Ask
        assertEquals(listOf(Source.THEGAMESDB, Source.LIBRETRO, Source.IGDB, Source.STEAMGRIDDB, Source.SCREENSCRAPER, Source.METADATA), ask.sources)
        // A source without its key is not usable and is left out; one that is switched off too.
        val partial = CoverSources.plan(chain.copy(disabled = setOf(Source.IGDB)), everything - Source.STEAMGRIDDB, null, now) as Plan.Ask
        assertEquals(listOf(Source.THEGAMESDB, Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA), partial.sources)
    }

    @Test fun `a cover from a source that lost its key is looked up again`() {
        val row = Cached("https://img/x.jpg", "igdb", now - day)
        assertEquals(Plan.Use("https://img/x.jpg"), CoverSources.plan(Chain(), everything, row, now))
        assertEquals(
            Plan.Ask(listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.METADATA)),
            CoverSources.plan(Chain(), everything - Source.IGDB, row, now, unconfigured = setOf(Source.IGDB))
        )
        // Switched off: replaced too.
        assertTrue(CoverSources.plan(Chain(disabled = setOf(Source.IGDB)), everything, row, now) is Plan.Ask)
    }

    @Test fun `a fresh miss asks a new source as soon as its key exists`() {
        val miss = Cached("", CoverSources.missRecord(setOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA)), now - day)
        assertEquals(Plan.KnownMiss, CoverSources.plan(Chain(), all, miss, now))
        assertEquals(
            Plan.Ask(listOf(Source.THEGAMESDB, Source.IGDB), setOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.METADATA), now - day),
            CoverSources.plan(Chain(), all + Source.THEGAMESDB + Source.IGDB, miss, now)
        )
    }

    @Test fun `a new source that could not answer is never stored as a miss`() {
        val ask = Plan.Ask(listOf(Source.LIBRETRO, Source.THEGAMESDB, Source.STEAMGRIDDB, Source.IGDB))
        val s = CoverSources.settle(
            listOf(Source.LIBRETRO to Answer.Miss, Source.THEGAMESDB to Answer.Miss, Source.STEAMGRIDDB to Answer.Unavailable, Source.IGDB to Answer.Miss),
            ask, now
        )
        assertNull(s.url)
        assertEquals("miss:libretro+thegamesdb+igdb", s.record)
        assertFalse(s.remember)
        val found = CoverSources.settle(listOf(Source.LIBRETRO to Answer.Miss, Source.STEAMGRIDDB to Answer.Found("https://cdn2.steamgriddb.com/grid/a.png")), ask, now)
        assertEquals("steamgriddb", found.record)
        assertEquals(setOf(Source.THEGAMESDB, Source.IGDB), CoverSources.triedBy("miss:thegamesdb+igdb"))
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
        listOf(CoverApis.TGDB_KEY, CoverApis.SGDB_KEY, CoverApis.IGDB_CLIENT_ID_KEY, CoverApis.IGDB_CLIENT_SECRET_KEY).forEach {
            assertEquals(it, "s", SettingSchema.expectedType(it))
        }
    }

    @Test fun `a screenscraper cover is not kept once screenscraper is no longer set up`() {
        val row = Cached("https://ss/x", "screenscraper", now - day)
        assertEquals(Plan.Use("https://ss/x"), CoverSources.plan(Chain(), all, row, now))
        assertEquals(
            Plan.Ask(listOf(Source.LIBRETRO, Source.METADATA)),
            CoverSources.plan(Chain(), all - Source.SCREENSCRAPER, row, now, unconfigured = setOf(Source.SCREENSCRAPER))
        )
        // A libretro cover is unaffected.
        assertEquals(Plan.Use("u"), CoverSources.plan(Chain(), all, Cached("u", "libretro", now), now, unconfigured = setOf(Source.SCREENSCRAPER)))
    }
}
