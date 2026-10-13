package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomCandidate
import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomMatch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmulatorSaveLayoutsTest {
    private val ps1 = setOf("psx")
    private fun card(name: String) = LocalSaveFile(SaveKind.SAVE, "DuckStation (standalone)/$name", 131072, 1, ps1, SaveFolderLayout.MEMCARDS)
    private fun pack(folder: String) = LocalSaveFile(SaveKind.SAVE, "PPSSPP (standalone)/$folder.savedata.zip", 100, 1, setOf("psp"), SaveFolderLayout.SAVEDATA)

    @Test fun memoryCardNamesAreRead() {
        assertEquals(MemoryCard.Shared(1), EmulatorSaveLayouts.memoryCard("shared_card_1.mcd"))
        assertEquals(MemoryCard.Shared(2), EmulatorSaveLayouts.memoryCard("Shared_Card_2.MCD"))
        assertEquals(MemoryCard.PerGame("Crash Bandicoot", null, 1), EmulatorSaveLayouts.memoryCard("Crash Bandicoot_1.mcd"))
        assertEquals(MemoryCard.PerGame("SLUS-00594", "SLUS-00594", 2), EmulatorSaveLayouts.memoryCard("SLUS-00594_2.mcd"))
        // A title that merely contains a serial is a title.
        assertEquals(MemoryCard.PerGame("Game SLUS-00594 Edition", null, 1), EmulatorSaveLayouts.memoryCard("Game SLUS-00594 Edition_1.mcd"))
        assertEquals(MemoryCard.PerGame("Final Fantasy VII", null, 1), EmulatorSaveLayouts.memoryCard("Final Fantasy VII.mcr"))
        assertNull(EmulatorSaveLayouts.memoryCard("Crash Bandicoot_1.png"))
        assertNull(EmulatorSaveLayouts.memoryCard(".dogmatix-write-1.part"))
        assertTrue(EmulatorSaveLayouts.isSharedCard("shared_card_1.mcd"))
        assertFalse(EmulatorSaveLayouts.isSharedCard("Crash_1.mcd"))
    }

    @Test fun serialsAndProductIdsAreFoundInNames() {
        assertEquals(listOf("SLUS-00594"), EmulatorSaveLayouts.ps1Serials("Metal Gear Solid (USA) [SLUS-00594].chd"))
        assertEquals(listOf("SCES-02105"), EmulatorSaveLayouts.ps1Serials("SCES_021.05"))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.ps1Serials("XSLUS-00594"))
        assertEquals(listOf("ULUS10041"), EmulatorSaveLayouts.productIds("GTA LCS (USA) (ULUS-10041).iso"))
        assertEquals(listOf("NPUH10091"), EmulatorSaveLayouts.productIds("Game [NPUH10091].cso"))
        assertEquals("ULUS10041", EmulatorSaveLayouts.folderProductId("ULUS10041DATA00"))
        assertEquals("ULJM05500", EmulatorSaveLayouts.folderProductId("ULJM05500"))
        assertNull(EmulatorSaveLayouts.folderProductId("SYSTEM"))
        assertEquals("ULUS10041DATA00", EmulatorSaveLayouts.folderOfPack("ULUS10041DATA00.savedata.zip"))
        assertNull(EmulatorSaveLayouts.folderOfPack("ULUS10041DATA00.zip"))
        assertNull(EmulatorSaveLayouts.folderOfPack(".savedata.zip"))
    }

    @Test fun perGameCardsMatchByFileTitleGameTitleOrSerial() {
        val roms = listOf(
            RomCandidate(1, "Crash Bandicoot (USA).chd", "psx", "psx", "Crash Bandicoot"),
            RomCandidate(2, "Metal Gear Solid (USA) (Disc 1) [SLUS-00594].chd", "psx", "psx"),
            RomCandidate(3, "Spyro the Dragon (USA).chd", "psx", "psx")
        )
        // DuckStation's "file title" naming.
        assertEquals(RomMatch.Found(1), EmulatorSaveLayouts.matchRom(card("Crash Bandicoot (USA)_1.mcd"), roms))
        // Its "game title" naming, also when the file name lost a character DuckStation cannot write.
        assertEquals(RomMatch.Found(3), EmulatorSaveLayouts.matchRom(card("Spyro the Dragon_1.mcd"), roms))
        assertEquals(RomMatch.Found(1), EmulatorSaveLayouts.matchRom(card("Crash_ Bandicoot_1.mcd"), roms))
        // Serial naming.
        assertEquals(RomMatch.Found(2), EmulatorSaveLayouts.matchRom(card("SLUS-00594_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("SLUS-99999_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("Tekken 3_1.mcd"), roms))
        // A shared card is never matched by name.
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("shared_card_1.mcd"), roms))
    }

    @Test fun cardsOfAMultiDiscGameGoWithTheFirstDiscButRegionsStayAmbiguous() {
        val discs = listOf(
            RomCandidate(12, "Final Fantasy VII (USA) (Disc 2).chd", "psx"),
            RomCandidate(11, "Final Fantasy VII (USA) (Disc 1).chd", "psx"),
            RomCandidate(13, "Final Fantasy VII (USA) (Disc 3).chd", "psx")
        )
        assertEquals(RomMatch.Found(11), EmulatorSaveLayouts.matchRom(card("Final Fantasy VII_1.mcd"), discs))
        val regions = discs + RomCandidate(21, "Final Fantasy VII (Europe) (Disc 1).chd", "psx")
        assertEquals(RomMatch.Ambiguous, EmulatorSaveLayouts.matchRom(card("Final Fantasy VII_1.mcd"), regions))
        val twoRegions = listOf(RomCandidate(1, "Tekken 3 (USA).chd", "psx"), RomCandidate(2, "Tekken 3 (Europe).chd", "psx"))
        assertEquals(RomMatch.Ambiguous, EmulatorSaveLayouts.matchRom(card("Tekken 3_1.mcd"), twoRegions))
    }

    @Test fun gamesOfAnotherPlatformAreNotTaken() {
        val roms = listOf(RomCandidate(1, "Tetris (World).gb", "gb", "gb"), RomCandidate(2, "Tetris (World).chd", "psx", "psx"))
        assertEquals(RomMatch.Found(2), EmulatorSaveLayouts.matchRom(card("Tetris_1.mcd"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(card("Tetris_1.mcd"), roms.take(1)))
    }

    @Test fun ppssppFoldersMatchOnlyByProductId() {
        val roms = listOf(
            RomCandidate(5, "Grand Theft Auto - Liberty City Stories (USA) (ULUS-10041).iso", "psp", "psp"),
            RomCandidate(6, "Patapon (USA).iso", "psp", "psp")
        )
        assertEquals(RomMatch.Found(5), EmulatorSaveLayouts.matchRom(pack("ULUS10041DATA00"), roms))
        // No product id in any name: unmatched, never a guess by title.
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(pack("UCUS98711DATA"), roms))
        assertEquals(RomMatch.NotFound, EmulatorSaveLayouts.matchRom(pack("SYSTEMDATA"), roms))
        assertEquals(listOf("ULUS10041", "ULUS-10041", "ULUS 10041"), EmulatorSaveLayouts.searchTerms(pack("ULUS10041DATA00")))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(pack("SYSTEMDATA")))
        assertEquals(listOf("SLUS-00594", "SLUS 00594", "SLUS00594"), EmulatorSaveLayouts.searchTerms(card("SLUS-00594_1.mcd")))
        assertEquals(SaveSyncPlanner.searchTerms("Crash Bandicoot.mcd"), EmulatorSaveLayouts.searchTerms(card("Crash Bandicoot_1.mcd")))
        assertEquals(emptyList<String>(), EmulatorSaveLayouts.searchTerms(card("shared_card_1.mcd")))
    }

    @Test fun savesBelongToTheGameOnThePageByName() {
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("Crash Bandicoot_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("Crash Bandicoot (USA)_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(card("SLUS-00594_1.mcd"), "Metal Gear Solid (USA) [SLUS-00594].chd"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(card("SLUS-00594_1.mcd"), "Metal Gear Solid (USA).chd"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(card("shared_card_1.mcd"), "Crash Bandicoot (USA).chd"))
        assertTrue(EmulatorSaveLayouts.belongsToGame(pack("ULUS10041DATA00"), "GTA (USA) (ULUS-10041).iso"))
        assertFalse(EmulatorSaveLayouts.belongsToGame(pack("ULUS10041DATA00"), "GTA (USA).iso"))
    }

    @Test fun theInnerSavesFolderIsFoundWhenTheParentWasPicked() {
        val tree = mapOf("" to setOf("PSP", "Android"), "PSP" to setOf("SAVEDATA", "PPSSPP_STATE"))
        assertEquals("PSP/SAVEDATA", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { tree[it] })
        assertEquals("SAVEDATA", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("SAVEDATA") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("ULUS10041DATA00") else null })
        assertEquals("memcards", EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { if (it == "") setOf("memcards", "savestates") else null })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { emptySet() })
        assertEquals("", EmulatorSaveLayouts.innerPath(SaveFolderLayout.FILES) { tree[it] })
        // The parent is picked before the emulator made its saves folder: refused, never the parent itself.
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.MEMCARDS) { if (it == "") setOf("savestates", "bios") else null })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("PSP") else if (it == "PSP") setOf("SYSTEM") else null })
        assertNull(EmulatorSaveLayouts.innerPath(SaveFolderLayout.SAVEDATA) { if (it == "") setOf("SYSTEM", "PPSSPP_STATE") else null })
    }

    @Test fun cardsAndSaveFoldersHoldSeveralSaves() {
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("PPSSPP (standalone)/ULUS10041DATA00.savedata.zip"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("DuckStation/Crash Bandicoot_1.mcd"))
        assertTrue(EmulatorSaveLayouts.holdsSeveralSaves("DuckStation/shared_card_1.mcd"))
        assertFalse(EmulatorSaveLayouts.holdsSeveralSaves("mGBA/Pokemon Emerald.sav"))
    }

    @Test fun packsAreTheSameBytesWhateverTheOrderAndUnpackBackToTheFiles() {
        val files = listOf("PARAM.SFO" to byteArrayOf(1, 2, 3), "DATA.BIN" to ByteArray(1000) { it.toByte() }, "sub/ICON0.PNG" to byteArrayOf(9))
        val a = SavePack.pack(files)
        val b = SavePack.pack(files.reversed())
        assertArrayEquals(a, b)
        assertEquals(a.size.toLong(), SavePack.packedSize(files.map { it.first to it.second.size.toLong() }))
        val back = SavePack.unpack(a, 1_000_000)
        assertEquals(files.map { it.first }.sorted(), back.map { it.first })
        back.forEach { (path, bytes) -> assertArrayEquals(files.first { it.first == path }.second, bytes) }
        // Packing what was unpacked gives the very same bytes again (what a download is verified with).
        assertArrayEquals(a, SavePack.pack(back))
    }

    @Test fun packsFromElsewhereAreReadButUnsafeOnesRefused() {
        val ordinary = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("DATA.BIN")); zip.write(ByteArray(50) { 7 }); zip.closeEntry() }
        }.toByteArray()
        assertEquals(listOf("DATA.BIN"), SavePack.unpack(ordinary, 1000).map { it.first })
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("../escape.bin")); zip.write(1); zip.closeEntry() }
        }.toByteArray()
        try { SavePack.unpack(evil, 1000); fail("traversal accepted") } catch (_: ArchiveSafetyException) { }
        try { SavePack.unpack(ordinary, 10); fail("size limit ignored") } catch (_: java.io.IOException) { }
    }
}
