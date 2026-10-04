package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontendArtworkTest {
    @Test fun pegasusLooksInAMediaFolderNextToTheGames() {
        assertEquals("media/Advance Wars (USA)/boxFront.jpg", FrontendArtwork.pegasusCoverPath("Advance Wars (USA).gba", "jpg"))
        assertEquals("media/Final Fantasy VII (USA)/boxFront.png", FrontendArtwork.pegasusCoverPath("Final Fantasy VII (USA).m3u", "png"))
    }

    @Test fun retroArchUsesLibretroSystemNamesAndItsCharacterRule() {
        val gba = LibretroThumbnails.systemFor("nintendo_gameboy_advance")!!
        assertEquals("Nintendo - Game Boy Advance/Named_Boxarts/Advance Wars (USA).png", FrontendArtwork.retroArchCoverPath(gba, "Advance Wars (USA).zip"))
        val md = LibretroThumbnails.systemFor("sega_genesis")!!
        assertEquals("Sega - Mega Drive - Genesis", FrontendArtwork.retroArchSystem(md))
        assertEquals("Sega - Mega Drive - Genesis/Named_Boxarts/Sonic _ Knuckles (World).png", FrontendArtwork.retroArchCoverPath(md, "Sonic & Knuckles (World).md"))
    }
}
