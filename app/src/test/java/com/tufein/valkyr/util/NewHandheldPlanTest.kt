package com.tufein.valkyr.util

import com.tufein.valkyr.util.NewHandheldPlan.Action
import com.tufein.valkyr.util.NewHandheldPlan.ConsoleEmulators
import com.tufein.valkyr.util.NewHandheldPlan.CountKind
import com.tufein.valkyr.util.NewHandheldPlan.Detail
import com.tufein.valkyr.util.NewHandheldPlan.Mark
import com.tufein.valkyr.util.NewHandheldPlan.Snapshot
import com.tufein.valkyr.util.NewHandheldPlan.State
import com.tufein.valkyr.util.NewHandheldPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewHandheldPlanTest {

    private fun step(s: Snapshot, step: Step, marks: Map<Step, Mark> = emptyMap()) =
        NewHandheldPlan.plan(s, marks).single { it.step == step }

    private fun console(id: String, hasGames: Boolean = true, known: Boolean = true, launchable: Boolean = false) =
        ConsoleEmulators(id, id.uppercase(), hasGames, known, launchable, suggestion = if (known) "RetroArch" else null)

    /** A handheld that has everything: every step done. */
    private val ready = Snapshot(
        sources = 3, libraryFolderSet = true, filesOnDisk = true, consolesWithGames = 2,
        emulators = listOf(console("snes", launchable = true), console("gba", launchable = true)),
        favourites = 4, collectionGames = 6, rommConfigured = true, rommGames = 100, rommMissing = 80,
        saveFoldersSet = true, saveSyncDone = true, frontendsReady = 1
    )

    @Test fun `a fresh install has every required step to do and starts with the settings`() {
        val steps = NewHandheldPlan.plan(Snapshot())
        assertEquals(Step.entries, steps.map { it.step })
        assertEquals(State.TODO, steps[0].state)
        assertEquals(Detail.SOURCES_NONE, steps[0].detail)
        assertEquals(Action.RESTORE_FILE, steps[0].primary)
        assertEquals(Detail.LIBRARY_NO_FOLDER, steps[1].detail)
        assertEquals(Detail.EMULATORS_WAITING, steps[2].detail)
        assertEquals(Detail.GAMES_WAITING, steps[3].detail)
        // Without RomM there is no save sync to set up.
        assertEquals(State.NOT_NEEDED, steps[4].state)
        val summary = NewHandheldPlan.summary(steps)
        assertEquals(Step.SOURCES, summary.next)
        assertEquals(1, summary.settled)
        assertEquals(6, summary.total)
        assertEquals(4, summary.requiredOpen)
        assertFalse(summary.ready)
    }

    @Test fun `a handheld with everything is ready`() {
        val steps = NewHandheldPlan.plan(ready)
        assertTrue(steps.all { it.state == State.DONE })
        val summary = NewHandheldPlan.summary(steps)
        assertTrue(summary.ready)
        assertNull(summary.next)
        assertEquals(1f, summary.fraction)
    }

    @Test fun `sources are done with sources or the user's own cloud`() {
        assertEquals(Detail.SOURCES_OK, step(Snapshot(sources = 2), Step.SOURCES).detail)
        assertEquals(2, step(Snapshot(sources = 2), Step.SOURCES).a)
        val cloud = step(Snapshot(cloudConfigured = true), Step.SOURCES)
        assertEquals(State.DONE, cloud.state)
        assertEquals(Detail.SOURCES_CLOUD, cloud.detail)
        assertEquals(Action.CLOUD_BACKUP, cloud.primary)
    }

    @Test fun `library needs a folder and consoles with games`() {
        assertEquals(Action.PICK_FOLDER, step(Snapshot(), Step.LIBRARY).primary)
        val empty = step(Snapshot(libraryFolderSet = true), Step.LIBRARY)
        assertEquals(Detail.LIBRARY_EMPTY, empty.detail)
        assertEquals(Action.SCAN_LIBRARY, empty.primary)
        val unmatched = step(Snapshot(libraryFolderSet = true, filesOnDisk = true), Step.LIBRARY)
        assertEquals(State.TODO, unmatched.state)
        assertEquals(Detail.LIBRARY_UNMATCHED, unmatched.detail)
        val ok = step(Snapshot(libraryFolderSet = true, filesOnDisk = true, consolesWithGames = 3), Step.LIBRARY)
        assertEquals(State.DONE, ok.state)
        assertEquals(3, ok.a)
    }

    @Test fun `emulators judge the consoles with games and list what is missing`() {
        val s = Snapshot(emulators = listOf(
            console("snes", launchable = true),
            console("psx"),
            console("n64", hasGames = false),
            console("odd", known = false)
        ))
        val r = step(s, Step.EMULATORS)
        assertEquals(State.TODO, r.state)
        assertEquals(Detail.EMULATORS_MISSING, r.detail)
        assertEquals(Action.EMULATOR_CHECK, r.primary)
        // Two judged consoles with games, one of them without an emulator.
        assertEquals(1, r.a)
        assertEquals(2, r.b)
        // Consoles with games first; the one the catalogue cannot judge is left out.
        assertEquals(listOf("psx", "n64"), r.missingEmulators.map { it.consoleId })
    }

    @Test fun `emulators done when every console with games can start them`() {
        val s = Snapshot(emulators = listOf(console("snes", launchable = true), console("n64", hasGames = false)))
        val r = step(s, Step.EMULATORS)
        assertEquals(State.DONE, r.state)
        // A wanted console without an emulator is still listed, but does not block.
        assertEquals(listOf("n64"), r.missingEmulators.map { it.consoleId })
    }

    @Test fun `emulators look at favourites and wishes while there are no games`() {
        val r = step(Snapshot(emulators = listOf(console("gba", hasGames = false))), Step.EMULATORS)
        assertEquals(Detail.EMULATORS_MISSING, r.detail)
        assertEquals(1, r.b)
        val unknown = step(Snapshot(emulators = listOf(console("odd", known = false))), Step.EMULATORS)
        assertEquals(Detail.EMULATORS_UNCHECKED, unknown.detail)
        // An app picked by hand counts even when the catalogue does not know the console.
        val own = step(Snapshot(emulators = listOf(console("odd", known = false, launchable = true))), Step.EMULATORS)
        assertEquals(State.DONE, own.state)
    }

    @Test fun `games offer the favourites download first and count everything`() {
        val s = Snapshot(
            sources = 1, favourites = 10, favouritesMissing = 4, collectionGames = 5, collectionGamesMissing = 2,
            collectionsMissing = 1, wishes = 3, wishesMissing = 3, rommConfigured = true, rommGames = 50, rommMissing = 40
        )
        val r = step(s, Step.GAMES)
        assertEquals(State.TODO, r.state)
        assertEquals(6, r.a)
        assertEquals(Action.DOWNLOAD_FAVOURITES, r.primary)
        assertEquals(listOf(Action.DOWNLOAD_COLLECTION, Action.OPEN_WISHLIST, Action.COPY_GAMES), r.more)
        assertEquals(listOf(CountKind.FAVOURITES, CountKind.COLLECTIONS, CountKind.WISHLIST, CountKind.ROMM), r.counts.map { it.kind })
        assertEquals(40, r.counts.last().missing)
    }

    @Test fun `games with several collections missing open the collections`() {
        val r = step(Snapshot(sources = 1, collectionGames = 5, collectionGamesMissing = 5, collectionsMissing = 2), Step.GAMES)
        assertEquals(Action.OPEN_COLLECTIONS, r.primary)
        assertEquals(listOf(Action.COPY_GAMES), r.more)
    }

    @Test fun `wishlist and RomM alone never make the games step to do`() {
        val r = step(Snapshot(sources = 1, wishes = 5, wishesMissing = 5, rommConfigured = true, rommGames = 9, rommMissing = 9), Step.GAMES)
        assertEquals(State.DONE, r.state)
        assertEquals(listOf(Action.OPEN_WISHLIST), r.more)
        val nothing = step(Snapshot(sources = 1), Step.GAMES)
        assertEquals(State.NOT_NEEDED, nothing.state)
        assertEquals(Detail.GAMES_NOTHING, nothing.detail)
        // RomM games only count while RomM is set up.
        assertTrue(step(Snapshot(sources = 1, rommGames = 9), Step.GAMES).counts.isEmpty())
    }

    @Test fun `saves follow the save sync`() {
        val romm = Snapshot(rommConfigured = true)
        assertEquals(Detail.SAVES_NO_FOLDERS, step(romm, Step.SAVES).detail)
        val folders = romm.copy(saveFoldersSet = true)
        val first = step(folders, Step.SAVES)
        assertEquals(Detail.SAVES_READY, first.detail)
        assertEquals(Action.SYNC_SAVES, first.primary)
        assertEquals(Detail.SAVES_RUNNING, step(folders.copy(saveSyncRunning = true, saveSyncDone = true), Step.SAVES).detail)
        assertEquals(Detail.SAVES_ERROR, step(folders.copy(saveError = true, saveSyncDone = true), Step.SAVES).detail)
        val conflicts = step(folders.copy(saveConflicts = 2, saveSyncDone = true), Step.SAVES)
        assertEquals(State.TODO, conflicts.state)
        assertEquals(2, conflicts.a)
        assertEquals(State.DONE, step(folders.copy(saveSyncDone = true), Step.SAVES).state)
        assertEquals(Detail.SAVES_AUTO, step(folders.copy(saveSyncAuto = true), Step.SAVES).detail)
    }

    @Test fun `launcher is optional and never blocks ready`() {
        val steps = NewHandheldPlan.plan(ready.copy(frontendsReady = 0))
        val launcher = steps.single { it.step == Step.LAUNCHER }
        assertEquals(State.TODO, launcher.state)
        val summary = NewHandheldPlan.summary(steps)
        assertTrue(summary.ready)
        assertEquals(Step.LAUNCHER, summary.next)
        assertEquals(State.DONE, step(Snapshot(coversFolderSet = true), Step.LAUNCHER).state)
    }

    @Test fun `marks settle only steps still to do and can be undone`() {
        val marks = mapOf(Step.SOURCES to Mark.SKIPPED, Step.LIBRARY to Mark.DONE, Step.SAVES to Mark.SKIPPED)
        val steps = NewHandheldPlan.plan(Snapshot(), marks)
        val sources = steps.single { it.step == Step.SOURCES }
        assertEquals(State.SKIPPED, sources.state)
        assertTrue(sources.markApplies)
        assertEquals(State.DONE, steps.single { it.step == Step.LIBRARY }.state)
        // The app already sees saves as not needed: the mark changes nothing.
        val saves = steps.single { it.step == Step.SAVES }
        assertEquals(State.NOT_NEEDED, saves.state)
        assertFalse(saves.markApplies)
        // Required steps first: the emulators are next, not the skipped settings.
        assertEquals(Step.EMULATORS, NewHandheldPlan.summary(steps).next)
        assertEquals(State.TODO, NewHandheldPlan.plan(Snapshot()).first().state)
    }

    @Test fun `a step the app finds done again stays done whatever the mark`() {
        assertEquals(State.DONE, NewHandheldPlan.combine(State.DONE, Mark.SKIPPED))
        assertEquals(State.NOT_NEEDED, NewHandheldPlan.combine(State.NOT_NEEDED, Mark.DONE))
        assertEquals(State.SKIPPED, NewHandheldPlan.combine(State.TODO, Mark.SKIPPED))
        assertEquals(State.TODO, NewHandheldPlan.combine(State.TODO, null))
    }

    @Test fun `marks survive storing and ignore what they do not know`() {
        val marks = mapOf(Step.GAMES to Mark.DONE, Step.LAUNCHER to Mark.SKIPPED)
        assertEquals(marks, NewHandheldPlan.decodeMarks(NewHandheldPlan.encodeMarks(marks)))
        assertEquals(mapOf(Step.SAVES to Mark.DONE), NewHandheldPlan.decodeMarks(setOf("SAVES=DONE", "BIOS=DONE", "GAMES=MAYBE", "junk")))
        assertTrue(NewHandheldPlan.decodeMarks(null).isEmpty())
    }

    @Test fun `the checklist's own keys are known with their types`() {
        assertEquals("ss", SettingSchema.expectedType(NewHandheldPlan.KEY_MARKS))
        assertEquals("l", SettingSchema.expectedType(NewHandheldPlan.KEY_SAVES_SYNCED_AT))
        assertEquals("b", SettingSchema.expectedType(NewHandheldPlan.KEY_HINT_DISMISSED))
    }

    @Test fun `missing games follow the library's own ownership rule`() {
        val snes = "nintendo_super_nintendo_entertainment_system"
        val gba = "nintendo_gameboy_advance"
        val owned = (LibraryKeys.keysFor(LibraryKeys.folderScope("snes"), "Chrono Trigger (USA).sfc") +
            LibraryKeys.keysFor(LibraryKeys.folderScope("gba"), "Metroid Fusion (USA).gba") +
            LibraryKeys.keysFor(LibraryKeys.ROOT_SCOPE, "Loose Game.zip")).toSet()
        val games = listOf(
            snes to "Chrono Trigger (USA).zip",
            snes to "Chrono Trigger (USA).sfc",
            snes to "Metroid Fusion (USA).gba",
            gba to "Metroid%20Fusion%20(USA).zip",
            gba to "Loose Game.zip",
            gba to "Golden Sun (USA).gba",
            snes to ""
        )
        val expected = games.filter { (console, file) -> !LibraryKeys.isOwned(console, file, owned) }
        assertEquals(expected, NewHandheldPlan.missingGames(games, owned))
        assertEquals(expected.size, NewHandheldPlan.missingCount(games, owned))
        assertEquals(listOf(snes to "Metroid Fusion (USA).gba", gba to "Golden Sun (USA).gba", snes to ""), expected)
        assertEquals(games.size, NewHandheldPlan.missingCount(games, emptySet()))
    }

    @Test fun `wishes on the handheld match the wishlist's own rule`() {
        val owned = (LibraryKeys.keysFor("gba", "Golden Sun (USA).gba") +
            LibraryKeys.keysFor("snes", "Chrono Trigger (USA).sfc") +
            LibraryKeys.keysFor("snes", "Legend of Zelda, The - A Link to the Past (USA).sfc") +
            LibraryKeys.keysFor("psx", "Pokémon Stadium (Europe).bin") +
            LibraryKeys.keysFor("snes", "Super Mario World 2 - Yoshi's Island (USA).sfc")).toSet()
        val wishes = listOf(
            NewHandheldPlan.Wish("Golden Sun", setOf("gba")),
            NewHandheldPlan.Wish("Golden Sun", setOf("snes")),
            NewHandheldPlan.Wish("Chrono Trigger", null),
            NewHandheldPlan.Wish("The Legend of Zelda: A Link to the Past", null),
            NewHandheldPlan.Wish("Pokemon Stadium", null),
            NewHandheldPlan.Wish("Yoshi's Island", setOf("snes")),
            NewHandheldPlan.Wish("Secret of Mana", null),
            NewHandheldPlan.Wish("The", null)
        )
        val expected = wishes.count { WishlistMatch.onDevice(it.title, it.scopes, owned) }
        assertEquals(expected, NewHandheldPlan.wishesOnDevice(wishes, owned))
        assertEquals(5, expected)
        assertEquals(0, NewHandheldPlan.wishesOnDevice(wishes, emptySet()))
    }
}
