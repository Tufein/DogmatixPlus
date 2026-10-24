package com.tufein.valkyr.util

import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.CoverSources.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtworkTest {

    private val order = listOf(Source.LIBRETRO, Source.SCREENSCRAPER, Source.ROMM)

    @Test fun `the saved order only holds sources that have these pictures`() {
        assertEquals(order, CoverArtwork.orderOf(null))
        assertEquals(order, CoverArtwork.orderOf("igdb,steamgriddb,nonsense"))
        assertEquals(listOf(Source.ROMM, Source.LIBRETRO, Source.SCREENSCRAPER), CoverArtwork.orderOf("romm,libretro,screenscraper"))
        // A missing source returns at its default place, a duplicate goes.
        assertEquals(listOf(Source.LIBRETRO, Source.ROMM, Source.SCREENSCRAPER), CoverArtwork.orderOf("romm,screenscraper,romm"))
        assertEquals("romm,libretro,screenscraper", CoverArtwork.encodeOrder(listOf(Source.ROMM, Source.LIBRETRO, Source.SCREENSCRAPER, Source.IGDB)))
    }

    @Test fun `until a switch is touched the 5_5 behaviour stands`() {
        // Only libretro-thumbnails is asked...
        assertEquals(setOf(Source.SCREENSCRAPER, Source.ROMM), CoverArtwork.disabledOf(null, emptySet()))
        // ...and it stays off when the cover chain switched it off, as 5.5 did.
        assertEquals(setOf(Source.SCREENSCRAPER, Source.ROMM, Source.LIBRETRO), CoverArtwork.disabledOf(null, setOf(Source.LIBRETRO, Source.IGDB)))
        assertEquals(listOf(Source.LIBRETRO), CoverArtwork.Chain().active)
    }

    @Test fun `once a switch was saved the saved set is the truth`() {
        assertEquals(emptySet<Source>(), CoverArtwork.disabledOf(emptySet(), setOf(Source.LIBRETRO)))
        assertEquals(setOf(Source.LIBRETRO), CoverArtwork.disabledOf(setOf("libretro", "igdb", "bogus"), emptySet()))
    }

    @Test fun `the active sources keep the user's order`() {
        val chain = CoverArtwork.Chain(listOf(Source.ROMM, Source.LIBRETRO, Source.SCREENSCRAPER), setOf(Source.LIBRETRO))
        assertEquals(listOf(Source.ROMM, Source.SCREENSCRAPER), chain.active)
        assertTrue(chain.enabled(Source.ROMM))
        assertFalse(chain.enabled(Source.LIBRETRO))
    }

    @Test fun `moving works on the value`() {
        assertEquals(listOf(Source.SCREENSCRAPER, Source.LIBRETRO, Source.ROMM), CoverArtwork.move(order, Source.SCREENSCRAPER, -1))
        assertEquals(order, CoverArtwork.move(order, Source.LIBRETRO, -1))
        assertEquals(listOf(Source.LIBRETRO, Source.ROMM, Source.SCREENSCRAPER), CoverArtwork.move(order, Source.ROMM, -1))
    }

    @Test fun `RomM keeps screenshots but no title screens`() {
        assertTrue(CoverArtwork.supports(Source.ROMM, LibretroThumbnails.Art.SNAP))
        assertFalse(CoverArtwork.supports(Source.ROMM, LibretroThumbnails.Art.TITLE))
        assertTrue(CoverArtwork.supports(Source.LIBRETRO, LibretroThumbnails.Art.TITLE))
        assertTrue(CoverArtwork.supports(Source.SCREENSCRAPER, LibretroThumbnails.Art.SNAP))
        assertFalse(CoverArtwork.supports(Source.IGDB, LibretroThumbnails.Art.SNAP))
        assertFalse(CoverArtwork.supports(Source.THEGAMESDB, LibretroThumbnails.Art.TITLE))
    }

    @Test fun `a source that could not answer keeps the result from being a miss`() {
        assertEquals(Answer.Found("u"), CoverArtwork.verdict(listOf(Answer.Unavailable, Answer.Found("u"), Answer.Found("v"))))
        assertEquals(Answer.Unavailable, CoverArtwork.verdict(listOf(Answer.Miss, Answer.Unavailable)))
        assertEquals(Answer.Miss, CoverArtwork.verdict(listOf(Answer.Miss, Answer.Miss)))
        assertEquals(Answer.Miss, CoverArtwork.verdict(emptyList()))
    }

    @Test fun `the settings are registered for backups`() {
        assertEquals("s", SettingSchema.expectedType(CoverArtwork.ORDER_KEY))
        assertEquals("ss", SettingSchema.expectedType(CoverArtwork.DISABLED_KEY))
        assertFalse(CloudSettingKeys.isSecret(CoverArtwork.ORDER_KEY))
        assertNull(CoverArtwork.SETTING_TYPES["covers43_order"])
    }

    @Test fun `ScreenScraper art follows the first preferred region`() {
        fun media(type: String, region: String, url: String) = ScreenScraper.Media(type, region, url, "png")
        val game = ScreenScraper.Game("1", listOf("Game"), listOf(
            media("sstitle", "eu", "https://www.screenscraper.fr/api2/mediaJeu.php?x=title-eu"),
            media("sstitle", "us", "https://www.screenscraper.fr/api2/mediaJeu.php?x=title-us"),
            media("ss", "wor", "https://www.screenscraper.fr/api2/mediaJeu.php?x=ss-wor"),
            media("box-2D", "us", "https://www.screenscraper.fr/api2/mediaJeu.php?x=box")
        ))
        assertEquals("https://www.screenscraper.fr/api2/mediaJeu.php?x=title-us", ScreenScraper.pickArt(game, listOf("us", "eu"), LibretroThumbnails.Art.TITLE))
        // No preferred region has one: any.
        assertEquals("https://www.screenscraper.fr/api2/mediaJeu.php?x=title-eu", ScreenScraper.pickArt(game, listOf("jp"), LibretroThumbnails.Art.TITLE))
        assertEquals("https://www.screenscraper.fr/api2/mediaJeu.php?x=ss-wor", ScreenScraper.pickArt(game, emptyList(), LibretroThumbnails.Art.SNAP))
        assertNull(ScreenScraper.pickArt(ScreenScraper.Game("2", emptyList(), listOf(media("box-2D", "us", "https://www.screenscraper.fr/api2/b"))), emptyList(), LibretroThumbnails.Art.SNAP))
        // The cover choice is unchanged by the refactor.
        assertEquals("https://www.screenscraper.fr/api2/mediaJeu.php?x=box", ScreenScraper.pickCover(game, listOf("us"), false))
    }
}
