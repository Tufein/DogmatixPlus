package com.cortinadev.dogmatix.util

/**
 * The family a console belongs to, and the colour 5.0 gives it (console chips, cover placeholders,
 * statistics). Matched on words in the console id (`nintendo_gameboy_advance`, `sony_psp`,
 * `super_nintendo_entertainment_system`), so imported sources with their own ids still get a
 * sensible colour. Pure JVM for the tests.
 */
enum class ConsoleFamily(val argb: Long) {
    NINTENDO(0xFFFF5A5F),
    NINTENDO_HANDHELD(0xFF8FCF3C),
    PLAYSTATION(0xFF5B8CFF),
    SEGA(0xFF2FA8FF),
    XBOX(0xFF5CC93B),
    ATARI(0xFFFF8A3D),
    NEC(0xFFFFB547),
    SNK(0xFFE7C24A),
    BANDAI(0xFFE86BD6),
    ARCADE(0xFFA57BFF),
    COMPUTER(0xFF2ED6B8),
    OTHER(0xFF9AA0AE);

    companion object {
        private val handheld = listOf("gameboy", "game_boy", "gba", "gbc", "nintendo_ds", "ds", "dsi", "3ds", "pokemon_mini", "virtual_boy", "nds")
        private val nintendo = listOf("nintendo", "nes", "famicom", "snes", "n64", "gamecube", "wii", "switch")
        private val playstation = listOf("playstation", "psp", "ps1", "ps2", "ps3", "psx", "vita", "sony")
        private val sega = listOf("sega", "genesis", "mega_drive", "megadrive", "master_system", "game_gear", "saturn", "dreamcast", "32x", "sg_1000", "mega_cd", "sega_cd")
        private val xbox = listOf("xbox", "microsoft")
        private val atari = listOf("atari", "lynx", "jaguar")
        private val nec = listOf("nec", "pc_engine", "pce", "turbografx", "pc_fx", "supergrafx")
        private val snk = listOf("snk", "neo_geo", "neogeo", "ngp")
        private val bandai = listOf("bandai", "wonderswan")
        private val arcade = listOf("arcade", "mame", "fbneo", "fba", "cps1", "cps2", "cps3", "naomi", "atomiswave")
        private val computer = listOf("amiga", "commodore", "c64", "msx", "msx2", "dos", "zx", "spectrum", "amstrad", "cpc", "apple", "pc98", "x68000", "atari_st", "scummvm", "computer")

        fun of(consoleId: String): ConsoleFamily {
            val id = "_" + consoleId.lowercase().replace('-', '_').replace(' ', '_') + "_"
            // Whole words only: "nes" must not match "genesis", "ds" not "dreamcast".
            fun has(words: List<String>) = words.any { w -> id.contains("_${w}_") }
            return when {
                has(arcade) -> ARCADE
                has(computer) -> COMPUTER
                has(handheld) -> NINTENDO_HANDHELD
                has(nintendo) -> NINTENDO
                has(playstation) -> PLAYSTATION
                has(sega) -> SEGA
                has(xbox) -> XBOX
                has(atari) -> ATARI
                has(nec) -> NEC
                has(snk) -> SNK
                has(bandai) -> BANDAI
                else -> OTHER
            }
        }
    }
}
