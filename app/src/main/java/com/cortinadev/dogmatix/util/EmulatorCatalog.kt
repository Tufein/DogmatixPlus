package com.cortinadev.dogmatix.util

/**
 * How sure a launch template is. Dogmatix+ cannot be tested against real emulators in the build
 * environment, so every template says where it comes from:
 *  - [SURE]: copied field for field from ES-DE's Android configuration (`es_find_rules.xml` and
 *    `es_systems.xml`, the files the frontend launches these apps with every day): package, activity,
 *    action, category, extras. Still not run on a device by us.
 *  - [LIKELY]: the same contract as a [SURE] app of the same family (a fork), or documented
 *    behaviour, but not listed by ES-DE itself.
 *  - [BEST_EFFORT]: only the package name is known; Play sends a plain `ACTION_VIEW` with the file to
 *    that package and lets the app (or the system) take it.
 */
enum class Confidence { SURE, LIKELY, BEST_EFFORT }

/**
 * The systems the catalogue can launch, matched from a console id (`nintendo_gameboy_advance`,
 * `super_nintendo_entertainment_system`, a folder-like `psx`) by [EmulatorCatalog.systemOf].
 *
 * @property keys console-id words (lower case, `_` separated) that mean this system.
 * @property cores libretro cores that run it in RetroArch, preferred first (file name without
 *  `_libretro_android.so`); empty = RetroArch has nothing for it.
 * @property disc games come as disc images, which a zip hides from every emulator (see
 *  [PlayRecipe.needsExtract]).
 */
enum class PlaySystem(
    val label: String,
    val keys: List<String>,
    val cores: List<String> = emptyList(),
    val disc: Boolean = false
) {
    NES("NES", listOf("nintendo_entertainment_system", "nes", "famicom", "famicom_disk_system", "fds"), listOf("fceumm", "mesen", "nestopia", "quicknes")),
    SNES("SNES", listOf("super_nintendo_entertainment_system", "super_nintendo", "snes", "super_famicom", "sfc"), listOf("snes9x", "bsnes", "snes9x2010", "snes9x2005_plus")),
    N64("Nintendo 64", listOf("nintendo_64", "n64"), listOf("mupen64plus_next_gles3", "parallel_n64")),
    GB("Game Boy", listOf("gameboy", "game_boy", "gb"), listOf("gambatte", "sameboy", "gearboy", "mgba")),
    GBC("Game Boy Color", listOf("gameboy_color", "game_boy_color", "gbc"), listOf("gambatte", "sameboy", "gearboy", "mgba")),
    GBA("Game Boy Advance", listOf("gameboy_advance", "game_boy_advance", "gba"), listOf("mgba", "vbam", "vba_next", "gpsp")),
    NDS("Nintendo DS", listOf("nintendo_ds", "nintendo_dsi", "nds", "dsi"), listOf("melondsds", "melonds")),
    N3DS("Nintendo 3DS", listOf("nintendo_3ds", "3ds", "n3ds"), listOf("citra")),
    GAMECUBE("GameCube", listOf("gamecube", "nintendo_gamecube", "gc", "ngc"), listOf("dolphin"), disc = true),
    WII("Wii", listOf("wii", "nintendo_wii"), listOf("dolphin"), disc = true),
    WIIU("Wii U", listOf("wii_u", "wiiu", "nintendo_wii_u"), disc = true),
    SWITCH("Switch", listOf("switch", "nintendo_switch")),
    PS1("PlayStation", listOf("playstation", "ps1", "psx", "ps_one", "psone"), listOf("pcsx_rearmed", "swanstation", "mednafen_psx_hw", "mednafen_psx"), disc = true),
    PS2("PlayStation 2", listOf("playstation_2", "ps2"), listOf("pcsx2"), disc = true),
    PS3("PlayStation 3", listOf("playstation_3", "ps3"), disc = true),
    PSP("PSP", listOf("playstation_portable", "psp"), listOf("ppsspp"), disc = true),
    MASTER_SYSTEM("Master System", listOf("master_system", "mastersystem", "sega_master_system", "sms"), listOf("genesis_plus_gx", "smsplus", "gearsystem", "picodrive")),
    GENESIS("Mega Drive / Genesis", listOf("genesis", "mega_drive", "megadrive", "sega_genesis", "sega_mega_drive", "md"), listOf("genesis_plus_gx", "picodrive", "blastem")),
    GAME_GEAR("Game Gear", listOf("game_gear", "gamegear", "sega_game_gear", "gg"), listOf("genesis_plus_gx", "smsplus", "gearsystem")),
    SEGA_CD("Sega CD", listOf("sega_cd", "segacd", "mega_cd", "megacd"), listOf("genesis_plus_gx", "picodrive"), disc = true),
    SEGA_32X("32X", listOf("sega_32x", "32x"), listOf("picodrive")),
    SATURN("Saturn", listOf("saturn", "sega_saturn"), listOf("mednafen_saturn", "yabasanshiro", "yabause"), disc = true),
    DREAMCAST("Dreamcast", listOf("dreamcast", "sega_dreamcast", "dc"), listOf("flycast"), disc = true),
    PC_ENGINE("PC Engine", listOf("pc_engine", "pcengine", "turbografx_16", "turbografx16", "turbografx", "pc_engine_cd", "turbografx_cd", "supergrafx", "pce", "tg16"), listOf("mednafen_pce_fast", "mednafen_pce", "mednafen_supergrafx")),
    NEO_GEO("Neo Geo", listOf("neo_geo", "neogeo", "neo_geo_aes", "neo_geo_mvs"), listOf("fbneo")),
    NEO_GEO_CD("Neo Geo CD", listOf("neo_geo_cd", "neogeocd"), listOf("neocd"), disc = true),
    ARCADE("Arcade", listOf("arcade", "mame", "fbneo", "fba", "cps1", "cps2", "cps3"), listOf("fbneo", "mame2003_plus", "mamearcade")),
    ATARI_2600("Atari 2600", listOf("atari_2600", "2600"), listOf("stella", "stella2014")),
    ATARI_7800("Atari 7800", listOf("atari_7800", "7800"), listOf("prosystem")),
    ATARI_LYNX("Atari Lynx", listOf("atari_lynx", "lynx"), listOf("handy", "mednafen_lynx")),
    ATARI_JAGUAR("Atari Jaguar", listOf("atari_jaguar", "jaguar"), listOf("virtualjaguar")),
    NEO_GEO_POCKET("Neo Geo Pocket", listOf("neo_geo_pocket", "neogeo_pocket", "neo_geo_pocket_color", "ngp", "ngpc"), listOf("mednafen_ngp", "race")),
    WONDERSWAN("WonderSwan", listOf("wonderswan", "wonder_swan", "wonderswan_color", "ws", "wsc"), listOf("mednafen_wswan")),
    VIRTUAL_BOY("Virtual Boy", listOf("virtual_boy", "virtualboy", "vb"), listOf("mednafen_vb")),
    POKEMON_MINI("Pokemon mini", listOf("pokemon_mini", "pokemini"), listOf("pokemini")),
    PANASONIC_3DO("3DO", listOf("3do", "panasonic_3do"), listOf("opera"), disc = true)
}

/** Where a launch template takes a value from; the file is the only thing that changes between games. */
enum class Slot {
    /** The document URI under the library's SAF tree (ES-DE's `%ROMSAF%`). */
    SAF_URI,

    /** A URI the emulator may read through our FileProvider (ES-DE's `%ROMPROVIDER%`); see [PlayRecipe.fileUri]. */
    PROVIDER_URI,

    /** The absolute file-system path of the game (ES-DE's `%ROM%`). */
    PATH
}

/** What an intent extra carries. */
sealed interface ExtraValue {
    data class Of(val slot: Slot) : ExtraValue
    data class Text(val text: String) : ExtraValue
    data class Flag(val on: Boolean) : ExtraValue

    /** RetroArch: the libretro core's `.so` under the app's private `cores` folder. */
    data object Core : ExtraValue

    /** RetroArch: the `retroarch.cfg` under the app's external files folder. */
    data object Config : ExtraValue
}

data class ExtraSpec(val key: String, val value: ExtraValue)

/**
 * How to start one game in an emulator: the intent's action, categories, data and extras.
 * A null [action] leaves it unset (an explicit component needs none; ES-DE sends none for these).
 * [clearTask] adds CLEAR_TASK | CLEAR_TOP (ES-DE's `%ACTIVITY_CLEAR_TASK%` / `%ACTIVITY_CLEAR_TOP%`).
 */
data class LaunchTemplate(
    val action: String? = null,
    val categories: List<String> = emptyList(),
    val data: Slot? = null,
    val extras: List<ExtraSpec> = emptyList(),
    val clearTask: Boolean = false
)

/**
 * One installable flavour of an emulator: its package and the activities to try, in order. An
 * activity starting with `.` is relative to the package (as in ES-DE's find rules). No activities =
 * only the generic `ACTION_VIEW` to the package.
 */
data class Variant(val packageName: String, val activities: List<String> = emptyList())

/**
 * A known Android emulator.
 *
 * @property variants installable packages, preferred first.
 * @property template null = generic `ACTION_VIEW` to the package.
 * @property archives archive extensions the app opens itself for cartridge systems; any other
 *  archive has to be extracted first.
 * @property libretro RetroArch: needs a core ([PlaySystem.cores]) on top of the package.
 */
data class Emulator(
    val id: String,
    val label: String,
    val variants: List<Variant>,
    val systems: Set<PlaySystem>,
    val template: LaunchTemplate?,
    val confidence: Confidence,
    val archives: Set<String> = emptySet(),
    val libretro: Boolean = false
)

/** One thing Play can start a game with: an installed emulator package (and a RetroArch core). */
data class PlayTarget(
    val emulatorId: String,
    val label: String,
    val packageName: String,
    val core: String? = null
) {
    /** What [GameLaunchKeys] stores for this target. */
    val key: String get() = GameLaunchKeys.catalogue(emulatorId, core)
}

/** The catalogue of well-known Android emulators and the rules that map a console to them. */
object EmulatorCatalog {

    private const val NS = "android.intent.action"
    private const val RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private const val YUZU_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"
    private const val CITRA_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"

    private val withCores: Set<PlaySystem> = PlaySystem.entries.filter { it.cores.isNotEmpty() }.toSet()

    private fun viewSaf(clearTask: Boolean = false, category: List<String> = emptyList()) =
        LaunchTemplate("$NS.VIEW", category, Slot.SAF_URI, clearTask = clearTask)

    /** The Imagine-engine "<system>.emu" apps (Robert Broglia): no action, just the file. */
    private fun explus(id: String, label: String, packageName: String, vararg systems: PlaySystem) = Emulator(
        id, label, listOf(Variant(packageName, listOf("com.imagine.BaseActivity"))), systems.toSet(),
        LaunchTemplate(data = Slot.SAF_URI), Confidence.SURE, archives = setOf("zip", "7z")
    )

    /** Preferred emulators come first: standalone apps before RetroArch, as ES-DE ranks them for most systems. */
    val all: List<Emulator> = listOf(
        // ---- Sony ----
        Emulator(
            "duckstation", "DuckStation", listOf(Variant("com.github.stenzek.duckstation", listOf(".EmulationActivity"))),
            setOf(PlaySystem.PS1),
            LaunchTemplate(extras = listOf(ExtraSpec("resumeState", ExtraValue.Flag(false)), ExtraSpec("bootPath", ExtraValue.Of(Slot.SAF_URI))), clearTask = true),
            Confidence.SURE
        ),
        Emulator(
            "epsxe", "ePSXe", listOf(Variant("com.epsxe.ePSXe", listOf(".ePSXe"))), setOf(PlaySystem.PS1),
            LaunchTemplate("$NS.MAIN", extras = listOf(ExtraSpec("com.epsxe.ePSXe.isoName", ExtraValue.Of(Slot.PATH)))),
            Confidence.SURE
        ),
        Emulator(
            "nethersx2", "NetherSX2 / AetherSX2",
            listOf(
                Variant("xyz.aethersx2.android", listOf(".EmulationActivity")),
                Variant("xyz.aethersx2.tturnip", listOf("xyz.aethersx2.android.EmulationActivity")),
                Variant("xyz.aethersx2.cturnip", listOf("xyz.aethersx2.android.EmulationActivity"))
            ),
            setOf(PlaySystem.PS2),
            LaunchTemplate("$NS.MAIN", extras = listOf(ExtraSpec("bootPath", ExtraValue.Of(Slot.SAF_URI))), clearTask = true),
            Confidence.SURE
        ),
        Emulator(
            "aps3e", "aPS3e",
            listOf(Variant("aenu.aps3e.premium", listOf("aenu.aps3e.EmulatorActivity")), Variant("aenu.aps3e", listOf("aenu.aps3e.EmulatorActivity"))),
            setOf(PlaySystem.PS3),
            // ES-DE's "aPS3e ISO" command; a game folder needs a different extra (game_dir), not offered here.
            LaunchTemplate("aenu.intent.action.APS3E", extras = listOf(ExtraSpec("iso_uri", ExtraValue.Of(Slot.SAF_URI)))),
            Confidence.SURE
        ),
        Emulator(
            "ppsspp", "PPSSPP",
            listOf(
                Variant("org.ppsspp.ppssppgold", listOf("org.ppsspp.ppsspp.PpssppActivity")),
                Variant("org.ppsspp.ppsspp", listOf(".PpssppActivity"))
            ),
            setOf(PlaySystem.PSP),
            viewSaf(category = listOf("android.intent.category.DEFAULT")),
            Confidence.SURE
        ),
        // ---- Nintendo consoles ----
        Emulator(
            "dolphin", "Dolphin",
            listOf(Variant("org.dolphinemu.dolphinemu", listOf(".ui.main.TvMainActivity", ".ui.main.MainActivity"))),
            setOf(PlaySystem.GAMECUBE, PlaySystem.WII),
            LaunchTemplate("$NS.MAIN", listOf("android.intent.category.LEANBACK_LAUNCHER"), extras = listOf(ExtraSpec("AutoStartFile", ExtraValue.Of(Slot.SAF_URI)))),
            Confidence.SURE
        ),
        Emulator(
            "dolphin_mmjr", "Dolphin MMJR",
            listOf(
                Variant("org.mm.jr", listOf("org.dolphinemu.dolphinemu.ui.main.MainActivity")),
                Variant("org.dolphinemu.mmjr", listOf("org.dolphinemu.dolphinemu.ui.main.MainActivity"))
            ),
            setOf(PlaySystem.GAMECUBE, PlaySystem.WII),
            LaunchTemplate("$NS.VIEW", extras = listOf(ExtraSpec("AutoStartFile", ExtraValue.Of(Slot.SAF_URI)))),
            Confidence.SURE
        ),
        Emulator(
            "cemu", "Cemu",
            listOf(
                Variant("info.cemu.cemu", listOf("info.cemu.cemu.emulation.EmulationActivity")),
                Variant("info.cemu.Cemu", listOf("info.cemu.Cemu.emulation.EmulationActivity"))
            ),
            setOf(PlaySystem.WIIU), LaunchTemplate(data = Slot.SAF_URI), Confidence.SURE
        ),
        Emulator(
            "azahar", "Azahar", listOf(Variant("org.azahar_emu.azahar", listOf(CITRA_ACTIVITY))), setOf(PlaySystem.N3DS),
            LaunchTemplate(data = Slot.SAF_URI, clearTask = true), Confidence.SURE
        ),
        Emulator(
            "lime3ds", "Lime3DS", listOf(Variant("io.github.lime3ds.android", listOf(".activities.EmulationActivity", CITRA_ACTIVITY))), setOf(PlaySystem.N3DS),
            LaunchTemplate(data = Slot.SAF_URI, clearTask = true), Confidence.SURE
        ),
        Emulator(
            "citra", "Citra",
            listOf(Variant("org.citra.citra_emu", listOf(".activities.EmulationActivity")), Variant("org.citra.citra_emu.canary", listOf(CITRA_ACTIVITY))),
            setOf(PlaySystem.N3DS), LaunchTemplate(data = Slot.SAF_URI, clearTask = true), Confidence.SURE
        ),
        Emulator(
            "citra_mmj", "Citra MMJ", listOf(Variant("org.citra.emu", listOf(".ui.EmulationActivity"))), setOf(PlaySystem.N3DS),
            LaunchTemplate(extras = listOf(ExtraSpec("GamePath", ExtraValue.Of(Slot.PATH)))), Confidence.SURE
        ),
        Emulator(
            "panda3ds", "Panda3DS", listOf(Variant("com.panda3ds.pandroid", listOf(".app.MainActivity"))), setOf(PlaySystem.N3DS),
            LaunchTemplate(data = Slot.PROVIDER_URI), Confidence.SURE
        ),
        Emulator(
            "melonds", "melonDS", listOf(Variant("me.magnum.melonds", listOf(".ui.emulator.EmulatorActivity"))), setOf(PlaySystem.NDS),
            LaunchTemplate("me.magnum.melonds.LAUNCH_ROM", extras = listOf(ExtraSpec("uri", ExtraValue.Of(Slot.SAF_URI)))),
            Confidence.SURE, archives = setOf("zip", "7z")
        ),
        Emulator(
            "watermelonds", "WatermelonDS", listOf(Variant("me.magnum.melondualds", listOf("me.magnum.melonds.ui.emulator.EmulatorActivity"))), setOf(PlaySystem.NDS),
            LaunchTemplate("me.magnum.melonds.LAUNCH_ROM", extras = listOf(ExtraSpec("uri", ExtraValue.Of(Slot.SAF_URI)))),
            Confidence.SURE, archives = setOf("zip", "7z")
        ),
        Emulator(
            "drastic", "DraStic", listOf(Variant("com.dsemu.drastic", listOf(".DraSticActivity"))), setOf(PlaySystem.NDS),
            LaunchTemplate(data = Slot.SAF_URI, clearTask = true), Confidence.SURE, archives = setOf("zip", "7z")
        ),
        Emulator(
            "noods", "NooDS", listOf(Variant("com.hydra.noods", listOf(".FileBrowser"))), setOf(PlaySystem.NDS, PlaySystem.GBA),
            LaunchTemplate(extras = listOf(ExtraSpec("LaunchPath", ExtraValue.Of(Slot.PATH))), clearTask = true), Confidence.SURE, archives = setOf("zip")
        ),
        Emulator(
            "m64plus_fz", "M64Plus FZ",
            listOf(
                Variant("org.mupen64plusae.v3.fzurita.pro", listOf("paulscode.android.mupen64plusae.SplashActivity")),
                Variant("org.mupen64plusae.v3.fzurita", listOf("paulscode.android.mupen64plusae.SplashActivity")),
                Variant("org.mupen64plusae.v3.fzurita.amazon", listOf("paulscode.android.mupen64plusae.SplashActivity"))
            ),
            setOf(PlaySystem.N64), viewSaf(), Confidence.SURE, archives = setOf("zip", "7z")
        ),
        Emulator(
            "mupen64plus_ae", "Mupen64Plus AE", listOf(Variant("org.mupen64plusae.v3.alpha", listOf("paulscode.android.mupen64plusae.SplashActivity"))), setOf(PlaySystem.N64),
            viewSaf(), Confidence.SURE, archives = setOf("zip", "7z")
        ),
        Emulator(
            "my_boy", "My Boy!", listOf(Variant("com.fastemulator.gba", listOf(".EmulatorActivity"))), setOf(PlaySystem.GBA),
            viewSaf(), Confidence.SURE, archives = setOf("zip")
        ),
        Emulator(
            "my_oldboy", "My OldBoy!", listOf(Variant("com.fastemulator.gbc", listOf(".EmulatorActivity"))), setOf(PlaySystem.GB, PlaySystem.GBC),
            viewSaf(), Confidence.SURE, archives = setOf("zip")
        ),
        Emulator(
            "pizza_boy_gba", "Pizza Boy GBA",
            listOf(
                Variant("it.dbtecno.pizzaboygbapro", listOf("it.dbtecno.pizzaboygbapro.MainActivity")),
                Variant("it.dbtecno.pizzaboygba", listOf("it.dbtecno.pizzaboygba.MainActivity"))
            ),
            setOf(PlaySystem.GBA),
            LaunchTemplate(extras = listOf(ExtraSpec("rom_uri", ExtraValue.Of(Slot.SAF_URI))), clearTask = true), Confidence.SURE, archives = setOf("zip")
        ),
        Emulator(
            "pizza_boy_gbc", "Pizza Boy GBC",
            listOf(
                Variant("it.dbtecno.pizzaboypro", listOf("it.dbtecno.pizzaboypro.MainActivity")),
                Variant("it.dbtecno.pizzaboy", listOf("it.dbtecno.pizzaboy.MainActivity"))
            ),
            setOf(PlaySystem.GB, PlaySystem.GBC),
            LaunchTemplate(extras = listOf(ExtraSpec("rom_uri", ExtraValue.Of(Slot.SAF_URI))), clearTask = true), Confidence.SURE, archives = setOf("zip")
        ),
        // ---- Nintendo Switch (a yuzu lineage: the activity and the way it takes the game are shared) ----
        Emulator(
            "eden", "Eden",
            listOf(
                Variant("dev.eden.eden_emulator", listOf(YUZU_ACTIVITY)),
                Variant("dev.legacy.eden_emulator", listOf(YUZU_ACTIVITY)),
                Variant("dev.eden.eden_emulator.nightly", listOf(YUZU_ACTIVITY)),
                Variant("dev.legacy.eden_emulator.nightly", listOf(YUZU_ACTIVITY))
            ),
            setOf(PlaySystem.SWITCH),
            // ES-DE launches Eden with this action (the activity's amiibo filter) and the game as data.
            LaunchTemplate("android.nfc.action.TECH_DISCOVERED", data = Slot.PROVIDER_URI), Confidence.SURE
        ),
        Emulator(
            "yuzu", "yuzu",
            listOf(Variant("org.yuzu.yuzu_emu", listOf(".activities.EmulationActivity")), Variant("org.yuzu.yuzu_emu.ea", listOf(YUZU_ACTIVITY))),
            setOf(PlaySystem.SWITCH),
            // Same contract as Eden, which is a fork of it; yuzu is not in ES-DE's rules any more.
            LaunchTemplate("android.nfc.action.TECH_DISCOVERED", data = Slot.PROVIDER_URI), Confidence.LIKELY
        ),
        Emulator(
            // Package name from the project's build files, not listed by ES-DE: generic ACTION_VIEW only.
            "sudachi", "Sudachi", listOf(Variant("org.sudachi.sudachi_emu")), setOf(PlaySystem.SWITCH), null, Confidence.BEST_EFFORT
        ),
        Emulator(
            "kenji_nx", "Kenji-NX", listOf(Variant("org.kenjinx.android", listOf(".MainActivity"))), setOf(PlaySystem.SWITCH),
            LaunchTemplate("org.kenjinx.android.LAUNCH_GAME", extras = listOf(ExtraSpec("bootPath", ExtraValue.Of(Slot.SAF_URI)))), Confidence.SURE
        ),
        Emulator(
            "skyline", "Skyline", listOf(Variant("skyline.emu", listOf("emu.skyline.EmulationActivity"))), setOf(PlaySystem.SWITCH),
            LaunchTemplate("$NS.VIEW", data = Slot.PROVIDER_URI), Confidence.SURE
        ),
        // ---- Sega ----
        Emulator(
            "flycast", "Flycast", listOf(Variant("com.flycast.emulator", listOf("com.flycast.emulator.MainActivity", "com.reicast.emulator.MainActivity"))), setOf(PlaySystem.DREAMCAST),
            viewSaf(), Confidence.SURE
        ),
        Emulator(
            "redream", "Redream", listOf(Variant("io.recompiled.redream", listOf(".MainActivity"))), setOf(PlaySystem.DREAMCAST),
            viewSaf(), Confidence.SURE
        ),
        Emulator(
            "yabasanshiro", "Yaba Sanshiro 2",
            listOf(
                Variant("org.devmiyax.yabasanshioro2.pro", listOf("org.uoyabause.android.Yabause")),
                Variant("org.devmiyax.yabasanshioro2", listOf("org.uoyabause.android.Yabause"))
            ),
            setOf(PlaySystem.SATURN),
            LaunchTemplate("$NS.VIEW", extras = listOf(ExtraSpec("org.uoyabause.android.FileNameUri", ExtraValue.Of(Slot.SAF_URI))), clearTask = true),
            Confidence.SURE
        ),
        explus("saturn_emu", "Saturn.emu", "com.explusalpha.SaturnEmu", PlaySystem.SATURN),
        explus("md_emu", "MD.emu", "com.explusalpha.MdEmu", PlaySystem.GENESIS, PlaySystem.MASTER_SYSTEM, PlaySystem.GAME_GEAR, PlaySystem.SEGA_CD),
        // ---- Imagine-engine ".emu" apps ----
        explus("snes9x_explus", "Snes9x EX+", "com.explusalpha.Snes9xPlus", PlaySystem.SNES),
        explus("nes_emu", "NES.emu", "com.explusalpha.NesEmu", PlaySystem.NES),
        explus("gba_emu", "GBA.emu", "com.explusalpha.GbaEmu", PlaySystem.GBA),
        explus("gbc_emu", "GBC.emu", "com.explusalpha.GbcEmu", PlaySystem.GB, PlaySystem.GBC),
        explus("pce_emu", "PCE.emu", "com.PceEmu", PlaySystem.PC_ENGINE),
        explus("neo_emu", "NEO.emu", "com.explusalpha.NeoEmu", PlaySystem.NEO_GEO),
        explus("ngp_emu", "NGP.emu", "com.explusalpha.NgpEmu", PlaySystem.NEO_GEO_POCKET),
        explus("lynx_emu", "Lynx.emu", "com.explusalpha.LynxEmu", PlaySystem.ATARI_LYNX),
        explus("a2600_emu", "2600.emu", "com.explusalpha.A2600Emu", PlaySystem.ATARI_2600),
        explus("swan_emu", "Swan.emu", "com.explusalpha.SwanEmu", PlaySystem.WONDERSWAN),
        // ---- RetroArch last: it runs everything with a core, but a standalone app is the better default ----
        Emulator(
            "retroarch", "RetroArch",
            listOf(
                Variant("com.retroarch.aarch64", listOf(RA_ACTIVITY)),
                Variant("com.retroarch.ra32", listOf(RA_ACTIVITY)),
                Variant("com.retroarch", listOf(RA_ACTIVITY))
            ),
            withCores,
            LaunchTemplate(
                extras = listOf(
                    ExtraSpec("ROM", ExtraValue.Of(Slot.PATH)),
                    ExtraSpec("LIBRETRO", ExtraValue.Core),
                    ExtraSpec("CONFIGFILE", ExtraValue.Config)
                ),
                // As ES-DE does: without a fresh task RetroArch resumes the running game instead of loading this one.
                clearTask = true
            ),
            Confidence.SURE, archives = setOf("zip"), libretro = true
        )
    )

    // ---- Lookups ---------------------------------------------------------------------------------

    private val byId: Map<String, Emulator> = all.associateBy { it.id }

    fun byId(id: String): Emulator? = byId[id]

    /** Every package of the catalogue; the manifest `<queries>` must list each of them. */
    fun allPackages(): List<String> = all.flatMap { e -> e.variants.map { it.packageName } }.distinct()

    /** The emulators that can start [system], in the order Play prefers them. */
    fun emulatorsFor(system: PlaySystem): List<Emulator> = all.filter { system in it.systems }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /**
     * The system of a library console id or folder name; the most specific key wins (so
     * `gameboy_advance` is not the Game Boy and `playstation_2` not the PlayStation), null when no
     * system fits. Same scoring as the RetroAchievements console match.
     */
    fun systemOf(consoleId: String): PlaySystem? {
        val id = normalize(consoleId)
        if (id.isEmpty()) return null
        val tokens = id.split('_')
        fun score(key: String): Int {
            val k = normalize(key)
            val hit = id == k || id.endsWith("_$k") || k in tokens || (k.length >= 5 && id.replace("_", "").contains(k.replace("_", "")))
            return if (hit) k.length else 0
        }
        val best = PlaySystem.entries.maxByOrNull { s -> s.keys.maxOf { score(it) } } ?: return null
        val bestScore = best.keys.maxOf { score(it) }
        if (bestScore == 0) return null
        // A newer console of the same family (`sony_playstation_vita`, `playstation4`) contains a
        // shorter key of an older one; when the newer name fits at least as well, it is not that one.
        return best.takeIf { unsupported.maxOf { score(it) } < bestScore }
    }

    /** Consoles the catalogue has no emulator for whose names contain the key of one it has. */
    private val unsupported = listOf(
        "playstation_vita", "ps_vita", "psvita", "vita", "playstation_4", "playstation4", "ps4", "playstation_5", "playstation5", "ps5",
        "nintendo_switch_2", "switch_2", "switch2"
    )

    /** The first installed variant of [emulator] (packages in [installed]), or null when none is. */
    fun installedVariant(emulator: Emulator, installed: Set<String>): Variant? = emulator.variants.firstOrNull { it.packageName in installed }

    /** Display name of a libretro core: `snes9x` -> "Snes9x", `mupen64plus_next_gles3` -> "Mupen64Plus-Next". */
    fun coreLabel(core: String): String = coreLabels[core] ?: core.replace("_libretro_android", "").split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private val coreLabels = mapOf(
        "fceumm" to "FCEUmm", "mesen" to "Mesen", "nestopia" to "Nestopia UE", "quicknes" to "QuickNES",
        "snes9x" to "Snes9x", "bsnes" to "bsnes", "snes9x2010" to "Snes9x 2010", "snes9x2005_plus" to "Snes9x 2005 Plus",
        "mupen64plus_next_gles3" to "Mupen64Plus-Next", "parallel_n64" to "ParaLLEl N64",
        "gambatte" to "Gambatte", "sameboy" to "SameBoy", "gearboy" to "Gearboy", "mgba" to "mGBA", "vbam" to "VBA-M", "vba_next" to "VBA Next", "gpsp" to "gpSP",
        "melondsds" to "melonDS DS", "melonds" to "melonDS", "citra" to "Citra", "dolphin" to "Dolphin",
        "pcsx_rearmed" to "PCSX ReARMed", "swanstation" to "SwanStation", "mednafen_psx_hw" to "Beetle PSX HW", "mednafen_psx" to "Beetle PSX",
        "pcsx2" to "LRPS2", "ppsspp" to "PPSSPP", "genesis_plus_gx" to "Genesis Plus GX", "picodrive" to "PicoDrive", "blastem" to "BlastEm",
        "smsplus" to "SMS Plus GX", "gearsystem" to "Gearsystem", "mednafen_saturn" to "Beetle Saturn", "yabasanshiro" to "YabaSanshiro", "yabause" to "Yabause",
        "flycast" to "Flycast", "mednafen_pce_fast" to "Beetle PCE Fast", "mednafen_pce" to "Beetle PCE", "mednafen_supergrafx" to "Beetle SuperGrafx",
        "fbneo" to "FinalBurn Neo", "mame2003_plus" to "MAME 2003-Plus", "mamearcade" to "MAME", "neocd" to "NeoCD",
        "stella" to "Stella", "stella2014" to "Stella 2014", "prosystem" to "ProSystem", "handy" to "Handy", "mednafen_lynx" to "Beetle Lynx",
        "virtualjaguar" to "Virtual Jaguar", "mednafen_ngp" to "Beetle NeoPop", "race" to "RACE", "mednafen_wswan" to "Beetle Cygne",
        "mednafen_vb" to "Beetle VB", "pokemini" to "PokeMini", "opera" to "Opera"
    )

    /**
     * The targets Play can offer for [system] with the packages in [installed]: one per installed
     * emulator, RetroArch once per core of the system (best core first). The order is the catalogue's.
     */
    fun targetsFor(system: PlaySystem, installed: Set<String>): List<PlayTarget> = emulatorsFor(system).flatMap { e ->
        val variant = installedVariant(e, installed) ?: return@flatMap emptyList()
        if (e.libretro) system.cores.map { core -> PlayTarget(e.id, "${e.label} (${coreLabel(core)})", variant.packageName, core) }
        else listOf(PlayTarget(e.id, e.label, variant.packageName))
    }

    /** The manifest snippet that makes every catalogue package visible to the app (Android 11+ package visibility). */
    fun queriesXml(): String = buildString {
        append("<queries>\n")
        allPackages().forEach { append("    <package android:name=\"").append(it).append("\" />\n") }
        append("</queries>")
    }
}
