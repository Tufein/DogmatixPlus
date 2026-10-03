package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun file(name: String, folder: String = ".../ROMs/gba", scope: String = "nintendo_gameboy_advance", size: Long = 100) =
        DiskFile(scope, scope.takeUnless { it.isEmpty() || it.startsWith("folder:") }, folder, name, size, "content://$folder/$name")

    @Test
    fun regionVariantsAreReported() {
        val groups = DuplicateFinder.find(listOf(
            file("Golden Sun (USA).gba", size = 8_000),
            file("Golden Sun (Europe) (En,Fr,De).gba", size = 8_000),
            file("Advance Wars (USA).gba")
        ))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroup.Kind.VARIANT, groups[0].kind)
        assertEquals("Golden Sun", groups[0].title)
        assertEquals(8_000L, groups[0].reclaimable)
    }

    @Test
    fun sameFileInTwoFoldersIsIdentical() {
        val groups = DuplicateFinder.find(listOf(
            file("Metroid Fusion (USA).gba", folder = ".../ROMs/gba"),
            file("Metroid Fusion (USA).gba", folder = ".../ROMs/Game Boy Advance")
        ))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroup.Kind.IDENTICAL, groups[0].kind)
    }

    @Test
    fun discsOfOneGameAreNotDuplicates() {
        val groups = DuplicateFinder.find(listOf(
            file("Final Fantasy VII (USA) (Disc 1).chd", scope = "sony_playstation"),
            file("Final Fantasy VII (USA) (Disc 2).chd", scope = "sony_playstation"),
            file("Final Fantasy VII (USA) (Disc 3).chd", scope = "sony_playstation")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun cueBinAndTracksCountAsOneGame() {
        val files = listOf(
            file("Rayman (USA).cue", scope = "sony_playstation"),
            file("Rayman (USA) (Track 1).bin", scope = "sony_playstation"),
            file("Rayman (USA) (Track 2).bin", scope = "sony_playstation")
        )
        assertEquals(1, DuplicateFinder.entries(files).size)
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun differentConsolesNeverMatch() {
        val groups = DuplicateFinder.find(listOf(
            file("Tetris (World).gb", scope = "nintendo_gameboy"),
            file("Tetris (World).nes", scope = "nintendo_nes")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun shortcutsArtworkAndSavesAreIgnored() {
        assertFalse(DuplicateFinder.isGameFile("★ Search for more games....dgmtx"))
        assertFalse(DuplicateFinder.isGameFile("Golden Sun (USA).sav"))
        assertFalse(DuplicateFinder.isGameFile("cover.png"))
        assertFalse(DuplicateFinder.isGameFile(".nomedia"))
        assertTrue(DuplicateFinder.isGameFile("Golden Sun (USA).gba"))
        val groups = DuplicateFinder.find(listOf(
            file("Golden Sun (USA).gba"),
            file("Golden Sun (USA).sav")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun versionSuffixDoesNotSplitTitles() {
        assertEquals(DuplicateFinder.titleKey("Pokemon Emerald v1.1"), DuplicateFinder.titleKey("Pokémon Emerald (USA)"))
    }

    @Test
    fun largestCopyLeadsTheGroup() {
        val groups = DuplicateFinder.find(listOf(
            file("Zelda (USA).gba", size = 10),
            file("Zelda (Europe).gba", size = 30),
            file("Zelda (Japan).gba", size = 20)
        ))
        assertEquals(listOf(30L, 20L, 10L), groups[0].entries.map { it.size })
        assertEquals(30L, groups[0].reclaimable)
    }

    @Test
    fun looseFilesOfDifferentSystemsDoNotMatch() {
        val files = listOf(
            file("Tetris (World).gb", folder = ".../ROMs", scope = ""),
            file("Tetris (World).nes", folder = ".../ROMs", scope = ""),
            file("Tetris (Japan).gb", folder = ".../ROMs", scope = "")
        )
        // The .nes ROM is its own game: never part of the Game Boy entry, never offered with it.
        assertEquals(3, DuplicateFinder.entries(files).size)
        val groups = DuplicateFinder.find(files)
        assertEquals(1, groups.size)
        assertEquals(listOf("Tetris (Japan).gb", "Tetris (World).gb"), groups[0].entries.flatMap { it.files }.map { it.name }.sorted())
    }

    // ---- Per-game folders (pre-push review: generic names destroyed other games) ----------

    private fun dc(game: String, name: String, size: Long) =
        file(name, folder = ".../ROMs/dc/$game", scope = "sega_dreamcast", size = size).copy(inSubfolder = true)

    private fun gdiFolder(game: String, bigTrack: Long) = listOf(
        dc(game, "disc.gdi", 1), dc(game, "track01.bin", 1_000), dc(game, "track02.raw", 2_000), dc(game, "track03.bin", bigTrack)
    )

    @Test
    fun gdiFoldersOfDifferentGamesAreNotDuplicates() {
        val files = gdiFolder("Crazy Taxi (USA)", 900_000) + gdiFolder("Shenmue (USA)", 1_100_000)
        val entries = DuplicateFinder.entries(files)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.isFolderGame && it.files.size == 4 })
        assertEquals(setOf("Crazy Taxi (USA)", "Shenmue (USA)"), entries.map { it.baseName }.toSet())
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun gdiFoldersOfTheSameGameAreVariantsAsWholeFolders() {
        val groups = DuplicateFinder.find(gdiFolder("Crazy Taxi (USA)", 900_000) + gdiFolder("Crazy Taxi (Europe)", 950_000))
        assertEquals(1, groups.size)
        assertEquals(DuplicateGroup.Kind.VARIANT, groups[0].kind)
        assertTrue(groups[0].entries.all { it.isFolderGame && it.files.size == 4 })
    }

    @Test
    fun namedSheetJoinsItsGenericTracks() {
        val files = listOf(dc("Crazy Taxi", "Crazy Taxi.gdi", 1), dc("Crazy Taxi", "track01.bin", 10), dc("Crazy Taxi", "track03.bin", 99))
        val entries = DuplicateFinder.entries(files)
        assertEquals(1, entries.size)
        assertEquals(3, entries[0].files.size)
    }

    @Test
    fun regionFolderWithStrayGenericFileIsNeverMerged() {
        val usa = ".../ROMs/ps2/USA"
        val files = listOf("Game A (USA).iso", "Game B (USA).iso", "game.iso").map {
            file(it, folder = usa, scope = "sony_ps2").copy(inSubfolder = true)
        }
        assertEquals(3, DuplicateFinder.entries(files).size)
    }

    @Test
    fun genericFileNamesAreNeverComparedOnTheirOwn() {
        val files = listOf("Kings Quest", "Space Quest").flatMap { game ->
            listOf("RESOURCE.MAP", "RESOURCE.000").map { file(it, folder = ".../ROMs/scummvm/$game", scope = "pc_scummvm").copy(inSubfolder = true) }
        }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun genericNamesInTheConsoleFolderItselfAreNotMerged() {
        // inSubfolder = false: the console folder is never treated as one game.
        val files = listOf(file("track01.bin"), file("Golden Sun (USA).gba"), file("Metroid (USA).gba"))
        assertEquals(3, DuplicateFinder.entries(files).size)
    }

    // ---- Identity of files and folders --------------------------------------------------------

    @Test
    fun sameNameWithDifferentSizeIsAVariantNotAnIdenticalCopy() {
        val groups = DuplicateFinder.find(listOf(
            file("Zelda (USA).gba", folder = ".../ROMs/gba", size = 100),
            file("Zelda (USA).gba", folder = ".../ROMs/Game Boy Advance", size = 120)
        ))
        assertEquals(DuplicateGroup.Kind.VARIANT, groups.single().kind)
    }

    @Test
    fun theSamePhysicalFileReachedTwiceIsCountedOnce() {
        val a = file("Zelda (USA).gba", folder = ".../ROMs/gba").copy(fileId = "/storage/emulated/0/roms/gba/zelda (usa).gba")
        val b = a.copy(folder = "...//storage/emulated/0/ROMs/gba", uri = "content://downloads/raw", dirId = "other")
        assertEquals(1, DuplicateFinder.entries(listOf(a, b)).size)
        assertTrue(DuplicateFinder.find(listOf(a, b)).isEmpty())
    }

    @Test
    fun foldersWithTheSameDisplayPathOnDifferentVolumesStayApart() {
        val internal = file("Zelda (USA).gba", folder = ".../ROMs/gba").copy(dirId = "/storage/emulated/0/roms/gba")
        val sd = file("Zelda (USA).gba", folder = ".../ROMs/gba")
            .copy(dirId = "/storage/1234-5678/roms/gba", uri = "content://sd/zelda", fileId = "/storage/1234-5678/roms/gba/zelda (usa).gba")
        val groups = DuplicateFinder.find(listOf(internal, sd))
        assertEquals(DuplicateGroup.Kind.IDENTICAL, groups.single().kind)
        assertTrue(groups.single().entries.all { it.files.size == 1 })
    }

    // ---- Companions that must never be deleted with a ROM -------------------------------------

    @Test
    fun statesSavesAndPatchesAreNotGameFiles() {
        listOf("Zelda.state1", "Zelda.state.auto", "Zelda.ss1", "Zelda.st0", "Zelda.eep", "Zelda.sra", "Zelda.fla",
            "Zelda.mpk", "Zelda.dsv", "Zelda.rtc", "Zelda.ips", "Zelda.bps", "Zelda.ups", "Zelda.cht", "Zelda.xdelta"
        ).forEach { assertFalse(it, DuplicateFinder.isGameFile(it)) }
        assertTrue(DuplicateFinder.isGameFile("Zelda.st"))  // Atari ST image, not a save state
        val entry = DuplicateFinder.entries(listOf(file("Zelda (USA).gba"), file("Zelda (USA).ss1"), file("Zelda (USA).state2"))).single()
        assertEquals(listOf("Zelda (USA).gba"), entry.files.map { it.name })
    }

    @Test
    fun aRomAndItsZipAreTwoCopies() {
        val groups = DuplicateFinder.find(listOf(file("Zelda (USA).gba", size = 100), file("Zelda (USA).zip", size = 60)))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].entries.size)
    }

    // ---- Titles -------------------------------------------------------------------------------

    @Test
    fun sidesOfOneDiskAreNotDuplicates() {
        val groups = DuplicateFinder.find(listOf(
            file("Ultima V (Disk 1 of 2)(Side A).d64", scope = "commodore_c64"),
            file("Ultima V (Disk 1 of 2)(Side B).d64", scope = "commodore_c64")
        ))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun nonLatinTitlesKeepTheirLetters() {
        assertTrue(DuplicateFinder.titleKey("Тетрис 2") != DuplicateFinder.titleKey("Марио 2"))
        assertTrue(DuplicateFinder.titleKey("ドラゴンクエスト").isNotEmpty())
        assertEquals(DuplicateFinder.titleKey("Pokémon Emerald"), DuplicateFinder.titleKey("Pokemon Emerald"))
    }

    @Test
    fun groupScopeIsTheFolderNotTheComparisonKey() {
        val groups = DuplicateFinder.find(listOf(
            file("Tetris (World).gb", folder = ".../ROMs/Misc Games", scope = "folder:Misc Games"),
            file("Tetris (Japan).gb", folder = ".../ROMs/Misc Games", scope = "folder:Misc Games")
        ))
        assertEquals("folder:Misc Games", groups.single().scope)
    }

    // ---- Second review: structure instead of name lists ---------------------------------------

    private fun sub(folder: String, name: String, scope: String = "pc_dos", size: Long = 100) =
        file(name, folder = folder, scope = scope, size = size).copy(inSubfolder = true)

    @Test
    fun oneGamesCompanionFilesInAConsoleFolderStayOneGame() {
        val files = listOf(
            file("Sonic CD (USA).cue", scope = "sega_cd"), file("Sonic CD (USA).iso", scope = "sega_cd"),
            file("Sonic CD (USA) (Track 02).wav", scope = "sega_cd"),
            file("DOOM.EXE", scope = "pc_dos"), file("DOOM.WAD", scope = "pc_dos")
        )
        // The disc image is one game; DOOM.EXE is a program, DOOM.WAD its data: neither is ever offered.
        assertEquals(3, DuplicateFinder.entries(files).size)
        assertEquals(1, DuplicateFinder.entries(files).count { it.files.size == 3 })
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun programFoldersAreNeverCompared() {
        val files = listOf("Kings Quest", "Space Quest").flatMap { game ->
            listOf("VOL.0", "VOL.1", "LOGDIR", "WORDS.TOK", "OBJECT").map { sub(".../ROMs/scummvm/$game", it, scope = "pc_scummvm") }
        } + listOf("Half-Life", "Portal").map { sub(".../ROMs/pc/$it", "d3dx9_43.dll", scope = "pc_windows", size = 2_000) } +
            listOf("Half-Life", "Portal").map { sub(".../ROMs/pc/$it", "game.iso", scope = "pc_windows", size = 9_000) }
        assertTrue(DuplicateFinder.find(files).isEmpty())
        assertTrue(DuplicateFinder.entries(files).none { it.comparable })
    }

    @Test
    fun aFolderWithOneTitledImagePlusAnotherImageIsNotMerged() {
        val tekken = listOf("Tekken 3 (USA).cue", "Tekken 3 (USA).bin", "disc.cue", "disc.bin").map { sub(".../ROMs/psx/Tekken", it, scope = "sony_psx") }
        val entries = DuplicateFinder.entries(tekken)
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.isFolderGame && it.files.size == 4 })
        // The generic "disc" entry is never offered; only "Tekken 3 (USA)" can be compared.
        val europe = file("Tekken 3 (Europe).cue", folder = ".../ROMs/psx", scope = "sony_psx")
        val group = DuplicateFinder.find(tekken + europe).single()
        assertEquals(setOf("Tekken 3 (USA)", "Tekken 3 (Europe)"), group.entries.map { it.baseName }.toSet())
    }

    @Test
    fun foldersOfGenericImagesAreNeverMergedOrCompared() {
        val files = listOf("game.iso", "rom.iso", "disc1.iso").map { sub(".../ROMs/ps2/Stuff", it, scope = "sony_ps2") }
        assertEquals(3, DuplicateFinder.entries(files).size)
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun genericFolderNamesNeverBecomeTitles() {
        val files = listOf("Okami", "Zelda").map { sub(".../ROMs/ps2/$it/ISO", "game.iso", scope = "sony_ps2", size = 5_000) }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun aSingleGameFolderWithAGenericFileIsNamedAfterTheFolder() {
        val files = listOf("Okami (USA)", "Okami (Europe)").flatMap { game ->
            listOf("image.cue", "image.bin").map { sub(".../ROMs/ps2/$game", it, scope = "sony_ps2") }
        } + listOf("Zelda (USA)").flatMap { game -> listOf("image.cue", "image.bin").map { sub(".../ROMs/ps2/$game", it, scope = "sony_ps2") } }
        val group = DuplicateFinder.find(files).single()
        assertEquals(setOf("Okami (USA)", "Okami (Europe)"), group.entries.map { it.baseName }.toSet())
    }

    @Test
    fun aRegionFolderWithASingleGameUsesTheGameNotTheRegion() {
        val okami = sub(".../ROMs/ps2/USA", "Okami (USA).iso", scope = "sony_ps2")
        val zelda = sub(".../ROMs/ps2/Europe", "Zelda (Europe).iso", scope = "sony_ps2")
        assertEquals(setOf("Okami (USA)", "Zelda (Europe)"), DuplicateFinder.entries(listOf(okami, zelda)).map { it.baseName }.toSet())
        assertTrue(DuplicateFinder.find(listOf(okami, zelda)).isEmpty())
    }

    @Test
    fun aPlaylistIsNotACopyOfItsDiscs() {
        val files = listOf(file("Final Fantasy VII (USA).m3u", folder = ".../ROMs/psx", scope = "sony_psx")) +
            listOf("disc1.cue", "disc1.bin", "disc2.cue", "disc2.bin").map { sub(".../ROMs/psx/Final Fantasy VII (USA)", it, scope = "sony_psx") }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun identicalCopiesWithoutProvablyDifferentPathsAreNotOffered() {
        val a = file("Zelda (USA).gba", folder = ".../ROMs/gba")
            .copy(fileId = "com.android.providers.downloads.documents|msf:12", rootId = "root:a")
        val b = file("Zelda (USA).gba", folder = ".../ROMs/Game Boy Advance")
            .copy(fileId = "/storage/emulated/0/roms/gba/zelda (usa).gba", rootId = "custom:b")
        assertTrue(DuplicateFinder.find(listOf(a, b)).isEmpty())
        // Inside one scan route the ids of a provider are unique, so real copies there are offered.
        assertEquals(1, DuplicateFinder.find(listOf(a, b.copy(rootId = "root:a"))).size)
    }

    @Test
    fun patchesAndCdSavesAreNotPartOfTheGame() {
        assertFalse(DuplicateFinder.isGameFile("Game (Europe).ppf"))
        assertFalse(DuplicateFinder.isGameFile("Sonic CD (USA).brm"))
    }

    // ---- Third review: only clear games, shallow levels, no add-ons ---------------------------

    private fun at(level: Int, folder: String, name: String, scope: String, size: Long = 100) =
        file(name, folder = folder, scope = scope, size = size).copy(level = level)

    @Test
    fun folderFormatGamesWithFixedNamesAreNeverOffered() {
        val ps3 = listOf("Demon's Souls (USA)", "Gran Turismo 5 (Europe)").map { at(1, ".../ROMs/ps3/$it", "PS3_DISC.SFB", "sony_ps3", 8192) }
        assertTrue(DuplicateFinder.find(ps3).isEmpty())
        assertTrue(DuplicateFinder.entries(ps3).none { it.comparable })
        val wiiu = listOf("Zelda (USA)", "Mario Kart 8 (USA)").flatMap { game ->
            listOf("code/fw.img", "meta/iconTex.tga", "code/U-King.rpx").map { at(2, ".../wiiu/$game/${it.substringBefore('/')}", it.substringAfter('/'), "nintendo_wiiu") }
        }
        assertTrue(DuplicateFinder.find(wiiu).isEmpty())
    }

    @Test
    fun nothingDeeperThanOneFolderIsCompared() {
        val files = listOf("Okami", "Zelda").map { at(2, ".../ROMs/ps2/$it/Images", "Game (USA).iso", "sony_ps2", 900) }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun updatesAndDlcAreNeverACopyOfTheBaseGame() {
        val switch = listOf(
            "Super Mario Odyssey [0100000000010000][v0].nsp", "Super Mario Odyssey [0100000000010800][v393216][UPD].nsp",
            "Super Mario Odyssey [0100000000011001][DLC].nsp"
        ).mapIndexed { i, n -> file(n, folder = ".../ROMs/switch", scope = "nintendo_switch", size = 1000L + i) }
        assertTrue(DuplicateFinder.find(switch).isEmpty())
        val n3ds = listOf("Animal Crossing - New Leaf (USA).3ds", "Animal Crossing - New Leaf (USA) (Update) (v1.5).cia")
            .mapIndexed { i, n -> file(n, folder = ".../ROMs/3ds", scope = "nintendo_3ds", size = 100L + i) }
        assertTrue(DuplicateFinder.find(n3ds).isEmpty())
        // But two dumps of the base game are still found.
        val bases = listOf("Super Mario Odyssey [0100000000010000][v0].nsp", "Super Mario Odyssey [0100000000010000][v0] (Rev 1).nsp")
            .mapIndexed { i, n -> file(n, folder = ".../ROMs/switch", scope = "nintendo_switch", size = 1000L + i) }
        assertEquals(1, DuplicateFinder.find(bases).size)
    }

    @Test
    fun containerFormatsOutsideConsoleFoldersNeedAnIdenticalTwin() {
        fun loose(name: String, size: Long, folder: String = ".../Dogmatix") = file(name, folder = folder, scope = "", size = size)
        // A Mega Drive zip and a Game Gear zip with the same title are different games.
        assertTrue(DuplicateFinder.find(listOf(loose("Sonic The Hedgehog (USA, Europe).zip", 500), loose("Sonic The Hedgehog (Japan, USA).zip", 300))).isEmpty())
        // PS1 and PS2 chd of one title in unknown folders.
        val chds = listOf("psx" to 400L, "ps2" to 500L).map { (dir, size) -> file("Spider-Man (USA).chd", folder = ".../roms/$dir", scope = "folder:roms", size = size).copy(level = 1) }
        assertTrue(DuplicateFinder.find(chds).isEmpty())
        // Exact twins (same name and size) are still reported.
        val twins = listOf(".../a", ".../b").map { loose("Tetris (USA).zip", 123, it) }
        assertEquals(DuplicateGroup.Kind.IDENTICAL, DuplicateFinder.find(twins).single().kind)
    }

    @Test
    fun mameZipAndItsChdFolderAreOneGame() {
        val files = listOf(
            file("kinst.zip", folder = ".../ROMs/mame", scope = "arcade_mame", size = 1_000),
            file("kinst.chd", folder = ".../ROMs/mame/kinst", scope = "arcade_mame", size = 280_000).copy(level = 1)
        )
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun partsOfOneGameAreNeverCopiesOfEachOther() {
        listOf(
            "Last Ninja 2, The (1988)(System 3)(Tape 1 of 2).tap" to "Last Ninja 2, The (1988)(System 3)(Tape 2 of 2).tap",
            "Lords of Midnight (1984)(Beyond)(Part 1 of 2).tzx" to "Lords of Midnight (1984)(Beyond)(Part 2 of 2).tzx",
            "Final Fantasy VIII (Disc One).chd" to "Final Fantasy VIII (Disc Two).chd",
            "Parasite Eve (Disc II).chd" to "Parasite Eve (Disc III).chd"
        ).forEach { (a, b) ->
            assertTrue(a, DuplicateFinder.find(listOf(file(a, scope = "x_console"), file(b, scope = "x_console"))).isEmpty())
        }
        // "Disc 2", "Disc Two" and "Disc II" are the same disc: that really is a duplicate.
        assertEquals(DuplicateFinder.titleKey("Parasite Eve (Disc 2)"), DuplicateFinder.titleKey("Parasite Eve (Disc II)"))
        assertEquals(DuplicateFinder.titleKey("Parasite Eve (Disc 2)"), DuplicateFinder.titleKey("Parasite Eve (Disc Two)"))
    }

    @Test
    fun unknownCompanionFilesAreNeverDeletedWithTheGame() {
        val files = listOf("Nights into Dreams (USA).cue", "Nights into Dreams (USA) (Track 1).bin", "Nights into Dreams (USA).bkr",
            "Nights into Dreams (USA).smpc").map { file(it, folder = ".../ROMs/saturn", scope = "sega_saturn") } +
            file("Nights into Dreams (Europe).chd", folder = ".../ROMs/saturn", scope = "sega_saturn", size = 900)
        val usa = DuplicateFinder.find(files).single().entries.first { "USA" in it.baseName }
        assertEquals(listOf("Nights into Dreams (USA) (Track 1).bin", "Nights into Dreams (USA).cue"), usa.files.map { it.name })
    }

    @Test
    fun documentsAreNeverOffered() {
        val docs = listOf("CV.docx", "CV (1).docx", "Bank statement.csv", "Bank statement (1).csv").mapIndexed { i, n -> file(n, folder = ".../Download", scope = "", size = 10L + i) }
        assertTrue(DuplicateFinder.find(docs).isEmpty())
        // A browser copy of a real ROM is still a duplicate.
        assertEquals(1, DuplicateFinder.find(listOf(file("Zelda (USA).gba", size = 5), file("Zelda (USA) (1).gba", size = 5, folder = ".../ROMs/gba/more").copy(level = 1))).size)
    }

    @Test
    fun biosFilesAreNeverOffered() {
        val files = listOf(file("scph5501.bin", folder = ".../ROMs/psx", scope = "sony_psx", size = 524_288),
            file("scph5501.bin", folder = ".../ROMs/psx/bios", scope = "sony_psx", size = 524_288).copy(level = 1))
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun aMegaDriveRomIsAGameButMarkdownIsNot() {
        assertTrue(DuplicateFinder.isGameFile(file("Aladdin (USA).md", scope = "sega_genesis")))
        assertFalse(DuplicateFinder.isGameFile(file("README.md", scope = "sega_genesis")))
        assertFalse(DuplicateFinder.isGameFile(file("notes.md", folder = ".../Download", scope = "")))
    }

    @Test
    fun aSingleFileInARegionFolderNeverRemovesTheFolder() {
        val japan = at(1, ".../ROMs/psx/Japan", "Crash Bandicoot (Japan).chd", "sony_psx", 400)
        val usa = file("Crash Bandicoot (USA).chd", folder = ".../ROMs/psx", scope = "sony_psx", size = 410)
        val group = DuplicateFinder.find(listOf(japan, usa)).single()
        assertTrue(group.entries.none { it.isFolderGame })
    }

    @Test
    fun aPlainFolderNameDoesNotMakeATitle() {
        val files = listOf("Crash", "Spyro").map { at(1, ".../ROMs/psx/Favorites", "disc.cue", "sony_psx", 100).copy(dirId = "/vol/$it", fileId = "/vol/$it/disc.cue") }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }

    @Test
    fun audioTracksAloneAreNotAGame() {
        val files = listOf("Game A", "Game B").map { at(1, ".../ROMs/ost/$it", "Theme.mp3", "x_console") }
        assertTrue(DuplicateFinder.find(files).isEmpty())
    }
}
