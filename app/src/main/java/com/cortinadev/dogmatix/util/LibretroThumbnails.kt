package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * Box art from libretro-thumbnails (the covers RetroArch shows), which needs no API key. Files
 * are named after the No-Intro / Redump name, so a well-named ROM finds its cover exactly:
 * `…/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Advance Wars (USA).png`. Pure JVM for tests.
 */
object LibretroThumbnails {

    data class System(val repo: String, val keys: List<String>)

    val systems = listOf(
        System("Nintendo_-_Game_Boy_Advance", listOf("gameboy_advance", "game_boy_advance", "gba")),
        System("Nintendo_-_Game_Boy_Color", listOf("gameboy_color", "game_boy_color", "gbc")),
        System("Nintendo_-_Game_Boy", listOf("gameboy", "game_boy", "gb")),
        System("Nintendo_-_Nintendo_Entertainment_System", listOf("nintendo_entertainment_system", "nes", "famicom")),
        System("Nintendo_-_Family_Computer_Disk_System", listOf("famicom_disk", "fds")),
        System("Nintendo_-_Super_Nintendo_Entertainment_System", listOf("super_nintendo_entertainment_system", "super_nintendo", "snes", "sfc", "super_famicom")),
        System("Nintendo_-_Nintendo_64", listOf("nintendo_64", "n64")),
        System("Nintendo_-_Nintendo_DS", listOf("nintendo_ds", "nds")),
        System("Nintendo_-_Nintendo_3DS", listOf("nintendo_3ds", "3ds")),
        System("Nintendo_-_GameCube", listOf("gamecube", "ngc", "gc")),
        System("Nintendo_-_Wii", listOf("wii")),
        System("Nintendo_-_Virtual_Boy", listOf("virtual_boy", "virtualboy")),
        System("Nintendo_-_Pokemon_Mini", listOf("pokemon_mini", "pokemini")),
        System("Sega_-_Mega_Drive_-_Genesis", listOf("genesis", "mega_drive", "megadrive", "md")),
        System("Sega_-_Master_System_-_Mark_III", listOf("master_system", "mastersystem", "sms")),
        System("Sega_-_Game_Gear", listOf("game_gear", "gamegear", "gg")),
        System("Sega_-_32X", listOf("32x", "sega_32x")),
        System("Sega_-_Mega-CD_-_Sega_CD", listOf("sega_cd", "segacd", "mega_cd", "megacd", "scd")),
        System("Sega_-_Saturn", listOf("saturn")),
        System("Sega_-_Dreamcast", listOf("dreamcast", "dc")),
        System("Sega_-_SG-1000", listOf("sg_1000", "sg1000")),
        System("Sony_-_PlayStation", listOf("playstation", "psx", "ps1")),
        System("Sony_-_PlayStation_2", listOf("playstation_2", "ps2")),
        System("Sony_-_PlayStation_Portable", listOf("playstation_portable", "psp")),
        System("NEC_-_PC_Engine_-_TurboGrafx_16", listOf("pc_engine", "pcengine", "turbografx_16", "pce", "tg16")),
        System("NEC_-_PC_Engine_CD_-_TurboGrafx-CD", listOf("pc_engine_cd", "turbografx_cd", "pce_cd")),
        System("SNK_-_Neo_Geo_Pocket_Color", listOf("neo_geo_pocket_color", "ngpc")),
        System("SNK_-_Neo_Geo_Pocket", listOf("neo_geo_pocket", "ngp")),
        System("Bandai_-_WonderSwan_Color", listOf("wonderswan_color", "wsc")),
        System("Bandai_-_WonderSwan", listOf("wonderswan", "ws")),
        System("Atari_-_2600", listOf("atari_2600", "2600")),
        System("Atari_-_7800", listOf("atari_7800", "7800")),
        System("Atari_-_Lynx", listOf("lynx", "atari_lynx")),
        System("Atari_-_Jaguar", listOf("jaguar", "atari_jaguar")),
        System("Coleco_-_ColecoVision", listOf("colecovision", "coleco")),
        System("Mattel_-_Intellivision", listOf("intellivision")),
        System("Microsoft_-_MSX", listOf("msx")),
        System("The_3DO_Company_-_3DO", listOf("3do"))
    )

    /** The libretro-thumbnails repository of a library console (most specific key wins), or null. */
    fun systemFor(consoleId: String): System? {
        val id = consoleId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val tokens = id.split('_')
        fun score(key: String): Int {
            val hit = id == key || id.endsWith("_$key") || key in tokens || (key.length >= 5 && id.replace("_", "").contains(key.replace("_", "")))
            return if (hit) key.length else 0
        }
        return systems.maxByOrNull { s -> s.keys.maxOf { score(it) } }?.takeIf { s -> s.keys.maxOf { score(it) } > 0 }
    }

    /** libretro's file name rule: these characters become `_`. */
    fun thumbnailName(stem: String): String = stem.replace(Regex("""[&*/:`<>?\\|"]"""), "_")

    /** Names to try for a ROM file, best first: the full name, without the disc tag, without revision / extra tags. */
    fun candidates(fileName: String): List<String> {
        val stem = fileName.substringBeforeLast('.').trim()
        val noDisc = stem.replace(Regex("""\s*\((?:Disc|Disk|CD)\s*\d+[^)]*\)""", RegexOption.IGNORE_CASE), "").trim()
        // Keep the title and the first (region) group only: "Game (USA) (Rev 1) (En,Fr)" → "Game (USA)".
        val firstGroup = Regex("""^(.*?\([^)]*\))""").find(noDisc)?.groupValues?.get(1)?.trim()
        return listOfNotNull(stem, noDisc, firstGroup).distinct().map(::thumbnailName)
    }

    fun boxartUrl(system: System, name: String): String =
        "https://raw.githubusercontent.com/libretro-thumbnails/${system.repo}/master/Named_Boxarts/" +
            URLEncoder.encode("$name.png", "UTF-8").replace("+", "%20")

    /** GitHub serves a symlinked thumbnail as a tiny text file holding the target's name; null when [body] is not one. */
    fun symlinkTarget(body: ByteArray): String? {
        if (body.size > 300 || body.isEmpty()) return null
        if (body.size >= 4 && body[0] == 0x89.toByte() && body[1] == 'P'.code.toByte()) return null
        val text = body.toString(Charsets.UTF_8).trim()
        return text.substringAfterLast('/').takeIf { it.endsWith(".png", ignoreCase = true) && '\n' !in it }?.removeSuffix(".png")
    }
}
