package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffGuideTest {
    private val duck = EmulatorSaveFolder("DuckStation (standalone)", setOf("psx", "ps"), "content://duck", SaveFolderLayout.MEMCARDS)
    private val psp = EmulatorSaveFolder("PPSSPP (standalone)", setOf("psp"), "content://psp", SaveFolderLayout.SAVEDATA)
    private val drastic = EmulatorSaveFolder("DraStic (standalone)", setOf("nds"), "content://drastic")

    private fun support(console: String, emulator: String?, core: String? = null, app: String? = null, folders: List<EmulatorSaveFolder> = listOf(duck, psp, drastic),
        retroArch: Boolean = true, romm: Boolean = true, onRomm: Boolean = true) =
        HandoffGuide.support(console, emulator, core, app, folders, retroArch, romm, onRomm)

    @Test fun supportedCombinationsSayWhichEmulatorAndFolder() {
        assertEquals(HandoffSupport.Supported("duckstation", "DuckStation", folderLabel = duck.label, layout = SaveFolderLayout.MEMCARDS), support("sony_playstation", "duckstation"))
        assertEquals(HandoffSupport.Supported("ppsspp", "PPSSPP", folderLabel = psp.label, layout = SaveFolderLayout.SAVEDATA), support("sony_psp", "ppsspp"))
        assertEquals(HandoffSupport.Supported("drastic", "DraStic", folderLabel = drastic.label), support("nintendo_ds", "drastic"))
        val ra = support("nintendo_gameboy_advance", "retroarch", "mgba") as HandoffSupport.Supported
        assertEquals("mgba", ra.core)
        assertEquals("mGBA", ra.coreLabel)
        // Without a remembered core, RetroArch uses the system's first.
        assertEquals("snes9x", (support("super_nintendo_entertainment_system", "retroarch") as HandoffSupport.Supported).core)
    }

    @Test fun combinationsThatDoNotWorkGetTheirReason() {
        assertEquals(HandoffBlock.NO_ROMM, (support("sony_playstation", "duckstation", romm = false) as HandoffSupport.Unsupported).block)
        assertEquals(HandoffBlock.NOT_ON_ROMM, (support("sony_playstation", "duckstation", onRomm = false) as HandoffSupport.Unsupported).block)
        assertEquals(HandoffSupport.Unsupported(HandoffBlock.NO_EMULATOR), support("sony_playstation", null))
        assertEquals(HandoffSupport.Unsupported(HandoffBlock.UNKNOWN_APP, "Some App"), support("sony_playstation", null, app = "Some App"))
        // Dolphin keeps Wii saves in its NAND, out of the sync's reach.
        assertEquals(HandoffSupport.Unsupported(HandoffBlock.SAVES_NOT_SUPPORTED, "Dolphin"), support("nintendo_wii", "dolphin"))
        // The PPSSPP core keeps its saves in RetroArch's system folder, not its saves folder.
        val core = support("sony_psp", "retroarch", "ppsspp") as HandoffSupport.Unsupported
        assertEquals(HandoffBlock.SAVES_NOT_SUPPORTED, core.block)
        assertEquals("RetroArch (PPSSPP)", core.emulatorLabel)
        assertEquals(HandoffBlock.NO_RETROARCH_FOLDER, (support("nintendo_gameboy_advance", "retroarch", "mgba", retroArch = false) as HandoffSupport.Unsupported).block)
        val noFolder = support("sony_playstation", "duckstation", folders = listOf(drastic)) as HandoffSupport.Unsupported
        assertEquals(HandoffBlock.NO_EMULATOR_FOLDER, noFolder.block)
        assertEquals("DuckStation/memcards", noFolder.folderHint)
        // A folder of that emulator for another console does not count.
        assertEquals(HandoffBlock.NO_EMULATOR_FOLDER, (support("nintendo_ds", "drastic", folders = listOf(duck)) as HandoffSupport.Unsupported).block)
    }

    @Test fun emulatorsWithOnlySharedCardsAreNeverHandedOver() {
        val ps2 = EmulatorSaveFolder("AetherSX2 - NetherSX2 (standalone)", setOf("ps2"), "content://ps2", SaveFolderLayout.PS2_MEMCARDS, 7, "Ico")
        val gc = EmulatorSaveFolder("Dolphin (standalone)", setOf("ngc", "gc"), "content://gc", SaveFolderLayout.GC_MEMCARDS, 8, "Pikmin")
        // Without a folder, with one, and with its shared cards synced: the sync reaches the cards, a hand-off never takes them.
        for (folders in listOf(emptyList(), listOf(ps2, gc), listOf(ps2.copy(sharedCardsRomId = null), gc.copy(sharedCardsRomId = null)))) {
            assertEquals(
                HandoffSupport.Unsupported(HandoffBlock.SHARED_CARDS_ONLY, "NetherSX2 / AetherSX2", "AetherSX2 or NetherSX2 data folder/memcards"),
                support("sony_playstation_2", "nethersx2", folders = folders)
            )
            assertEquals(
                HandoffSupport.Unsupported(HandoffBlock.SHARED_CARDS_ONLY, "Dolphin", "Dolphin user folder/GC"),
                support("nintendo_gamecube", "dolphin", folders = folders)
            )
        }
        // Dolphin MMJR keeps its GameCube cards the same way.
        assertEquals(HandoffBlock.SHARED_CARDS_ONLY, (support("nintendo_gamecube", "dolphin_mmjr") as HandoffSupport.Unsupported).block)
        assertEquals(HandoffBlock.SAVES_NOT_SUPPORTED, (support("nintendo_wii", "dolphin_mmjr") as HandoffSupport.Unsupported).block)
        // RetroArch's PCSX2 and Dolphin cores keep their cards outside RetroArch's saves folder.
        assertEquals(HandoffBlock.SAVES_NOT_SUPPORTED, (support("sony_playstation_2", "retroarch", "pcsx2") as HandoffSupport.Unsupported).block)
        assertEquals(HandoffBlock.SAVES_NOT_SUPPORTED, (support("nintendo_gamecube", "retroarch", "dolphin") as HandoffSupport.Unsupported).block)
        // DuckStation keeps going per game, its shared cards stay behind (see SaveHandoff.isInGameSave).
        assertTrue(support("sony_playstation", "duckstation") is HandoffSupport.Supported)
        assertFalse(SaveHandoff.isInGameSave("Mcd001.ps2"))
        assertFalse(SaveHandoff.isInGameSave("MemoryCardA.USA.raw"))
        assertFalse(SaveHandoff.isInGameSave("shared_card_1.mcd"))
    }

    @Test fun theCodeIsTheSameForTheSameSavesInAnyOrder() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val code = HandoffGuide.code(listOf(a, b))!!
        assertEquals(code, HandoffGuide.code(listOf(b, a.uppercase())))
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$").matches(code))
        assertNotEquals(code, HandoffGuide.code(listOf(a)))
        assertNull(HandoffGuide.code(emptyList()))
        assertTrue(HandoffGuide.sameCode(code, code.lowercase().replace("-", " ")))
        assertFalse(HandoffGuide.sameCode(code, null))
        assertFalse(HandoffGuide.sameCode("", ""))
    }

    @Test fun linksRoundTripAndRefuseAnythingOdd() {
        val request = HandoffLink.Request("sony_playstation", "Crash Bandicoot (USA) & Friends+.chd", "K7PQ-2M4X")
        val link = HandoffLink.build(request)
        assertTrue(link.startsWith("dogmatix://handoff?"))
        assertEquals(request, HandoffLink.parse(link))
        assertEquals(HandoffLink.Request("psx", "Game.chd", null), HandoffLink.parse("dogmatix://handoff?console=psx&file=Game.chd"))
        assertNull(HandoffLink.parse("dogmatix://library?console=psx&file=Game.chd"))
        assertNull(HandoffLink.parse("https://handoff?console=psx&file=Game.chd"))
        assertNull(HandoffLink.parse("dogmatix://handoff?console=psx&file=..%2F..%2Fsecret"))
        assertNull(HandoffLink.parse("dogmatix://handoff?file=Game.chd"))
        assertNull(HandoffLink.parse(null))
        // Codes keep only what a code can hold.
        assertEquals("K7PQ-2M4X", HandoffLink.parse("dogmatix://handoff?console=psx&file=G.chd&code=k7pq-2m4x%3Cscript%3E")?.code?.take(9))
    }

    @Test fun theSourceIsNamedByItsSite() {
        assertEquals("example.org", HandoffGuide.sourceLabel("https://www.example.org/files/psx/"))
        assertNull(HandoffGuide.sourceLabel("magnet:?xt=urn:btih:abc"))
        assertNull(HandoffGuide.sourceLabel(""))
    }
}
