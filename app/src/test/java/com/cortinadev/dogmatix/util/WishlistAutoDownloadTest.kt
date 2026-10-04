package com.cortinadev.dogmatix.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistAutoDownloadTest {
    @Test fun `wishlist auto download needs every word of the wish`() {
        assertTrue(GameTitleCleaner.containsAllWords("GBA1 Game 00321", "GBA1 Game 00321 (Europe) (En,Fr,De) (Rev 1).zip"))
        assertFalse(GameTitleCleaner.containsAllWords("GBA1 Game 00321", "GBA1 Game 03215 (World) (En,Fr,De) (Rev 2).zip"))
        assertTrue(GameTitleCleaner.containsAllWords("zelda minish cap", "Legend of Zelda, The - The Minish Cap (Europe).zip"))
        assertFalse(GameTitleCleaner.containsAllWords("", "anything.zip"))
    }
}
