package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameArtifactsTest {
    @Test fun `play includes a cue and track shared with another playlist while removal protects them`() {
        val names = listOf("Game.cue", "Track 1.bin", "Collection.m3u")
        val refs = mapOf("Game.cue" to listOf("Track 1.bin"), "Collection.m3u" to listOf("Game.cue", "Track 1.bin"))
        assertEquals(listOf("Game.cue", "Track 1.bin"), GameArtifacts.plan(names, "Game.cue", false) { refs[it].orEmpty() })
        assertTrue(GameArtifacts.plan(names, "Game.cue", true) { refs[it].orEmpty() }.isEmpty())
    }

    @Test fun `play grants shared tracks that deletion leaves for another cue`() {
        val names = listOf("Game.cue", "Shared.bin", "Other.cue")
        val refs = mapOf("Game.cue" to listOf("Shared.bin"), "Other.cue" to listOf("Shared.bin"))
        assertEquals(listOf("Game.cue", "Shared.bin"), GameArtifacts.plan(names, "Game.cue", false) { refs[it].orEmpty() })
        assertEquals(listOf("Game.cue"), GameArtifacts.plan(names, "Game.cue", true) { refs[it].orEmpty() })
    }

    @Test fun `play follows playlist to cue to track without reading unrelated broken descriptors`() {
        val names = listOf("Game.m3u", "Disc 1.cue", "Disc 1.bin", "Broken.cue")
        val seen = mutableListOf<String>()
        val actual = GameArtifacts.plan(names, "Game.m3u", false) { name ->
            seen += name
            when (name) {
                "Game.m3u" -> listOf("disc 1.CUE")
                "Disc 1.cue" -> listOf("Disc 1.bin")
                else -> error("An unrelated descriptor must not block Play")
            }
        }
        assertEquals(listOf("Game.m3u", "Disc 1.cue", "Disc 1.bin"), actual)
        assertEquals(listOf("Game.m3u", "Disc 1.cue"), seen)
    }

    @Test fun `references cannot grant saves metadata or files outside the folder and cycles terminate`() {
        val names = listOf("Game.m3u", "Disc.cue", "Track.bin", "Game.srm", "notes.txt")
        val refs = mapOf("Game.m3u" to listOf("Disc.cue", "../Other.iso", "Game.srm", "notes.txt"), "Disc.cue" to listOf("Game.m3u", "Track.bin"))
        assertEquals(listOf("Game.m3u", "Disc.cue", "Track.bin"), GameArtifacts.plan(names, "Game.m3u", false) { refs[it].orEmpty() })
    }

    @Test fun `malicious descriptors cannot include unknown key and configuration formats in read grants`() {
        val names = listOf("Game.cue", "Track.bin", "private.key", "private.config", "keys.dat", "Game.srm")
        val files = GameArtifacts.plan(names, "Game.cue", false) { listOf("Track.bin", "private.key", "private.config", "keys.dat", "Game.srm") }
        assertEquals(listOf("Game.cue", "Track.bin"), files)
        assertEquals(emptyList<String>(), GameArtifacts.plan(names, "private.key", false) { emptyList() })
    }

    @Test fun `read-only planning recognizes megadrive md files without widening old removal rules`() {
        assertEquals(listOf("Game.md"), GameArtifacts.plan(listOf("Game.md"), "Game.md", false) { emptyList() })
        assertTrue(GameArtifacts.plan(listOf("Game.md"), "Game.md", true) { emptyList() }.isEmpty())
        assertEquals(listOf("Game.m3u", "Track.bin"), GameArtifacts.plan(listOf("Game.m3u", "README.md", "Track.bin"), "Game.m3u", true) { listOf("README.md", "Track.bin") })
    }
}
