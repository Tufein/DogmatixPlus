package com.cortinadev.dogmatix.util

/**
 * Covers for launchers (3.1.0 for iiSU and Daijishō, 3.2.0 for any other launcher): where a cover
 * goes in the media folder the user picked for that launcher, and which games still need one.
 * Pure JVM for the tests.
 *
 * Launchers rarely document a media folder layout Dogmatix+ could rely on, so the layout is the
 * user's choice. `<system>` is the console folder of the games (the folder iiSU also uses as a
 * console's `shortName`, see [IisuJson]); `<name>` is the game's file name without extension:
 *  - [Layout.NAME_WITHOUT_EXTENSION] (default): `<system>/<name>.png`
 *  - [Layout.NAME_WITH_EXTENSION]: `<system>/<file name with extension>.png`
 *  - [Layout.FLAT]: `<name>.png`, all games in the media folder itself
 *  - [Layout.NAMED_BOXARTS]: `<system>/Named_Boxarts/<name>.png`, libretro thumbnails style, with
 *    libretro's character rule applied to the name ([LibretroThumbnails.thumbnailName])
 *  - [Layout.PEGASUS]: `<name>/boxFront.png`, one folder per game as in a Pegasus `media` folder
 *  - [Layout.GAMELIST_IMAGES]: `<system>/images/<name>-image.png`, EmulationStation gamelist style
 * Covers are always written as PNG. An image that is already there is never replaced unless the
 * user asked for it; any image with the same base name in the cover's folder (another image
 * extension too) counts as one.
 */
object FrontendCovers {

    /** The launchers this export writes for. [key] is what the settings store. */
    enum class Target(val key: String) {
        IISU("iisu"), DAIJISHO("daijisho"), OTHER("other");

        companion object { fun of(key: String?): Target = entries.firstOrNull { it.key == key } ?: IISU }
    }

    /**
     * How covers are named and arranged below the media folder. [key] is what the settings store
     * (never change one). [perSystem]: one folder per console folder; without it the games of all
     * consoles share one set of names, so two consoles' games of the same name collide.
     */
    enum class Layout(val key: String, val perSystem: Boolean) {
        NAME_WITHOUT_EXTENSION("stem", true),
        NAME_WITH_EXTENSION("full", true),
        FLAT("flat", false),
        NAMED_BOXARTS("boxarts", true),
        PEGASUS("pegasus", false),
        GAMELIST_IMAGES("gamelist", true);

        companion object { fun of(key: String?): Layout = entries.firstOrNull { it.key == key } ?: NAME_WITHOUT_EXTENSION }
    }

    /**
     * The layouts offered for [target], its default first. iiSU and Daijishō get the plain ones;
     * the layouts named after another frontend's media folder only make sense for "another launcher".
     */
    fun layoutsFor(target: Target): List<Layout> = when (target) {
        Target.OTHER -> Layout.entries
        Target.IISU, Target.DAIJISHO -> listOf(Layout.NAME_WITHOUT_EXTENSION, Layout.NAME_WITH_EXTENSION, Layout.FLAT)
    }

    /** The stored layout of [target]; one it does not offer (e.g. from an edited backup) becomes its default. */
    fun layoutOf(target: Target, key: String?): Layout =
        Layout.of(key).takeIf { it in layoutsFor(target) } ?: layoutsFor(target).first()

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

    /** The fixed parts of the frontend-style layouts. */
    const val NAMED_BOXARTS_FOLDER = "Named_Boxarts"
    const val PEGASUS_FILE = "boxFront"
    const val GAMELIST_FOLDER = "images"
    const val GAMELIST_SUFFIX = "-image"

    /**
     * A game on the device: its console, the console folder it sits in ([system], the folder the
     * cover goes in), the file a frontend lists ([fileName]) and the name covers are looked up by.
     */
    data class Game(val consoleId: String, val system: String, val fileName: String, val title: String)

    /** Where one cover goes: the folder [dir] below the media folder ("" = the media folder itself) and the file [name] in it. */
    data class Place(val dir: String, val name: String) {
        val path: String get() = if (dir.isEmpty()) name else "$dir/$name"
    }

    /** One cover to write at [place]. [replaces] an image of that exact name. */
    data class Item(val game: Game, val place: Place, val replaces: Boolean) {
        val path: String get() = place.path
    }

    data class Plan(
        val items: List<Item>,
        /** Games that already have a cover in the media folder (left alone). */
        val kept: Int,
        /** Games whose cover name would be unsafe or is taken by another game (all of [shared] included). */
        val skipped: Int,
        /** Of [skipped]: games whose cover file a game of another console would use too (mostly layouts without console folders). */
        val shared: Int = 0
    ) {
        val systems: Int get() = items.map { it.game.system.lowercase() }.distinct().size
        /** The first cover's path, to show the user what the names look like. */
        val example: String? get() = items.firstOrNull()?.path
    }

    /**
     * The game's part of a cover name in [layout]: the file name without extension, the whole
     * file name, or (RetroArch) the name with libretro's character rule; null when it cannot be
     * a safe file name.
     */
    fun gameName(fileName: String, layout: Layout): String? {
        val name = fileName.substringAfterLast('/').trim()
        val base = when (layout) {
            Layout.NAME_WITH_EXTENSION -> name
            Layout.NAMED_BOXARTS -> LibretroThumbnails.thumbnailName(LibraryKeys.baseName(name))
            else -> LibraryKeys.baseName(name)
        }.trim()
        return base.takeIf { safeSegment(it) }
    }

    /** Where [game]'s cover goes in [layout], or null when its console folder or name cannot be used. */
    fun place(game: Game, layout: Layout): Place? {
        if (layout.perSystem && !safeSegment(game.system)) return null
        val name = gameName(game.fileName, layout) ?: return null
        val place = when (layout) {
            Layout.NAME_WITHOUT_EXTENSION, Layout.NAME_WITH_EXTENSION -> Place(game.system, "$name.$EXTENSION")
            Layout.FLAT -> Place("", "$name.$EXTENSION")
            Layout.NAMED_BOXARTS -> Place("${game.system}/$NAMED_BOXARTS_FOLDER", "$name.$EXTENSION")
            Layout.PEGASUS -> Place(name, "$PEGASUS_FILE.$EXTENSION")
            Layout.GAMELIST_IMAGES -> Place("${game.system}/$GAMELIST_FOLDER", "$name$GAMELIST_SUFFIX.$EXTENSION")
        }
        val segments = place.dir.split('/').filter { it.isNotEmpty() } + place.name
        return place.takeIf { segments.all { it.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES } }
    }

    /** The cover's path below the media folder, or null when the console folder or the name cannot be used. */
    fun coverPath(game: Game, layout: Layout): String? = place(game, layout)?.path

    /**
     * Whether [names] (the lower-cased file names in the cover's folder) already hold a cover for
     * [coverName]: the same name, or the same base name with another image extension.
     */
    fun hasCover(names: Set<String>, coverName: String): Boolean {
        val lower = coverName.lowercase()
        if (lower in names) return true
        val base = lower.substringBeforeLast('.')
        return IMAGE_EXTENSIONS.any { "$base.$it" in names }
    }

    /**
     * The covers to write for [games] in [layout]. [existing] maps a folder below the media folder
     * ([Place.dir], any case; "" for the media folder itself) to the lower-cased names of the files
     * in it. With [overwrite] an image of the exact cover name is replaced; an image with another
     * extension stays and the PNG is added next to it.
     *
     * Games that would share one cover file: within one console (the same game as `.zip` and
     * `.gba`) the first is planned and the others skipped; across consoles (a flat layout's
     * `Tetris.png` for Game Boy and NES) every one of them is skipped, as any cover would be the
     * wrong one for the others.
     */
    fun plan(games: List<Game>, layout: Layout, existing: Map<String, Set<String>>, overwrite: Boolean): Plan {
        val present = existing.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, v) -> v.flatten().toSet() }
        val sorted = games.sortedWith(compareBy({ it.system.lowercase() }, { it.fileName.lowercase() }))
        val placed = sorted.map { it to place(it, layout) }
        val consolesOf = placed.mapNotNull { (g, p) -> p?.let { it.path.lowercase() to g.consoleId.lowercase() } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet().size }
        val seen = HashSet<String>()
        val items = ArrayList<Item>()
        var kept = 0
        var skipped = 0
        var shared = 0
        for ((game, place) in placed) {
            if (place == null) { skipped++; continue }
            val key = place.path.lowercase()
            if ((consolesOf[key] ?: 0) > 1) { skipped++; shared++; continue }
            if (!seen.add(key)) { skipped++; continue }
            val names = present[place.dir.lowercase()].orEmpty()
            val exact = place.name.lowercase() in names
            when {
                !hasCover(names, place.name) -> items += Item(game, place, replaces = false)
                overwrite -> items += Item(game, place, replaces = exact)
                else -> kept++
            }
        }
        return Plan(items, kept, skipped, shared)
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

    /**
     * A game of [games] to show example names with: the first one (in plan order) every layout
     * can name, else the first one at all; null without games.
     */
    fun sampleOf(games: List<Game>): Game? {
        val sorted = games.sortedWith(compareBy({ it.system.lowercase() }, { it.fileName.lowercase() }))
        return sorted.firstOrNull { g -> Layout.entries.all { place(g, it) != null } } ?: sorted.firstOrNull()
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
