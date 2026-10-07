package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorCatalogTest {

    @Test fun `emulator ids are unique and a package belongs to one emulator`() {
        assertEquals(EmulatorCatalog.all.size, EmulatorCatalog.all.map { it.id }.toSet().size)
        val packages = EmulatorCatalog.all.flatMap { e -> e.variants.map { it.packageName } }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test fun `packages are plain package names`() {
        EmulatorCatalog.allPackages().forEach { assertTrue(it, Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it)) }
    }

    @Test fun `every emulator has a variant, a system and a template unless it is best effort`() {
        EmulatorCatalog.all.forEach { e ->
            assertTrue(e.id, e.variants.isNotEmpty() && e.systems.isNotEmpty())
            if (e.template == null) assertEquals(e.id, Confidence.BEST_EFFORT, e.confidence)
            else assertTrue(e.id, e.variants.all { it.activities.isNotEmpty() })
        }
    }

    @Test fun `every system has at least one emulator`() {
        PlaySystem.entries.forEach { assertTrue(it.name, EmulatorCatalog.emulatorsFor(it).isNotEmpty()) }
    }

    @Test fun `only retroarch needs a core and it runs exactly the systems that have cores`() {
        val ra = EmulatorCatalog.byId("retroarch")!!
        assertTrue(ra.libretro)
        assertEquals(PlaySystem.entries.filter { it.cores.isNotEmpty() }.toSet(), ra.systems)
        assertEquals(listOf("retroarch"), EmulatorCatalog.all.filter { it.libretro }.map { it.id })
    }

    @Test fun `console ids map to their system`() {
        val expected = mapOf(
            "nintendo_gameboy_advance" to PlaySystem.GBA, "nintendo_gameboy" to PlaySystem.GB, "nintendo_gameboy_color" to PlaySystem.GBC,
            "super_nintendo_entertainment_system" to PlaySystem.SNES, "nintendo_entertainment_system" to PlaySystem.NES,
            "nintendo_64" to PlaySystem.N64, "nintendo_ds" to PlaySystem.NDS, "nintendo_3ds" to PlaySystem.N3DS,
            "nintendo_gamecube" to PlaySystem.GAMECUBE, "gamecube" to PlaySystem.GAMECUBE, "nintendo_wii" to PlaySystem.WII,
            "nintendo_wii_u" to PlaySystem.WIIU, "nintendo_switch" to PlaySystem.SWITCH,
            "sony_playstation" to PlaySystem.PS1, "sony_playstation_2" to PlaySystem.PS2, "sony_playstation_3" to PlaySystem.PS3,
            "sony_psp" to PlaySystem.PSP, "sony_playstation_portable" to PlaySystem.PSP, "psx" to PlaySystem.PS1,
            "sega_genesis" to PlaySystem.GENESIS, "genesis" to PlaySystem.GENESIS, "sega_master_system" to PlaySystem.MASTER_SYSTEM,
            "sega_game_gear" to PlaySystem.GAME_GEAR, "sega_cd" to PlaySystem.SEGA_CD, "sega_saturn" to PlaySystem.SATURN,
            "sega_dreamcast" to PlaySystem.DREAMCAST, "nec_pc_engine_cd" to PlaySystem.PC_ENGINE_CD,
            "snk_neo_geo" to PlaySystem.NEO_GEO, "snk_neo_geo_cd" to PlaySystem.NEO_GEO_CD, "snk_neo_geo_pocket" to PlaySystem.NEO_GEO_POCKET,
            "atari_2600" to PlaySystem.ATARI_2600, "atari_lynx" to PlaySystem.ATARI_LYNX, "arcade" to PlaySystem.ARCADE
        )
        expected.forEach { (id, system) -> assertEquals(id, system, EmulatorCatalog.systemOf(id)) }
    }

    @Test fun `an unknown console has no system`() {
        assertNull(EmulatorCatalog.systemOf("proper_romsets"))
        assertNull(EmulatorCatalog.systemOf(""))
    }

    @Test fun `newer consoles of a family do not fall back to an older one`() {
        listOf(
            "sony_playstation_vita", "playstation_vita", "psvita", "ps_vita", "vita",
            "sony_playstation_4", "playstation4", "ps4", "sony_playstation_5", "ps5", "nintendo_switch_2"
        ).forEach { assertNull(it, EmulatorCatalog.systemOf(it)) }
        assertEquals(PlaySystem.PS1, EmulatorCatalog.systemOf("Sony - PlayStation"))
        assertEquals(PlaySystem.PS2, EmulatorCatalog.systemOf("playstation2"))
        assertEquals(PlaySystem.SWITCH, EmulatorCatalog.systemOf("switch"))
    }

    @Test fun `retroarch starts in a fresh task so a second game does not resume the first`() {
        assertTrue(EmulatorCatalog.byId("retroarch")!!.template!!.clearTask)
    }

    @Test fun `standalone emulators come before retroarch`() {
        val ids = EmulatorCatalog.emulatorsFor(PlaySystem.PS1).map { it.id }
        assertEquals("retroarch", ids.last())
        assertTrue(ids.indexOf("duckstation") < ids.indexOf("retroarch"))
    }

    @Test fun `targets list installed emulators and retroarch once per core`() {
        val installed = setOf("com.retroarch", "com.github.stenzek.duckstation")
        val targets = EmulatorCatalog.targetsFor(PlaySystem.PS1, installed)
        assertEquals("duckstation", targets.first().emulatorId)
        val ra = targets.filter { it.emulatorId == "retroarch" }
        assertEquals(PlaySystem.PS1.cores, ra.map { it.core })
        assertEquals("RetroArch (Standard) (PCSX ReARMed)", ra.first().label)
        assertEquals("catalog:retroarch@com.retroarch|pcsx_rearmed", ra.first().key)
        assertTrue(EmulatorCatalog.targetsFor(PlaySystem.PS1, emptySet()).isEmpty())
    }

    @Test fun `the first installed variant wins in catalogue order`() {
        val ra = EmulatorCatalog.byId("retroarch")!!
        assertEquals("com.retroarch.aarch64", EmulatorCatalog.installedVariant(ra, setOf("com.retroarch", "com.retroarch.aarch64"))?.packageName)
        assertEquals("com.retroarch", EmulatorCatalog.installedVariant(ra, setOf("com.retroarch"))?.packageName)
        assertNull(EmulatorCatalog.installedVariant(ra, setOf("org.ppsspp.ppsspp")))
    }

    @Test fun `every installed flavour is selectable with a distinct stable key`() {
        val targets = EmulatorCatalog.targetsFor(PlaySystem.PSP, setOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp", "com.retroarch", "com.retroarch.ra32"))
        val ppsspp = targets.filter { it.emulatorId == "ppsspp" }
        assertEquals(listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp"), ppsspp.map { it.packageName })
        assertEquals(listOf("PPSSPP (Gold)", "PPSSPP (Standard)"), ppsspp.map { it.label })
        assertEquals(targets.size, targets.map { it.key }.toSet().size)
        assertEquals(setOf("com.retroarch", "com.retroarch.ra32"), targets.filter { it.emulatorId == "retroarch" }.map { it.packageName }.toSet())
    }

    @Test fun `pc engine cd ids stay distinct from cartridge systems`() {
        listOf("pc_engine_cd", "pcenginecd", "NEC PC Engine CD", "turbografx_cd", "tg-cd", "pcecd", "nec_turbografx_16_cd").forEach {
            assertEquals(it, PlaySystem.PC_ENGINE_CD, EmulatorCatalog.systemOf(it))
        }
        assertEquals(PlaySystem.PC_ENGINE, EmulatorCatalog.systemOf("nec_pc_engine"))
        assertTrue(PlaySystem.PC_ENGINE_CD.disc)
    }

    @Test fun `core labels are readable`() {
        assertEquals("Snes9x", EmulatorCatalog.coreLabel("snes9x"))
        assertEquals("Mupen64Plus-Next", EmulatorCatalog.coreLabel("mupen64plus_next_gles3"))
        assertEquals("Some Core", EmulatorCatalog.coreLabel("some_core"))
    }

    @Test fun `queries snippet lists every package`() {
        val xml = EmulatorCatalog.queriesXml()
        assertTrue(xml.startsWith("<queries>") && xml.endsWith("</queries>"))
        EmulatorCatalog.allPackages().forEach { assertTrue(it, xml.contains("<package android:name=\"$it\" />")) }
        assertFalse(EmulatorCatalog.allPackages().contains("org.es_de.frontend"))
    }
}
