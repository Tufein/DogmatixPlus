package com.cortinadev.dogmatix.util

/**
 * Redump publishes its DAT files without an account at `http://redump.org/datfile/<system>/`
 * (a ZIP with one `.dat`). This maps a console to its Redump system; consoles that are not disc
 * based have no Redump DAT (No-Intro, which covers cartridges, needs an account).
 */
object RedumpSystems {

    private val systems = listOf(
        listOf("playstation_2", "ps2") to "ps2",
        listOf("playstation_portable", "psp") to "psp",
        listOf("playstation_3", "ps3") to "ps3",
        listOf("playstation", "psx", "ps1") to "psx",
        listOf("saturn") to "ss",
        listOf("sega_cd", "segacd", "mega_cd", "megacd") to "mcd",
        listOf("dreamcast") to "dc",
        listOf("gamecube", "ngc") to "gc",
        listOf("wii") to "wii",
        listOf("pc_engine_cd", "pcenginecd", "turbografx_cd", "pce_cd") to "pce",
        listOf("pc_fx", "pcfx") to "pc-fx",
        listOf("3do") to "3do",
        listOf("neo_geo_cd", "neogeocd") to "ngcd",
        listOf("xbox") to "xbox",
        listOf("cd_i", "cdi") to "cdi",
        listOf("jaguar_cd") to "ajcd"
    )

    /** The Redump system of [consoleId], or null. More specific names win (PlayStation 2 before PlayStation). */
    fun systemFor(consoleId: String): String? {
        val id = consoleId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val tokens = id.split('_')
        return systems.firstOrNull { (keys, _) ->
            keys.any { k -> id == k || id.endsWith("_$k") || k in tokens || (k.contains('_') && id.contains(k)) }
        }?.second
    }

    fun url(system: String): String = "http://redump.org/datfile/$system/"
}
