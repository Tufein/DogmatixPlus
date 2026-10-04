package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import java.net.URLEncoder
import java.security.MessageDigest

/** One game of RetroAchievements' list for a console, with the ROM hashes it accepts. */
data class RaGame(val id: Int, val title: String, val achievements: Int, val hashes: Set<String>)

/**
 * RetroAchievements support for cartridge systems: which RA console a library console is, the RA
 * hash of a ROM (RA's own rules: a plain MD5 for most systems, without the copier / iNES / Lynx /
 * 7800 headers, N64 in big-endian order), and the game list RA's web API returns. Disc systems
 * hash several files in a special way and are left out. Pure JVM so it can be unit-tested.
 */
object RetroAchievements {

    enum class Rule { PLAIN, NES, SNES, LYNX, A7800, N64, PCE }

    data class RaConsole(val id: Int, val name: String, val keys: List<String>, val rule: Rule = Rule.PLAIN)

    val consoles = listOf(
        RaConsole(5, "Game Boy Advance", listOf("gameboy_advance", "game_boy_advance", "gba")),
        RaConsole(6, "Game Boy Color", listOf("gameboy_color", "game_boy_color", "gbc")),
        RaConsole(4, "Game Boy", listOf("gameboy", "game_boy", "gb")),
        RaConsole(7, "NES / Famicom", listOf("nintendo_entertainment_system", "nes", "famicom", "fds"), Rule.NES),
        RaConsole(3, "SNES / Super Famicom", listOf("super_nintendo_entertainment_system", "super_nintendo", "snes", "super_famicom", "sfc"), Rule.SNES),
        RaConsole(2, "Nintendo 64", listOf("nintendo_64", "n64"), Rule.N64),
        RaConsole(28, "Virtual Boy", listOf("virtual_boy", "virtualboy", "vb")),
        RaConsole(24, "Pokémon mini", listOf("pokemon_mini", "pokemini")),
        RaConsole(1, "Mega Drive / Genesis", listOf("genesis", "mega_drive", "megadrive", "md")),
        RaConsole(11, "Master System", listOf("master_system", "mastersystem", "sms")),
        RaConsole(15, "Game Gear", listOf("game_gear", "gamegear", "gg")),
        RaConsole(10, "32X", listOf("32x", "sega_32x")),
        RaConsole(33, "SG-1000", listOf("sg_1000", "sg1000")),
        RaConsole(8, "PC Engine / TurboGrafx-16", listOf("pc_engine", "pcengine", "turbografx_16", "turbografx16", "pce", "tg16"), Rule.PCE),
        RaConsole(25, "Atari 2600", listOf("atari_2600", "2600")),
        RaConsole(51, "Atari 7800", listOf("atari_7800", "7800"), Rule.A7800),
        RaConsole(13, "Atari Lynx", listOf("lynx", "atari_lynx"), Rule.LYNX),
        RaConsole(14, "Neo Geo Pocket", listOf("neo_geo_pocket", "neogeo_pocket", "ngp", "ngpc")),
        RaConsole(53, "WonderSwan", listOf("wonderswan", "wonder_swan", "ws", "wsc")),
        RaConsole(44, "ColecoVision", listOf("colecovision", "coleco")),
        RaConsole(45, "Intellivision", listOf("intellivision")),
        RaConsole(29, "MSX", listOf("msx", "msx2")),
        RaConsole(46, "Vectrex", listOf("vectrex")),
        RaConsole(23, "Odyssey² / Videopac", listOf("odyssey2", "odyssey", "videopac")),
        RaConsole(63, "Watara Supervision", listOf("supervision", "watara"))
    )

    /** The RA console of a library console id or folder name (most specific key wins), or null. */
    fun consoleFor(consoleId: String): RaConsole? {
        val id = normalize(consoleId)
        val tokens = id.split('_')
        fun score(key: String): Int {
            val k = normalize(key)
            val hit = id == k || id.endsWith("_$k") || k in tokens || (k.length >= 5 && id.replace("_", "").contains(k.replace("_", "")))
            return if (hit) k.length else 0
        }
        return consoles.maxByOrNull { c -> c.keys.maxOf { score(it) } }?.takeIf { c -> c.keys.maxOf { score(it) } > 0 }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** RA's hash of a ROM's bytes under [rule] (lower-case hex MD5). */
    fun hash(rom: ByteArray, rule: Rule): String = md5(normalizeRom(rom, rule))

    fun normalizeRom(rom: ByteArray, rule: Rule): ByteArray = when (rule) {
        Rule.PLAIN -> rom
        Rule.NES -> if (rom.startsWith(byteArrayOf(0x4E, 0x45, 0x53, 0x1A)) || rom.startsWith(byteArrayOf(0x46, 0x44, 0x53, 0x1A))) rom.copyOfRange(16, rom.size) else rom
        Rule.SNES -> if (rom.size % 8192 == 512) rom.copyOfRange(512, rom.size) else rom
        Rule.PCE -> if (rom.size % 131072 == 512) rom.copyOfRange(512, rom.size) else rom
        Rule.LYNX -> if (rom.startsWith("LYNX".toByteArray()) && rom.size > 64) rom.copyOfRange(64, rom.size) else rom
        Rule.A7800 -> if (rom.size > 128 && String(rom, 1, 9, Charsets.US_ASCII) == "ATARI7800") rom.copyOfRange(128, rom.size) else rom
        Rule.N64 -> n64BigEndian(rom)
    }

    /** `.z64` stays, `.v64` (byte-swapped) and `.n64` (word-swapped, little-endian) are turned into `.z64` order. */
    private fun n64BigEndian(rom: ByteArray): ByteArray {
        if (rom.size < 4) return rom
        val b0 = rom[0].toInt() and 0xFF
        val out = rom.copyOf()
        when (b0) {
            0x37 -> { var i = 0; while (i + 1 < out.size) { val t = out[i]; out[i] = out[i + 1]; out[i + 1] = t; i += 2 } }
            0x40 -> { var i = 0; while (i + 3 < out.size) { out[i] = rom[i + 3]; out[i + 1] = rom[i + 2]; out[i + 2] = rom[i + 1]; out[i + 3] = rom[i]; i += 4 } }
        }
        return out
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** `API_GetGameList.php?…&h=1` → games with their hashes (games without achievements are left out). */
    fun parseGameList(json: String): List<RaGame> = runCatching {
        JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("ID")?.asInt ?: return@mapNotNull null
            val hashes = o.getAsJsonArray("Hashes")?.mapNotNull { runCatching { it.asString.lowercase() }.getOrNull() }?.toSet().orEmpty()
            val count = o.get("NumAchievements")?.takeUnless { it.isJsonNull }?.asInt ?: 0
            RaGame(id, o.get("Title")?.asString.orEmpty(), count, hashes)
        }.filter { it.achievements > 0 }
    }.getOrDefault(emptyList())

    /** URL of RA's game list for a console (the key is the user's own web API key). */
    fun gameListUrl(consoleId: Int, user: String, key: String): String =
        "https://retroachievements.org/API/API_GetGameList.php?z=${enc(user)}&y=${enc(key)}&i=$consoleId&h=1&f=1"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
