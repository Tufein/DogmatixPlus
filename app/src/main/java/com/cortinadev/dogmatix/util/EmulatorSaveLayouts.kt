package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomCandidate
import com.cortinadev.dogmatix.util.SaveSyncPlanner.RomMatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** How an emulator keeps its in-game saves in the folder picked for it (see [EmulatorSaveFolder]). */
enum class SaveFolderLayout {
    /** One file per game, named after the ROM (DraStic, mGBA, Snes9x EX+…). */
    FILES,

    /**
     * DuckStation's `memcards` folder: a card per game, named after the game's title or serial
     * (`Game Title_1.mcd`, `SLUS-00594_1.mcd`), and shared cards (`shared_card_1.mcd`) that hold
     * the saves of many games and are only synced when the user asks for it.
     */
    MEMCARDS,

    /**
     * PPSSPP's `PSP/SAVEDATA`: one folder per save (`ULUS10041DATA00/` with `PARAM.SFO`, `DATA.BIN`,
     * icons…). Each folder travels as one zip ([SavePack]) named `<folder>.savedata.zip`.
     */
    SAVEDATA
}

/** A DuckStation memory card file, from its name. */
sealed interface MemoryCard {
    val slot: Int

    /** `shared_card_1.mcd`: one card for every game. */
    data class Shared(override val slot: Int) : MemoryCard

    /** `Game Title_1.mcd` / `SLUS-00594_1.mcd`: [serial] is set when the name is a serial. */
    data class PerGame(val title: String, val serial: String?, override val slot: Int) : MemoryCard
}

/**
 * The emulator-specific part of syncing DuckStation memory cards and PPSSPP save folders: which
 * files are what, which game they belong to and what to ask RomM for. Nothing is guessed: a card
 * or save folder that fits no game, or several, is left alone ([RomMatch.NotFound] /
 * [RomMatch.Ambiguous]), the same as for any other save. Pure JVM for the tests.
 */
object EmulatorSaveLayouts {
    /** What a PPSSPP save folder is called on the server: `ULUS10041DATA00.savedata.zip`. */
    const val PACK_SUFFIX = ".savedata.zip"

    private val cardExtensions = setOf("mcd", "mcr")
    private val sharedCard = Regex("""^shared_card_(\d+)$""", RegexOption.IGNORE_CASE)
    private val slotSuffix = Regex("""^(.+)_(\d)$""")

    /** PlayStation serial prefixes (Redump's list of the ones that occur on discs). */
    private const val PS1_PREFIXES = "SCES|SCED|SCUS|SCPS|SCAJ|SCKA|SCPM|SCZS|SLES|SLED|SLUS|SLPS|SLPM|SLAJ|SLKA|SIPS|PAPX|PBPX|PCPX|ESPM|CPCS|TCPS"
    private val ps1Serial = Regex("""(?<![A-Za-z0-9])($PS1_PREFIXES)[-_ ]?(\d{3})\.?(\d{2})(?!\d)""", RegexOption.IGNORE_CASE)

    /** PSP product ids: UMD games (`ULUS10041`, `UCES00001`) and PSN releases (`NPUH10091`). */
    private val pspId = Regex("""(?<![A-Za-z0-9])((?:U[CL]|NP)[A-Za-z]{2})[-_ ]?(\d{5})(?!\d)""")
    private val pspFolder = Regex("""^((?:U[CL]|NP)[A-Z]{2})(\d{5})""")

    private val tags = Regex("""\([^)]*\)|\[[^]]*]""")
    private val discTag = Regex("""\(\s*(?:disc|disk|cd)\s*(\d+)[^)]*\)""", RegexOption.IGNORE_CASE)

    // ---- DuckStation -------------------------------------------------------------------------

    /** The card [fileName] is, or null when it is no memory card. */
    fun memoryCard(fileName: String): MemoryCard? {
        if (fileName.startsWith(".")) return null
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in cardExtensions) return null
        val stem = fileName.substringBeforeLast('.').trim()
        if (stem.isEmpty()) return null
        sharedCard.matchEntire(stem)?.let { return MemoryCard.Shared(it.groupValues[1].toInt()) }
        val slotted = slotSuffix.matchEntire(stem)
        val title = (slotted?.groupValues?.get(1) ?: stem).trim()
        if (title.isEmpty()) return null
        val serial = ps1Serials(title).singleOrNull()?.takeIf { normalize(it) == normalize(title) }
        return MemoryCard.PerGame(title, serial, slotted?.groupValues?.get(2)?.toInt() ?: 1)
    }

    fun isSharedCard(fileName: String): Boolean = memoryCard(fileName) is MemoryCard.Shared

    /**
     * Whether the sync path [path] is a memory card or a PPSSPP save folder: one file that holds
     * several saves, so its removal from a device is never copied to the server.
     */
    fun holdsSeveralSaves(path: String): Boolean = path.substringAfterLast('/').let { isPackName(it) || memoryCard(it) != null }

    /** Every PlayStation serial in [text], as DuckStation writes them (`SLUS-00594`). */
    fun ps1Serials(text: String): List<String> =
        ps1Serial.findAll(text).map { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}${it.groupValues[3]}" }.distinct().toList()

    // ---- PPSSPP --------------------------------------------------------------------------------

    /** Every PSP product id in [text] (`ULUS10041`, also written `ULUS-10041`). */
    fun productIds(text: String): List<String> =
        pspId.findAll(text).map { (it.groupValues[1] + it.groupValues[2]).uppercase() }.distinct().toList()

    /** The game's product id at the start of a SAVEDATA folder name (`ULUS10041DATA00` → `ULUS10041`). */
    fun folderProductId(folder: String): String? = pspFolder.find(folder.uppercase())?.let { it.groupValues[1] + it.groupValues[2] }

    fun packName(folder: String): String = folder + PACK_SUFFIX

    fun isPackName(fileName: String): Boolean =
        fileName.length > PACK_SUFFIX.length && fileName.endsWith(PACK_SUFFIX, ignoreCase = true)

    /** The save folder a pack stands for (`ULUS10041DATA00.savedata.zip` → `ULUS10041DATA00`). */
    fun folderOfPack(fileName: String): String? =
        if (isPackName(fileName)) fileName.dropLast(PACK_SUFFIX.length).takeIf { it.isNotBlank() && '/' !in it } else null

    // ---- Matching ------------------------------------------------------------------------------

    /** What to ask RomM's search for, tried in turn (see [SaveSyncPlanner.searchTerms]). */
    fun searchTerms(local: LocalSaveFile): List<String> = when (local.layout) {
        SaveFolderLayout.FILES -> SaveSyncPlanner.searchTerms(local.name)
        SaveFolderLayout.MEMCARDS -> when (val card = memoryCard(local.name)) {
            is MemoryCard.PerGame -> card.serial?.let { s -> listOf(s, s.replace('-', ' '), s.replace("-", "")) }
                ?: SaveSyncPlanner.searchTerms("${card.title}.mcd")
            // A shared card belongs to the game the user chose for it; nothing to search.
            else -> emptyList()
        }
        SaveFolderLayout.SAVEDATA -> folderOfPack(local.name)?.let(::folderProductId)
            ?.let { id -> listOf(id, id.take(4) + "-" + id.drop(4), id.take(4) + " " + id.drop(4)) }.orEmpty()
    }.filter { it.isNotBlank() }.distinct()

    /** The ROM [local] is the save of (see [SaveSyncPlanner.matchRom] for ordinary files). */
    fun matchRom(local: LocalSaveFile, candidates: List<RomCandidate>): RomMatch = when (local.layout) {
        SaveFolderLayout.FILES -> SaveSyncPlanner.matchRom(local, candidates)
        SaveFolderLayout.MEMCARDS -> when (val card = memoryCard(local.name)) {
            is MemoryCard.PerGame -> matchCard(card, local, candidates)
            else -> RomMatch.NotFound
        }
        SaveFolderLayout.SAVEDATA -> folderOfPack(local.name)?.let(::folderProductId)
            ?.let { id -> pick(candidates.filter { id in productIds(it.fsName) || id in productIds(it.name) }, local) }
            ?: RomMatch.NotFound
    }

    /**
     * Whether [local] is a save of the game in [gameFileName] (the library's file name), from the
     * name alone: a card named after its title or serial, a save folder starting with its product
     * id. Shared cards belong to no single game. Ordinary files are answered by [CloudSaves.belongsToGame].
     */
    fun belongsToGame(local: LocalSaveFile, gameFileName: String): Boolean = when (local.layout) {
        SaveFolderLayout.FILES -> CloudSaves.belongsToGame(local.name, CloudSaves.gameStem(gameFileName))
        SaveFolderLayout.MEMCARDS -> when (val card = memoryCard(local.name)) {
            is MemoryCard.PerGame -> if (card.serial != null) card.serial in ps1Serials(gameFileName) else {
                val wanted = normalize(card.title)
                wanted.isNotEmpty() && (normalize(SaveSyncPlanner.romStem(gameFileName)) == wanted || normalize(cleanTitle(gameFileName)) == wanted)
            }
            else -> false
        }
        SaveFolderLayout.SAVEDATA -> folderOfPack(local.name)?.let(::folderProductId)?.let { it in productIds(gameFileName) } == true
    }

    /**
     * DuckStation names a per-game card after the serial, the file title (the ROM's name without
     * extension) or the game's title, depending on its settings. The serial must appear in the ROM's
     * name; the file title must equal the ROM's; the game title must equal the ROM's title without
     * region/version tags or RomM's own name for it.
     */
    private fun matchCard(card: MemoryCard.PerGame, local: LocalSaveFile, candidates: List<RomCandidate>): RomMatch {
        card.serial?.let { serial -> return pick(candidates.filter { serial in ps1Serials(it.fsName) || serial in ps1Serials(it.name) }, local) }
        val wanted = normalize(card.title)
        if (wanted.isEmpty()) return RomMatch.NotFound
        val byFile = candidates.filter { normalize(SaveSyncPlanner.romStem(it.fsName)) == wanted }
        if (byFile.isNotEmpty()) return pick(byFile, local)
        return pick(candidates.filter { normalize(cleanTitle(it.fsName)) == wanted || (it.name.isNotBlank() && normalize(cleanTitle(it.name)) == wanted) }, local)
    }

    /**
     * One game out of [hits]: ROMs on another platform than the folder's are dropped; the discs of
     * one game (`(Disc 1)`, `(Disc 2)`… with otherwise the same name) share one card, which goes
     * with the first disc. Anything else with more than one game left is ambiguous.
     */
    private fun pick(hits: List<RomCandidate>, local: LocalSaveFile): RomMatch {
        val hints = local.platformHints
        val pool = hits.distinctBy { it.id }.filter { rom ->
            val platforms = listOf(rom.platformSlug, rom.platformFsSlug).map(ConsoleFolderAliases::normalize).filter { it.isNotEmpty() }
            hints.isEmpty() || platforms.isEmpty() || platforms.any { it in hints }
        }
        if (pool.isEmpty()) return RomMatch.NotFound
        if (pool.size == 1) return RomMatch.Found(pool.single().id)
        val discs = pool.map { discNumber(it.fsName) }
        val sameGame = pool.map { normalize(SaveSyncPlanner.romStem(it.fsName).replace(discTag, "")) }.distinct().size == 1
        if (sameGame && discs.none { it == null } && discs.distinct().size == discs.size) {
            return RomMatch.Found(pool.minBy { discNumber(it.fsName) ?: Int.MAX_VALUE }.id)
        }
        return RomMatch.Ambiguous
    }

    private fun discNumber(fsName: String): Int? = discTag.find(fsName)?.groupValues?.get(1)?.toIntOrNull()

    /** `Game (USA) (Disc 1).chd` → `Game`. */
    fun cleanTitle(name: String): String = SaveSyncPlanner.romStem(name).replace(tags, " ").trim()

    /** Letters and digits only, lower case: DuckStation replaces characters a file name cannot hold. */
    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The folder inside the picked one that really holds the saves, when the user picked its parent:
     * DuckStation's user folder (holding `memcards`), PPSSPP's `PSP` folder or its storage root
     * (holding `PSP/SAVEDATA`). [dirsAt] lists the folders at a path below the picked one.
     */
    fun innerPath(layout: SaveFolderLayout, dirsAt: (String) -> Set<String>?): String? {
        fun child(path: String, name: String): String? = dirsAt(path)?.firstOrNull { it.equals(name, ignoreCase = true) }
        fun has(path: String, names: Set<String>): Boolean = dirsAt(path).orEmpty().any { it.lowercase() in names }
        return when (layout) {
            SaveFolderLayout.FILES -> ""
            // The parent was picked but the emulator has not made the folder yet: there is nothing
            // to sync, and the picked folder itself is not where the emulator will look (null).
            SaveFolderLayout.MEMCARDS -> child("", "memcards") ?: if (has("", duckStationUserFolders)) null else ""
            SaveFolderLayout.SAVEDATA -> child("", "SAVEDATA")
                ?: child("", "PSP")?.let { psp -> child(psp, "SAVEDATA")?.let { "$psp/$it" } ?: return null }
                ?: if (has("", ppssppPspFolders)) null else ""
        }
    }

    /** Folders next to `memcards` in DuckStation's user folder. */
    private val duckStationUserFolders = setOf("savestates", "bios", "cache", "covers", "gamesettings", "inputprofiles", "screenshots", "shaders", "textures", "cheats")
    /** Folders next to `SAVEDATA` in PPSSPP's `PSP` folder. */
    private val ppssppPspFolders = setOf("system", "ppsspp_state", "game", "textures", "cheats", "plugins", "texdump")
}

/**
 * A save folder as one zip, written the same way on every device: files sorted by path, stored
 * uncompressed, fixed dates and UTF-8 names. The same folder contents give the same bytes (and
 * so the same hash RomM compares), whatever the device's zlib version or time zone.
 */
object SavePack {
    const val MAX_ENTRIES = 512

    fun pack(files: List<Pair<String, ByteArray>>): ByteArray {
        require(files.size <= MAX_ENTRIES) { "Too many files in one save folder" }
        val entries = files.map { (path, bytes) -> ArchivePlan.safeRelativePath(path) to bytes }.sortedBy { it.first }
        require(entries.map { ArchivePlan.portableKey(it.first) }.distinct().size == entries.size) { "Two files of the save folder have the same name" }
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for ((path, data) in entries) {
            val name = path.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val offset = out.size()
            out.write(le(30) { putInt(0x04034b50); putShort(10); putShort(0x0800); putShort(0); putShort(0); putShort(DOS_DATE); putInt(crc); putInt(data.size); putInt(data.size); putShort(name.size.toShort()); putShort(0) })
            out.write(name)
            out.write(data)
            central.write(le(46) {
                putInt(0x02014b50); putShort(20); putShort(10); putShort(0x0800); putShort(0); putShort(0); putShort(DOS_DATE)
                putInt(crc); putInt(data.size); putInt(data.size); putShort(name.size.toShort()); putShort(0); putShort(0); putShort(0); putShort(0); putInt(0); putInt(offset)
            })
            central.write(name)
        }
        val centralOffset = out.size()
        out.write(central.toByteArray())
        out.write(le(22) { putInt(0x06054b50); putShort(0); putShort(0); putShort(entries.size.toShort()); putShort(entries.size.toShort()); putInt(central.size()); putInt(centralOffset); putShort(0) })
        return out.toByteArray()
    }

    /** The size [pack] gives for files of these paths and sizes, known before anything is read. */
    fun packedSize(files: List<Pair<String, Long>>): Long = 22L + files.sumOf { (path, size) ->
        val name = ArchivePlan.safeRelativePath(path).toByteArray(Charsets.UTF_8).size
        30L + name + size + 46L + name
    }

    /** The files of a pack (any ordinary zip), paths checked; throws for anything unsafe or larger than [maxBytes] in all. */
    fun unpack(bytes: ByteArray, maxBytes: Long): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (out.size >= MAX_ENTRIES) throw IOException("Too many files in the save")
                val path = ArchivePlan.safeRelativePath(entry.name)
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("Save is too large")
                    buffer.write(chunk, 0, n)
                }
                out += path to buffer.toByteArray()
            }
        }
        ArchivePlan.check(out.mapIndexed { i, (path, data) -> ArchivePlan.Entry(i, path, size = data.size.toLong()) })
        return out
    }

    /** 1980-01-01, the first day a zip can hold; the time field stays 00:00. */
    private const val DOS_DATE: Short = 0x21

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
}
