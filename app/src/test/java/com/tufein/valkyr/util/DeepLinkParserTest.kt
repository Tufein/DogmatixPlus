package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {

    @Test
    fun `wrong scheme or host is ignored`() {
        assertNull(DeepLinkParser.parse(null))
        assertNull(DeepLinkParser.parse(""))
        assertNull(DeepLinkParser.parse("https://library?q=mario"))
        assertNull(DeepLinkParser.parse("dogmatix://settings"))
        assertNull(DeepLinkParser.parse("not a uri at all"))
    }

    @Test
    fun `empty query yields an empty request`() {
        val r = DeepLinkParser.parse("dogmatix://library")!!
        assertEquals(emptySet<String>(), r.consoles)
        assertEquals(emptySet<String>(), r.tags)
        assertNull(r.query)
        assertNull(r.favouritesOnly)
    }

    @Test
    fun `parses consoles tags query and favourites`() {
        val r = DeepLinkParser.parse("dogmatix://library?console=nintendo_snes,sega_genesis&region=USA,%20Europe&lang=En&type=GAME&q=super%20mario&fav=1")!!
        assertEquals(setOf("nintendo_snes", "sega_genesis"), r.consoles)
        assertEquals(setOf("USA", "Europe", "En", "GAME"), r.tags)
        assertEquals("super mario", r.query)
        assertEquals(true, r.favouritesOnly)
    }

    @Test
    fun `fav accepts true false and yes and keys are case-insensitive`() {
        assertEquals(true, DeepLinkParser.parse("dogmatix://library?FAV=true")!!.favouritesOnly)
        assertEquals(true, DeepLinkParser.parse("dogmatix://library?fav=yes")!!.favouritesOnly)
        assertEquals(false, DeepLinkParser.parse("dogmatix://library?fav=0")!!.favouritesOnly)
        assertEquals(false, DeepLinkParser.parse("dogmatix://library?fav=false")!!.favouritesOnly)
    }

    @Test
    fun `blank values and plus-encoded spaces are handled`() {
        val r = DeepLinkParser.parse("dogmatix://library?q=yu+gi+oh&console=&region=,")!!
        assertEquals("yu gi oh", r.query)
        assertEquals(emptySet<String>(), r.consoles)
        assertEquals(emptySet<String>(), r.tags)
    }

    @Test
    fun `preferred and legacy scheme parse to the same request`() {
        val tail = "library?console=snes&q=mario&fav=1&new=1&collection=3"
        val preferred = DeepLinkParser.parse("valkyr://$tail")!!
        assertEquals(preferred, DeepLinkParser.parse("dogmatix://$tail"))
        assertEquals(preferred, DeepLinkParser.parse("VALKYR://$tail"))
        assertEquals(setOf("snes"), preferred.consoles)
        assertEquals(3L, preferred.collectionId)
    }

    @Test
    fun `unknown schemes and hosts are still refused for both schemes`() {
        assertNull(DeepLinkParser.parse("valkyr://settings"))
        assertNull(DeepLinkParser.parse("valkyrx://library"))
        assertNull(DeepLinkParser.parse("other://library?q=x"))
    }

    @Test
    fun `scheme helpers accept exactly the two app schemes`() {
        assertEquals("valkyr", DeepLinkParser.SCHEME)
        assertEquals(true, DeepLinkParser.isAppScheme("valkyr"))
        assertEquals(true, DeepLinkParser.isAppScheme("Dogmatix"))
        assertEquals(false, DeepLinkParser.isAppScheme("https"))
        assertEquals(false, DeepLinkParser.isAppScheme(null))
        assertEquals(true, DeepLinkParser.hasAppScheme("valkyr://library"))
        assertEquals(true, DeepLinkParser.hasAppScheme("DOGMATIX://library"))
        assertEquals(false, DeepLinkParser.hasAppScheme("valkyr:/library"))
    }
}
