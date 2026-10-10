package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import org.junit.Assert.*
import org.junit.Test

class GamePackagesTest {
    private fun identity(file: DownloadableFileEntity) = GamePackages.identity(file.consoleId, file.downloadUrl,
        file.fileName, file.torrentMagnet, file.torrentFileIndex)
    private fun manifest(parts: List<GamePackages.Part> = listOf(GamePackages.Part("Disc 1/Track.bin", 5, "a".repeat(64)))) =
        GamePackages.Manifest(sourceIdentity = "b".repeat(64), consoleId = "sony_playstation", fileName = "Different archive.zip",
            rootUri = "content://test/tree/root", subPath = "psx/Game", createdAt = 1, parts = parts)
    private fun refuses(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun `history restoration keeps authoritative source identity without sourceUrl`() {
        val source = DownloadableFileEntity(name = "Game", fileName = "Game.zip", consoleId = "sony_playstation",
            downloadUrl = "https://source.invalid/files/Game.zip", sourceUrl = "https://source.invalid/files/")
        val restored = DownloadHistoryEntity(source.fileName, source.name, source.consoleId, source.downloadUrl,
            10, "zip", null, null, "COMPLETED", 1, 2).toEntity()
        assertEquals("", restored.sourceUrl)
        assertEquals(identity(source), identity(restored))
        assertNotEquals(identity(source), identity(source.copy(downloadUrl = "https://another.invalid/Game.zip")))
        assertNotEquals(identity(source), identity(source.copy(consoleId = "sega_saturn")))
    }

    @Test fun `torrent file index and actual root remain distinct identities`() {
        val source = DownloadableFileEntity(name = "Game", fileName = "Game.zip", consoleId = "sony_playstation",
            downloadUrl = "torrent", torrentMagnet = "magnet:?xt=urn:btih:abc", torrentFileIndex = 0)
        assertNotEquals(identity(source), identity(source.copy(torrentFileIndex = 1)))
        assertNotEquals(GamePackages.receiptKey(identity(source), "content://test/tree/one"),
            GamePackages.receiptKey(identity(source), "content://test/tree/two"))
        assertNotEquals(GamePackages.identity("ab", "c", "d", null, null),
            GamePackages.identity("a", "bc", "d", null, null))
    }

    @Test fun `durable package retains different nested discs and zero byte auxiliary files`() {
        val original = manifest(listOf(GamePackages.Part("Disc 1/Track.bin", 5, "a".repeat(64)),
            GamePackages.Part("Disc 2/Track.bin", 6, "c".repeat(64)), GamePackages.Part("Readme.txt", 0, "d".repeat(64))))
        assertEquals(original, GamePackages.decode(GamePackages.encode(original)))
    }

    @Test fun `corrupt numbers duplicate fields missing fields and trailing payloads never authorize files`() {
        val json = GamePackages.encode(manifest()).toString(Charsets.UTF_8)
        listOf(
            json.replace("\"schema\":1", "\"schema\":2"),
            json.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            json.replace("\"createdAt\":1", "\"createdAt\":1.5"),
            json.replace("\"createdAt\":1", "\"createdAt\":1e0"),
            json.replace("\"createdAt\":1", "\"createdAt\":\"1\""),
            json.replace("\"createdAt\":1", "\"createdAt\":9223372036854775808"),
            json.replace("\"bytes\":5", "\"bytes\":null"),
            json.replace("\"bytes\":5", "\"bytes\":5,\"bytes\":5"),
            json.replace("\"bytes\":5", "\"bytes\":5,\"extra\":true"),
            json.replace("\"subPath\":\"psx/Game\",", ""),
            "$json{}"
        ).forEach { value -> refuses { GamePackages.decode(value.toByteArray()) } }
        refuses { GamePackages.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        refuses { GamePackages.decode(ByteArray(GamePackages.MAX_MANIFEST_BYTES + 1)) }
    }

    @Test fun `traversal portable collisions private paths and byte overflow are rejected before use`() {
        listOf("../Track.bin", "/Track.bin", "Disc/../Track.bin", "Disc\\Track.bin", "Disc/.dogmatix-trash/Track.bin",
            "Disc/Track?.bin").forEach { path -> refuses { GamePackages.validate(manifest(listOf(GamePackages.Part(path, 5, "a".repeat(64))))) } }
        refuses { GamePackages.validate(manifest(listOf(GamePackages.Part("Disc/Track.bin", 5, "a".repeat(64)),
            GamePackages.Part("disc/track.bin", 6, "a".repeat(64))))) }
        refuses { GamePackages.validate(manifest(listOf(GamePackages.Part("One.bin", Long.MAX_VALUE, "a".repeat(64)),
            GamePackages.Part("Two.bin", 1, "a".repeat(64))))) }
        refuses { GamePackages.validate(manifest().copy(subPath = ".dogmatix-trash/Game")) }
    }

    @Test fun `nested disc references resolve in their own folder and reject ambiguous unsafe siblings`() {
        val paths = listOf("Game.m3u", "Disc 1/Game.cue", "Disc 1/Track.bin", "Disc 2/Game.cue", "Disc 2/Track.bin")
        assertTrue(GameReadiness.referencesPresentAt("Game.m3u", "Disc 1/Game.cue\nDisc 2/Game.cue", paths))
        assertTrue(GameReadiness.referencesPresentAt("Disc 1/Game.cue", "FILE \"Track.bin\" BINARY", paths))
        assertFalse(GameReadiness.referencesPresentAt("Disc 1/Game.cue", "FILE \"Track.bin\" BINARY", paths - "Disc 1/Track.bin"))
        assertFalse(GameReadiness.referencesPresentAt("Disc 1/Game.cue", "FILE \"../Disc 2/Track.bin\" BINARY", paths))
        assertFalse(GameReadiness.referencesPresentAt("Game.m3u", "Game.srm", paths + "Game.srm"))
        assertFalse(GameReadiness.referencesPresentAt("Disc 1/Game.cue", "FILE \"Track.bin\" BINARY", paths + "Disc 1/track.bin"))
    }
}
