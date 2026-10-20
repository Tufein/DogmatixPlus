package com.tufein.valkyr.util

import com.tufein.valkyr.util.PackageCopyPlan.Decision
import com.tufein.valkyr.util.PackageCopyPlan.Existing
import com.tufein.valkyr.util.PackageCopyPlan.FileState
import com.tufein.valkyr.util.PackageCopyPlan.Part
import com.tufein.valkyr.util.PackageCopyPlan.Source
import com.tufein.valkyr.util.TargetFolderLayouts.Layout
import com.tufein.valkyr.util.TargetFolderLayouts.System
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TargetFolderLayoutsTest {

    private val others = Layout.entries.filter { it != Layout.LIBRARY }

    // ---- The table ---------------------------------------------------------------------------

    @Test fun `every folder name and roms folder is one safe path segment`() {
        others.forEach { layout ->
            System.entries.forEach { system ->
                TargetFolderLayouts.folderFor(layout, system)?.let { assertTrue("$layout $system $it", TargetFolderLayouts.isSafeSegment(it)) }
            }
            layout.romsFolder?.let { assertTrue("$layout $it", TargetFolderLayouts.isSafeSegment(it)) }
        }
        listOf("", ".", "..", "a/b", "a\\b", ".hidden", "what?", "trailing.", "x".repeat(65)).forEach {
            assertFalse(it, TargetFolderLayouts.isSafeSegment(it))
        }
    }

    @Test fun `two systems share a folder only where the other system documents it`() {
        others.forEach { layout ->
            val shared = TargetFolderLayouts.sharedFolders(layout)
            val allowed = TargetFolderLayouts.SHARED_FOLDERS[layout].orEmpty()
                .mapKeys { ArchivePlan.portableKey(it.key) }.mapValues { it.value }
            assertEquals("$layout", allowed.keys, shared.keys)
            shared.forEach { (folder, systems) -> assertEquals("$layout $folder", allowed.getValue(folder), systems.toSet()) }
        }
    }

    @Test fun `the library layout has no table and keeps the library folder`() {
        System.entries.forEach { assertNull(TargetFolderLayouts.folderFor(Layout.LIBRARY, it)) }
        assertEquals("Gameboy Advance", TargetFolderLayouts.destinationFolder(Layout.LIBRARY, "nintendo_gameboy_advance", "Gameboy Advance", "Roms"))
        assertEquals("", TargetFolderLayouts.destinationFolder(Layout.LIBRARY, "nintendo_gameboy_advance", "", null))
    }

    @Test fun `unknown consoles and other machines have no folder`() {
        listOf("", "sony_handheld", "nintendo_handheld", "pc_windows", "sony_playstation_4", "sony_playstation_5", "nintendo_switch_2",
            "microsoft_xbox_one", "nintendo_64dd", "commodore_amiga_cd32", "proper_romsets", "Unknown").forEach { id ->
            assertNull(id, TargetFolderLayouts.systemOf(id))
            others.forEach { assertNull("$it $id", TargetFolderLayouts.folderFor(it, id)) }
        }
    }

    @Test fun `console ids of this app map to their system`() {
        val expected = mapOf(
            "nintendo_nintendo_entertainment_system" to System.NES, "nintendo_nes" to System.NES, "nintendo_famicom_disk_system" to System.FDS,
            "nintendo_super_nintendo_entertainment_system" to System.SNES, "super_nintendo_entertainment_system" to System.SNES, "nintendo_snes" to System.SNES,
            "nintendo_nintendo_64" to System.N64, "nintendo_64" to System.N64, "nintendo_gameboy" to System.GB, "nintendo_game_boy" to System.GB,
            "nintendo_gb" to System.GB, "nintendo_gameboy_color" to System.GBC, "nintendo_game_boy_color" to System.GBC,
            "nintendo_gameboy_advance" to System.GBA, "nintendo_gba" to System.GBA, "nintendo_ds" to System.NDS, "nintendo_dsi" to System.NDS,
            "nintendo_nintendo_3ds" to System.N3DS, "nintendo_3ds" to System.N3DS, "nintendo_gamecube" to System.GAMECUBE,
            "nintendo_wii" to System.WII, "nintendo_wii_u" to System.WII_U, "nintendo_wiiu" to System.WII_U, "nintendo_switch" to System.SWITCH,
            "nintendo_virtual_boy" to System.VIRTUAL_BOY, "nintendo_pokemon_mini" to System.POKEMON_MINI,
            "sony_playstation" to System.PS1, "sony_psx" to System.PS1, "sony_ps1" to System.PS1, "sony_playstation_2" to System.PS2,
            "sony_ps2" to System.PS2, "sony_playstation_3" to System.PS3, "sony_ps3" to System.PS3, "sony_psp" to System.PSP,
            "sony_playstation_portable" to System.PSP, "sony_playstation_vita" to System.PS_VITA,
            "sega_master_system" to System.MASTER_SYSTEM, "sega_genesis" to System.GENESIS, "sega_md" to System.GENESIS,
            "sega_mega_drive" to System.GENESIS, "sega_game_gear" to System.GAME_GEAR, "sega_cd" to System.SEGA_CD, "sega_mega_cd" to System.SEGA_CD,
            "sega_32x" to System.SEGA_32X, "sega_saturn" to System.SATURN, "sega_dreamcast" to System.DREAMCAST,
            "nec_pc_engine" to System.PC_ENGINE, "pc_engine" to System.PC_ENGINE, "nec_turbografx_16" to System.PC_ENGINE,
            "pc_engine_cd" to System.PC_ENGINE_CD, "nec_pc_engine_cd" to System.PC_ENGINE_CD, "nec_turbografx_16_cd" to System.PC_ENGINE_CD,
            "nec_supergrafx" to System.SUPERGRAFX, "pc_fx" to System.PC_FX, "snk_neo_geo" to System.NEO_GEO, "snk_neo_geo_cd" to System.NEO_GEO_CD,
            "snk_neo_geo_pocket" to System.NEO_GEO_POCKET, "snk_neo_geo_pocket_color" to System.NEO_GEO_POCKET_COLOR,
            "bandai_wonderswan_color" to System.WONDERSWAN_COLOR, "atari_2600" to System.ATARI_2600, "atari_7800" to System.ATARI_7800,
            "atari_lynx" to System.ATARI_LYNX, "atari_jaguar" to System.ATARI_JAGUAR, "atari_jaguar_cd" to System.ATARI_JAGUAR_CD,
            "atari_st" to System.ATARI_ST, "coleco_colecovision" to System.COLECOVISION, "gce_vectrex" to System.VECTREX,
            "mattel_intellivision" to System.INTELLIVISION, "commodore_c64" to System.C64, "commodore_64" to System.C64,
            "commodore_amiga" to System.AMIGA, "pc_dos" to System.DOS, "pc_scummvm" to System.SCUMMVM, "panasonic_3do" to System.PANASONIC_3DO,
            "microsoft_xbox" to System.XBOX, "microsoft_xbox_360" to System.XBOX_360, "arcade_mame" to System.ARCADE, "arcade_fbneo" to System.FBNEO,
            "homebrew_chip_8" to System.CHIP_8, "homebrew_tic_80" to System.TIC_80, "homebrew_wasm_4" to System.WASM_4,
            "homebrew_vircon32" to System.VIRCON32, "homebrew_uzebox" to System.UZEBOX,
            // Folder-like ids, as imported sources and the alias table use them.
            "gba" to System.GBA, "psx" to System.PS1, "sfc" to System.SNES, "fc" to System.NES, "md" to System.GENESIS, "megadrive" to System.GENESIS,
            "Nintendo - Game Boy Advance" to System.GBA, "msx2" to System.MSX2, "msx" to System.MSX
        )
        expected.forEach { (id, system) -> assertEquals(id, system, TargetFolderLayouts.systemOf(id)) }
    }

    @Test fun `every console of the romset catalogue that a system knows is matched`() {
        val document = listOf(File("src/main/assets/romsets.json"), File("app/src/main/assets/romsets.json")).first { it.isFile }
        val ids = JsonParser.parseString(document.readText()).asJsonObject.getAsJsonArray("entries").map { it.asJsonObject.get("id").asString }
        // ScummVM ids are matched too; only these have no system in any layout.
        val unknown = ids.filter { TargetFolderLayouts.systemOf(it) == null }
        assertEquals(emptyList<String>(), unknown)
    }

    @Test fun `each system gets its documented name`() {
        fun f(layout: Layout, id: String) = TargetFolderLayouts.folderFor(layout, id)
        assertEquals("gc", f(Layout.ES_DE, "nintendo_gamecube"))
        assertEquals("gamecube", f(Layout.BATOCERA, "nintendo_gamecube"))
        assertEquals("gamecube", f(Layout.ROCKNIX, "nintendo_gamecube"))
        assertEquals("gc", f(Layout.ARKOS, "nintendo_gamecube"))
        assertNull(f(Layout.ONION, "nintendo_gamecube"))
        assertEquals("megacd", f(Layout.BATOCERA, "sega_cd"))
        assertEquals("segacd", f(Layout.KNULLI, "sega_cd"))
        assertEquals("wswanc", f(Layout.BATOCERA, "bandai_wonderswan_color"))
        assertEquals("wonderswancolor", f(Layout.ES_DE, "bandai_wonderswan_color"))
        assertEquals("WS", f(Layout.ONION, "bandai_wonderswan_color"))
        assertEquals("o2em", f(Layout.KNULLI, "magnavox_odyssey_2"))
        assertEquals("3ds", f(Layout.BATOCERA, "nintendo_3ds"))
        assertEquals("n3ds", f(Layout.ES_DE, "nintendo_3ds"))
        assertEquals("pokemonmini", f(Layout.ARKOS, "nintendo_pokemon_mini"))
        assertEquals("coleco", f(Layout.ROCKNIX, "coleco_colecovision"))
        assertEquals("sgfx", f(Layout.ROCKNIX, "nec_supergrafx"))
        assertEquals("pc", f(Layout.ROCKNIX, "pc_dos"))
        assertEquals("FC", f(Layout.ONION, "nintendo_nes"))
        assertEquals("SFC", f(Layout.ONION, "nintendo_snes"))
        assertEquals("PS", f(Layout.ONION, "sony_psx"))
        assertEquals("MD", f(Layout.ONION, "sega_genesis"))
        assertEquals("SEGASGONE", f(Layout.ONION, "sega_sg_1000"))
        assertEquals("sg-1000", f(Layout.ES_DE, "sega_sg_1000"))
        assertEquals("sg1000", f(Layout.BATOCERA, "sega_sg_1000"))
        assertEquals("psx", f(Layout.MUOS, "sony_playstation"))
        assertNull(f(Layout.MUOS, "sony_playstation_2"))
        assertNull(f(Layout.BATOCERA, "commodore_amiga"))
        assertNull(f(Layout.BATOCERA, "nintendo_switch"))
    }

    @Test fun `onion names are capitals and muos names survive its own clean-up`() {
        System.entries.forEach { system ->
            TargetFolderLayouts.folderFor(Layout.ONION, system)?.let { assertEquals(it, it.uppercase(), it) }
            // muOS lower-cases a folder and drops spaces, - _ +; its names are already in that form.
            TargetFolderLayouts.folderFor(Layout.MUOS, system)?.let { assertTrue(it, Regex("[a-z0-9]+").matches(it)) }
        }
    }

    @Test fun `console folders go inside the roms folder unless that folder was picked`() {
        assertEquals("Roms/GBA", TargetFolderLayouts.destinationFolder(Layout.ONION, "nintendo_gba", "gba", null))
        assertEquals("GBA", TargetFolderLayouts.destinationFolder(Layout.ONION, "nintendo_gba", "gba", "Roms"))
        assertEquals("GBA", TargetFolderLayouts.destinationFolder(Layout.ONION, "nintendo_gba", "gba", "roms"))
        assertEquals("Roms/GBA", TargetFolderLayouts.destinationFolder(Layout.ONION, "nintendo_gba", "gba", "Miyoo"))
        assertEquals("ROMS/gba", TargetFolderLayouts.destinationFolder(Layout.MUOS, "nintendo_gba", "gba", null))
        assertEquals("roms/gba", TargetFolderLayouts.destinationFolder(Layout.BATOCERA, "nintendo_gba", "Gameboy Advance", null))
        assertEquals("gba", TargetFolderLayouts.destinationFolder(Layout.ES_DE, "nintendo_gba", "Gameboy Advance", null))
        assertEquals("gba", TargetFolderLayouts.destinationFolder(Layout.ARKOS, "nintendo_gba", "Gameboy Advance", null))
        assertNull(TargetFolderLayouts.destinationFolder(Layout.ONION, "nintendo_64", "n64", null))
    }

    @Test fun `stored keys round trip and an unknown key is the library`() {
        Layout.entries.forEach { assertEquals(it, Layout.of(it.key)) }
        assertEquals(Layout.LIBRARY, Layout.of(null))
        assertEquals(Layout.LIBRARY, Layout.of("amiga-os"))
        assertEquals(Layout.entries.size, Layout.entries.map { it.key }.toSet().size)
        assertEquals("s", SettingSchema.expectedType(TargetFolderLayouts.FOLDER_SETTING_KEY))
    }

    // ---- Planning with another system's names ------------------------------------------------

    private fun part(path: String, bytes: Long = 100) = Part(path, bytes, path.hashCode().toString().padStart(64, '0').takeLast(64), "content://src/$path")

    private val mario = Source("a", "Mario.zip", "nintendo_gba", "Gameboy Advance", listOf(part("Mario.gba", 4096)))
    private val zelda = Source("b", "Zelda.zip", "nintendo_gba", "Gameboy Advance", listOf(part("Zelda.gba", 8192), part("Zelda.srm", 64)))
    private val disc = Source("c", "Game (USA).7z", "sony_playstation", "psx", listOf(
        part("Game (USA)/Game (USA).cue", 120), part("Game (USA)/Game (USA).bin", 5000)))
    private val mario64 = Source("d", "Mario 64.zip", "nintendo_64", "n64", listOf(part("Mario 64.z64", 9000)))
    private val deep = Source("e", "Saga.7z", "sony_playstation", "psx", listOf(part("Saga/Disc 1/Saga.cue", 10), part("Saga/Disc 1/Saga.bin", 900)))

    private fun dest(vararg entries: Pair<String, Existing>): (String) -> Existing? {
        val map = entries.associate { ArchivePlan.portableKey(it.first) to it.second }
        return { map[ArchivePlan.portableKey(it)] }
    }

    @Test fun `the library layout leaves every game where it is`() {
        val placement = TargetFolderLayouts.place(Layout.LIBRARY, listOf(mario, mario64), "Roms")
        assertEquals(listOf(mario, mario64), placement.sources)
        assertTrue(placement.unsupported.isEmpty() && placement.tooDeep.isEmpty() && placement.caseDifferences.isEmpty())
    }

    @Test fun `onion copy plans every file under Roms with the layout folder and skips what it has no folder for`() {
        val placement = TargetFolderLayouts.place(Layout.ONION, listOf(mario, zelda, disc, mario64), null)
        assertEquals(listOf("d"), placement.unsupported.map { it.key })
        val plan = PackageCopyPlan.plan(placement.sources, dest(), freeBytes = 1L shl 30)
        assertEquals(listOf("Roms/GBA/Mario.gba", "Roms/GBA/Zelda.gba", "Roms/PS/Game (USA)/Game (USA).cue", "Roms/PS/Game (USA)/Game (USA).bin"),
            plan.files.map { it.target })
        assertEquals(listOf("Roms/GBA/Zelda.srm"), plan.saves)
        // The game the layout cannot place counts nowhere: not in the files, not in the space.
        assertEquals(4096L + 8192 + 120 + 5000, plan.bytesToCopy)
        assertTrue(plan.games.none { it.key == "d" })
    }

    @Test fun `what is already there is looked up at the new place`() {
        val placement = TargetFolderLayouts.place(Layout.MUOS, listOf(mario, zelda), "ROMS")
        val plan = PackageCopyPlan.plan(placement.sources, dest(
            "gba" to Existing(true, 0), "gba/Mario.gba" to Existing(false, 4096), "gba/Zelda.gba" to Existing(false, 1)))
        assertEquals(FileState.PRESENT, plan.files.single { it.target == "gba/Mario.gba" }.state)
        val zeldaGame = plan.games.single { it.key == "b" }
        assertEquals(Decision.SKIPPED, zeldaGame.decision)
        assertEquals(listOf("gba/Zelda.gba"), zeldaGame.conflicts)
        // The same files in the library's own folder do not count.
        val elsewhere = PackageCopyPlan.plan(placement.sources, dest("Gameboy Advance/Mario.gba" to Existing(false, 4096)))
        assertTrue(elsewhere.files.all { it.state == FileState.NEW })
    }

    @Test fun `a clash is renamed inside the layout folder`() {
        val placement = TargetFolderLayouts.place(Layout.ES_DE, listOf(disc), null)
        val plan = PackageCopyPlan.plan(placement.sources, dest("psx/Game (USA)/Game (USA).cue" to Existing(false, 1)), CopyConflictChoice.RENAME)
        val game = plan.games.single()
        assertEquals(Decision.RENAMED, game.decision)
        assertEquals("psx/Game (USA) (2)", game.root)
        assertEquals(listOf("psx/Game (USA) (2)/Game (USA).cue", "psx/Game (USA) (2)/Game (USA).bin"), plan.files.map { it.target })
    }

    @Test fun `an existing folder in other letter case is used as it is and reported`() {
        val existing = mapOf("roms" to "ROMS", "roms/gba" to "ROMS/GBA")
        val placement = TargetFolderLayouts.place(Layout.KNULLI, listOf(mario, disc), null) { existing[ArchivePlan.portableKey(it)] }
        assertEquals(listOf("ROMS/GBA", "roms/psx"), placement.sources.map { it.folder })
        // Only the first folder that is spelled differently is named, not every console folder inside it.
        assertEquals(listOf(TargetFolderLayouts.CaseDifference("roms", "ROMS")), placement.caseDifferences)
        val plan = PackageCopyPlan.plan(placement.sources, dest())
        assertEquals("ROMS/GBA/Mario.gba", plan.files.first().target)
    }

    @Test fun `one differently spelled parent is reported once however many console folders are inside it`() {
        // What the folder on the card really does: every path below an existing "ROMS" is spelled with it.
        val real = { path: String -> path.split('/').let { parts -> if (parts.first() == "roms") "ROMS/" + parts.drop(1).joinToString("/") else path } }
        val placement = TargetFolderLayouts.place(Layout.KNULLI, listOf(mario, disc), null) { real(it) }
        assertEquals(listOf("ROMS/gba", "ROMS/psx"), placement.sources.map { it.folder })
        assertEquals(listOf(TargetFolderLayouts.CaseDifference("roms", "ROMS")), placement.caseDifferences)
        // Only the last folder differs: that one is named.
        val last = TargetFolderLayouts.place(Layout.KNULLI, listOf(mario), null) { path -> path.replace("gba", "GBA") }
        assertEquals(listOf(TargetFolderLayouts.CaseDifference("roms/gba", "roms/GBA")), last.caseDifferences)
    }

    @Test fun `variants with their own games are not filed under the system they are named after`() {
        listOf("sony_playstation_minis", "sony_playstation_mobile", "sega_cd_32x", "nintendo_snes_msu1", "nintendo_nes_hacks").forEach {
            assertNull(it, TargetFolderLayouts.systemOf(it))
        }
        assertNotNull(TargetFolderLayouts.systemOf("sony_playstation"))
        assertNotNull(TargetFolderLayouts.systemOf("sega_cd"))
        assertNotNull(TargetFolderLayouts.systemOf("nintendo_snes"))
        assertNotNull(TargetFolderLayouts.systemOf("nintendo_nes"))
    }

    @Test fun `a picked roms folder in other letter case is reported`() {
        val placement = TargetFolderLayouts.place(Layout.ONION, listOf(mario), "ROMS")
        assertEquals("GBA", placement.sources.single().folder)
        assertEquals(listOf(TargetFolderLayouts.CaseDifference("Roms", "ROMS")), placement.caseDifferences)
        assertTrue(TargetFolderLayouts.place(Layout.ONION, listOf(mario), "Roms").caseDifferences.isEmpty())
    }

    @Test fun `onion subfolders with an apostrophe are flagged and other systems are not`() {
        val quote = Source("q", "Pokemon's Saga.7z", "sony_playstation", "psx", listOf(part("Pokemon's Saga/Saga.cue", 10)))
        val plain = Source("p", "Saga.7z", "sony_playstation", "psx", listOf(part("Saga/Saga.cue", 10)))
        // An apostrophe in the file name itself is fine: only folder names are the problem.
        val fileOnly = Source("f", "Saga.7z", "sony_playstation", "psx", listOf(part("Saga/Pokemon's.cue", 10)))
        assertEquals(listOf("q"), TargetFolderLayouts.place(Layout.ONION, listOf(quote, plain, fileOnly), null).apostrophes.map { it.key })
        assertTrue(TargetFolderLayouts.place(Layout.ES_DE, listOf(quote), null).apostrophes.isEmpty())
        assertTrue(TargetFolderLayouts.place(Layout.LIBRARY, listOf(quote), null).apostrophes.isEmpty())
    }

    @Test fun `games deeper than onion looks are flagged but still copied`() {
        val placement = TargetFolderLayouts.place(Layout.ONION, listOf(disc, deep), null)
        assertEquals(listOf("e"), placement.tooDeep.map { it.key })
        assertEquals(listOf("c", "e"), placement.sources.map { it.key })
        assertTrue(TargetFolderLayouts.place(Layout.ES_DE, listOf(deep), null).tooDeep.isEmpty())
    }

    @Test fun `two consoles sharing a folder end in one folder and a name clash between them is a clash`() {
        val ws = Source("w", "Gunpey.zip", "bandai_wonderswan", "ws", listOf(part("Gunpey.ws", 10)))
        val wsc = Source("x", "Gunpey EX.zip", "bandai_wonderswan_color", "wsc", listOf(part("Gunpey.ws", 20)))
        val placement = TargetFolderLayouts.place(Layout.ONION, listOf(ws, wsc), "Roms")
        assertEquals(listOf("WS", "WS"), placement.sources.map { it.folder })
        val plan = PackageCopyPlan.plan(placement.sources, dest())
        assertEquals(Decision.COPY, plan.games.single { it.key == "w" }.decision)
        assertEquals(Decision.SKIPPED, plan.games.single { it.key == "x" }.decision)
        assertEquals(listOf("WS/Gunpey.ws"), plan.files.map { it.target })
    }
}
