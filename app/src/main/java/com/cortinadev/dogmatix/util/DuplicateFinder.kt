package com.cortinadev.dogmatix.util

import java.text.Normalizer

/**
 * A file found on disk by the library scan.
 * [scope] says which console it belongs to: the console id when the folder could be matched,
 * otherwise `folder:<name>` for an unknown top-level folder or `""` for loose files in the root.
 */
data class DiskFile(
    val scope: String,
    val consoleId: String?,
    /** Readable path of the folder holding the file, relative to where the scan started (".../ROMs/gba/USA"). */
    val folder: String,
    val name: String,
    val size: Long,
    /** SAF document URI, kept as a string so this model stays free of Android types. */
    val uri: String,
    /**
     * Identity of the folder holding the file. Unlike [folder] (a display string) it keeps the
     * storage volume, so `ROMs/psx` on internal storage and on the SD card stay apart.
     */
    val dirId: String = folder,
    /** Identity of the file itself; the same physical file reached twice has the same id. */
    val fileId: String = uri,
    /** The file sits in a sub-folder below its console folder, so the folder may be one game. */
    val inSubfolder: Boolean = false,
    /** How many folders below its console folder the file sits (0 = directly in it). */
    val level: Int = if (inSubfolder) 1 else 0,
    /** SAF document URI of the holding folder (to remove a game folder once it is empty). */
    val dirUri: String = "",
    /** Identity of the walk that found the file (download folder or one per-console folder). */
    val rootId: String = "",
    /** Last change (epoch millis) as the storage provider reports it; 0 when unknown. */
    val lastModified: Long = 0L
)

/**
 * One game on disk: the files in the same folder that share a base name and belong to one image
 * ("Game.cue" + "Game.bin" + "Game (Track 2).bin"), or a whole per-game folder holding one disc
 * image with generic names (`Crazy Taxi/disc.gdi` + `track01.bin` …), so a game always counts —
 * and is deleted — as one unit.
 */
data class GameEntry(
    val scope: String,
    val consoleId: String?,
    val folder: String,
    /** Base name without extension or track suffix (or the folder name of a game folder), as shown to the user. */
    val baseName: String,
    val files: List<DiskFile>,
    /** The entry is a whole per-game folder (see [DuplicateFinder.entries]); it is removed once emptied. */
    val isFolderGame: Boolean = false,
    /**
     * False when nothing reliable says which game this is (a program folder, files in an
     * unknown format, add-ons, BIOS, generic names…): it still counts as a file on disk but is
     * never offered as a duplicate.
     */
    val comparable: Boolean = true
) {
    val size: Long get() = files.sumOf { it.size }
    /** Stable, unique id (its files are never part of another entry). */
    val id: String get() = files.first().fileId
}

data class DuplicateGroup(
    val scope: String,
    val consoleId: String?,
    /** Cleaned title shared by the entries ("Chrono Trigger"). */
    val title: String,
    val kind: Kind,
    /** Largest first, so the copy most likely worth keeping leads the list. */
    val entries: List<GameEntry>
) {
    enum class Kind {
        /** The same file (same name and size) exists in more than one folder of the same console. */
        IDENTICAL,
        /** Different releases or formats of the same game: regions, revisions, translations, zip vs rom. */
        VARIANT
    }

    /** Space freed by keeping only the largest entry. */
    val reclaimable: Long get() = entries.sumOf { it.size } - (entries.maxOfOrNull { it.size } ?: 0L)
}

/**
 * Finds games that are on disk more than once. Pure logic over [DiskFile]s so it can be unit
 * tested; the disk walk lives in `LibraryScanService`.
 *
 * Two entries are duplicates when, within the same console, their titles match after
 * [GameTitleCleaner] drops tags, regions and versions. Because the result drives a delete
 * button, the rules are structural and err on the side of reporting less:
 *  - only files in a known game format (ROM, disc image, archive) are ever compared; anything
 *    else — documents, saves in unknown formats, engine data — is counted but never offered;
 *  - disc, side, tape and part numbers are part of the key, so the parts of one game are never
 *    compared against each other;
 *  - below the console folder only a recognised disc-image folder or a single titled game is
 *    compared; program folders and deeper levels never are;
 *  - updates, DLC, BIOS files and generic names are never offered;
 *  - outside console folders a container format (zip, chd, iso…) says nothing about the system,
 *    so it is only offered when name and size are identical;
 *  - copies found through different storage routes that cannot be told apart are not offered.
 */
object DuplicateFinder {

    /** Files that are never games: shortcuts, artwork, notes, saves, states, patches, our own temp files. */
    private val ignoredExtensions = setOf(
        "dgmtx", "tmp", "part", "txt", "nfo", "md", "xml", "json", "html", "url", "lnk", "ini", "cfg", "db",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "pdf",
        "sav", "srm", "state", "sta", "mcr", "mcd", "rtc", "eep", "sra", "fla", "mpk", "dsv", "auto", "brm",
        "ips", "bps", "ups", "xdelta", "ppf", "cht"
    )
    /** Numbered save-state slots: RetroArch `.state1`, mGBA `.ss1`, others `.st1`. */
    private val numberedState = Regex("^(state|ss|st)\\d+$")

    /** Parts of one disc image (sheet, data and audio tracks); in unknown folders they share a format. */
    private val discImageParts = setOf(
        "cue", "bin", "img", "sub", "ccd", "mds", "mdf", "gdi", "raw", "toc", "sbi", "m3u", "iso", "ecm"
    )
    /** CD audio tracks: they ride along with their sheet but are never a game on their own. */
    private val audioTracks = setOf("wav", "mp3", "ogg", "flac", "ape")
    /** What a per-game folder holding one disc image may contain. */
    private val imageSetParts = setOf("cue", "bin", "img", "sub", "ccd", "mds", "mdf", "gdi", "raw", "toc", "sbi", "m3u", "pbp")
    /** The file that describes a disc image; a folder with several of them holds several images. */
    private val imageSheets = setOf("gdi", "cue", "ccd", "mds", "toc", "pbp")
    /** Self-contained copies that may sit next to the same game unpacked. */
    private val archiveFormats = setOf("zip", "7z", "rar")
    /** Formats that do not say which system a game is for. */
    private val containerFormats = archiveFormats + setOf(
        "chd", "iso", "cso", "ciso", "zso", "jso", "dax", "rvz", "wbfs", "wia", "gcz", "pbp", "nsz", "xcz", "disc"
    )
    /** Split archive volumes (`.001`, `.z01`, `.r00`): parts of one archive, never copies. */
    private val splitVolume = Regex("^(\\d{3}|z\\d{2}|r\\d{2})$")
    /** Parts of a program (PC/DOS/ScummVM games): such a folder is one thing whose files mean nothing alone. */
    private val programParts = setOf("exe", "dll", "bat", "com", "so", "dylib", "elf", "sh", "app", "jar", "pak", "dat", "sou", "tok", "map")

    /** The formats of games, discs and archives; everything else is never compared. */
    private val gameFormats = discImageParts + archiveFormats + audioTracks + setOf(
        // Discs and containers
        "chd", "cso", "ciso", "zso", "jso", "dax", "pbp", "cdi", "nrg", "xiso", "rvz", "wbfs", "wia", "gcz", "gcm",
        // Nintendo
        "nes", "fds", "unf", "unif", "sfc", "smc", "swc", "fig", "n64", "z64", "v64", "ndd", "gb", "gbc", "gba", "nds", "dsi",
        "3ds", "cci", "cia", "cxi", "vb", "nsp", "xci", "nsz", "xcz", "wad", "wud", "wux", "rpx",
        // Sega
        "md", "gen", "smd", "sms", "gg", "sg", "32x", "bs",
        // NEC, SNK, Atari, Bandai, other consoles
        "pce", "sgx", "ngp", "ngc", "a26", "a52", "a78", "j64", "jag", "lnx", "ws", "wsc", "col", "int", "vec", "min", "pkg", "vpk",
        // Computers
        "d64", "t64", "tap", "tzx", "crt", "prg", "adf", "dms", "ipf", "dsk", "hdf", "atr", "xex", "car", "cas", "cdt",
        "sna", "z80", "stx", "msa", "d88", "fdi", "hdi", "rom", "st"
    )
    private fun isGameFormat(ext: String) = ext in gameFormats

    /** Names that mark the files of a per-game folder holding one disc image. */
    private val folderGameMarker = Regex("(?i)^(track\\s*\\d+|disc\\s*\\d*|disk\\s*\\d*|cd\\s*\\d*|game|rom|eboot|image)$")
    private val trackStem = Regex("(?i)^track\\s*\\d+$")
    /** Names (of files or folders) that say nothing about which game it is. */
    private val genericName = Regex(
        "(?i)^(track\\s*\\d+|disc\\s*\\d*|disk\\s*\\d*|cd\\s*\\d*|dvd\\s*\\d*|game|games|rom|roms|eboot|image|iso|gdi|cue|chd|files|data\\d*|" +
            "resource(\\.\\w+)?|unityplayer|steam_api(64)?|setup|install|autorun|readme|license|changelog|notes|default|index|main|start|launcher|" +
            "usa|us|europe|eu|eur|japan|jp|jpn|world|pal|ntsc|ntsc-u|ntsc-j|new folder)$"
    )
    /** Updates, DLC and similar add-ons of a base game: never a copy of it. */
    private val addOnTag = Regex("(?i)[\\[(]\\s*(upd(ate)?|dlc|patch|add-?on|season pass)\\b")
    /** Switch / 3DS title ids: the base game ends in 000, updates and DLC do not. */
    private val titleId = Regex("(?i)\\[0100[0-9a-f]{9}([0-9a-f]{3})]")
    private val biosStem = Regex("(?i)^(scph\\d+\\w*|.*bios.*|dc_(boot|flash)|syscard\\d*|firmware)$")
    private val biosFolders = setOf("bios", "system", "firmware")
    private val tagged = Regex("[(\\[]")

    private val trackSuffix = Regex("(?i)\\s*\\(track\\s*\\d+\\)\\s*$")
    private val discTag = Regex(
        "(?i)\\b(disc|disk|cd|dvd|gd|side|tape|part|file)\\s*(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|[ivx]+|[a-z])(?:\\s*of\\s*\\w+)?\\b"
    )
    private val spelledNumbers = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val romanNumbers = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")

    /** Name-only check, for files whose console is not known. `.md` (Markdown) is not counted here. */
    fun isGameFile(name: String): Boolean {
        if (name.startsWith(".")) return false
        val ext = extension(name)
        return ext !in ignoredExtensions && !numberedState.matches(ext)
    }

    /** As [isGameFile], but `.md` counts as a Mega Drive ROM inside a console folder (not for README.md). */
    fun isGameFile(file: DiskFile): Boolean =
        isGameFile(file.name) ||
            (extension(file.name) == "md" && file.consoleId != null && !genericName.matches(stem(file.name).trim()))

    /** Groups [files] into games (see [GameEntry]); files that are not games are dropped. */
    fun entries(files: List<DiskFile>): List<GameEntry> =
        files.filter { isGameFile(it) }
            .distinctBy { it.fileId }
            .groupBy { it.scope to it.dirId }
            .flatMap { (_, dirFiles) -> entriesOfFolder(dirFiles) }

    /**
     * An entry that is one recognisable game: a known game format with a real title, no update or
     * DLC, no BIOS, not a program folder. What the duplicate finder compares and the "Free up
     * space" tool offers.
     */
    fun isPlainGame(entry: GameEntry): Boolean =
        entry.comparable && !(!entry.isFolderGame && isGeneric(entry.baseName)) && !isAddOn(entry.baseName) && !isBios(entry)

    fun find(files: List<DiskFile>): List<DuplicateGroup> =
        entries(files)
            .filter { isPlainGame(it) }
            // A playlist only points at discs that are counted on their own.
            .filterNot { e -> e.files.all { extension(it.name) == "m3u" } }
            .groupBy { it.comparisonScope() to titleKey(it.baseName) }
            .filter { (key, group) -> key.second.isNotEmpty() && group.size > 1 }
            .mapNotNull { (_, group) ->
                val identical = group.groupBy { it.baseName.lowercase() to it.size }.filterValues { it.size > 1 }
                // Outside console folders a container format says nothing about the system.
                if (group.first().consoleId == null &&
                    formatKey(group.first().files.maxBy { it.size }.name) in containerFormats && identical.isEmpty()
                ) return@mapNotNull null
                // Copies found through different scan routes that cannot be proven to be different
                // files (a storage provider without file paths) might be one file seen twice.
                val roots = group.flatMap { e -> e.files.map { it.rootId } }.toSet()
                if (roots.size > 1 && group.any { e -> e.files.any { it.fileId.isPathless() } }) return@mapNotNull null
                DuplicateGroup(
                    scope = group.first().scope,
                    consoleId = group.first().consoleId,
                    title = GameTitleCleaner.clean(group.first().baseName + ".x").ifBlank { group.first().baseName },
                    kind = if (identical.isNotEmpty()) DuplicateGroup.Kind.IDENTICAL else DuplicateGroup.Kind.VARIANT,
                    entries = group.sortedByDescending { it.size }
                )
            }
            .sortedWith(compareByDescending<DuplicateGroup> { it.reclaimable }.thenBy { it.title.lowercase() })

    /** Comparison key: cleaned, accent-free title (any script) plus every disc / side / tape / part tag. */
    fun titleKey(baseName: String): String {
        val parts = discTag.findAll(baseName).joinToString("") { m ->
            val kind = when (m.groupValues[1].lowercase()) {
                "side" -> "s"
                "tape" -> "t"
                "part" -> "p"
                "file" -> "f"
                else -> "d"
            }
            kind + discNumber(m.groupValues[2])
        }
        val title = normalize(GameTitleCleaner.clean("$baseName.x"))
        if (title.isEmpty()) return ""
        return if (parts.isEmpty()) title else "$title#$parts"
    }

    /** "2", "Two" and "II" are the same disc. */
    private fun discNumber(value: String): String {
        val v = value.lowercase()
        spelledNumbers.indexOf(v).takeIf { it >= 0 }?.let { return (it + 1).toString() }
        romanNumbers.indexOf(v).takeIf { it >= 0 && v.length > 1 }?.let { return (it + 1).toString() }
        return v.trimStart('0').ifEmpty { "0" }
    }

    /**
     * The games in one folder. Files sharing a base name are one game ("Game.cue" + "Game.iso"
     * + "Game (Track 2).wav"); only a self-contained archive next to them counts as a copy of its
     * own, and a file in a format that is not a game format is an entry of its own that is never
     * compared. Sub-folders below the console folder get stricter rules, because there one folder
     * is often one game whose files have generic names:
     *  - a folder holding a program (exe, dll, numbered data files…) is one unit, never compared;
     *  - a folder holding exactly one disc image (`disc.gdi` + `track01.bin`…) is one game named
     *    after the folder (or after its one titled file);
     *  - deeper than one level nothing else is compared;
     *  - otherwise the games in it are compared by their own file names.
     */
    private fun entriesOfFolder(dirFiles: List<DiskFile>): List<GameEntry> {
        val first = dirFiles.first()
        val folderName = first.folder.substringAfterLast('/').trim()
        if (first.depth >= 1) {
            if (dirFiles.any { isProgramPart(it.name) }) return listOf(folderEntry(dirFiles, folderName, comparable = false))
            if (isImageSet(dirFiles)) return listOf(imageSetEntry(dirFiles, folderName))
        }
        val (known, other) = dirFiles.partition { isGameFormat(extension(it.name)) }
        val entries = known
            .groupBy { entryKey(it.name) + if (first.consoleId == null) "|" + formatKey(it.name) else "" }
            .flatMap { (_, sameStem) -> splitArchives(sameStem) }
            .map { group ->
                // CD audio alone is not a game; deeper than one level nothing is compared.
                val comparable = first.depth <= 1 && group.any { extension(it.name) !in audioTracks }
                // A CHD in a folder of its own name belongs to the archive next to that folder (MAME).
                val mameLayout = first.depth >= 1 && group.all { extension(it.name) == "chd" } &&
                    stem(group.first().name).equals(folderName, ignoreCase = true)
                fileEntry(group, comparable = comparable && !mameLayout)
            }
        return entries + other.map { fileEntry(listOf(it), comparable = false) }
    }

    /** One image set: a single sheet (or numbered discs) whose other files are its tracks. */
    private fun isImageSet(dirFiles: List<DiskFile>): Boolean {
        if (!dirFiles.all { extension(it.name) in imageSetParts }) return false
        val sheets = dirFiles.filter { extension(it.name) in imageSheets }
        if (sheets.size > 1 && !sheets.all { discTag.containsMatchIn(stem(it.name)) }) return false
        val stems = dirFiles.map { stem(it.name).trim() }
        val generic = stems.filter { folderGameMarker.matches(it) }
        if (generic.isEmpty()) return false
        val titled = stems.filterNot { folderGameMarker.matches(it) }.map { it.lowercase() }.distinct()
        return when (titled.size) {
            0 -> true
            1 -> generic.all { trackStem.matches(it) }   // "Crazy Taxi.gdi" + "track01.bin"…
            else -> false
        }
    }

    /**
     * A folder with one disc image is one game. It is named after its one titled file, or after
     * the folder; with generic file names the folder name must carry a tag — "Crazy Taxi (USA)" —
     * before it is trusted as a title (a plain "Favorites" or "Disc Image" folder says nothing).
     */
    private fun imageSetEntry(dirFiles: List<DiskFile>, folderName: String): GameEntry {
        val titled = dirFiles.map { stem(it.name).trim() }.filterNot { folderGameMarker.matches(it) }.distinctBy { it.lowercase() }
        val name = titled.singleOrNull() ?: folderName
        val trusted = !isGeneric(name) && (titled.isNotEmpty() || tagged.containsMatchIn(name))
        return folderEntry(dirFiles, name, comparable = trusted && dirFiles.first().depth == 1)
    }

    /** Archives become copies of their own; everything else with the same base name is one game. */
    private fun splitArchives(sameStem: List<DiskFile>): List<List<DiskFile>> {
        if (sameStem.size < 2 || sameStem.any { splitVolume.matches(extension(it.name)) }) return listOf(sameStem)
        val (archives, rest) = sameStem.partition { extension(it.name) in archiveFormats }
        return archives.map { listOf(it) } + listOf(rest).filter { it.isNotEmpty() }
    }

    private fun isProgramPart(name: String): Boolean {
        val ext = extension(name)
        return ext.isEmpty() || ext in programParts || ext.all { it.isDigit() }
    }

    private fun isGeneric(name: String): Boolean = name.isBlank() || genericName.matches(name.trim())

    private fun isAddOn(name: String): Boolean =
        addOnTag.containsMatchIn(name) || titleId.find(name)?.groupValues?.get(1)?.let { it != "000" } == true

    private fun isBios(entry: GameEntry): Boolean =
        entry.folder.split('/').any { it.trim().lowercase() in biosFolders } || biosStem.matches(entry.baseName.trim())

    /** Levels below the console folder, also for a file only flagged as being in a sub-folder. */
    private val DiskFile.depth: Int get() = if (inSubfolder) maxOf(level, 1) else level

    /** A file id without a file-system path (`provider|document-id`): it cannot be matched against other routes. */
    private fun String.isPathless(): Boolean = !startsWith("/") && contains('|')

    private fun folderEntry(dirFiles: List<DiskFile>, name: String, comparable: Boolean): GameEntry {
        val first = dirFiles.first()
        return GameEntry(
            scope = first.scope,
            consoleId = first.consoleId,
            folder = first.folder,
            baseName = name.ifBlank { stem(first.name) },
            files = dirFiles.sortedBy { it.name.lowercase() },
            isFolderGame = true,
            comparable = comparable
        )
    }

    private fun fileEntry(group: List<DiskFile>, comparable: Boolean): GameEntry {
        val first = group.first()
        return GameEntry(
            scope = first.scope,
            consoleId = first.consoleId,
            folder = first.folder,
            baseName = stem(group.minBy { it.name.length }.name),
            files = group.sortedBy { it.name.lowercase() },
            comparable = comparable
        )
    }

    /**
     * Files outside any console folder (loose in the root, unknown folders) may belong to any
     * system: there the format stands in for the console, so "Tetris.gb" and "Tetris.nes"
     * are not reported against each other.
     */
    private fun GameEntry.comparisonScope(): String =
        if (consoleId != null) scope
        else scope + "|" + formatKey(files.maxBy { it.size }.name)

    /** Disc image parts share one format; every other extension is its own (so `.gb` never joins `.nes`). */
    private fun formatKey(fileName: String): String = extension(fileName).let { if (it in discImageParts || it in audioTracks) "disc" else it }

    private fun entryKey(fileName: String): String = stem(fileName).lowercase()

    /** Base name without extension and without a "(Track N)" suffix. */
    private fun stem(fileName: String): String = baseName(fileName).replace(trackSuffix, "")

    private fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    private fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
