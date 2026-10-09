package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadSourceKind
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.*
import org.junit.Test

class DownloadSourcePolicyTest {
    @Test fun postProcessingAndCompletedRowsCannotChangeSource() {
        assertFalse(DownloadSourcePolicy.canChange(DownloadStatus.COPYING))
        assertFalse(DownloadSourcePolicy.canChange(DownloadStatus.UNZIPPING))
        assertFalse(DownloadSourcePolicy.canChange(DownloadStatus.COMPLETED))
        assertTrue(DownloadSourcePolicy.canChange(DownloadStatus.DOWNLOADING))
        assertTrue(DownloadSourcePolicy.canChange(DownloadStatus.QUEUED))
        assertTrue(DownloadSourcePolicy.canChange(DownloadStatus.PAUSED))
        assertTrue(DownloadSourcePolicy.canChange(DownloadStatus.FAILED))
        assertTrue(DownloadSourcePolicy.canChange(DownloadStatus.STOPPED))
    }

    @Test fun replacementRequiresExactConsoleFilenameAndCompatibleSize() {
        val file = file()
        assertTrue(DownloadSourcePolicy.sameFile(file, file.copy(downloadUrl = "https://mirror.test/file")))
        assertFalse(DownloadSourcePolicy.sameFile(file, file.copy(consoleId = "other")))
        assertFalse(DownloadSourcePolicy.sameFile(file, file.copy(fileName = "game.gba")))
        assertFalse(DownloadSourcePolicy.sameFile(file, file.copy(fileName = "Game (Europe).gba")))
        assertFalse(DownloadSourcePolicy.sameFile(file, file.copy(fileSize = 2_000L)))
        assertTrue(DownloadSourcePolicy.sameFile(file, file.copy(fileSize = 0L)))
    }

    @Test fun labelsNeverIncludeCredentialsPathsQueriesOrMalformedSourceText() {
        val file = file().copy(sourceUrl = "https://user:password@www.example.test/private?token=secret")
        assertEquals("example.test", DownloadSourcePolicy.host(file))
        assertEquals("download.test", DownloadSourcePolicy.host(file.copy(sourceUrl = "private token=SECRET")))
        assertEquals("Source", DownloadSourcePolicy.safeLabel(file.copy(sourceUrl = "token=SECRET", downloadUrl = "not a URI")))
        assertEquals("", DownloadSourcePolicy.host(file.copy(sourceUrl = "secret@example", downloadUrl = "private:password")))
    }

    @Test fun magnetNamesAreNeverDisplayedAndRommShowsServerHost() {
        val torrent = file().copy(sourceUrl = "magnet:?xt=urn:btih:hash&dn=private-token-secret", downloadUrl = "magnet:?token=secret", torrentFileIndex = 0, torrentMagnet = "magnet:?xt=hash")
        assertEquals("", DownloadSourcePolicy.host(torrent))
        assertEquals("Torrent", DownloadSourcePolicy.safeLabel(torrent))
        assertEquals(DownloadSourceKind.TORRENT, DownloadSourcePolicy.kind(torrent))
        val romm = file().copy(sourceUrl = "romm://gba", downloadUrl = "https://romm.test/api/roms/17/content/Game.gba?key=secret")
        assertEquals("romm.test", DownloadSourcePolicy.host(romm))
        assertEquals(DownloadSourceKind.ROMM, DownloadSourcePolicy.kind(romm))
    }

    private fun file() = DownloadableFileEntity(name = "Game", fileName = "Game.gba", consoleId = "gba", downloadUrl = "https://download.test/file", fileSize = 1_000L)
}
