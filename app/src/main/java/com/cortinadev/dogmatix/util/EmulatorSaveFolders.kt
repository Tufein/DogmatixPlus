package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Saves folders of standalone emulators (DraStic, mGBA, Snes9x EX+…) synced with RomM next to
 * RetroArch's. Each folder is one console's and is shown to the sync as a folder named after the
 * emulator ([label]) inside the saves folder: its files go up with that emulator name, server
 * saves of that emulator come down into it, and [platforms] picks the right game when the same
 * file name exists on several platforms. The presets say "(standalone)" where RetroArch has a core
 * folder of the same name, so the two never mix. Only in-game saves (battery saves, memory cards), not
 * save states. Pure JVM for the tests.
 */
data class EmulatorSaveFolder(
    /** Emulator name, also the folder name the sync sees and RomM's *emulator* ("DraStic"). */
    val label: String,
    /** RomM platform names this folder's games are on, normalized ("nds"). Empty = no hint. */
    val platforms: Set<String>,
    /** Picked folder (Storage Access Framework tree URI). */
    val uri: String
)

object EmulatorSaveFolders {
    /** Emulators whose save files are named after the ROM, so the sync can tie them to a game. */
    data class Preset(val label: String, val platforms: Set<String>, val folderHint: String)

    val presets: List<Preset> = listOf(
        Preset("DraStic (standalone)", setOf("nds"), "DraStic/backup"),
        Preset("melonDS (standalone)", setOf("nds"), "melonDS saves folder"),
        Preset("mGBA (standalone)", setOf("gba", "gbc", "gb"), "mGBA saves folder"),
        Preset("Pizza Boy GBA", setOf("gba"), "PizzaBoyGBA/saves"),
        Preset("Pizza Boy GBC", setOf("gbc", "gb"), "PizzaBoyGBC/saves"),
        Preset("GBA.emu", setOf("gba"), "GBA.emu saves"),
        Preset("GBC.emu", setOf("gbc", "gb"), "GBC.emu saves"),
        Preset("Snes9x EX+", setOf("snes", "sfc"), "Snes9x EX+ saves"),
        Preset("NES.emu", setOf("nes", "famicom"), "NES.emu saves"),
        Preset("MD.emu", setOf("genesis", "megadrive", "md", "genesisslashmegadrive"), "MD.emu saves")
    )

    /** A folder name RomM accepts as *emulator* (see [SaveSyncPlanner.emulatorFor]); blank when none is left. */
    fun cleanLabel(name: String): String =
        name.filterNot { it in "/\\:*?\"<>|" || it.isISOControl() }.trim().take(50)

    /** [label] made unique among [taken] (case-insensitive): "DraStic", "DraStic 2"… */
    fun uniqueLabel(label: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if (label.lowercase() !in used) return label
        var n = 2
        while ("$label $n".lowercase() in used) n++
        return "$label $n"
    }

    /** The extra folder a sync path ("DraStic (standalone)/Game.dsv") points into, and the path inside it. */
    fun locate(path: String, folders: List<EmulatorSaveFolder>): Pair<EmulatorSaveFolder, String>? {
        if (!path.contains('/')) return null
        val top = path.substringBefore('/')
        val folder = folders.firstOrNull { it.label.equals(top, ignoreCase = true) } ?: return null
        return folder to path.substringAfter('/')
    }

    fun toJson(folders: List<EmulatorSaveFolder>): String = JsonArray().apply {
        folders.forEach { f ->
            add(JsonObject().apply {
                addProperty("label", f.label)
                add("platforms", JsonArray().apply { f.platforms.sorted().forEach(::add) })
                addProperty("uri", f.uri)
            })
        }
    }.toString()

    fun fromJson(json: String?): List<EmulatorSaveFolder> = runCatching {
        JsonParser.parseString(json?.takeIf { it.isNotBlank() } ?: "[]").asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val label = cleanLabel(o.get("label")?.asString.orEmpty()).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val uri = o.get("uri")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EmulatorSaveFolder(label, o.getAsJsonArray("platforms")?.map { ConsoleFolderAliases.normalize(it.asString) }?.toSet().orEmpty(), uri)
        }.distinctBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
