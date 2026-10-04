package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EsdeFavouritesTest {
    @Test fun `es-de favourites are read and matched by name without extension`() {
        val xml = """<?xml version="1.0"?><gameList>
            <game><path>./Pixel Quest (USA).gba</path><name>Pixel Quest</name><favorite>true</favorite></game>
            <game><path>./Moon Miners (USA).gba</path><favorite>false</favorite></game>
            <game><path>./sub/Tom &amp; Jerry (Europe).zip</path><favorite>true</favorite></game>
        </gameList>"""
        val favs = EsdeFavourites.favouriteFiles(xml)
        assertEquals(listOf("Pixel Quest (USA).gba", "Tom & Jerry (Europe).zip"), favs)
        assertEquals(listOf("Pixel%20Quest%20(USA).zip", "Tom & Jerry (Europe).7z"),
            EsdeFavourites.match(favs, listOf("Pixel%20Quest%20(USA).zip", "Moon Miners (USA).zip", "Tom & Jerry (Europe).7z")))
    }
}
