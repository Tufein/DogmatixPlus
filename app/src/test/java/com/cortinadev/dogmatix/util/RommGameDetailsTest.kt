package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommGameDetailsTest {

    private fun json(s: String) = JsonParser.parseString(s)
    private val base = "https://romm.local"

    /** A trimmed RomM 4.x / 5.x `GET /api/roms/{id}` answer. */
    private val rom5 = """
        {"id": 1534, "name": "Chrono Trigger", "fs_name": "Chrono Trigger (USA).sfc", "platform_id": 4,
         "platform_slug": "snes", "platform_display_name": "Super Nintendo",
         "summary": "A time-travelling RPG.",
         "path_cover_small": "roms/4/1534/cover/small.png", "path_cover_large": "roms/4/1534/cover/big.png",
         "url_cover": "https://images.igdb.com/igdb/image/upload/t_cover_big/co1abc.jpg",
         "merged_screenshots": ["/assets/romm/resources/roms/4/1534/screenshots/0.jpg", "roms/4/1534/screenshots/1.jpg"],
         "url_screenshots": ["https://images.igdb.com/igdb/image/upload/t_original/sc1.jpg"],
         "metadatum": {"rom_id": 1534, "genres": ["Role-playing (RPG)", "Adventure"], "first_release_date": 794880000000, "average_rating": 92.67},
         "igdb_metadata": {"total_rating": "93.1", "genres": ["Role-playing (RPG)"]},
         "rom_user": {"id": 9, "user_id": 1, "rom_id": 1534, "backlogged": false, "now_playing": true, "hidden": false,
                      "rating": 9, "difficulty": 4, "completion": 60, "status": "incomplete", "last_played": "2026-09-30T18:02:11.000000"}}
    """

    /** A RomM 3.x answer: genres at the top, seconds, IGDB rating, no status yet. */
    private val rom3 = """
        {"id": 77, "name": "Metroid Fusion", "fs_name": "Metroid Fusion (USA).gba",
         "first_release_date": 1037577600, "genres": ["Platform", "Shooter"],
         "igdb_metadata": {"total_rating": 89.4, "summary": "Samus returns."},
         "path_screenshots": ["roms/2/77/screenshots/0.jpg"],
         "rom_user": {"id": 3, "note_raw_markdown": "", "note_is_public": false, "is_main_sibling": false}}
    """

    @Test fun `a RomM 5 game is read with its metadata, pictures and play data`() {
        val info = RommGameDetails.parse(json(rom5), base)!!
        assertEquals(1534, info.romId)
        assertEquals("Chrono Trigger", info.name)
        assertEquals("A time-travelling RPG.", info.summary)
        assertEquals(listOf("Role-playing (RPG)", "Adventure"), info.genres)
        assertEquals(1995, info.releaseYear)
        assertEquals(92, info.ratingPercent)
        assertEquals("$base/assets/romm/resources/roms/4/1534/cover/big.png", info.coverUrl)
        assertEquals(
            listOf("$base/assets/romm/resources/roms/4/1534/screenshots/0.jpg", "$base/assets/romm/resources/roms/4/1534/screenshots/1.jpg"),
            info.screenshots
        )
        assertEquals("Super Nintendo", info.platformName)
        assertTrue(info.propsSupported)
        assertEquals(RommPlayStatus.INCOMPLETE, info.props.status)
        assertTrue(info.props.nowPlaying)
        assertFalse(info.props.backlogged)
        assertEquals(9, info.props.rating)
        assertEquals("2026-09-30T18:02:11.000000", info.props.lastPlayed)
    }

    @Test fun `a RomM 3 game falls back to its older fields`() {
        val info = RommGameDetails.parse(json(rom3), base)!!
        assertEquals("Samus returns.", info.summary)
        assertEquals(listOf("Platform", "Shooter"), info.genres)
        assertEquals(2002, info.releaseYear)
        assertEquals(89, info.ratingPercent)
        assertEquals(listOf("$base/assets/romm/resources/roms/2/77/screenshots/0.jpg"), info.screenshots)
        assertNull(info.coverUrl)
        assertFalse("no status fields: the pills are hidden", info.propsSupported)
        assertEquals(RommUserProps(), info.props)
    }

    @Test fun `IGDB style genre objects, absolute screenshots and no rom_user are fine`() {
        val info = RommGameDetails.parse(json(
            """{"id": 5, "name": "X", "igdb_metadata": {"genres": [{"id": 12, "name": "Puzzle"}], "first_release_date": "1999-03-21"},
                "url_screenshots": ["https://images.igdb.com/a.jpg", "//images.igdb.com/b.jpg"], "rom_user": null}"""
        ), base)!!
        assertEquals(listOf("Puzzle"), info.genres)
        assertEquals(1999, info.releaseYear)
        assertEquals(listOf("https://images.igdb.com/a.jpg", "https://images.igdb.com/b.jpg"), info.screenshots)
        assertFalse(info.propsSupported)
    }

    @Test fun `garbage is not a game`() {
        assertNull(RommGameDetails.parse(json("""{"detail": "Not found"}"""), base))
        assertNull(RommGameDetails.parse(json("""[1, 2]"""), base))
        assertNull(RommGameDetails.parse(null, base))
        // An object without id is accepted when the caller knows the id it asked for.
        assertEquals(42, RommGameDetails.parse(json("""{"name": "Y"}"""), base, fallbackId = 42)!!.romId)
    }

    @Test fun `ratings are normalised to a hundred`() {
        assertEquals(85, RommGameDetails.ratingPercent(json("85.4")))
        assertEquals(85, RommGameDetails.ratingPercent(json("8.5")))
        assertEquals(76, RommGameDetails.ratingPercent(json("7.6"), outOfTen = true))
        assertEquals(80, RommGameDetails.ratingPercent(json("16"), outOfTwenty = true))
        assertEquals(90, RommGameDetails.ratingPercent(json("4.5"), outOfFive = true))
        assertEquals(93, RommGameDetails.ratingPercent(json("\"93.1\"")))
        assertNull(RommGameDetails.ratingPercent(json("0")))
        assertNull(RommGameDetails.ratingPercent(json("\"n/a\"")))
        assertEquals(100, RommGameDetails.ratingPercent(json("140")))
    }

    @Test fun `release years from every date shape`() {
        assertEquals(1995, RommGameDetails.releaseYear(json("794880000")))      // seconds
        assertEquals(1995, RommGameDetails.releaseYear(json("794880000000")))   // milliseconds
        assertEquals(2001, RommGameDetails.releaseYear(json("\"2001-03-21\"")))
        assertEquals(2001, RommGameDetails.releaseYear(json("\"2001\"")))
        assertEquals(1987, RommGameDetails.releaseYear(json("1987")))
        assertNull(RommGameDetails.releaseYear(json("0")))
        assertNull(RommGameDetails.releaseYear(json("null")))
        assertNull(RommGameDetails.releaseYear(json("\"soon\"")))
    }

    @Test fun `times with and without an offset`() {
        assertEquals(1_000_000_000_000L, RommGameDetails.epochMillis("2001-09-09T01:46:40Z"))
        assertEquals(1_000_000_000_000L, RommGameDetails.epochMillis("2001-09-09T01:46:40+00:00"))
        assertEquals(1_000_000_000_000L, RommGameDetails.epochMillis("2001-09-09T01:46:40.000000"))
        assertEquals(1_000_000_000_000L, RommGameDetails.epochMillis("2001-09-09 01:46:40"))
        assertNull(RommGameDetails.epochMillis(""))
        assertNull(RommGameDetails.epochMillis("yesterday"))
    }

    @Test fun `status values of every server spelling`() {
        assertEquals(RommPlayStatus.COMPLETED_100, RommPlayStatus.fromApi("completed_100"))
        assertEquals(RommPlayStatus.COMPLETED_100, RommPlayStatus.fromApi("COMPLETED_100"))
        assertEquals(RommPlayStatus.NEVER_PLAYING, RommPlayStatus.fromApi("never-playing"))
        assertEquals(RommPlayStatus.FINISHED, RommPlayStatus.fromApi(" finished "))
        assertNull(RommPlayStatus.fromApi(""))
        assertNull(RommPlayStatus.fromApi("something_new"))
    }

    @Test fun `props answers are read plain or wrapped, ratings above ten are scaled`() {
        assertEquals(RommPlayStatus.FINISHED, RommGameDetails.parseProps(json("""{"status": "finished", "rating": 7}"""))!!.status)
        assertEquals(7, RommGameDetails.parseProps(json("""{"rom_user": {"rating": "7"}}"""))!!.rating)
        assertTrue(RommGameDetails.parseProps(json("""{"data": {"backlogged": 1}}"""))!!.backlogged)
        assertEquals(8, RommGameDetails.parseProps(json("""{"rating": 80}"""))!!.rating)
        assertNull(RommGameDetails.parseProps(json("""true""")))
    }

    @Test fun `pills toggle flags and pick or clear one status`() {
        var p = RommUserProps()
        p = RommProps.toggle(p, RommProps.Pill.NOW_PLAYING)
        assertTrue(p.nowPlaying)
        p = RommProps.toggle(p, RommProps.Pill.FINISHED)
        assertEquals(RommPlayStatus.FINISHED, p.status)
        p = RommProps.toggle(p, RommProps.Pill.COMPLETED_100)
        assertEquals(RommPlayStatus.COMPLETED_100, p.status)
        assertTrue(RommProps.isOn(p, RommProps.Pill.COMPLETED_100))
        assertFalse(RommProps.isOn(p, RommProps.Pill.FINISHED))
        p = RommProps.toggle(p, RommProps.Pill.COMPLETED_100)
        assertNull(p.status)
        assertTrue("flags are independent of the status", p.nowPlaying)
    }

    @Test fun `rating steps stay within zero and ten`() {
        val p = RommUserProps(rating = 9)
        assertEquals(10, RommProps.stepRating(p, 1).rating)
        assertEquals(10, RommProps.stepRating(p, 5).rating)
        assertEquals(0, RommProps.stepRating(RommUserProps(rating = 1), -3).rating)
    }

    @Test fun `only changed fields are written, a cleared status as null`() {
        val old = RommUserProps(status = RommPlayStatus.RETIRED, nowPlaying = true, rating = 6)
        val new = old.copy(status = null, rating = 8)
        val changes = RommProps.changes(old, new)
        assertEquals(mapOf("status" to null, "rating" to 8), changes)
        assertEquals("""{"data":{"status":null,"rating":8}}""", RommProps.body(changes))
        assertEquals(new, RommProps.apply(old, changes))
        assertTrue(RommProps.changes(old, old).isEmpty())
        assertEquals(
            """{"data":{"status":"completed_100","backlogged":true}}""",
            RommProps.body(RommProps.changes(RommUserProps(), RommUserProps(status = RommPlayStatus.COMPLETED_100, backlogged = true)))
        )
    }
}
