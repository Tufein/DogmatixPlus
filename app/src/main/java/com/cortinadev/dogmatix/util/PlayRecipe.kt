package com.cortinadev.dogmatix.util

/**
 * What [com.cortinadev.dogmatix.data.service.GameLaunchService] remembers per console, as one
 * string so the values 2.3.0 stored keep working:
 *  - a flattened component (`com.foo/com.foo.Activity`): an app found through `ACTION_VIEW` (2.3.0);
 *  - `catalog:<emulator>` or `catalog:retroarch|<core>`: a catalogue emulator started with its recipe;
 *  - [AUTOMATIC]: always the first app offered (the catalogue's preferred emulator).
 * Each console is its own entry, so one unreadable value never affects another. Pure JVM.
 */
object GameLaunchKeys {

    const val AUTOMATIC = "auto"
    private const val CATALOGUE = "catalog:"

    fun catalogue(emulatorId: String, core: String? = null): String =
        CATALOGUE + emulatorId + (core?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: "")

    /** The emulator id and RetroArch core of a catalogue key; null for anything else. */
    fun parseCatalogue(key: String?): Pair<String, String?>? {
        val value = key?.trim().orEmpty()
        if (!value.startsWith(CATALOGUE)) return null
        val body = value.removePrefix(CATALOGUE)
        val id = body.substringBefore('|').trim().ifEmpty { return null }
        return id to body.substringAfter('|', "").trim().ifEmpty { null }
    }

    /** The package of a stored component (`pkg/cls`), or null when [key] is not one. */
    fun componentPackage(key: String?): String? {
        val value = key?.trim().orEmpty()
        if (value.startsWith(CATALOGUE) || value == AUTOMATIC || '/' !in value) return null
        return value.substringBefore('/').takeIf { it.isNotBlank() }
    }

    /**
     * The handler [stored] means among [handlers] (in offer order), or null when it names nothing
     * offered now (uninstalled, or the console has no such emulator): the caller then asks again.
     * A component stored by 2.3.0 for an app that the catalogue now knows maps to that app's
     * catalogue entry (its first core for RetroArch), so the recipe replaces the plain `ACTION_VIEW`.
     */
    fun <T> resolve(stored: String?, handlers: List<T>, keyOf: (T) -> String, packageOf: (T) -> String): T? {
        val value = stored?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value == AUTOMATIC) return handlers.firstOrNull()
        handlers.firstOrNull { keyOf(it) == value }?.let { return it }
        val pkg = componentPackage(value) ?: return null
        return handlers.firstOrNull { packageOf(it) == pkg }
    }

    /** Catalogue handlers first, then the generic ones of packages not offered yet (one per package). */
    fun <T> merge(catalogue: List<T>, generic: List<T>, packageOf: (T) -> String): List<T> {
        val taken = catalogue.mapTo(HashSet(), packageOf)
        return catalogue + generic.filter { packageOf(it) !in taken }.distinctBy(packageOf)
    }
}

/** The file Play starts: where it is in the shapes emulators ask for. */
data class FileRef(
    val name: String,
    /** The document URI under the library's SAF tree (`content://…/tree/…/document/…`). */
    val documentUri: String?,
    /** The absolute path when the provider's document id spells one (internal storage, SD card); null otherwise. */
    val path: String?,
    /** A FileProvider URI of this app, only when this app can read the path itself; see [PlayRecipe.fileUri]. */
    val providerUri: String? = null
)

/** One extra of an [IntentSpec]. */
sealed interface SpecExtra {
    val key: String

    data class Text(override val key: String, val value: String) : SpecExtra
    data class Flag(override val key: String, val value: Boolean) : SpecExtra
}

/**
 * An intent to try, as plain data: the service turns it into an `Intent`. [className] null =
 * addressed to the package only. [mime] is only set for the generic fallback.
 */
data class IntentSpec(
    val action: String?,
    val packageName: String,
    val className: String?,
    val categories: List<String>,
    val dataUri: String?,
    val mime: String?,
    val extras: List<SpecExtra>,
    val clearTask: Boolean,
    /** Content URIs the receiver gets read access to: the file, URIs in extras, and the game's other files. */
    val grantUris: List<String>
)

/** What [PlayRecipe.build] made: intents to try in order, and whether the template had to be skipped for want of a path. */
data class BuiltRecipe(val attempts: List<IntentSpec>, val templateNeedsPath: Boolean) {
    /** At least one attempt uses the emulator's own launch template (not only the generic fallback). */
    val usesTemplate: Boolean get() = attempts.any { it.className != null }
}

/**
 * Turns an emulator template + a file into the intents Play tries, first to last: one per activity
 * of the template, then a plain `ACTION_VIEW` to the package. Pure JVM: no Android types, so the
 * component / extras / data of every template can be unit-tested.
 */
object PlayRecipe {

    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Archives; no emulator opens them for a disc system, some do for cartridges (see [Emulator.archives]). */
    val archiveExtensions = setOf("zip", "7z", "rar", "gz", "tar")

    /** Extensions in the order they are the better entry of a game: playlist, sheets, then images. */
    private val entryPriority = listOf("m3u", "cue", "gdi", "chd", "cso", "ciso", "iso", "pbp", "rvz", "wbfs", "gcz", "nsp", "xci", "3ds", "cia", "cci", "cxi")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Sort key of a game file: a playlist before a cue sheet before a disc image; anything else after, in its order. */
    fun entryRank(name: String): Int = entryPriority.indexOf(ext(name)).let { if (it < 0) entryPriority.size else it }

    /** Where a RetroArch package keeps its cores (private to the app; ES-DE's `%INTERNALDATA%/<pkg>/cores`). */
    fun corePath(packageName: String, core: String): String = "/data/data/$packageName/cores/${core}_libretro_android.so"

    /** RetroArch's own configuration file (ES-DE's `%EXTERNALDATA%/Android/data/<pkg>/files/retroarch.cfg`). */
    fun configPath(externalRoot: String, packageName: String): String = "${externalRoot.trimEnd('/')}/Android/data/$packageName/files/retroarch.cfg"

    /** `.Foo` is relative to the package, anything else is already a class name. */
    fun className(packageName: String, activity: String): String = if (activity.startsWith(".")) packageName + activity else activity

    /**
     * The URI for a [Slot.PROVIDER_URI] / [Slot.SAF_URI] value. The library lives behind the Storage
     * Access Framework and this app has no all-files access, so a FileProvider URI of the same path
     * would open nothing: the document URI (readable through the grant) stands in for it unless
     * [FileRef.providerUri] says this app can read the file itself.
     */
    fun fileUri(file: FileRef, slot: Slot): String? = when (slot) {
        Slot.SAF_URI -> file.documentUri
        Slot.PROVIDER_URI -> file.providerUri ?: file.documentUri
        Slot.PATH -> file.path
    }

    /**
     * The attempts for [file] in [emulator] ([variant] is the installed package). [siblings] are the
     * document URIs of the game's other files (a cue sheet's tracks, an m3u's discs): they are
     * granted with the file so an emulator that opens them next to it may read them.
     */
    fun build(emulator: Emulator, variant: Variant, core: String?, file: FileRef, externalRoot: String, siblings: List<String> = emptyList()): BuiltRecipe {
        val attempts = ArrayList<IntentSpec>()
        var needsPath = false
        val template = emulator.template
        if (template != null) {
            for (activity in variant.activities) {
                val spec = fromTemplate(template, variant.packageName, className(variant.packageName, activity), core, file, externalRoot, siblings)
                if (spec == null) needsPath = true else attempts += spec
            }
        }
        generic(variant.packageName, file, siblings)?.let { attempts += it }
        return BuiltRecipe(attempts, needsPath)
    }

    private fun fromTemplate(t: LaunchTemplate, packageName: String, className: String, core: String?, file: FileRef, externalRoot: String, siblings: List<String>): IntentSpec? {
        val grants = LinkedHashSet<String>()
        var data: String? = null
        if (t.data != null) {
            data = fileUri(file, t.data) ?: return null
            if (t.data != Slot.PATH) grants += data
        }
        val extras = ArrayList<SpecExtra>()
        for (extra in t.extras) {
            when (val v = extra.value) {
                is ExtraValue.Of -> {
                    val text = fileUri(file, v.slot) ?: return null
                    if (v.slot != Slot.PATH) grants += text
                    extras += SpecExtra.Text(extra.key, text)
                }
                is ExtraValue.Text -> extras += SpecExtra.Text(extra.key, v.text)
                is ExtraValue.Flag -> extras += SpecExtra.Flag(extra.key, v.on)
                ExtraValue.Core -> extras += SpecExtra.Text(extra.key, corePath(packageName, core ?: return null))
                ExtraValue.Config -> extras += SpecExtra.Text(extra.key, configPath(externalRoot, packageName))
            }
        }
        // A template that reads a path opens the other files by path as well: no grants needed.
        if (grants.isNotEmpty()) grants += siblings
        return IntentSpec(t.action, packageName, className, t.categories, data, null, extras, t.clearTask, grants.toList())
    }

    /** `ACTION_VIEW` of the file addressed to [packageName]: the activity is left to the system. */
    fun generic(packageName: String, file: FileRef, siblings: List<String> = emptyList()): IntentSpec? {
        val uri = file.documentUri ?: file.providerUri ?: return null
        return IntentSpec(ACTION_VIEW, packageName, null, emptyList(), uri, mimeFor(file.name), emptyList(), false, (listOf(uri) + siblings).distinct())
    }

    fun mimeFor(fileName: String): String = when (ext(fileName)) {
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        else -> "application/octet-stream"
    }

    /**
     * Whether [emulator] cannot open [fileName] as it is and the game has to be extracted first: an
     * archive that the emulator does not read for this system (disc systems never; cartridges only
     * for the archive kinds the emulator lists). Play does not extract anything itself.
     */
    fun needsExtract(emulator: Emulator, system: PlaySystem, fileName: String): Boolean {
        val ext = ext(fileName)
        if (ext !in archiveExtensions) return false
        return system.disc || ext !in emulator.archives
    }
}
