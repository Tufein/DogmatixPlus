package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameShareTextTest {

    @Test
    fun `deep link round-trips through the parser`() {
        val link = GameShareText.deepLink("nintendo_snes", "Super Mario World & Co")
        val request = DeepLinkParser.parse(link)
        assertEquals(setOf("nintendo_snes"), request?.consoles)
        assertEquals("Super Mario World & Co", request?.query)
    }

    @Test
    fun `deep link needs a title`() {
        assertNull(GameShareText.deepLink("nintendo_snes", "  "))
    }

    @Test
    fun `text carries the link on its own line`() {
        assertEquals("Hi\ndogmatix://x", GameShareText.withLink("Hi", "dogmatix://x"))
        assertEquals("Hi", GameShareText.withLink("Hi", null))
    }

    @Test
    fun `card tags put regions first and merge languages`() {
        val tags = GameShareText.cardTags(listOf("En", "Fr", "USA", "Rev 1", "Beta", "Europe"))
        assertEquals(listOf("USA", "Europe", "En, Fr", "Rev 1"), tags)
    }

    @Test
    fun `card tags are empty without tags`() {
        assertTrue(GameShareText.cardTags(emptyList()).isEmpty())
    }

    @Test
    fun `file name is safe`() {
        assertEquals("dogmatix-pokemon-emerald-usa.png", GameShareText.fileName("Pokémon Emerald (USA)!".replace("é", "e")))
        assertEquals("dogmatix-game.png", GameShareText.fileName("???"))
        assertTrue(GameShareText.fileName("../../etc/passwd").none { it == '/' })
        assertTrue(GameShareText.fileName("a".repeat(300)).length < 70)
    }

    @Test
    fun `wishlist text lists wishes and counts the rest`() {
        val wishes = (1..65).map { "Game $it" to (if (it == 1) "SNES" else null) }
        val lines = GameShareText.wishlistText("My wishlist", wishes) { "+$it more" }.lines()
        assertEquals("My wishlist", lines[0])
        assertEquals("• Game 1 (SNES)", lines[1])
        assertEquals("• Game 2", lines[2])
        assertEquals("+5 more", lines.last())
        assertEquals(GameShareText.MAX_WISHES + 2, lines.size)
    }

    @Test
    fun `cover box is 3 to 4 and centred`() {
        val box = GameShareText.coverBox()
        assertEquals(box.height * 3f / 4f, box.width, 0.01f)
        assertEquals((GameShareText.CARD_WIDTH - box.right), box.left, 0.01f)
    }

    @Test
    fun `centre crop keeps the aspect ratio`() {
        val dst = GameShareText.Box(0f, 0f, 300f, 400f)
        val wide = GameShareText.centerCrop(800, 400, dst)
        assertEquals(300f, wide.width, 0.01f)
        assertEquals(400f, wide.height, 0.01f)
        assertEquals(250f, wide.left, 0.01f)
        val tall = GameShareText.centerCrop(300, 800, dst)
        assertEquals(300f, tall.width, 0.01f)
        assertEquals(400f, tall.height, 0.01f)
        assertEquals(200f, tall.top, 0.01f)
    }

    @Test
    fun `fit size shrinks only when needed and stops at the minimum`() {
        assertEquals(60f, GameShareText.fitSize(60f, 500f, 900f, 30f), 0.01f)
        assertEquals(30f, GameShareText.fitSize(60f, 1800f, 900f, 30f), 0.01f)
        assertEquals(40f, GameShareText.fitSize(60f, 1350f, 900f, 30f), 0.01f)
        assertEquals(30f, GameShareText.fitSize(60f, 9000f, 900f, 30f), 0.01f)
    }
}
