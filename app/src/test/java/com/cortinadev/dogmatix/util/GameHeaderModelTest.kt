package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.GameHeaderModel.Badge
import com.cortinadev.dogmatix.util.GameHeaderModel.More
import com.cortinadev.dogmatix.util.GameHeaderModel.Quick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameHeaderModelTest {

    @Test fun `year is read from the usual date shapes`() {
        assertEquals("1998", GameHeaderModel.year("1998-11-21"))
        assertEquals("1998", GameHeaderModel.year("Nov 21, 1998"))
        assertEquals("2004", GameHeaderModel.year("2004"))
        assertEquals("1991", GameHeaderModel.year("21/08/1991"))
    }

    @Test fun `no year from blanks or stray numbers`() {
        assertNull(GameHeaderModel.year(null))
        assertNull(GameHeaderModel.year(""))
        assertNull(GameHeaderModel.year("Unknown"))
        assertNull(GameHeaderModel.year("12345678"))
        assertNull(GameHeaderModel.year("1850"))
    }

    private fun badges(
        hasRegion: Boolean = false,
        torrent: Boolean = false,
        fromRomm: Boolean = false,
        downloading: Boolean = false,
        owned: Boolean = false,
        onRomm: Boolean = false,
        verified: Boolean = false,
        found: Boolean = false,
        favourite: Boolean = false
    ) = GameHeaderModel.badges(hasRegion, torrent, fromRomm, downloading, owned, onRomm, verified, found, favourite)

    @Test fun `a plain direct file shows only its source`() {
        assertEquals(listOf(Badge.SOURCE_DIRECT), badges())
    }

    @Test fun `badges keep the info pill order`() {
        assertEquals(
            listOf(Badge.REGION, Badge.SOURCE_TORRENT, Badge.ON_DEVICE, Badge.VERIFIED, Badge.FOUND, Badge.FAVOURITE),
            badges(hasRegion = true, torrent = true, owned = true, verified = true, found = true, favourite = true)
        )
    }

    @Test fun `a RomM file already on RomM shows only on RomM`() {
        assertEquals(listOf(Badge.ON_ROMM), badges(fromRomm = true, onRomm = true))
        assertEquals(listOf(Badge.SOURCE_ROMM), badges(fromRomm = true))
    }

    @Test fun `downloading replaces on device`() {
        val list = badges(owned = true, downloading = true)
        assertTrue(Badge.DOWNLOADING in list)
        assertFalse(Badge.ON_DEVICE in list)
    }

    @Test fun `home screen icon only for a game on the device`() {
        assertEquals(listOf(Quick.FAVOURITE, Quick.SHARE, Quick.MORE), GameHeaderModel.quick(owned = false, downloading = false))
        assertEquals(listOf(Quick.FAVOURITE, Quick.HOME_SCREEN, Quick.SHARE, Quick.MORE), GameHeaderModel.quick(owned = true, downloading = false))
        assertEquals(listOf(Quick.FAVOURITE, Quick.SHARE, Quick.MORE), GameHeaderModel.quick(owned = true, downloading = true))
    }

    private fun more(
        owned: Boolean = false,
        downloading: Boolean = false,
        readiness: Boolean = false,
        handoff: Boolean = false,
        betterVersion: Boolean = false,
        updateAvailable: Boolean = false,
        missingDlc: Int = 0
    ) = GameHeaderModel.more(owned, downloading, readiness, handoff, betterVersion, updateAvailable, missingDlc)

    @Test fun `up next always comes first`() {
        assertEquals(More.UP_NEXT, more().first())
        assertEquals(More.UP_NEXT, more(owned = true, readiness = true, handoff = true).first())
        assertEquals(More.UP_NEXT, more(downloading = true).first())
    }

    @Test fun `a game not on the device can be planned and collected`() {
        assertEquals(listOf(More.UP_NEXT, More.DOWNLOAD_WHEN, More.COLLECTIONS), more())
    }

    @Test fun `a game on the device keeps every 8_0 action in its order`() {
        assertEquals(
            listOf(
                More.UP_NEXT, More.READINESS, More.HANDOFF, More.DOWNLOAD_AGAIN, More.EMULATOR,
                More.DOWNLOAD_BEST, More.UPDATE, More.DLC, More.COLLECTIONS, More.REMOVE
            ),
            more(owned = true, readiness = true, handoff = true, betterVersion = true, updateAvailable = true, missingDlc = 2)
        )
    }

    @Test fun `nothing destructive or repeated while downloading`() {
        val list = more(owned = true, downloading = true)
        assertFalse(More.REMOVE in list)
        assertFalse(More.DOWNLOAD_AGAIN in list)
        assertFalse(More.DOWNLOAD_WHEN in list)
    }

    @Test fun `every secondary action is either a round button or in the card`() {
        // FAVOURITE and SHARE are round buttons; everything else from GamePageModel.actions stays in the card.
        val all = GamePageModel.actions(owned = true, downloading = false, betterVersion = true, updateAvailable = true, missingDlc = 1)
        val card = more(owned = true, betterVersion = true, updateAvailable = true, missingDlc = 1).map { it.name }
        val quick = GameHeaderModel.quick(owned = true, downloading = false).map { it.name }
        all.forEach { action -> assertTrue(action.name, action.name in card || action.name in quick) }
    }

    @Test fun `portrait cover is about forty percent of the width within bounds`() {
        assertEquals(160, GameHeaderModel.portraitCoverWidth(400))
        assertEquals(120, GameHeaderModel.portraitCoverWidth(240))
        assertEquals(240, GameHeaderModel.portraitCoverWidth(1200))
    }
}
