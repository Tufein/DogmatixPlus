package com.cortinadev.dogmatix.util

/**
 * Covers for iiSU and Daijishō (3.1.0): where a cover goes in the media folder the user picked
 * for that frontend, and which games still need one. Pure JVM for the tests.
 *
 * Neither frontend documents a media folder layout Dogmatix+ could rely on, so the layout is
 * the user's choice, with one folder per system named like the console folder of the games
 * (the folder iiSU also uses as a console's `shortName`, see [IisuJson]):
 *  - [Layout.NAME_WITHOUT_EXTENSION] (default): `<system>/<rom file name without extension>.png`
 *  - [Layout.NAME_WITH_EXTENSION]: `<system>/<rom file name with extension>.png`, for a frontend
 *    that matches artwork on the full file name.
 * Covers are always written as PNG. An image that is already there is never replaced unless the
 * user asked for it; any image with the same name (another image extension too) counts as one.
 */
object FrontendCovers {

    /** The frontends this export writes for. [key] is what the settings store. */
    enum class Target(val key: String) {
        IISU("iisu"), DAIJISHO("daijisho");

        companion object { fun of(key: String?): Target = entries.firstOrNull { it.key == key } ?: IISU }
    }

    enum class Layout(val key: String) {
        NAME_WITHOUT_EXTENSION("stem"), NAME_WITH_EXTENSION("full");

        companion object { fun of(key: String?): Layout = entries.firstOrNull { it.key == key } ?: NAME_WITHOUT_EXTENSION }
    }

    /** The preference keys of each target's folder, layout and overwrite choice (see FrontendCoversSettings). */
    fun folderKey(target: Target) = "covers31_${target.key}_folder"
    fun layoutKey(target: Target) = "covers31_${target.key}_layout"
    fun overwriteKey(target: Target) = "covers31_${target.key}_overwrite"

    /** Their backup types, for [SettingSchema]. */
    val SETTING_TYPES: Map<String, String> by lazy {
        Target.entries.flatMap { listOf(folderKey(it) to "s", layoutKey(it) to "s", overwriteKey(it) to "b") }.toMap()
    }

    const val EXTENSION = "png"
    /** Image files that count as "a cover is already there". */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    /** File names longer than this (in UTF-8 bytes) are refused by most file systems. */
    const val MAX_NAME_BYTES = 255

    /**
     * A game on the device: its console, the console folder it sits in ([system], the folder the
     * cover goes in), the file a frontend lists ([fileName]) and the name covers are looked up by.
     */
    data class Game(val consoleId: String, val system: String, val fileName: String, val title: String)

    /** One cover to write: `<system>/<coverName>` below the media folder. [replaces] an image of that exact name. */
    data class Item(val game: Game, val coverName: String, val replaces: Boolean) {
        val path: String get() = "${game.system}/$coverName"
    }

    data class Plan(
        val items: List<Item>,
        /** Games that already have a cover in the media folder (left alone). */
        val kept: Int,
        /** Games whose cover name would be unsafe or is taken by another game of the same system. */
        val skipped: Int
    ) {
        val systems: Int get() = items.map { it.game.system.lowercase() }.distinct().size
        /** The first cover's path, to show the user what the names look like. */
        val example: String? get() = items.firstOrNull()?.path
    }

    /** The cover's file name for a game file, or null when it cannot be a safe file name. */
    fun coverName(fileName: String, layout: Layout): String? {
        val name = fileName.substringAfterLast('/').trim()
        val base = when (layout) {
            Layout.NAME_WITHOUT_EXTENSION -> LibraryKeys.baseName(name)
            Layout.NAME_WITH_EXTENSION -> name
        }.trim()
        if (!safeSegment(base)) return null
        val cover = "$base.$EXTENSION"
        return cover.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES }
    }

    /** The cover's path below the media folder, or null when the system or the name cannot be used. */
    fun coverPath(game: Game, layout: Layout): String? {
        if (!safeSegment(game.system)) return null
        return coverName(game.fileName, layout)?.let { "${game.system}/$it" }
    }

    /**
     * Whether [names] (the lower-cased file names in a system folder) already hold a cover for
     * [coverName]: the same name, or the same name with another image extension.
     */
    fun hasCover(names: Set<String>, coverName: String): Boolean {
        val lower = coverName.lowercase()
        if (lower in names) return true
        val base = lower.substringBeforeLast('.')
        return IMAGE_EXTENSIONS.any { "$base.$it" in names }
    }

    /**
     * The covers to write for [games] in [layout]. [existing] maps a system folder (any case) to
     * the lower-cased names of the files in it. With [overwrite] an image of the exact cover name
     * is replaced; an image with another extension stays and the PNG is added next to it.
     */
    fun plan(games: List<Game>, layout: Layout, existing: Map<String, Set<String>>, overwrite: Boolean): Plan {
        val present = existing.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, v) -> v.flatten().toSet() }
        val seen = HashSet<String>()
        val items = ArrayList<Item>()
        var kept = 0
        var skipped = 0
        for (game in games.sortedWith(compareBy({ it.system.lowercase() }, { it.fileName.lowercase() }))) {
            val name = if (safeSegment(game.system)) coverName(game.fileName, layout) else null
            if (name == null || !seen.add("${game.system.lowercase()}/${name.lowercase()}")) { skipped++; continue }
            val names = present[game.system.lowercase()].orEmpty()
            val exact = name.lowercase() in names
            when {
                !hasCover(names, name) -> items += Item(game, name, replaces = false)
                overwrite -> items += Item(game, name, replaces = exact)
                else -> kept++
            }
        }
        return Plan(items, kept, skipped)
    }

    /** Games found on the device, and how many were left out because no console folder holds them. */
    data class Found(val games: List<Game>, val outside: Int)

    /**
     * The games of a library walk ([files]) a frontend lists: one per game (the file it launches,
     * e.g. the `.m3u` of a disc set), in the console folder that names its console. Consoles in
     * [hiddenConsoles] (the active profile hides them) are left out entirely.
     */
    fun gamesOf(files: List<DiskFile>, hiddenConsoles: Set<String> = emptySet()): Found {
        val games = ArrayList<Game>()
        var outside = 0
        for (entry in DuplicateFinder.entries(files)) {
            val consoleId = entry.consoleId ?: continue
            if (consoleId in hiddenConsoles || !entry.comparable) continue
            val main = FrontendMetadata.mainFile(entry.files.map { it.name }) ?: continue
            val file = entry.files.first { it.name == main }
            val system = CoverPlanner.systemOf(file)
            if (system == null) { outside++; continue }
            games += Game(consoleId, system, main, entry.baseName)
        }
        return Found(games, outside)
    }

    /** True when [bytes] start with the PNG signature. */
    fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /**
     * The power-of-two sample size that brings an image of [width] x [height] within [maxSide]
     * pixels on its longer side (1 = full size); keeps a huge cover from taking all memory.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSide) sample *= 2
        return sample
    }

    /** Longest side a written cover keeps. */
    const val MAX_SIDE = 2048

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** One path segment that is safe as a file or folder name on Android shared storage. */
    private fun safeSegment(s: String): Boolean =
        s.isNotBlank() && s != "." && s != ".." && !s.startsWith(".dogmatix-") &&
            s.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' || it.isISOControl() }
}
