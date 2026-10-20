package com.tufein.valkyr.util

/*
 * Library repair (3.0.0): finds what a handheld library actually runs into (empty or cut-off game
 * files, disc sets with a missing track or a dead playlist, leftovers of interrupted downloads,
 * stale "on this device" marks…) and describes a fix for each. Pure over a listing of the folders,
 * so every rule is tested with fake files; the service gathers the listing and runs the fixes.
 */

/**
 * A file a finding points at. [folder] is the readable folder for the screen; anything shared or
 * exported carries [name] only. [uri] / [dirUri] are SAF document URIs (strings, no Android types).
 */
data class RepairFile(
    val name: String,
    val size: Long,
    val uri: String,
    val dirUri: String,
    val folder: String,
    val consoleId: String?,
    val lastModified: Long = 0L,
    /** Identity of the holding folder (see [DiskFile.dirId]). */
    val dirId: String = folder
) {
    companion object {
        fun of(file: DiskFile) = RepairFile(file.name, file.size, file.uri, file.dirUri, file.folder, file.consoleId, file.lastModified, file.dirId)
    }
}

/** A library row a fix can download again. [fileName] is the row's own (maybe URL-encoded) name. */
data class LibraryRef(val rowId: Long, val consoleId: String, val fileName: String, val size: Long)

/** How much a finding matters: it stops a game, it is worth a look, or it is only tidying up. */
enum class RepairSeverity { HIGH, MEDIUM, LOW }

enum class RepairKind(val severity: RepairSeverity) {
    /** A game file of 0 bytes. */
    EMPTY_FILE(RepairSeverity.HIGH),
    /** A `.cue` / `.gdi` names tracks that are not in its folder. */
    MISSING_TRACKS(RepairSeverity.HIGH),
    /** A `.m3u` names some discs that are gone (others are still there). */
    MISSING_DISCS(RepairSeverity.HIGH),
    /** A `.m3u` none of whose discs are there. */
    DEAD_PLAYLIST(RepairSeverity.MEDIUM),
    /** A sheet that names nothing or cannot be read. */
    UNREADABLE_SHEET(RepairSeverity.MEDIUM),
    /** Much smaller than the library row of the same name says. */
    TRUNCATED(RepairSeverity.MEDIUM),
    /** Disc tracks (`Game (Track 2).bin`, `track02.bin`) that no sheet in the folder lists. */
    ORPHAN_TRACKS(RepairSeverity.MEDIUM),
    /** A `.dogmatix-…part` staging file no download owns any more. */
    LEFTOVER_PART(RepairSeverity.LOW),
    /** A partial file of a browser or another download app (`.crdownload`, `.part`…). */
    FOREIGN_PART(RepairSeverity.LOW),
    /** Several playlists in one folder that list exactly the same discs. */
    DUPLICATE_PLAYLIST(RepairSeverity.LOW),
    /** A game with several discs and no playlist ([PlaylistPlanner]). */
    NO_PLAYLIST(RepairSeverity.LOW),
    /** A cartridge file whose type belongs to another console than its folder's. Report only. */
    WRONG_CONSOLE(RepairSeverity.LOW),
    /** A top-level folder with games that matches no console. Report only. */
    UNMAPPED_FOLDER(RepairSeverity.LOW),
    /** The "on this device" marks and the disk disagree. */
    INDEX_STALE(RepairSeverity.LOW)
}

enum class RepairFixKind { TRASH, CREATE_PLAYLIST, REWRITE_PLAYLIST, DOWNLOAD_AGAIN, RESCAN }

/**
 * One way to fix a finding. [trash] is moved to Trash and recovery first (restorable); then the
 * [playlist] is written or the [download] queued. Nothing runs without the user choosing it.
 */
data class RepairFix(
    val kind: RepairFixKind,
    val trash: List<RepairFile> = emptyList(),
    val playlist: PlaylistPlan? = null,
    val download: LibraryRef? = null
) {
    /** Removes nothing, replaces nothing and downloads nothing: what "Fix safe items" may run. */
    val safe: Boolean
        get() = trash.isEmpty() && download == null && (kind == RepairFixKind.CREATE_PLAYLIST || kind == RepairFixKind.RESCAN)
}

/**
 * One problem. [subject] is what the title names (a file name, or a folder name for
 * [RepairKind.UNMAPPED_FOLDER]); [files] the affected files; [missing] the names a sheet lists
 * that are gone (or, for [RepairKind.INDEX_STALE], a few example names). [a] / [b] carry numbers
 * the sentence needs (sizes, counts); [text] a console id the sentence names.
 */
data class RepairFinding(
    val id: String,
    val kind: RepairKind,
    val consoleId: String?,
    val subject: String,
    val files: List<RepairFile> = emptyList(),
    val missing: List<String> = emptyList(),
    val fixes: List<RepairFix> = emptyList(),
    val a: Long = 0,
    val b: Long = 0,
    val text: String = ""
) {
    val severity: RepairSeverity get() = kind.severity
}

/**
 * What the service read. [sheets]: texts of the sheets it read, by [DiskFile.fileId] (null =
 * unreadable; a sheet missing from the map was not read and is skipped). [hidden]: the app's own
 * `.dogmatix-` files found by the repair walk. [ownedStaging]: staging names that a download in
 * the queue still owns. [indexKeys]: the library index's `scope|name` keys, [diskNames] every
 * file name on disk in lower case (null = the walk was cut short, so they are not compared).
 * [library]: rows by "consoleId|stored name in lower case". [busy]: a download or unpack is running.
 */
data class RepairInput(
    val files: List<DiskFile>,
    val sheets: Map<String, String?> = emptyMap(),
    val hidden: List<RepairFile> = emptyList(),
    val ownedStaging: Set<String> = emptySet(),
    val unmatchedFolders: List<String> = emptyList(),
    val indexKeys: Set<String>? = null,
    val diskNames: Set<String>? = null,
    val library: Map<String, LibraryRef> = emptyMap(),
    val busy: Boolean = false,
    val now: Long = System.currentTimeMillis()
)

data class RepairResult(
    val findings: List<RepairFinding>,
    /** More than [LibraryRepair.MAX_FINDINGS] were found; the rest is not listed. */
    val truncated: Boolean = false
)

object LibraryRepair {
    const val MAX_FINDINGS = 1000
    private const val HOUR = 60L * 60 * 1000
    /** A download part this fresh may belong to a download that is starting right now. */
    const val DOWNLOAD_PART_MIN_AGE = HOUR
    /** Unpack and copy stages are written in one go and renamed; a long unpack can leave one quiet for a while. */
    const val STAGE_MIN_AGE = 6 * HOUR
    /** A file is "cut off" when it is under this share of the size the library lists. */
    const val TRUNCATED_RATIO = 0.5

    private val foreignPartExtensions = setOf("crdownload", "part", "partial", "opdownload", "aria2", "!ut", "!qb")
    private val sheetExtensions = setOf("cue", "gdi", "m3u")
    private val trackFile = Regex("""(?i)^(.*?)\s*\(track\s*\d+\)\.(bin|img|raw|wav|iso)$""")
    private val genericTrack = Regex("""(?i)^track\s*\d+\.(bin|raw|img)$""")
    /** Library rows that bring a whole disc game back, best first. A sheet alone does not bring tracks. */
    private val redownloadFormats = listOf("zip", "7z", "rar", "chd")
    private val notGameFolders = setOf("bios", "system", "firmware", "saves", "save", "states", "screenshots", "media", "images", "videos", "manuals", "covers", "downloaded_images")

    /**
     * Cartridge types that say which console they are for, as console keys
     * ([ConsoleFormatter.consoleKey]) that run them. Only consoles listed here are judged.
     */
    private val cartridgeConsoles: Map<String, Set<String>> = mapOf(
        "gba" to setOf("gameboy_advance"),
        "gbc" to setOf("gameboy_color", "gameboy_advance"),
        "gb" to setOf("gameboy", "gameboy_color", "gameboy_advance"),
        "nes" to setOf("nintendo_entertainment_system"),
        "fds" to setOf("nintendo_entertainment_system"),
        "sfc" to setOf("super_nintendo_entertainment_system"),
        "smc" to setOf("super_nintendo_entertainment_system"),
        "n64" to setOf("nintendo_64"),
        "z64" to setOf("nintendo_64"),
        "v64" to setOf("nintendo_64"),
        "nds" to setOf("nintendo_ds", "nintendo_3ds"),
        "3ds" to setOf("nintendo_3ds"),
        "cia" to setOf("nintendo_3ds"),
        "sms" to setOf("master_system"),
        "gen" to setOf("genesis"),
        "smd" to setOf("genesis")
    )
    private val judgedConsoles = cartridgeConsoles.values.flatten().toSet()

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun stem(name: String) = name.substringBeforeLast('.')
    fun libraryKey(consoleId: String, storedName: String) = "$consoleId|${storedName.lowercase()}"

    /** True for the app's own staging files; recovery receipts and backups, the trash and write probes never count. */
    fun isOwnStaging(name: String): Boolean = name.endsWith(".part") &&
        listOf(".dogmatix-download-", ".dogmatix-extract-", ".dogmatix-copy-", ".dogmatix-write-").any { name.startsWith(it) }

    fun isForeignPart(name: String): Boolean = !name.startsWith(".") && ext(name) in foreignPartExtensions

    /** Old enough to be left over; an unknown time only counts while nothing is running. */
    fun stale(lastModified: Long, now: Long, minAge: Long, busy: Boolean): Boolean =
        if (lastModified > 0L) now - lastModified >= minAge else !busy

    /**
     * File names the service should look up in the library: every file of a console folder (for
     * the cut-off check) and, for each sheet or loose track, the archives that would bring the
     * whole game back. Pairs of (console id, stored name).
     */
    fun lookupNames(files: List<DiskFile>, limit: Int = 60_000): Set<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (file in files) {
            val console = file.consoleId ?: continue
            if (out.size >= limit) break
            out += console to file.name
            val e = ext(file.name)
            if (e in setOf("cue", "gdi") || trackFile.matches(file.name)) redownloadCandidates(gameStem(file)).forEach { out += console to it }
        }
        return out
    }

    /** `Game (Track 2).bin` → `Game`; a generic `track02.bin` → its folder's name; `Game.cue` → `Game`. */
    private fun gameStem(file: DiskFile): String {
        trackFile.find(file.name)?.let { return it.groupValues[1].trim() }
        if (genericTrack.matches(file.name) || (ext(file.name) == "gdi" && stem(file.name).equals("disc", ignoreCase = true)))
            return file.folder.substringAfterLast('/')
        return stem(file.name)
    }

    private fun redownloadCandidates(stem: String): List<String> = redownloadFormats.map { "$stem.$it" }

    private fun redownloadRef(consoleId: String?, stem: String, library: Map<String, LibraryRef>): LibraryRef? {
        consoleId ?: return null
        return redownloadCandidates(stem).firstNotNullOfOrNull { library[libraryKey(consoleId, it)] }
    }

    fun check(input: RepairInput): RepairResult {
        val out = ArrayList<RepairFinding>()
        val files = input.files
        val unknownSizeRoots = unknownSizeRoots(files)

        // Empty game files (0 bytes), unless the storage does not report sizes at all.
        val empty = files.filter { it.consoleId != null && it.size == 0L && it.rootId !in unknownSizeRoots && DuplicateFinder.isGameFile(it) && !isForeignPart(it.name) }
        empty.forEach { f ->
            out += RepairFinding("empty:${f.fileId}", RepairKind.EMPTY_FILE, f.consoleId, f.name, listOf(RepairFile.of(f)),
                fixes = listOf(RepairFix(RepairFixKind.TRASH, trash = listOf(RepairFile.of(f)))))
        }
        val emptyIds = empty.map { it.fileId }.toSet()

        // Sets: only the sheets that were read, and never an empty one (already listed above).
        val readable = files.filter { ext(it.name) !in sheetExtensions || (it.fileId in input.sheets && it.fileId !in emptyIds) }
        val read: (DiskFile) -> String? = { input.sheets[it.fileId] }
        val byFolder = files.groupBy { it.dirId }
        // A folder with a disc sheet that was not read (over the cap, too large, empty): its playlists
        // would see those discs as missing, so they are left alone.
        val unreadSheetDirs = files.filter { ext(it.name) in setOf("cue", "gdi") && it.fileId !in input.sheets }.map { it.dirId }.toSet()
        for (problem in SetChecker.check(readable, read)) {
            val sheet = problem.sheet
            val isPlaylist = ext(sheet.name) == "m3u"
            when (problem.kind) {
                SetProblem.Kind.EMPTY_SHEET -> out += RepairFinding(
                    "sheet:${sheet.fileId}", RepairKind.UNREADABLE_SHEET, sheet.consoleId, sheet.name, listOf(RepairFile.of(sheet)),
                    fixes = listOfNotNull(redownloadRef(sheet.consoleId, gameStem(sheet), input.library)?.let { RepairFix(RepairFixKind.DOWNLOAD_AGAIN, download = it) })
                )
                SetProblem.Kind.MISSING_TRACKS -> out += RepairFinding(
                    "tracks:${sheet.fileId}", RepairKind.MISSING_TRACKS, sheet.consoleId, sheet.name, listOf(RepairFile.of(sheet)), problem.missing,
                    fixes = listOfNotNull(redownloadRef(sheet.consoleId, gameStem(sheet), input.library)?.let { RepairFix(RepairFixKind.DOWNLOAD_AGAIN, download = it) })
                )
                SetProblem.Kind.MISSING_DISCS -> if (isPlaylist && sheet.dirId !in unreadSheetDirs) {
                    val entries = SheetParser.m3uFiles(input.sheets[sheet.fileId].orEmpty())
                    val missing = problem.missing.map { it.lowercase() }.toSet()
                    val kept = entries.filter { it.lowercase() !in missing }
                    val file = RepairFile.of(sheet)
                    if (kept.isEmpty()) {
                        out += RepairFinding("dead:${sheet.fileId}", RepairKind.DEAD_PLAYLIST, sheet.consoleId, sheet.name, listOf(file), problem.missing,
                            fixes = listOf(RepairFix(RepairFixKind.TRASH, trash = listOf(file))))
                    } else {
                        val plan = PlaylistPlan(sheet.consoleId, sheet.folder, sheet.dirUri, sheet.dirId, sheet.name, kept)
                        out += RepairFinding("discs:${sheet.fileId}", RepairKind.MISSING_DISCS, sheet.consoleId, sheet.name, listOf(file), problem.missing,
                            fixes = listOf(RepairFix(RepairFixKind.REWRITE_PLAYLIST, trash = listOf(file), playlist = plan)))
                    }
                }
            }
        }

        // Duplicate playlists: same discs, same folder. The one named after the game stays.
        for ((_, inFolder) in byFolder) {
            val lists = inFolder.filter { ext(it.name) == "m3u" && it.fileId in input.sheets && input.sheets[it.fileId] != null }
            if (lists.size < 2) continue
            val groups = lists.groupBy { f -> SheetParser.m3uFiles(input.sheets[f.fileId].orEmpty()).map { it.replace('\\', '/').lowercase() } }
            for ((entries, same) in groups) {
                if (entries.isEmpty() || same.size < 2) continue
                val game = entries.firstNotNullOfOrNull { PlaylistPlanner.split(stem(SheetParser.leafName(it)))?.first }?.lowercase()
                val keep = same.sortedWith(compareBy({ stem(it.name).lowercase() != game }, { it.name.length }, { it.name.lowercase() })).first()
                val extra = same.filter { it !== keep }.map(RepairFile::of)
                out += RepairFinding("dup:${keep.fileId}", RepairKind.DUPLICATE_PLAYLIST, keep.consoleId, keep.name, extra,
                    fixes = listOf(RepairFix(RepairFixKind.TRASH, trash = extra)))
            }
        }

        // Tracks no sheet lists (only in folders whose sheets could all be read).
        for ((_, inFolder) in byFolder) {
            val tracks = inFolder.filter { (trackFile.matches(it.name) || genericTrack.matches(it.name)) && it.fileId !in emptyIds }
            if (tracks.isEmpty()) continue
            val sheets = inFolder.filter { ext(it.name) in setOf("cue", "gdi") }
            if (sheets.any { it.fileId !in input.sheets }) continue
            val listed = sheets.flatMap { s ->
                val text = input.sheets[s.fileId] ?: return@flatMap emptyList()
                (if (ext(s.name) == "cue") SheetParser.cueFiles(text) else SheetParser.gdiFiles(text)).map { SheetParser.leafName(it).lowercase() }
            }.toSet()
            val orphans = tracks.filter { it.name.lowercase() !in listed }
            for ((stem, group) in orphans.groupBy { gameStem(it) }) {
                val first = group.first()
                out += RepairFinding("orphan:${first.dirId}|$stem", RepairKind.ORPHAN_TRACKS, first.consoleId, stem, group.map(RepairFile::of),
                    fixes = listOfNotNull(redownloadRef(first.consoleId, stem, input.library)?.let { RepairFix(RepairFixKind.DOWNLOAD_AGAIN, download = it) }))
            }
        }

        // Multi-disc games without a playlist (only folders whose playlists could be read, so none is made twice).
        val unreadPlaylistFolders = files.filter { ext(it.name) == "m3u" && it.fileId !in input.sheets }.map { it.dirId }.toSet()
        PlaylistPlanner.plan(files.filter { it.dirId !in unreadPlaylistFolders }).forEach { plan ->
            val discs = byFolder[plan.dirId].orEmpty().filter { it.name in plan.discs }.map(RepairFile::of)
            out += RepairFinding("playlist:${plan.id}", RepairKind.NO_PLAYLIST, plan.consoleId, stem(plan.fileName), discs,
                fixes = listOf(RepairFix(RepairFixKind.CREATE_PLAYLIST, playlist = plan)))
        }

        // Cut off: much smaller than the library row of the same name.
        for (f in files) {
            val console = f.consoleId ?: continue
            if (f.size <= 0L || f.fileId in emptyIds) continue
            val ref = input.library[libraryKey(console, f.name)] ?: continue
            if (ref.size > 0L && f.size < ref.size * TRUNCATED_RATIO) {
                val file = RepairFile.of(f)
                out += RepairFinding("cut:${f.fileId}", RepairKind.TRUNCATED, console, f.name, listOf(file), a = f.size, b = ref.size,
                    fixes = listOf(RepairFix(RepairFixKind.DOWNLOAD_AGAIN, trash = listOf(file), download = ref)))
            }
        }

        // Partial files of browsers and other download apps.
        files.filter { it.consoleId != null && isForeignPart(it.name) && stale(it.lastModified, input.now, DOWNLOAD_PART_MIN_AGE, input.busy) }.forEach { f ->
            val file = RepairFile.of(f)
            out += RepairFinding("foreign:${f.fileId}", RepairKind.FOREIGN_PART, f.consoleId, f.name, listOf(file),
                fixes = listOf(RepairFix(RepairFixKind.TRASH, trash = listOf(file))))
        }

        // The app's own staging files that nothing owns any more.
        input.hidden.filter { h ->
            isOwnStaging(h.name) && h.name !in input.ownedStaging && stale(
                h.lastModified, input.now,
                if (h.name.startsWith(".dogmatix-download-")) DOWNLOAD_PART_MIN_AGE else STAGE_MIN_AGE, input.busy
            )
        }.forEach { h ->
            out += RepairFinding("left:${h.uri}", RepairKind.LEFTOVER_PART, h.consoleId, h.folder.substringAfterLast('/'), listOf(h), a = h.size,
                fixes = listOf(RepairFix(RepairFixKind.TRASH, trash = listOf(h))))
        }

        // Cartridges in another console's folder (report only).
        for (f in files) {
            val console = f.consoleId ?: continue
            val key = ConsoleFormatter.consoleKey(console)
            if (key !in judgedConsoles) continue
            val accepts = cartridgeConsoles[ext(f.name)] ?: continue
            if (key !in accepts) out += RepairFinding("wrong:${f.fileId}", RepairKind.WRONG_CONSOLE, console, f.name, listOf(RepairFile.of(f)), text = accepts.first())
        }

        // Folders that match no console (report only).
        val unmatched = input.unmatchedFolders.map { it.lowercase() }.toSet()
        files.filter { it.scope.startsWith("folder:") }
            .groupBy { it.scope.removePrefix("folder:") }
            .forEach { (folder, inFolder) ->
                if (folder.lowercase() !in unmatched || folder.lowercase() in notGameFolders) return@forEach
                val games = inFolder.filter { DuplicateFinder.isGameFile(it) && DuplicateFinder.isKnownGameFormat(it.name) }
                if (games.isNotEmpty()) out += RepairFinding("unmapped:$folder", RepairKind.UNMAPPED_FOLDER, null, folder, games.take(20).map(RepairFile::of), a = games.size.toLong())
            }

        index(input)?.let { out += it }

        val sorted = out.distinctBy { it.id }.sortedWith(compareBy({ it.severity.ordinal }, { it.kind.ordinal }, { it.subject.lowercase() }))
        return RepairResult(sorted.take(MAX_FINDINGS), sorted.size > MAX_FINDINGS)
    }

    /**
     * Roots whose storage seems not to report sizes: most of their (ten or more) game files are 0
     * bytes. A real empty file is rare; a provider without sizes would otherwise flag every game.
     */
    internal fun unknownSizeRoots(files: List<DiskFile>): Set<String> =
        files.filter { DuplicateFinder.isGameFile(it) }.groupBy { it.rootId }.filter { (_, inRoot) ->
            inRoot.size >= 10 && inRoot.count { it.size == 0L } * 2 > inRoot.size
        }.keys

    /**
     * The "on this device" marks against the disk: game files the index does not know, and marked
     * game files that are gone. Keys of a just-finished download (`console:` scope) and of a
     * package receipt are not files on disk and are not compared.
     */
    internal fun index(input: RepairInput): RepairFinding? {
        val keys = input.indexKeys ?: return null
        val disk = input.diskNames ?: return null
        val indexNames = keys.mapTo(HashSet()) { it.substringAfter('|') }
        val unknown = disk.filter { DuplicateFinder.isKnownGameFormat(it) && it !in indexNames }.sorted()
        val gone = keys.asSequence().filterNot { it.startsWith("console:") }.map { it.substringAfter('|') }
            .filter { '.' in it && DuplicateFinder.isKnownGameFormat(it) && it !in disk }.distinct().sorted().toList()
        if (unknown.isEmpty() && gone.isEmpty()) return null
        return RepairFinding("index", RepairKind.INDEX_STALE, null, "", missing = (unknown + gone).take(10),
            fixes = listOf(RepairFix(RepairFixKind.RESCAN)), a = unknown.size.toLong(), b = gone.size.toLong())
    }

    /**
     * What a profile may see: with restrictions, only findings of a console it shows, and none of
     * a game whose library row carries a hidden tag ([hiddenIds]). Shared folders (no console) stay
     * with the unrestricted profile, as in Trash and recovery.
     */
    fun visible(findings: List<RepairFinding>, restrictions: LibraryRestrictions, hiddenIds: Set<String>): List<RepairFinding> =
        if (!restrictions.active) findings
        else findings.filter { it.consoleId != null && it.consoleId !in restrictions.hiddenConsoles && it.id !in hiddenIds }

    /** The fixes "Fix safe items" runs: the first safe fix of each finding. */
    fun safeFixes(findings: List<RepairFinding>): List<Pair<RepairFinding, RepairFix>> =
        findings.mapNotNull { f -> f.fixes.firstOrNull { it.safe }?.let { f to it } }

    /** Severity counts for the summary (HIGH, MEDIUM, LOW). */
    fun counts(findings: List<RepairFinding>): Map<RepairSeverity, Int> =
        RepairSeverity.entries.associateWith { s -> findings.count { it.severity == s } }
}

/** The plain-text report "Share report" sends: names only, never a folder path or a URI. */
object RepairReport {
    data class Row(val severity: String, val title: String, val files: List<String>)

    fun text(title: String, summary: String, rows: List<Row>, maxFiles: Int = 5): String = buildString {
        appendLine(title)
        appendLine(summary)
        rows.forEach { row ->
            appendLine()
            appendLine("[${row.severity}] ${DiagnosticsRedactor.redact(row.title)}")
            row.files.take(maxFiles).forEach { appendLine("  - " + DiagnosticsRedactor.redact(SheetParser.leafName(it))) }
            if (row.files.size > maxFiles) appendLine("  … +${row.files.size - maxFiles}")
        }
    }.trimEnd()
}
