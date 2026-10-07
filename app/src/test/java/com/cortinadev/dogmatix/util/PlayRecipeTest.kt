package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRecipeTest {
    private val doc = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fsnes%2FGame.sfc"
    private val file = FileRef("Game.sfc", doc, "/storage/emulated/0/ROMs/snes/Game.sfc")
    private val root = "/storage/emulated/0"

    private fun build(id: String, packageName: String, core: String? = null, f: FileRef = file): BuiltRecipe {
        val e = EmulatorCatalog.byId(id)!!
        return PlayRecipe.build(e, e.variants.first { it.packageName == packageName }, core, f, root)
    }

    private fun IntentSpec.text(key: String) = (extras.firstOrNull { it.key == key } as? SpecExtra.Text)?.value

    @Test fun `retroarch gets the rom path, the core and the config`() {
        val built = build("retroarch", "com.retroarch.aarch64", "snes9x")
        val spec = built.attempts.first()
        assertEquals("com.retroarch.aarch64", spec.packageName)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", spec.className)
        assertNull(spec.dataUri)
        assertEquals("/storage/emulated/0/ROMs/snes/Game.sfc", spec.text("ROM"))
        assertEquals("/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so", spec.text("LIBRETRO"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", spec.text("CONFIGFILE"))
        assertTrue(spec.grantUris.isEmpty())
        assertTrue(spec.clearTask)
    }

    @Test fun `retroarch without a path cannot launch, only the generic fallback is left`() {
        val built = build("retroarch", "com.retroarch", "snes9x", file.copy(path = null))
        assertTrue(built.templateNeedsPath)
        assertTrue(built.attempts.none { it.className != null })
        assertFalse(built.usesTemplate)
    }

    @Test fun `duckstation boots the document through its activity`() {
        val spec = build("duckstation", "com.github.stenzek.duckstation").attempts.first()
        assertEquals("com.github.stenzek.duckstation.EmulationActivity", spec.className)
        assertEquals(doc, spec.text("bootPath"))
        assertEquals(listOf(SpecExtra.Flag("resumeState", false)), spec.extras.filterIsInstance<SpecExtra.Flag>())
        assertTrue(spec.clearTask)
        assertEquals(listOf(doc), spec.grantUris)
    }

    @Test fun `nethersx2 variants keep their own activity class`() {
        assertEquals("xyz.aethersx2.android.EmulationActivity", build("nethersx2", "xyz.aethersx2.android").attempts.first().className)
        assertEquals("xyz.aethersx2.android.EmulationActivity", build("nethersx2", "xyz.aethersx2.tturnip").attempts.first().className)
        assertEquals("android.intent.action.MAIN", build("nethersx2", "xyz.aethersx2.android").attempts.first().action)
    }

    @Test fun `ppsspp views the document with the default category`() {
        val spec = build("ppsspp", "org.ppsspp.ppssppgold").attempts.first()
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", spec.className)
        assertEquals(PlayRecipe.ACTION_VIEW, spec.action)
        assertEquals(listOf("android.intent.category.DEFAULT"), spec.categories)
        assertEquals(doc, spec.dataUri)
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", build("ppsspp", "org.ppsspp.ppsspp").attempts.first().className)
    }

    @Test fun `watermelonds uses its documented uri data contract for standard and nightly`() {
        listOf("me.magnum.melondualds", "me.magnum.melondualds.nightly").forEach { pkg ->
            val spec = build("watermelonds", pkg).attempts.first()
            assertEquals("me.magnum.melonds.ui.emulator.EmulatorActivity", spec.className)
            assertEquals(PlayRecipe.ACTION_VIEW, spec.action)
            assertEquals(doc, spec.dataUri)
            assertEquals(listOf(doc), spec.grantUris)
            assertTrue(spec.extras.isEmpty())
        }
    }

    @Test fun `dolphin starts the tv activity with AutoStartFile, then the main one`() {
        val built = build("dolphin", "org.dolphinemu.dolphinemu")
        assertEquals(listOf("org.dolphinemu.dolphinemu.ui.main.TvMainActivity", "org.dolphinemu.dolphinemu.ui.main.MainActivity", null), built.attempts.map { it.className })
        assertEquals(doc, built.attempts.first().text("AutoStartFile"))
        assertEquals(listOf("android.intent.category.LEANBACK_LAUNCHER"), built.attempts.first().categories)
    }

    @Test fun `eden uses the amiibo action with the file as data, a provider uri when there is one`() {
        val plain = build("eden", "dev.eden.eden_emulator").attempts.first()
        assertEquals("android.nfc.action.TECH_DISCOVERED", plain.action)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", plain.className)
        assertEquals(doc, plain.dataUri)
        val provider = "content://com.cortinadev.dogmatix.provider/download_dir/ROMs/Game.nsp"
        val spec = build("eden", "dev.eden.eden_emulator", f = file.copy(providerUri = provider)).attempts.first()
        assertEquals(provider, spec.dataUri)
    }

    @Test fun `a path-only emulator is skipped without a path and used with one`() {
        assertTrue(build("noods", "com.hydra.noods", f = file.copy(path = null)).templateNeedsPath)
        val spec = build("noods", "com.hydra.noods").attempts.first()
        assertEquals("/storage/emulated/0/ROMs/snes/Game.sfc", spec.text("LaunchPath"))
        assertEquals("com.hydra.noods.FileBrowser", spec.className)
        assertTrue(spec.grantUris.isEmpty())
    }

    @Test fun `an app with two possible activities tries both, then the generic view`() {
        val built = build("lime3ds", "io.github.lime3ds.android")
        assertEquals(
            listOf("io.github.lime3ds.android.activities.EmulationActivity", "org.citra.citra_emu.activities.EmulationActivity", null),
            built.attempts.map { it.className }
        )
        assertEquals(PlayRecipe.ACTION_VIEW, built.attempts.last().action)
    }

    @Test fun `a best-effort emulator only gets the generic view with a mime type`() {
        val built = build("sudachi", "org.sudachi.sudachi_emu", f = file.copy(name = "Game.nsp"))
        assertEquals(1, built.attempts.size)
        val spec = built.attempts.single()
        assertEquals("org.sudachi.sudachi_emu", spec.packageName)
        assertNull(spec.className)
        assertEquals(doc, spec.dataUri)
        assertEquals("application/octet-stream", spec.mime)
        assertEquals(listOf(doc), spec.grantUris)
    }

    @Test fun `the generic view carries the archive mime type and needs a uri`() {
        val spec = PlayRecipe.generic("org.example.emu", file.copy(name = "Game.zip"))!!
        assertEquals("org.example.emu", spec.packageName)
        assertEquals("application/zip", spec.mime)
        assertEquals(doc, spec.dataUri)
        assertNull(PlayRecipe.generic("org.example.emu", FileRef("x", null, "/p")))
    }

    @Test fun `the other files of a game are granted with the file`() {
        val track = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fpsx%2FGame%20(Track%201).bin"
        val cue = file.copy(name = "Game.cue")
        val e = EmulatorCatalog.byId("duckstation")!!
        val built = PlayRecipe.build(e, e.variants.first(), null, cue, root, listOf(track))
        assertEquals(listOf(doc, track), built.attempts.first().grantUris)
        assertEquals(listOf(doc, track), built.attempts.last().grantUris)
        // A path template reads the tracks by path: nothing to grant.
        val ra = EmulatorCatalog.byId("retroarch")!!
        assertTrue(PlayRecipe.build(ra, ra.variants.first(), "pcsx_rearmed", cue, root, listOf(track)).attempts.first().grantUris.isEmpty())
    }

    @Test fun `playlists and sheets rank before images`() {
        val names = listOf("Game.iso", "Game (Disc 1).cue", "Game.m3u", "Game.sfc", "Game.chd")
        assertEquals(listOf("Game.m3u", "Game (Disc 1).cue", "Game.chd", "Game.iso", "Game.sfc"), names.sortedBy { PlayRecipe.entryRank(it) })
    }

    @Test fun `cue and gdi start without offering their audio and data tracks as separate games`() {
        assertEquals(listOf("Game.cue"), PlayRecipe.entryFiles(listOf("Game.cue", "Game.bin", "Track 2.wav", "Game.sub", "Game.sbi")))
        assertEquals(listOf("Game.gdi"), PlayRecipe.entryFiles(listOf("Game.gdi", "track01.bin", "track02.raw", "track03.bin")))
        assertEquals(listOf("Game.m3u", "Disc 1.cue"), PlayRecipe.entryFiles(listOf("Disc 1.cue", "Track.wav", "Game.m3u", "Disc 1.bin")))
    }

    @Test fun `standalone binary and megadrive roms remain playable and extracted files precede an archive`() {
        assertEquals(listOf("Game.bin"), PlayRecipe.entryFiles(listOf("Game.zip", "Game.bin")))
        assertEquals(listOf("Game.img"), PlayRecipe.entryFiles(listOf("Game.img")))
        assertEquals(listOf("Game.md"), PlayRecipe.entryFiles(listOf("Game.md")))
        assertEquals(listOf("Game.zip"), PlayRecipe.entryFiles(listOf("Game.zip")))
        assertTrue(PlayRecipe.entryFiles(listOf("Track.wav", "Game.sub", "private.key")).isEmpty())
    }

    @Test fun `relative activity names resolve against the package`() {
        assertEquals("com.dsemu.drastic.DraSticActivity", PlayRecipe.className("com.dsemu.drastic", ".DraSticActivity"))
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", PlayRecipe.className("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp.PpssppActivity"))
    }

    @Test fun `an archive needs extracting for discs and for emulators that do not read it`() {
        val ra = EmulatorCatalog.byId("retroarch")!!
        val duck = EmulatorCatalog.byId("duckstation")!!
        assertFalse(PlayRecipe.needsExtract(ra, PlaySystem.SNES, "Game.zip"))
        assertTrue(PlayRecipe.needsExtract(ra, PlaySystem.SNES, "Game.7z"))
        assertTrue(PlayRecipe.needsExtract(ra, PlaySystem.PS1, "Game.zip"))
        assertTrue(PlayRecipe.needsExtract(duck, PlaySystem.PS1, "Game.ZIP"))
        assertFalse(PlayRecipe.needsExtract(duck, PlaySystem.PS1, "Game.chd"))
        assertFalse(PlayRecipe.needsExtract(duck, PlaySystem.PS1, "Game.cue"))
        val pce = EmulatorCatalog.byId("pce_emu")!!
        assertTrue(PlayRecipe.needsExtract(pce, PlaySystem.PC_ENGINE_CD, "Game.zip"))
        assertFalse(PlayRecipe.needsExtract(pce, PlaySystem.PC_ENGINE, "Game.zip"))
    }
}
