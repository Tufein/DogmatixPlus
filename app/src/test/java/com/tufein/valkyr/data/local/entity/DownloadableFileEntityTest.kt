package com.tufein.valkyr.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadableFileEntityTest {

    @Test
    fun rommUrlsAreRecognisedLikeTheSqlPattern() {
        // SQL: downloadUrl LIKE '%/api/roms/%/content/%' (ASCII case-insensitive)
        assertTrue(DownloadableFileEntity.isRommContentUrl("https://romm.example/api/roms/123/content/Game.zip"))
        assertTrue(DownloadableFileEntity.isRommContentUrl("https://romm.example/API/ROMS/1/Content/x"))
        assertTrue(DownloadableFileEntity.isRommContentUrl("http://h/sub/api/roms//content/x"))
        assertFalse(DownloadableFileEntity.isRommContentUrl("https://romm.example/api/roms/123"))
        assertFalse(DownloadableFileEntity.isRommContentUrl("https://romm.example/api/roms/content/x"))
        assertFalse(DownloadableFileEntity.isRommContentUrl("https://files.example/Game.zip"))
        assertFalse(DownloadableFileEntity.isRommContentUrl(""))
    }

    @Test
    fun defaultsFillTheSearchColumnsFromNameAndUrl() {
        val f = DownloadableFileEntity(name = "Yu-Gi-Oh! Duel Monsters", fileName = "a.zip", consoleId = "gba",
            downloadUrl = "https://romm.example/api/roms/5/content/a.zip")
        assertEquals("yugiohduelmonsters", f.searchKey)
        assertEquals("yu gi oh duel monsters yugiohduelmonsters", f.searchWords)
        assertTrue(f.isRomm)
        assertFalse(f.copy(downloadUrl = "https://x.example/a.zip", isRomm = false).isRomm)
    }
}
