package com.cortinadev.dogmatix

import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadPlan
import com.cortinadev.dogmatix.util.DownloadPlanItem
import com.cortinadev.dogmatix.util.DownloadPlanStatus
import com.cortinadev.dogmatix.util.Profile
import com.cortinadev.dogmatix.util.Profiles
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end receiving-device resolution and confirmation, with the real queue held safely. */
class DownloadPlanServiceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun receivingDeviceResolvesExactIdentityAndAppliesEachConditionBeforeWorkersRun() = runBlocking {
        fixture { f ->
            val encoded = f.file("Plan%20Game.zip")
            val mirror = encoded.copy(id = 0, downloadUrl = f.hiddenSource + "/" + encoded.fileName, sourceUrl = f.hiddenSource)
            val crossConsoleOnly = f.file("other-console.zip", console = f.otherConsole)
            val disabled = f.file("disabled.zip", source = f.disabledSource)
            val hidden = f.file("hidden.zip", source = f.hiddenSource)
            val existing = f.file("existing.zip")
            val colliding = existing.copy(id = 0, consoleId = f.otherConsole)
            val next = f.file("next.zip")
            val dao = f.graph.database().downloadableFileDao()
            // Hidden source comes first in console configuration, but must never be chosen.
            dao.insertFiles(listOf(mirror, encoded, crossConsoleOnly, disabled, hidden, existing, colliding, next))
            val indexed = dao.filesByFileNames(listOf(encoded.fileName, hidden.fileName))
            dao.insertTags(indexed.filter { it.sourceUrl == f.hiddenSource }.map { FileTagEntity(it.id, "Adult") })
            f.graph.downloads().startDownload(existing, f.condition(3))
            eventually { existing.fileName in f.graph.downloads().itemWaits.value }
            val original = f.settingsSnapshot()
            val consoleBefore = f.graph.database().consoleDao().getConsoleById(f.console)
            val plan = DownloadPlan(listOf(
                f.item(next, f.condition(2)),
                f.item(encoded, f.condition(1)),
                DownloadPlanItem(f.console, f.name("Plan Game.zip"), "Decoded name is a different identity"),
                f.item(crossConsoleOnly).copy(consoleId = f.console),
                f.item(disabled),
                f.item(hidden),
                f.item(colliding)
            ))
            val uri = f.graph.plans().shareUri(plan)
            val preview = f.graph.plans().read(uri)
            assertEquals(listOf(
                DownloadPlanStatus.READY, DownloadPlanStatus.READY, DownloadPlanStatus.MISSING,
                DownloadPlanStatus.MISSING, DownloadPlanStatus.UNAVAILABLE, DownloadPlanStatus.RESTRICTED,
                DownloadPlanStatus.ALREADY_QUEUED
            ), preview.rows.map { it.status })
            assertTrue(f.graph.downloads().downloads.value.none { it.fileName == next.fileName || it.fileName == encoded.fileName })
            val accepted = f.graph.plans().confirm(preview.plan)
            assertEquals(2, accepted.readyCount)
            // Both conditions are registered synchronously, before the worker can acquire a slot.
            assertEquals(f.condition(2), f.graph.downloads().itemConditions.value[next.fileName])
            assertEquals(f.condition(1), f.graph.downloads().itemConditions.value[encoded.fileName])
            eventually {
                setOf(next.fileName, encoded.fileName).all { it in f.graph.downloads().itemWaits.value }
            }
            assertEquals(listOf(next.fileName, encoded.fileName),
                f.graph.downloads().downloads.value.filter { it.fileName in setOf(next.fileName, encoded.fileName) }.map { it.fileName })
            assertEquals(f.visibleSource, f.graph.downloads().entityFor(encoded.fileName)?.sourceUrl)
            assertEquals(f.console, f.graph.downloads().entityFor(existing.fileName)?.consoleId)
            assertTrue(listOf(existing.fileName, next.fileName, encoded.fileName).none(f.graph.downloads()::isTransferring))
            assertEquals(original, f.settingsSnapshot())
            assertEquals(consoleBefore, f.graph.database().consoleDao().getConsoleById(f.console))
            assertEquals(DownloadPlanStatus.ALREADY_QUEUED, f.graph.plans().preview(plan).rows.first().status)
        }
    }

    @Test fun confirmationRechecksSourcesAndProfileAndExportFollowsTheCurrentHeldQueue() = runBlocking {
        fixture { f ->
            val files = listOf(f.file("third.zip"), f.file("first.zip"), f.file("second.zip"), f.file("fourth.zip"))
            f.graph.database().downloadableFileDao().insertFiles(files)
            val plan = DownloadPlan(files.mapIndexed { i, file -> f.item(file, f.condition(i + 1)) })
            assertEquals(4, f.graph.plans().preview(plan).readyCount)
            f.profile(hideConsole = true)
            val restricted = f.graph.plans().confirm(plan)
            assertTrue(restricted.rows.all { it.status == DownloadPlanStatus.RESTRICTED })
            assertTrue(f.graph.downloads().downloads.value.none { it.fileName in files.map { file -> file.fileName } })
            f.profile()
            val console = f.graph.database().consoleDao().getConsoleById(f.console)!!
            f.graph.database().consoleDao().updateConsole(console.copy(urls = f.urls(visibleEnabled = false)))
            val unavailable = f.graph.plans().confirm(plan)
            assertTrue(unavailable.rows.all { it.status == DownloadPlanStatus.UNAVAILABLE })
            assertTrue(f.graph.downloads().downloads.value.none { it.fileName in files.map { file -> file.fileName } })
            f.graph.database().consoleDao().updateConsole(console)
            val original = f.settingsSnapshot()
            assertEquals(4, f.graph.plans().confirm(plan).readyCount)
            eventually { files.all { it.fileName in f.graph.downloads().itemWaits.value } }
            val selected = files.mapTo(HashSet()) { it.fileName }
            val before = f.graph.plans().export(selected)
            assertEquals(plan.items, before.items)
            // Releasing individual conditions under the global hold fills slots and the queue
            // without contacting a server or writing a ROM. Asynchronous arrivals retain ranks.
            f.graph.downloads().setCondition(selected, null)
            eventually { f.graph.downloads().queued.value.count { it in selected } == files.size - 1 }
            val waiting = f.graph.downloads().queued.value.filter { it in selected }
            assertEquals(files.map { it.fileName }.filter { it in waiting.toSet() }, waiting)
            if (waiting.size > 1) f.graph.downloads().moveToFront(waiting.last())
            val reordered = f.graph.downloads().queued.value.filter { it in selected }
            val after = f.graph.plans().export(selected)
            assertEquals(reordered, after.items.take(reordered.size).map { it.fileName })
            assertTrue(after.items.all { it.condition == null })
            assertTrue(files.none { f.graph.downloads().isTransferring(it.fileName) })
            assertEquals(original, f.settingsSnapshot())
        }
    }

    private inner class Fixture(val graph: DownloadPlanIntegrationEntryPoint, val prefix: String, val oldProfiles: String) {
        val console = prefix + "_a"
        val otherConsole = prefix + "_b"
        val visibleSource = "https://plan-service.invalid/" + prefix + "/visible"
        val hiddenSource = "https://plan-service.invalid/" + prefix + "/hidden"
        val disabledSource = "https://plan-service.invalid/" + prefix + "/disabled"
        val names = mutableSetOf<String>()
        private val now = System.currentTimeMillis()
        fun name(suffix: String): String = (prefix + "-" + suffix).also { names += it }
        fun file(suffix: String, console: String = this.console, source: String = visibleSource) =
            DownloadableFileEntity(name = "Portable " + suffix, fileName = name(suffix), consoleId = console,
                downloadUrl = source + "/" + prefix + "-" + suffix, sourceUrl = source, fileSize = 1024L)
        fun item(file: DownloadableFileEntity, condition: DownloadCondition? = null) =
            DownloadPlanItem(file.consoleId, file.fileName, file.name, condition)
        fun condition(days: Int) = DownloadCondition(ConditionKind.AT_TIME, days * 60, now + days * 86_400_000L)
        fun urls(visibleEnabled: Boolean = true) = SourcesJson.serializeUrlEntries(listOf(
            UrlEntry(hiddenSource), UrlEntry(visibleSource, enabled = visibleEnabled), UrlEntry(disabledSource, enabled = false)
        ))
        suspend fun profile(hideConsole: Boolean = false) {
            val profile = Profile(prefix, "Plan regression", hiddenConsoles = if (hideConsole) setOf(console) else emptySet(), hiddenTags = setOf("Adult"))
            graph.appSettings().setProfiles(Profiles.toJson(Profiles.fromJson(oldProfiles) + profile))
            graph.appSettings().setActiveProfile(prefix)
        }
        suspend fun settingsSnapshot(): List<Any> {
            val settings = graph.planSettings()
            return listOf(settings.concurrentDownloads.first(), settings.limitSpeed.first(), settings.downloadWifiOnly.first(),
                settings.downloadChargingOnly.first(), settings.downloadNightOnly.first(), settings.downloadNightStart.first(),
                settings.downloadNightEnd.first(), settings.downloadDirectory.first(), settings.consoleDownloadDirectories.first(),
                graph.downloads().gate.held.value)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, DownloadPlanIntegrationEntryPoint::class.java)
        val downloads = graph.downloads()
        val storedProfiles = graph.appSettings().profiles.first()
        val storedProfile = graph.appSettings().activeProfile.first()
        val slots = graph.planSettings().concurrentDownloads.first()
        val held = downloads.gate.held.value
        val f = Fixture(graph, "plan-service-" + UUID.randomUUID(), storedProfiles)
        try {
            downloads.gate.setHeld(true)
            eventually { downloads.gate.held.value }
            graph.planSettings().setConcurrentDownloads(1)
            f.profile()
            graph.database().consoleDao().insertConsoles(listOf(
                ConsoleEntity(f.console, "Plan A", "plan-regression", f.urls()),
                ConsoleEntity(f.otherConsole, "Plan B", "plan-regression", f.urls())
            ))
            block(f)
        } finally {
            f.names.forEach { downloads.deleteDownload(it, deleteFile = false) }
            eventually {
                downloads.downloads.value.none { it.fileName in f.names } &&
                    graph.database().downloadHistoryDao().getAll().none { it.fileName in f.names }
            }
            graph.database().consoleDao().deleteConsoleById(f.console)
            graph.database().consoleDao().deleteConsoleById(f.otherConsole)
            graph.appSettings().setProfiles(storedProfiles)
            graph.appSettings().setActiveProfile(storedProfile)
            graph.planSettings().setConcurrentDownloads(slots)
            downloads.gate.setHeld(held)
            eventually { downloads.gate.held.value == held }
        }
    }

    private suspend fun eventually(block: suspend () -> Boolean) = withTimeout(15_000) {
        while (!block()) delay(25)
    }
}
