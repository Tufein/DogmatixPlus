package com.cortinadev.dogmatix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadSourceChoices
import com.cortinadev.dogmatix.data.model.DownloadSourceKind
import com.cortinadev.dogmatix.data.model.DownloadSourceOption
import com.cortinadev.dogmatix.data.model.SourceChangeResult
import com.cortinadev.dogmatix.data.service.PartialDownloads
import com.cortinadev.dogmatix.ui.screens.download.DownloadSourceChoiceDialog
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.Profile
import com.cortinadev.dogmatix.util.Profiles
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real held queue and index; no remote request or ROM folder is used. */
class ManualSourceRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun confirmedChoiceIsPinnedAndOldPartialRemovedBeforeItsHistoryAndQueueAreReleased() = runBlocking {
        fixture { graph, a, b, condition ->
            val choices = graph.downloads().sourceChoices(a.fileName)!!
            assertEquals(2, choices.options.size)
            assertTrue(choices.options.none { it.label.contains("secret") || it.label.contains("private") || it.label.contains("@") })
            val picked = choices.options.single { !it.current }
            // The first source remains the ranked best. A manual choice must bypass pickBest.
            graph.sourcePick().setPickBest(true)
            graph.partials().put(a.fileName, PartialDownloads.Record(a.downloadUrl, "etag-old"))
            assertEquals(SourceChangeResult.STARTED, graph.downloads().changeSource(choices, picked.id))
            eventually {
                graph.partials().get(a.fileName) == null &&
                    graph.database().downloadHistoryDao().getAll().any { it.fileName == a.fileName && it.downloadUrl == b.downloadUrl }
            }
            val after = graph.downloads().sourceChoices(a.fileName)!!
            assertEquals("second.test", after.options.single { it.current }.label)
            assertEquals(b.downloadUrl, graph.downloads().entityFor(a.fileName)?.downloadUrl)
            assertEquals(condition, graph.downloads().itemConditions.value[a.fileName])
            assertFalse(graph.downloads().isTransferring(a.fileName))
            assertEquals(SourceChangeResult.STALE, graph.downloads().changeSource(choices, picked.id))
        }
    }

    @Test fun stopWhileChoicesAreOpenWinsAgainstTheirConfirmation() = runBlocking {
        fixture { graph, a, _, _ ->
            val choices = graph.downloads().sourceChoices(a.fileName)!!
            graph.downloads().cancelDownload(a.fileName)
            assertEquals(SourceChangeResult.STALE, graph.downloads().changeSource(choices, choices.options.single { !it.current }.id))
            assertEquals(a.downloadUrl, graph.downloads().entityFor(a.fileName)?.downloadUrl)
            assertFalse(graph.downloads().isTransferring(a.fileName))
        }
    }

    @Test fun disablingTheChosenSourceAfterLoadingPreventsRestart() = runBlocking {
        fixture { graph, a, _, _ ->
            val choices = graph.downloads().sourceChoices(a.fileName)!!
            val console = graph.database().consoleDao().getConsoleById(a.consoleId)!!
            graph.database().consoleDao().updateConsole(console.copy(urls = urls(secondEnabled = false)))
            assertEquals(SourceChangeResult.UNAVAILABLE, graph.downloads().changeSource(choices, choices.options.single { !it.current }.id))
            assertEquals(a.downloadUrl, graph.downloads().entityFor(a.fileName)?.downloadUrl)
        }
    }

    @Test fun switchingToRestrictedProfileAfterLoadingPreventsSourceChange() = runBlocking {
        fixture { graph, a, _, _ ->
            val settings = graph.appSettings()
            val originalProfiles = settings.profiles.first()
            val originalActive = settings.activeProfile.first()
            val choices = graph.downloads().sourceChoices(a.fileName)!!
            try {
                val profile = Profile("manual-source-child-${UUID.randomUUID()}", "Test", hiddenConsoles = setOf(a.consoleId))
                settings.setProfiles(Profiles.toJson(Profiles.fromJson(originalProfiles) + profile))
                settings.setActiveProfile(profile.id)
                assertEquals(SourceChangeResult.RESTRICTED, graph.downloads().changeSource(choices, choices.options.single { !it.current }.id))
                assertEquals(a.downloadUrl, graph.downloads().entityFor(a.fileName)?.downloadUrl)
            } finally {
                settings.setActiveProfile(originalActive)
                settings.setProfiles(originalProfiles)
            }
        }
    }

    @Test fun choosingARadioOptionDoesNothingUntilTheUserConfirmsThatExactOption() {
        var starts = 0
        var chosen = ""
        val choices = DownloadSourceChoices("opaque-session", "Game.gba", listOf(
            DownloadSourceOption("current-id", "first.test", true, DownloadSourceKind.WEB),
            DownloadSourceOption("exact-choice-id", "second.test", false, DownloadSourceKind.WEB)
        ))
        compose.setContent {
            DogmatixTheme {
                DownloadSourceChoiceDialog(choices, false, false, onDismiss = {}, onConfirm = { starts++; chosen = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.source26_restart_hint)).assertIsDisplayed()
        compose.onNodeWithText("second.test").performClick()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText(context.getString(R.string.source26_confirm)).performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals("exact-choice-id", chosen) }
    }

    private suspend fun fixture(block: suspend (ManualSourceEntryPoint, DownloadableFileEntity, DownloadableFileEntity, DownloadCondition) -> Unit) {
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, ManualSourceEntryPoint::class.java)
        val downloads = graph.downloads()
        val db = graph.database()
        val id = "manual-source-${UUID.randomUUID()}"
        val name = "$id.gba"
        val a = DownloadableFileEntity(name = "Source regression", fileName = name, consoleId = id,
            downloadUrl = "https://first.test/$name", sourceUrl = FIRST_SOURCE, fileSize = 1024L)
        val b = a.copy(downloadUrl = "https://second.test/$name", sourceUrl = SECOND_SOURCE)
        val disabled = a.copy(downloadUrl = "https://disabled.test/$name", sourceUrl = "https://disabled.test/")
        val hold = downloads.gate.held.value
        val pickBest = graph.sourcePick().pickBest.first()
        val condition = DownloadCondition(ConditionKind.AT_TIME, atMillis = System.currentTimeMillis() + 86_400_000L)
        try {
            downloads.gate.setHeld(true)
            graph.sourcePick().setPickBest(false)
            db.consoleDao().insertConsole(ConsoleEntity(id, "Source regression", "manual-regression", urls()))
            db.downloadableFileDao().insertFiles(listOf(a, b, disabled))
            withContext(Dispatchers.Default) { downloads.startDownload(a, condition) }
            block(graph, a, b, condition)
        } finally {
            withContext(Dispatchers.Default) { downloads.deleteDownload(name) }
            eventually { db.downloadHistoryDao().getAll().none { it.fileName == name } && downloads.entityFor(name) == null }
            graph.partials().remove(name)
            db.consoleDao().deleteConsoleById(id)
            graph.sourcePick().setPickBest(pickBest)
            downloads.gate.setHeld(hold)
        }
    }

    private suspend fun eventually(block: suspend () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(25)
    }

    private fun urls(secondEnabled: Boolean = true) = """[
        {"url":"$FIRST_SOURCE","enabled":true},
        {"url":"$SECOND_SOURCE","enabled":$secondEnabled},
        {"url":"https://disabled.test/","enabled":false}
    ]"""

    companion object {
        private const val FIRST_SOURCE = "https://user:secret@first.test/private?token=secret"
        private const val SECOND_SOURCE = "https://second.test/"
    }
}
