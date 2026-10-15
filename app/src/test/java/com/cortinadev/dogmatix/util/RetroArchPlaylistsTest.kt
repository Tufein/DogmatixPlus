package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RetroArchPlaylistsTest {

    private val external = "content://com.android.externalstorage.documents"

    /** A document id as Android puts it in a uri: `:` `/` and spaces escaped, brackets kept. */
    private fun enc(id: String) = id.replace("%", "%25").replace(":", "%3A").replace("/", "%2F").replace(" ", "%20")
        .replace("#", "%23").replace("+", "%2B").replace("&", "%26")

    /** A file found by the library walk below a tree picked at `primary:ROMs`. */
    private fun file(folder: String, name: String, console: String?, volume: String = "primary", inSubfolder: Boolean = false): DiskFile {
        val id = "$volume:ROMs/$folder/$name"
        return DiskFile(
            scope = console.orEmpty(), consoleId = console, folder = "…/ROMs/$folder", name = name, size = 1_000,
            uri = "$external/tree/${enc("$volume:ROMs")}/document/${enc(id)}", inSubfolder = inSubfolder
        )
    }

    private val gba = "nintendo_gameboy_advance"
    private val psx = "sony_playstation"
    private val gbaSystem = "Nintendo - Game Boy Advance"

    private fun game(label: String, path: String?, others: List<String> = emptyList()) = RetroArchPlaylists.Game(gba, "$label.gba", label, path, others)

    private fun parse(bytes: ByteArray): JsonObject = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject

    // ---- Paths ---------------------------------------------------------------------------------

    @Test fun `internal storage and sd card documents become the paths RetroArch opens`() {
        assertEquals(
            "/storage/emulated/0/ROMs/gba/Advance Wars (USA).gba",
            RetroArchPlaylists.filesystemPath(file("gba", "Advance Wars (USA).gba", gba).uri)
        )
        assertEquals(
            "/storage/ABCD-1234/ROMs/gba/Game.gba",
            RetroArchPlaylists.filesystemPath(file("gba", "Game.gba", gba, volume = "ABCD-1234").uri)
        )
        // Nested folders, and the tree itself.
        assertEquals(
            "/storage/emulated/0/Games/Retro/ROMs/snes/USA/Game (USA).sfc",
            RetroArchPlaylists.filesystemPath("$external/tree/${enc("primary:Games")}/document/${enc("primary:Games/Retro/ROMs/snes/USA/Game (USA).sfc")}")
        )
        assertEquals("/storage/emulated/0/ROMs", RetroArchPlaylists.filesystemPath("$external/tree/${enc("primary:ROMs")}"))
        assertEquals("/storage/1A2B-3C4D/Roms", RetroArchPlaylists.filesystemPath("$external/document/${enc("1A2B-3C4D:Roms")}"))
    }

    @Test fun `escaped names come back as they are spelled, a plus stays a plus`() {
        assertEquals(
            "/storage/emulated/0/ROMs/gba/Pokémon #1 + Sonic & Co.gba",
            RetroArchPlaylists.filesystemPath(file("gba", "Pokémon #1 + Sonic & Co.gba", gba).uri.replace("é", "%C3%A9"))
        )
    }

    @Test fun `a Downloads raw id carries its path, other ids have none`() {
        val downloads = "content://com.android.providers.downloads.documents"
        assertEquals(
            "/storage/emulated/0/Download/ROMs/gba/Game.gba",
            RetroArchPlaylists.filesystemPath("$downloads/tree/${enc("raw:/storage/emulated/0/Download/ROMs")}/document/${enc("raw:/storage/emulated/0/Download/ROMs/gba/Game.gba")}")
        )
        // Not supported: a Downloads msf id, a cloud provider, the Documents "home" root, no content uri, broken escapes.
        assertNull(RetroArchPlaylists.filesystemPath("$downloads/document/msf%3A1234"))
        assertNull(RetroArchPlaylists.filesystemPath("content://com.example.cloud.documents/tree/root/document/${enc("root:ROMs/gba/Game.gba")}"))
        assertNull(RetroArchPlaylists.filesystemPath("$external/tree/${enc("home:ROMs")}/document/${enc("home:ROMs/Game.gba")}"))
        assertNull(RetroArchPlaylists.filesystemPath("file:///storage/emulated/0/ROMs/Game.gba"))
        assertNull(RetroArchPlaylists.filesystemPath("$external/tree/primary%3AROMs/document/primary%3AROMs%2FGame%2"))
        assertNull(RetroArchPlaylists.filesystemPath("$external/tree/${enc("primary:ROMs")}/document/${enc("primary:ROMs/../Game.gba")}"))
        assertNull(RetroArchPlaylists.filesystemPath("$external/tree/${enc("primary:ROMs")}/document/${enc("no-volume")}"))
    }

    @Test fun `shared storage of another user is used when the device says so`() {
        assertEquals(
            "/storage/emulated/10/ROMs/gba/Game.gba",
            RetroArchPlaylists.filesystemPath(file("gba", "Game.gba", gba).uri, "/storage/emulated/10")
        )
        // An SD card is the same for every user.
        assertEquals(
            "/storage/ABCD-1234/ROMs/gba/Game.gba",
            RetroArchPlaylists.filesystemPath(file("gba", "Game.gba", gba, volume = "ABCD-1234").uri, "/storage/emulated/10")
        )
    }

    @Test fun `paths compare without case, through archives and storage aliases`() {
        val key = RetroArchPlaylists.pathKey("/storage/emulated/0/ROMs/gba/Game.zip")
        assertEquals(key, RetroArchPlaylists.pathKey("/sdcard/roms/GBA/game.ZIP#Game.gba"))
        assertEquals(key, RetroArchPlaylists.pathKey("/storage/self/primary/ROMs/gba/Game.zip"))
        assertEquals(key, RetroArchPlaylists.pathKey("/storage/emulated/10/ROMs//gba/Game.zip"))
        assertEquals(key, RetroArchPlaylists.pathKey("/mnt/sdcard/ROMs/gba/Game.zip"))
        assertEquals(RetroArchPlaylists.pathKey("/storage/ABCD-1234/x.7z"), RetroArchPlaylists.pathKey("/mnt/media_rw/ABCD-1234/x.7z#y.bin"))
        // A # that is no archive delimiter is part of the name.
        assertFalse(RetroArchPlaylists.pathKey("/x/Game #1.gba") == RetroArchPlaylists.pathKey("/x/Game "))
    }

    // ---- The games -----------------------------------------------------------------------------

    @Test fun `one entry per game with its file name as label, no shortcuts, saves or images`() {
        val found = RetroArchPlaylists.gamesOf(
            listOf(
                file("gba", "Advance Wars (USA).gba", gba),
                file("gba", "Advance Wars (USA).srm", gba),
                file("gba", "Advance Wars (USA).state1", gba),
                file("gba", "Advance Wars (USA).dgmtx", gba),
                file("gba", "Advance Wars (USA).png", gba),
                file("gba", "readme.txt", gba)
            )
        )
        val system = found.systems.single()
        assertEquals(gbaSystem, system.system)
        assertEquals(listOf(gba), system.consoleIds)
        val only = system.games.single()
        assertEquals("Advance Wars (USA)", only.label)
        assertEquals("Advance Wars (USA).gba", only.fileName)
        assertEquals("/storage/emulated/0/ROMs/gba/Advance Wars (USA).gba", only.path)
    }

    @Test fun `the label is the name the RetroArch cover gets`() {
        val label = RetroArchPlaylists.gamesOf(listOf(file("gba", "Sonic & Knuckles (USA).gba", gba))).systems.single().games.single().label
        val system = LibretroThumbnails.systemFor(gba)!!
        assertEquals(
            FrontendArtwork.retroArchCoverPath(system, "Sonic & Knuckles (USA).gba"),
            "${FrontendArtwork.retroArchSystem(system)}/Named_Boxarts/${RetroArchPlaylists.thumbnailName(label)}.png"
        )
        assertEquals("Nintendo - Game Boy Advance.lpl", RetroArchPlaylists.fileName(RetroArchPlaylists.systemOf(gba)!!))
    }

    @Test fun `consoles of one system share a playlist, hidden and unnamed consoles are left out`() {
        val found = RetroArchPlaylists.gamesOf(
            listOf(
                file("nes", "Zelda (USA).nes", "nintendo_nes"),
                file("famicom", "Zelda (Japan).nes", "nintendo_famicom"),
                file("snes", "Hidden (USA).sfc", "nintendo_snes"),
                file("arcade", "pacman.zip", "arcade")
            ),
            hiddenConsoles = setOf("nintendo_snes")
        )
        val nes = found.systems.single()
        assertEquals("Nintendo - Nintendo Entertainment System", nes.system)
        assertEquals(listOf("nintendo_famicom", "nintendo_nes"), nes.consoleIds)
        assertEquals(2, nes.games.size)
        assertEquals(1, found.noSystem)
        assertEquals(listOf("arcade"), found.noSystemConsoles)
    }

    @Test fun `a disc set is one entry for its m3u, disc sets without one are not listed`() {
        val found = RetroArchPlaylists.gamesOf(
            listOf(
                file("psx", "Big Game (Disc 1).cue", psx),
                file("psx", "Big Game (Disc 1).bin", psx),
                file("psx", "Big Game (Disc 2).cue", psx),
                file("psx", "Big Game (Disc 2).bin", psx),
                file("psx", "Big Game.m3u", psx),
                file("psx", "Other (Disc 1).chd", psx),
                file("psx", "Other (Disc 2).chd", psx),
                file("psx", "Single (USA).chd", psx),
                file("psx", "Lone (Disc 1).chd", psx)
            )
        )
        val system = found.systems.single()
        assertEquals("Sony - PlayStation", system.system)
        assertEquals(listOf("Big Game", "Lone (Disc 1)", "Single (USA)"), system.games.map { it.label }.sorted())
        assertEquals(1, system.discSetsWithoutM3u)
        val big = system.games.first { it.label == "Big Game" }
        assertEquals("/storage/emulated/0/ROMs/psx/Big Game.m3u", big.path)
        assertTrue(big.otherPaths.contains("/storage/emulated/0/ROMs/psx/Big Game (Disc 2).cue"))
        assertTrue(big.otherPaths.contains("/storage/emulated/0/ROMs/psx/Big Game (Disc 1).bin"))
    }

    @Test fun `an m3u claims the discs its lines name, also in a sub-folder`() {
        val m3u = file("psx", "Odd.m3u", psx)
        val found = RetroArchPlaylists.gamesOf(
            listOf(
                m3u,
                file("psx/discs", "Odd Start.chd", psx, inSubfolder = true),
                file("psx/discs", "Odd Finish.chd", psx, inSubfolder = true)
            ),
            m3uLines = mapOf(m3u.fileId to RetroArchPlaylists.m3uLines("#EXTM3U\r\ndiscs/Odd Start.chd\r\n\r\n./discs\\Odd Finish.chd\r\n".toByteArray())!!)
        )
        val games = found.systems.single().games
        assertEquals(listOf("Odd"), games.map { it.label })
        assertEquals(2, games.single().otherPaths.size)
    }

    @Test fun `a game in a folder of its own is named after the folder`() {
        val dc = "sega_dreamcast"
        val found = RetroArchPlaylists.gamesOf(
            listOf(
                file("dc/Crazy Taxi (USA)", "disc.gdi", dc, inSubfolder = true),
                file("dc/Crazy Taxi (USA)", "track01.bin", dc, inSubfolder = true),
                file("dc/Crazy Taxi (USA)", "track02.raw", dc, inSubfolder = true),
                file("dc/Crazy Taxi (USA)", "track03.bin", dc, inSubfolder = true)
            )
        )
        val game = found.systems.single().games.single()
        assertEquals("Crazy Taxi (USA)", game.label)
        assertEquals("/storage/emulated/0/ROMs/dc/Crazy Taxi (USA)/disc.gdi", game.path)
    }

    @Test fun `games on storage without a path are counted, never listed`() {
        val cloud = file("gba", "Cloud (USA).gba", gba).copy(uri = "content://com.example.cloud/document/abc")
        val found = RetroArchPlaylists.gamesOf(listOf(cloud, file("gba", "Local (USA).gba", gba)))
        val plan = RetroArchPlaylists.plan(found.systems.single(), RetroArchPlaylists.Read.New)
        assertEquals(1, plan.noPath)
        assertEquals(listOf("Local (USA)"), plan.add.map { it.label })
    }

    // ---- Reading, planning, writing ------------------------------------------------------------

    @Test fun `a new playlist has RetroArch's fields and DETECT cores, in label order`() {
        val games = RetroArchPlaylists.SystemGames(gbaSystem, listOf(gba), listOf(
            game("Zelda (USA)", "/storage/emulated/0/ROMs/gba/Zelda (USA).gba"),
            game("advance Wars (USA)", "/storage/emulated/0/ROMs/gba/advance Wars (USA).gba"),
            game("Metroid (USA)", "/storage/emulated/0/ROMs/gba/Metroid (USA).gba")
        ))
        val plan = RetroArchPlaylists.plan(games, RetroArchPlaylists.Read.New)
        assertEquals(RetroArchPlaylists.State.NEW, plan.state)
        val root = parse(RetroArchPlaylists.write(RetroArchPlaylists.Read.New, gbaSystem, plan.add))
        assertEquals("1.5", root.get("version").asString)
        assertEquals("", root.get("default_core_path").asString)
        assertEquals("", root.get("default_core_name").asString)
        for (k in listOf("label_display_mode", "right_thumbnail_mode", "left_thumbnail_mode", "sort_mode")) assertEquals(0, root.get(k).asInt)
        val items = root.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("advance Wars (USA)", "Metroid (USA)", "Zelda (USA)"), items.map { it.get("label").asString })
        val first = items.first()
        assertEquals("/storage/emulated/0/ROMs/gba/advance Wars (USA).gba", first.get("path").asString)
        assertEquals("DETECT", first.get("core_path").asString)
        assertEquals("DETECT", first.get("core_name").asString)
        assertEquals("00000000|crc", first.get("crc32").asString)
        assertEquals("Nintendo - Game Boy Advance.lpl", first.get("db_name").asString)
    }

    private val existing = """
        {
          "version": "1.5",
          "default_core_path": "/data/user/0/com.retroarch/cores/mgba_libretro_android.so",
          "default_core_name": "Nintendo - Game Boy Advance (mGBA)",
          "label_display_mode": 0,
          "right_thumbnail_mode": 4,
          "left_thumbnail_mode": 0,
          "thumbnail_match_mode": 0,
          "sort_mode": 2,
          "scan_content_dir": "/storage/emulated/0/ROMs/gba",
          "x_custom": {"nested": [1, 2.50, null, true], "note": "Ünïcödé <&> \"quoted\""},
          "x_null": null,
          "items": [
            {
              "path": "/sdcard/ROMs/gba/Zelda (USA).zip#Zelda (USA).gba",
              "label": "My Zelda",
              "core_path": "/data/user/0/com.retroarch/cores/mgba_libretro_android.so",
              "core_name": "Nintendo - Game Boy Advance (mGBA)",
              "crc32": "1234ABCD|crc",
              "db_name": "Nintendo - Game Boy Advance.lpl",
              "entry_slot": 3,
              "runtime_hours": 12
            },
            {
              "label": "An entry without a path"
            }
          ]
        }
    """.trimIndent()

    @Test fun `adding to a playlist keeps everything in it, unknown fields and numbers included`() {
        val read = RetroArchPlaylists.read(existing.toByteArray())
        assertTrue(read is RetroArchPlaylists.Read.Json)
        val games = RetroArchPlaylists.SystemGames(gbaSystem, listOf(gba), listOf(
            game("Zelda (USA)", "/storage/emulated/0/ROMs/gba/Zelda (USA).zip"),
            game("Wario (USA)", "/storage/emulated/0/ROMs/gba/Wario (USA).gba"),
            game("Kirby (USA)", "/storage/emulated/0/ROMs/gba/Kirby (USA).gba")
        ))
        val plan = RetroArchPlaylists.plan(games, read)
        assertEquals(RetroArchPlaylists.State.ADD, plan.state)
        assertEquals(1, plan.already)
        assertEquals(2, plan.existingEntries)
        val out = RetroArchPlaylists.write(read, gbaSystem, plan.add)
        val text = out.toString(Charsets.UTF_8)
        val before = JsonParser.parseString(existing).asJsonObject
        val after = parse(out)
        // Every top-level field in its order, with its value.
        assertEquals(before.keySet().toList(), after.keySet().toList())
        for (k in before.keySet().filter { it != "items" }) assertEquals(k, before.get(k), after.get(k))
        assertTrue(text.contains("\"sort_mode\": 2,"))
        assertTrue(text.contains("2.50"))
        assertTrue(text.contains("\"x_null\": null"))
        assertTrue(text.contains("Ünïcödé <&>"))
        // The entries that were there, untouched and first; the new ones after them, in label order.
        val items = after.getAsJsonArray("items").map { it.asJsonObject }
        val old = before.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(old, items.take(2))
        assertEquals(listOf("Kirby (USA)", "Wario (USA)"), items.drop(2).map { it.get("label").asString })
        // The original is never changed by writing (a copy is).
        assertEquals(2, (read as RetroArchPlaylists.Read.Json).items.size)
    }

    @Test fun `a game already listed by any of its files is not added again, nor twice`() {
        val read = RetroArchPlaylists.read(existing.toByteArray())
        val games = RetroArchPlaylists.SystemGames(gbaSystem, listOf(gba), listOf(
            // The playlist lists the zip (as an archive entry): the same game.
            game("Zelda", "/storage/emulated/0/roms/GBA/zelda (usa).ZIP"),
            game("Disc set", "/storage/emulated/0/ROMs/gba/Set.m3u", others = listOf("/sdcard/ROMs/gba/Zelda (USA).zip")),
            game("Twice A", "/storage/emulated/0/ROMs/gba/Twice.gba"),
            game("Twice B", "/storage/emulated/0/ROMs/gba/twice.gba")
        ))
        val plan = RetroArchPlaylists.plan(games, read)
        // Zelda by its zip, the disc set by one of its files, Twice B as Twice A's file in other case.
        assertEquals(3, plan.already)
        assertEquals(listOf("Twice A"), plan.add.map { it.label })
        // Written once, the next plan finds everything there.
        val again = RetroArchPlaylists.plan(games, RetroArchPlaylists.read(RetroArchPlaylists.write(read, gbaSystem, plan.add)))
        assertEquals(RetroArchPlaylists.State.NOTHING_TO_ADD, again.state)
        assertFalse(again.writes)
    }

    @Test fun `old format, compressed and broken playlists are left alone`() {
        val old = "/storage/emulated/0/ROMs/gba/Game.gba\nGame\nDETECT\nDETECT\n00000000|crc\nNintendo - Game Boy Advance.lpl\n"
        assertEquals(RetroArchPlaylists.LeftAlone.OLD_FORMAT, (RetroArchPlaylists.read(old.toByteArray()) as RetroArchPlaylists.Read.Kept).why)
        assertEquals(RetroArchPlaylists.LeftAlone.COMPRESSED, (RetroArchPlaylists.read("#RZIPv1#\u0000\u0001".toByteArray()) as RetroArchPlaylists.Read.Kept).why)
        val broken = listOf(
            "{\"items\": [",
            "{\"items\": [], \"items\": []}",
            "{\"items\": {}}",
            "{\"items\": [\"a\"]}",
            "{\"items\": [],}",
            "{\"version\": '1.5'}",
            "{} {}"
        )
        for (b in broken) {
            val read = RetroArchPlaylists.read(b.toByteArray())
            assertEquals(b, RetroArchPlaylists.LeftAlone.UNREADABLE, (read as? RetroArchPlaylists.Read.Kept)?.why)
        }
        assertEquals(RetroArchPlaylists.LeftAlone.UNREADABLE, (RetroArchPlaylists.read(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D)) as RetroArchPlaylists.Read.Kept).why)
        assertEquals(RetroArchPlaylists.LeftAlone.TOO_BIG, (RetroArchPlaylists.read(ByteArray(RetroArchPlaylists.MAX_PLAYLIST_BYTES + 1)) as RetroArchPlaylists.Read.Kept).why)

        val games = RetroArchPlaylists.SystemGames(gbaSystem, listOf(gba), listOf(game("Game", "/storage/emulated/0/ROMs/gba/New.gba")))
        val kept = RetroArchPlaylists.read(old.toByteArray())
        val plan = RetroArchPlaylists.plan(games, kept)
        assertEquals(RetroArchPlaylists.State.LEFT_ALONE, plan.state)
        assertEquals(RetroArchPlaylists.LeftAlone.OLD_FORMAT, plan.leftAlone)
        assertTrue(plan.add.isEmpty())
        try {
            RetroArchPlaylists.write(kept, gbaSystem, games.games)
            fail("A playlist that is left alone is never written")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `an empty file is a new playlist, a byte order mark is fine`() {
        assertTrue(RetroArchPlaylists.read(ByteArray(0)) is RetroArchPlaylists.Read.New)
        assertTrue(RetroArchPlaylists.read(" \n".toByteArray()) is RetroArchPlaylists.Read.New)
        assertTrue(RetroArchPlaylists.read(null) is RetroArchPlaylists.Read.New)
        val bom = "\uFEFF{\"version\": \"1.0\", \"items\": []}".toByteArray()
        val read = RetroArchPlaylists.read(bom)
        assertTrue(read is RetroArchPlaylists.Read.Json)
        // An older JSON version keeps its version: only entries are added.
        val out = parse(RetroArchPlaylists.write(read, gbaSystem, listOf(game("A", "/storage/emulated/0/a.gba"))))
        assertEquals("1.0", out.get("version").asString)
        // A playlist without items gets them.
        val bare = RetroArchPlaylists.read("{\"version\": \"1.5\"}".toByteArray())
        assertEquals(1, parse(RetroArchPlaylists.write(bare, gbaSystem, listOf(game("A", "/storage/emulated/0/a.gba")))).getAsJsonArray("items").size())
    }

    @Test fun `m3u lines skip blanks, a big or binary file is not read`() {
        assertEquals(listOf("#EXTM3U", "a.cue", "b.cue"), RetroArchPlaylists.m3uLines("#EXTM3U\r\na.cue\n\n  b.cue  \n".toByteArray()))
        assertNull(RetroArchPlaylists.m3uLines(ByteArray(RetroArchPlaylists.MAX_M3U_BYTES + 1) { 'a'.code.toByte() }))
        assertNull(RetroArchPlaylists.m3uLines(byteArrayOf(0, 1, 2)))
    }

    @Test fun `settings keys are typed for backups`() {
        assertEquals("s", SettingSchema.expectedType(RetroArchPlaylists.FOLDER_KEY))
        assertEquals("ss", SettingSchema.expectedType(RetroArchPlaylists.EXCLUDED_KEY))
        assertNotNull(SettingSchema.types[RetroArchPlaylists.FOLDER_KEY])
    }
}
