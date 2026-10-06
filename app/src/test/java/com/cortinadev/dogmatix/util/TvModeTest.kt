package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvModeTest {
    @Test fun `forced settings win over the device`() {
        assertTrue(TvMode.isActive(TvModeSetting.ON, uiModeTelevision = false, screenWidthDp = 400, screenHeightDp = 800, hasTouchscreen = true))
        assertFalse(TvMode.isActive(TvModeSetting.OFF, uiModeTelevision = true, screenWidthDp = 960, screenHeightDp = 540, hasTouchscreen = false))
    }

    @Test fun `auto follows the television ui mode`() {
        assertTrue(TvMode.isActive(TvModeSetting.AUTO, uiModeTelevision = true, screenWidthDp = 960, screenHeightDp = 540, hasTouchscreen = false))
        assertTrue(TvMode.isActive(TvModeSetting.AUTO, uiModeTelevision = true, screenWidthDp = 640, screenHeightDp = 360, hasTouchscreen = true))
    }

    @Test fun `auto treats a large screen without touch as a tv`() {
        assertTrue(TvMode.isActive(TvModeSetting.AUTO, false, 960, 540, hasTouchscreen = false))
        assertTrue(TvMode.isActive(TvModeSetting.AUTO, false, 540, 960, hasTouchscreen = false))
        // A large tablet is touched; a small screen without touch is a handheld with buttons.
        assertFalse(TvMode.isActive(TvModeSetting.AUTO, false, 1280, 800, hasTouchscreen = true))
        assertFalse(TvMode.isActive(TvModeSetting.AUTO, false, 640, 480, hasTouchscreen = false))
    }

    @Test fun `safe margins are five percent with a floor`() {
        assertEquals(48, TvMode.sidePaddingDp(960))
        assertEquals(48, TvMode.sidePaddingDp(400))
        assertEquals(64, TvMode.sidePaddingDp(1280))
        assertEquals(27, TvMode.edgePaddingDp(540))
        assertEquals(27, TvMode.edgePaddingDp(300))
        assertEquals(36, TvMode.edgePaddingDp(720))
    }

    @Test fun `the setting cycles both ways`() {
        assertEquals(TvModeSetting.ON, TvMode.shift(TvModeSetting.AUTO, 1))
        assertEquals(TvModeSetting.AUTO, TvMode.shift(TvModeSetting.OFF, 1))
        assertEquals(TvModeSetting.OFF, TvMode.shift(TvModeSetting.AUTO, -1))
    }

    @Test fun `unknown stored values are auto`() {
        assertEquals(TvModeSetting.AUTO, TvModeSetting.fromKey(null))
        assertEquals(TvModeSetting.AUTO, TvModeSetting.fromKey("SOMETIMES"))
        assertEquals(TvModeSetting.ON, TvModeSetting.fromKey("ON"))
    }

    @Test fun `remote keys map to the gamepad shortcuts`() {
        assertEquals(RemoteKey.PREV_SECTION, TvMode.remoteKey(TvMode.KEYCODE_CHANNEL_UP))
        assertEquals(RemoteKey.NEXT_SECTION, TvMode.remoteKey(TvMode.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(RemoteKey.DETAILS, TvMode.remoteKey(TvMode.KEYCODE_MENU))
        assertEquals(RemoteKey.SEARCH, TvMode.remoteKey(TvMode.KEYCODE_SEARCH))
        assertNull(TvMode.remoteKey(19))   // D-pad up stays with the focus system
        assertNull(TvMode.remoteKey(4))    // Back too
    }
}
