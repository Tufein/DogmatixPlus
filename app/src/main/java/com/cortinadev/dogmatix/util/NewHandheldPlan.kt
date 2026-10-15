package com.cortinadev.dogmatix.util

/**
 * "Set up a new handheld" (3.5.0): the checklist that takes a fresh install to a ready handheld.
 * It only decides; every step's action opens a screen or flow the app already has (restore,
 * library scan, readiness check, "Download all", save sync, frontend check…).
 *
 * The ViewModel gathers a [Snapshot] from what the app already knows (settings, the on-disk index,
 * favourites, collections, wishlist, the RomM game list, installed emulators); [plan] turns it and
 * the user's own [Mark]s into the ordered steps. No I/O here, so every rule is unit-tested.
 */
object NewHandheldPlan {

    /** The steps in the order the screen shows them. Only [LAUNCHER] is optional. */
    enum class Step(val optional: Boolean = false) {
        SOURCES, LIBRARY, EMULATORS, GAMES, SAVES, LAUNCHER(optional = true)
    }

    enum class State { TODO, DONE, SKIPPED, NOT_NEEDED }

    /** What the user said about a step by hand; kept per handheld, never in a settings backup. */
    enum class Mark { DONE, SKIPPED }

    /** Which sentence a step shows; the screen maps each to a string ([StepResult.a] / [StepResult.b] fill it). */
    enum class Detail {
        SOURCES_NONE, SOURCES_OK, SOURCES_CLOUD,
        LIBRARY_NO_FOLDER, LIBRARY_EMPTY, LIBRARY_UNMATCHED, LIBRARY_OK,
        EMULATORS_WAITING, EMULATORS_UNCHECKED, EMULATORS_MISSING, EMULATORS_OK,
        GAMES_WAITING, GAMES_NOTHING, GAMES_MISSING, GAMES_OK,
        SAVES_NO_ROMM, SAVES_NO_FOLDERS, SAVES_READY, SAVES_RUNNING, SAVES_CONFLICTS, SAVES_ERROR, SAVES_OK, SAVES_AUTO,
        LAUNCHER_TODO, LAUNCHER_OK
    }

    /** What a step's buttons do; the screen opens the matching existing screen or flow. */
    enum class Action {
        RESTORE_FILE, CLOUD_BACKUP, OPEN_SOURCES,
        PICK_FOLDER, SCAN_LIBRARY, LIBRARY_OVERVIEW,
        EMULATOR_CHECK, EMULATOR_SETTINGS,
        DOWNLOAD_FAVOURITES, DOWNLOAD_COLLECTION, OPEN_COLLECTIONS, OPEN_WISHLIST, COPY_GAMES,
        OPEN_ROMM, OPEN_SAVE_SYNC, SYNC_SAVES,
        FRONTEND_CHECK, FRONTEND_COVERS
    }

    /**
     * One console the emulator step looks at: it has games on this handheld, or is in the
     * favourites or the wishlist.
     *
     * @property known the app's emulator catalogue knows the console's system, so it can tell
     *  what is missing.
     * @property launchable an emulator of the catalogue is installed for it, or an app the user
     *  picked for it on a game page is.
     * @property suggestion the catalogue's preferred emulator for the system (what to install).
     */
    data class ConsoleEmulators(
        val consoleId: String,
        val name: String,
        val hasGames: Boolean,
        val known: Boolean,
        val launchable: Boolean,
        val suggestion: String? = null
    ) {
        /** Whether the step can judge this console at all. */
        val judged: Boolean get() = known || launchable
    }

    /** Games owned elsewhere against this handheld: "[missing] of [total]". */
    enum class CountKind { FAVOURITES, COLLECTIONS, WISHLIST, ROMM }

    data class Count(val kind: CountKind, val missing: Int, val total: Int)

    /** Everything the plan needs to know, gathered by the ViewModel from existing flows and queries. */
    data class Snapshot(
        /** Source entries over all consoles (enabled or not): the user has sources. */
        val sources: Int = 0,
        /** The user's own WebDAV cloud (backup, device sync) is set up. */
        val cloudConfigured: Boolean = false,
        /** The download (games) folder or a console's own folder is picked. */
        val libraryFolderSet: Boolean = false,
        /** The on-disk index found files at all (even in folders no console claims). */
        val filesOnDisk: Boolean = false,
        val consolesWithGames: Int = 0,
        val emulators: List<ConsoleEmulators> = emptyList(),
        val favourites: Int = 0,
        val favouritesMissing: Int = 0,
        val collectionGames: Int = 0,
        val collectionGamesMissing: Int = 0,
        /** Collections that miss at least one game here; with exactly one, its own "Download all" is offered. */
        val collectionsMissing: Int = 0,
        val wishes: Int = 0,
        /** Wishes that are not on this handheld (wanted, in a source or on the RomM server). */
        val wishesMissing: Int = 0,
        /** RomM address and token are set. */
        val rommConfigured: Boolean = false,
        /** Games in the RomM server's list (empty when "Mark games in RomM" is off). */
        val rommGames: Int = 0,
        val rommMissing: Int = 0,
        /** A saves or states folder (or an emulator's own folder) is picked for save sync. */
        val saveFoldersSet: Boolean = false,
        val saveSyncAuto: Boolean = false,
        val saveSyncRunning: Boolean = false,
        /** A save sync finished on this handheld (this session, or recorded by the checklist before). */
        val saveSyncDone: Boolean = false,
        val saveConflicts: Int = 0,
        val saveError: Boolean = false,
        /** Frontends the Frontend check reports as ready. */
        val frontendsReady: Int = 0,
        /** A media folder is picked in "Covers for launchers". */
        val coversFolderSet: Boolean = false
    )

    /**
     * One step as the screen shows it. [auto] is what the app sees by itself; [state] adds the
     * user's [mark] (a mark only changes a step the app still sees as to do).
     */
    data class StepResult(
        val step: Step,
        val state: State,
        val auto: State,
        val mark: Mark?,
        val detail: Detail,
        val a: Int = 0,
        val b: Int = 0,
        val primary: Action,
        val more: List<Action> = emptyList(),
        /** EMULATORS: the consoles without an emulator, consoles with games first. */
        val missingEmulators: List<ConsoleEmulators> = emptyList(),
        /** GAMES: how much of what is owned elsewhere is missing here. */
        val counts: List<Count> = emptyList()
    ) {
        /** The user's mark is what makes this step skipped or done (so it can be undone). */
        val markApplies: Boolean get() = mark != null && auto == State.TODO
    }

    /** How far the checklist is. [next] is the first step still to do (required steps first). */
    data class Summary(val settled: Int, val total: Int, val requiredOpen: Int, val next: Step?) {
        val ready: Boolean get() = requiredOpen == 0
        val fraction: Float get() = if (total == 0) 1f else settled.toFloat() / total
    }

    /** The steps in order, with the user's [marks] applied. */
    fun plan(s: Snapshot, marks: Map<Step, Mark> = emptyMap()): List<StepResult> =
        listOf(sources(s), library(s), emulators(s), games(s), saves(s), launcher(s)).map { r ->
            val mark = marks[r.step]
            r.copy(state = combine(r.auto, mark), mark = mark)
        }

    /** A step the app sees as done or not needed stays so; a mark only settles a step still to do. */
    fun combine(auto: State, mark: Mark?): State = when {
        auto != State.TODO -> auto
        mark == Mark.DONE -> State.DONE
        mark == Mark.SKIPPED -> State.SKIPPED
        else -> State.TODO
    }

    fun summary(steps: List<StepResult>): Summary {
        val open = steps.filter { it.state == State.TODO }
        return Summary(
            settled = steps.size - open.size,
            total = steps.size,
            requiredOpen = open.count { !it.step.optional },
            next = (open.firstOrNull { !it.step.optional } ?: open.firstOrNull())?.step
        )
    }

    private fun result(step: Step, auto: State, detail: Detail, primary: Action, more: List<Action> = emptyList(), a: Int = 0, b: Int = 0) =
        StepResult(step, auto, auto, null, detail, a, b, primary, more)

    private fun sources(s: Snapshot): StepResult = when {
        s.sources > 0 -> result(Step.SOURCES, State.DONE, Detail.SOURCES_OK, Action.OPEN_SOURCES, listOf(Action.CLOUD_BACKUP), a = s.sources)
        s.cloudConfigured -> result(Step.SOURCES, State.DONE, Detail.SOURCES_CLOUD, Action.CLOUD_BACKUP, listOf(Action.RESTORE_FILE, Action.OPEN_SOURCES))
        else -> result(Step.SOURCES, State.TODO, Detail.SOURCES_NONE, Action.RESTORE_FILE, listOf(Action.CLOUD_BACKUP, Action.OPEN_SOURCES))
    }

    private fun library(s: Snapshot): StepResult = when {
        !s.libraryFolderSet -> result(Step.LIBRARY, State.TODO, Detail.LIBRARY_NO_FOLDER, Action.PICK_FOLDER)
        s.consolesWithGames > 0 -> result(Step.LIBRARY, State.DONE, Detail.LIBRARY_OK, Action.LIBRARY_OVERVIEW, listOf(Action.SCAN_LIBRARY), a = s.consolesWithGames)
        // Files were found, but no console of the sources claims their folders.
        s.filesOnDisk -> result(Step.LIBRARY, State.TODO, Detail.LIBRARY_UNMATCHED, Action.LIBRARY_OVERVIEW, listOf(Action.OPEN_SOURCES, Action.SCAN_LIBRARY))
        else -> result(Step.LIBRARY, State.TODO, Detail.LIBRARY_EMPTY, Action.SCAN_LIBRARY, listOf(Action.LIBRARY_OVERVIEW, Action.PICK_FOLDER))
    }

    /**
     * Consoles with games decide; while there are none yet, the consoles of the favourites and
     * the wishlist do (their games are what will come). Consoles the catalogue cannot judge are
     * left out of the verdict.
     */
    private fun emulators(s: Snapshot): StepResult {
        val judged = s.emulators.filter { it.judged }
        val withGames = judged.filter { it.hasGames }
        val relevant = withGames.ifEmpty { judged }
        val missing = judged.filter { !it.launchable }
            .sortedWith(compareBy<ConsoleEmulators>({ !it.hasGames }, { it.name.lowercase() }, { it.consoleId }))
        val base = when {
            relevant.isEmpty() && s.emulators.any { it.hasGames } ->
                result(Step.EMULATORS, State.TODO, Detail.EMULATORS_UNCHECKED, Action.EMULATOR_SETTINGS)
            relevant.isEmpty() -> result(Step.EMULATORS, State.TODO, Detail.EMULATORS_WAITING, Action.EMULATOR_SETTINGS)
            relevant.all { it.launchable } -> result(Step.EMULATORS, State.DONE, Detail.EMULATORS_OK, Action.EMULATOR_SETTINGS)
            else -> result(
                Step.EMULATORS, State.TODO, Detail.EMULATORS_MISSING, Action.EMULATOR_CHECK, listOf(Action.EMULATOR_SETTINGS),
                a = relevant.count { !it.launchable }, b = relevant.size
            )
        }
        return base.copy(missingEmulators = missing)
    }

    /**
     * Favourites and collection games missing here make the step to do; the wishlist and the
     * RomM server are shown for information only (nobody wants a whole server on a handheld).
     */
    private fun games(s: Snapshot): StepResult {
        val counts = buildList {
            if (s.favourites > 0) add(Count(CountKind.FAVOURITES, s.favouritesMissing, s.favourites))
            if (s.collectionGames > 0) add(Count(CountKind.COLLECTIONS, s.collectionGamesMissing, s.collectionGames))
            if (s.wishes > 0) add(Count(CountKind.WISHLIST, s.wishesMissing, s.wishes))
            if (s.rommConfigured && s.rommGames > 0) add(Count(CountKind.ROMM, s.rommMissing, s.rommGames))
        }
        val collectionAction = if (s.collectionsMissing == 1) Action.DOWNLOAD_COLLECTION else Action.OPEN_COLLECTIONS
        val missing = s.favouritesMissing + s.collectionGamesMissing
        val base = when {
            counts.isEmpty() && s.sources == 0 && !s.cloudConfigured ->
                result(Step.GAMES, State.TODO, Detail.GAMES_WAITING, Action.COPY_GAMES)
            counts.isEmpty() -> result(Step.GAMES, State.NOT_NEEDED, Detail.GAMES_NOTHING, Action.COPY_GAMES)
            missing > 0 -> {
                val primary = if (s.favouritesMissing > 0) Action.DOWNLOAD_FAVOURITES else collectionAction
                val more = buildList {
                    if (s.collectionGamesMissing > 0 && primary != collectionAction) add(collectionAction)
                    if (s.wishesMissing > 0) add(Action.OPEN_WISHLIST)
                    add(Action.COPY_GAMES)
                }
                result(Step.GAMES, State.TODO, Detail.GAMES_MISSING, primary, more, a = missing)
            }
            else -> result(Step.GAMES, State.DONE, Detail.GAMES_OK, Action.COPY_GAMES, if (s.wishesMissing > 0) listOf(Action.OPEN_WISHLIST) else emptyList())
        }
        return base.copy(counts = counts)
    }

    /** Save sync works through RomM: without a RomM server it is not needed. */
    private fun saves(s: Snapshot): StepResult = when {
        !s.rommConfigured -> result(Step.SAVES, State.NOT_NEEDED, Detail.SAVES_NO_ROMM, Action.OPEN_ROMM)
        !s.saveFoldersSet -> result(Step.SAVES, State.TODO, Detail.SAVES_NO_FOLDERS, Action.OPEN_SAVE_SYNC)
        s.saveSyncRunning -> result(Step.SAVES, State.TODO, Detail.SAVES_RUNNING, Action.OPEN_SAVE_SYNC)
        s.saveError -> result(Step.SAVES, State.TODO, Detail.SAVES_ERROR, Action.OPEN_SAVE_SYNC)
        s.saveConflicts > 0 -> result(Step.SAVES, State.TODO, Detail.SAVES_CONFLICTS, Action.OPEN_SAVE_SYNC, a = s.saveConflicts)
        s.saveSyncDone -> result(Step.SAVES, State.DONE, Detail.SAVES_OK, Action.OPEN_SAVE_SYNC, listOf(Action.SYNC_SAVES))
        s.saveSyncAuto -> result(Step.SAVES, State.DONE, Detail.SAVES_AUTO, Action.OPEN_SAVE_SYNC, listOf(Action.SYNC_SAVES))
        else -> result(Step.SAVES, State.TODO, Detail.SAVES_READY, Action.SYNC_SAVES, listOf(Action.OPEN_SAVE_SYNC))
    }

    /** Optional: which launcher is in use cannot always be told (Daijishō, Pegasus), so it never says "not needed". */
    private fun launcher(s: Snapshot): StepResult =
        if (s.frontendsReady > 0 || s.coversFolderSet) result(Step.LAUNCHER, State.DONE, Detail.LAUNCHER_OK, Action.FRONTEND_CHECK, listOf(Action.FRONTEND_COVERS))
        else result(Step.LAUNCHER, State.TODO, Detail.LAUNCHER_TODO, Action.FRONTEND_CHECK, listOf(Action.FRONTEND_COVERS))

    // ---- Stored marks --------------------------------------------------------------------------

    /** Keys of the checklist's own DataStore file ("new_handheld"); never part of `user_settings`. */
    const val KEY_MARKS = "new_handheld_marks"
    const val KEY_SAVES_SYNCED_AT = "new_handheld_saves_synced_at"
    const val KEY_HINT_DISMISSED = "new_handheld_hint_dismissed"

    /** Their types, for [SettingSchema] (which knows every preference key the app reads). */
    val SETTING_TYPES: Map<String, String> = mapOf(KEY_MARKS to "ss", KEY_SAVES_SYNCED_AT to "l", KEY_HINT_DISMISSED to "b")

    /** The stored form: one `STEP=MARK` entry per marked step; unknown entries are ignored. */
    fun encodeMarks(marks: Map<Step, Mark>): Set<String> = marks.mapTo(HashSet()) { (step, mark) -> "${step.name}=${mark.name}" }

    fun decodeMarks(stored: Set<String>?): Map<Step, Mark> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<Step, Mark>()
        for (entry in stored) {
            val step = Step.entries.firstOrNull { it.name == entry.substringBefore('=') } ?: continue
            val mark = Mark.entries.firstOrNull { it.name == entry.substringAfter('=', "") } ?: continue
            out[step] = mark
        }
        return out
    }

    // ---- Counting against the on-disk index ----------------------------------------------------

    /**
     * How many of [games] (console id, file name) are not on this handheld by the on-disk index
     * [ownedKeys]: the same rule as [LibraryKeys.isOwned], with each console's scopes worked out
     * once instead of once per game (a RomM list can hold thousands).
     */
    fun missingCount(games: Collection<Pair<String, String>>, ownedKeys: Set<String>): Int = missingGames(games, ownedKeys).size

    /** The games of [games] that are not on this handheld (see [missingCount]), in their order. */
    fun missingGames(games: Collection<Pair<String, String>>, ownedKeys: Set<String>): List<Pair<String, String>> {
        if (ownedKeys.isEmpty()) return games.toList()
        val scopes = HashMap<String, Set<String>>()
        return games.filter { (consoleId, fileName) ->
            val name = runCatching { FileParsingUtils.storageFileName(fileName) }.getOrNull()?.lowercase()
            if (name == null) true else {
                val base = LibraryKeys.baseName(name)
                scopes.getOrPut(consoleId) { LibraryKeys.scopesFor(consoleId) }.none { "$it|$name" in ownedKeys || "$it|$base" in ownedKeys }
            }
        }
    }

    /** A wish as [wishesOnDevice] needs it: its title and the scopes of its console (null = any console). */
    data class Wish(val title: String, val scopes: Set<String>?)

    /**
     * How many [wishes] are already on this handheld, by the same rule as [WishlistMatch.onDevice]
     * (every word of the wish in a file name). Cheap enough for a big library: a name can only
     * match when it holds the wish's longest word, so the full word check runs on those names only
     * (names with accented letters are always checked in full).
     */
    fun wishesOnDevice(wishes: List<Wish>, ownedKeys: Set<String>): Int {
        if (wishes.isEmpty() || ownedKeys.isEmpty()) return 0
        val names = ownedKeys.map { key -> OwnedName(key.substringBefore('|'), key.substringAfter('|')) }
        return wishes.count { wish ->
            val longest = GameTitleCleaner.words(wish.title).filter { w -> w.all { it in 'a'..'z' || it in '0'..'9' } }.maxByOrNull { it.length }
            names.any { n ->
                (wish.scopes == null || n.scope in wish.scopes) &&
                    (longest == null || !n.ascii || n.name.contains(longest)) &&
                    GameTitleCleaner.containsAllWords(wish.title, n.name)
            }
        }
    }

    private class OwnedName(val scope: String, val name: String) {
        val ascii: Boolean = name.all { it.code < 128 }
    }
}
