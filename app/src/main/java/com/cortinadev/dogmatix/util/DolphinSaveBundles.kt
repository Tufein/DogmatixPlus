package com.cortinadev.dogmatix.util

import java.io.IOException

/** What a Dolphin save bundle holds: a GCI folder of one region and slot, or a part of the Wii NAND's saves. */
enum class DolphinBundleKind { GCI, WII }

/**
 * One opt-in bundle of a Dolphin folder: many saves that travel as one zip ([SavePack]), kept on
 * RomM with the game the user chose for them (never matched to a ROM by name).
 *
 * - [DolphinBundleKind.GCI]: `GC/<region>/Card A|B/*.gci`, Dolphin's GCI folder of one region and
 *   slot; the zip holds the `.gci` files flat (`01-GALE-SuperSmashBros.gci`).
 * - [DolphinBundleKind.WII]: `Wii/title/<titleType>/<title>/data/…`, the saves of Wii titles in
 *   Dolphin's NAND; the zip holds `<title>/data/<file>` paths. Never `content/` (a title's installed
 *   executables) and nothing outside `title/<titleType>` (`sys`, `shared2`, `ticket`, `import`, `tmp`, `wiimote`…).
 */
data class DolphinBundle(
    val kind: DolphinBundleKind,
    /** GCI only: `USA`, `EUR` or `JAP`. */
    val region: String = "",
    /** GCI only: `A` or `B`. */
    val slot: Char = 'A',
    /** Wii only: the title type folder ([DolphinSaveBundles.WII_GAMES] or [DolphinSaveBundles.WII_DISC_CHANNELS]). */
    val titleType: String = ""
) {
    /** The bundle's name on the server: `GCI-USA-A.gcifolder.zip`, `Wii-00010000.wiisaves.zip`. */
    val fileName: String get() = when (kind) {
        DolphinBundleKind.GCI -> "GCI-$region-$slot${DolphinSaveBundles.GCI_SUFFIX}"
        DolphinBundleKind.WII -> "Wii-$titleType${DolphinSaveBundles.WII_SUFFIX}"
    }

    /** Where the bundle's files are, below Dolphin's user folder: `GC/USA/Card A`, `Wii/title/00010000`. */
    val devicePath: String get() = when (kind) {
        DolphinBundleKind.GCI -> "GC/$region/Card $slot"
        DolphinBundleKind.WII -> "Wii/title/$titleType"
    }
}

/**
 * Dolphin's GCI folders and Wii saves as opt-in bundles for the save sync. Pure JVM for the tests.
 *
 * What this relies on comes from Dolphin's public source and documentation, not from a device:
 * Dolphin's GCI folder mode keeps one `.gci` file per save in `GC/<USA|EUR|JAP>/Card A|B`, named
 * `<maker code>-<game code>-<file name>.gci`; its emulated Wii NAND (`<user folder>/Wii`) keeps a
 * title's saves as plain, unencrypted files in `title/<8 hex digits>/<8 hex digits>/data/`, the
 * second part being the 4 ASCII characters of the game id in lower-case hex (`RSBE` → `52534245`).
 * Disc games are title type `00010000`; `00010004` holds channels some discs install (Wii Fit's,
 * Mario Kart's). How the Android builds (and forks such as Dolphin MMJR) lay this out on a handheld,
 * and whether they cope with files written while they are closed, is unverified on a device.
 */
object DolphinSaveBundles {
    const val GCI_SUFFIX = ".gcifolder.zip"
    const val WII_SUFFIX = ".wiisaves.zip"

    /** Title type of Wii disc games, and of channels discs install (optional). */
    const val WII_GAMES = "00010000"
    const val WII_DISC_CHANNELS = "00010004"

    /** Files in one bundle: a GameCube card holds up to 127 saves; a Wii NAND with every game played stays far below. */
    const val MAX_ENTRIES = 4096

    /** The size of one bundle (the zip, and its unpacked files): the sync's ordinary limit for one file. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** How deep a Wii save's files may sit: `<title>/data/` and a few folders the game made. */
    private const val MAX_WII_DEPTH = 8

    val regions = listOf("USA", "EUR", "JAP")
    val slots = listOf('A', 'B')

    private val gciBundleName = Regex("""^GCI-(USA|EUR|JAP)-([AB])\.gcifolder\.zip$""", RegexOption.IGNORE_CASE)
    private val wiiBundleName = Regex("""^Wii-(00010000|00010004)\.wiisaves\.zip$""", RegexOption.IGNORE_CASE)
    private val wiiTitle = Regex("""^[0-9a-fA-F]{8}$""")
    private val gciName = Regex("""^([0-9A-Za-z]{2})-([0-9A-Za-z]{4})-(.*)\.gci$""", RegexOption.IGNORE_CASE)

    fun gci(region: String, slot: Char) = DolphinBundle(DolphinBundleKind.GCI, region = region, slot = slot)
    fun wii(titleType: String) = DolphinBundle(DolphinBundleKind.WII, titleType = titleType)

    /** Every bundle a folder can have with these choices: six GCI folders, one or two Wii parts. */
    fun all(gci: Boolean, wii: Boolean, wiiChannels: Boolean): List<DolphinBundle> = buildList {
        if (gci) regions.forEach { r -> slots.forEach { s -> add(gci(r, s)) } }
        if (wii) { add(wii(WII_GAMES)); if (wiiChannels) add(wii(WII_DISC_CHANNELS)) }
    }

    /** The bundle a (server or sync) file name stands for, or null when it is none. */
    fun parse(fileName: String): DolphinBundle? {
        gciBundleName.matchEntire(fileName)?.let { return gci(it.groupValues[1].uppercase(), it.groupValues[2].uppercase().single()) }
        wiiBundleName.matchEntire(fileName)?.let { return wii(it.groupValues[1]) }
        return null
    }

    fun isBundleName(fileName: String): Boolean = parse(fileName) != null

    /** Whether the device file [local] is a bundle of a Dolphin folder: kept with the game the user chose, like a shared card. */
    fun isBundle(local: LocalSaveFile): Boolean = local.layout == SaveFolderLayout.GC_MEMCARDS && isBundleName(local.name)

    /** The real folder name among [names] for [wanted], ignoring case (`usa` for `USA`); null when there is none. */
    fun pick(names: Collection<String>, wanted: String): String? = names.firstOrNull { it == wanted } ?: names.firstOrNull { it.equals(wanted, ignoreCase = true) }

    // ---- What goes into a bundle -----------------------------------------------------------------

    /** Whether a file of a GCI folder is a save: `*.gci`, no hidden or staging file. */
    fun isGciFile(name: String): Boolean = !name.startsWith(".") && name.length > 4 && name.endsWith(".gci", ignoreCase = true) && strictSegment(name)

    /** Whether a folder in `Wii/title/<type>` is a title (8 hex digits). */
    fun isWiiTitle(name: String): Boolean = wiiTitle.matches(name)

    /** A GCI file name as Dolphin writes it: maker code, game code and the save's own name; null for other names. */
    data class GciName(val makerCode: String, val gameCode: String, val title: String)

    fun gciName(fileName: String): GciName? = gciName.matchEntire(fileName)?.let { GciName(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    /** The game id a Wii title folder stands for (`52534245` → `RSBE`), or null when it is no printable id. */
    fun wiiGameId(title: String): String? {
        if (!isWiiTitle(title)) return null
        val chars = title.chunked(2).map { it.toInt(16) }
        return if (chars.all { it in 0x20..0x7e }) chars.joinToString("") { it.toChar().toString() } else null
    }

    /**
     * Throws unless every path of a bundle of [bundle] is one this sync writes: no `..`, no absolute
     * path, no backslash, drive letter, control character or hidden name, nothing a storage would
     * rename; GCI bundles hold `.gci` files only, flat; Wii bundles hold only `<title>/data/…` files.
     * Also checks the number of files. Used for what comes from the server and for what goes up.
     */
    fun checkEntries(bundle: DolphinBundle, paths: List<String>) {
        if (paths.size > MAX_ENTRIES) throw BundleException("Too many files (${paths.size}; at most $MAX_ENTRIES)")
        val seen = HashSet<String>()
        paths.forEach { path ->
            if (!strictPath(path)) throw BundleException("Unsafe file name in the bundle: $path")
            val parts = path.split('/')
            val fits = when (bundle.kind) {
                DolphinBundleKind.GCI -> parts.size == 1 && isGciFile(parts[0])
                DolphinBundleKind.WII -> parts.size in 3..MAX_WII_DEPTH && isWiiTitle(parts[0]) && parts[1] == "data"
            }
            if (!fits) throw BundleException("Not a save of this bundle: $path")
            if (!seen.add(ArchivePlan.portableKey(path))) throw BundleException("Two files of the bundle have the same name: $path")
        }
    }

    /**
     * The files of a bundle that came from the server, every name checked as the zip has it (no
     * backslash, `..`, absolute path or name the storage would change) and then as a whole
     * ([checkEntries]); throws before anything is written.
     */
    fun unpack(bundle: DolphinBundle, bytes: ByteArray): List<Pair<String, ByteArray>> {
        if (bytes.size.toLong() > MAX_BYTES) throw BundleException("The bundle is larger than ${MAX_BYTES shr 20} MB")
        val entries = SavePack.unpack(bytes, MAX_BYTES, MAX_ENTRIES) { raw ->
            if (!strictPath(raw)) throw BundleException("Unsafe file name in the bundle: $raw")
        }
        checkEntries(bundle, entries.map { it.first })
        return entries
    }

    /** A `/`-separated relative path of plain names, each one kept exactly as it is by the zip and the storage. */
    fun strictPath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.length > 1024) return false
        val parts = path.split('/')
        if (parts.size > 64 || !parts.all(::strictSegment)) return false
        return runCatching { ArchivePlan.safeRelativePath(path) == path }.getOrDefault(false)
    }

    private fun strictSegment(name: String): Boolean =
        name.isNotEmpty() && name.length <= 255 && name != "." && name != ".." && !name.startsWith(".") &&
            name.trimEnd(' ', '.') == name && name.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
            !(name.length >= 2 && name[1] == ':' && name[0].isLetter())

    /** A bundle too large, too full or holding a name this sync cannot carry. */
    class BundleException(message: String) : IOException(message)
}
