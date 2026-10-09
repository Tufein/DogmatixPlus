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
import com.cortinadev.dogmatix.util.DownloadPlans
import com.cortinadev.dogmatix.util.Profile
import com.cortinadev.dogmatix.util.Profiles
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.ui.screens.download.DownloadPlanViewModel
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

    @Test fun hrefExportOmitsCredentialsAndImportUsesReceiverNamesUrlsAndConditions() = runBlocking {
        fixture { f ->
            val dao = f.graph.database().downloadableFileDao()
            val downloads = f.graph.downloads()
            // Occupy the only slot under the global hold. A WIFI condition then remains
            // registered regardless of the emulator's actual network capabilities.
            val blocker = f.file("blocker.zip")
            dao.insertFiles(listOf(blocker))
            downloads.startDownload(blocker)
            eventually { blocker.fileName in downloads.waitingFiles.value }

            val basenames = listOf(f.name("query%20Game.zip"), f.name("relative.zip"), f.name("absolute.zip"))
            val senderNames = listOf(
                basenames[0] + "?token=SENDER_QUERY_SECRET",
                "./" + basenames[1],
                "https://sender:SENDER_USERINFO_SECRET@sender.invalid/library/" + basenames[2] + "?token=SENDER_QUERY_SECRET"
            )
            val senders = senderNames.mapIndexed { i, raw ->
                f.names += raw
                DownloadableFileEntity(name = if (i == 0 || i == 2) raw else "Relative game", fileName = raw,
                    consoleId = f.console, downloadUrl = f.visibleSource + "/" + basenames[i],
                    sourceUrl = f.visibleSource, fileSize = 1024L)
            }
            downloads.startDownloads(senders, f.condition(2))
            eventually { senders.all { it.fileName in downloads.itemWaits.value } }
            val exported = f.graph.plans().export(senderNames.toSet())
            val json = DownloadPlans.encode(exported)
            assertEquals(basenames, exported.items.map { it.fileName })
            assertFalse(json.contains("SENDER_"))
            assertFalse(json.contains("sender.invalid"))
            assertFalse(json.contains("https://"))
            assertEquals(basenames[0], exported.items[0].displayName)
            assertEquals(basenames[2], exported.items[2].displayName)
            senderNames.forEach { downloads.deleteDownload(it, deleteFile = false) }
            eventually { downloads.downloads.value.none { it.fileName in senderNames } }

            val receiverNames = listOf(
                "./" + basenames[0] + "?token=RECEIVER_QUERY_SECRET",
                "folder/" + basenames[1] + "?auth=RECEIVER_QUERY_SECRET",
                f.visibleSource + "/" + basenames[2] + "?key=RECEIVER_QUERY_SECRET"
            )
            val receivers = receiverNames.mapIndexed { i, raw ->
                f.names += raw
                DownloadableFileEntity(name = "Receiving game $i", fileName = raw, consoleId = f.console,
                    downloadUrl = f.visibleSource + "/" + basenames[i] + "?token=RECEIVER_QUERY_SECRET",
                    sourceUrl = f.visibleSource, fileSize = 1024L)
            }
            dao.insertFiles(receivers)
            val wifi = DownloadCondition(ConditionKind.WIFI)
            val received = DownloadPlan(exported.items.mapIndexed { i, item -> item.copy(condition = if (i == 0) wifi else f.condition(3)) })
            val preview = f.graph.plans().preview(received)
            assertEquals(3, preview.readyCount)
            assertEquals(3, f.graph.plans().confirm(received).readyCount)
            assertEquals(wifi, downloads.itemConditions.value[receiverNames[0]])
            assertFalse(basenames[0] in downloads.itemConditions.value)
            receivers.forEach { receiver ->
                assertEquals(receiver.fileName, downloads.entityFor(receiver.fileName)?.fileName)
                assertEquals(receiver.downloadUrl, downloads.entityFor(receiver.fileName)?.downloadUrl)
            }
            assertTrue(receivers.none { downloads.isTransferring(it.fileName) })
            assertTrue(f.graph.plans().preview(received).rows.all { it.status == DownloadPlanStatus.ALREADY_QUEUED })
        }
    }

    @Test fun differentLocalFoldersWithTheSamePortableNameAreNotChosenAutomatically() = runBlocking {
        fixture { f ->
            val basename = f.name("ambiguous.zip")
            val rows = listOf("folderA", "folderB").map { folder ->
                val raw = "$folder/$basename?token=RECEIVER_SECRET"
                f.names += raw
                DownloadableFileEntity(name = "Matching title", fileName = raw, consoleId = f.console,
                    downloadUrl = f.visibleSource + "/" + raw, sourceUrl = f.visibleSource, fileSize = 1024L)
            }
            f.graph.database().downloadableFileDao().insertFiles(rows)
            val plan = DownloadPlan(listOf(DownloadPlanItem(f.console, basename, "Ambiguous game", f.condition(2))))
            val preview = f.graph.plans().preview(plan)
            assertEquals(DownloadPlanStatus.AMBIGUOUS, preview.rows.single().status)
            assertEquals(0, f.graph.plans().confirm(plan).readyCount)
            assertTrue(rows.none { f.graph.downloads().entityFor(it.fileName) != null })
        }
    }

    @Test fun disabledAndRestrictedVariantsCannotTurnAnAmbiguousNameIntoAnotherDownload() = runBlocking {
        fixture { f ->
            val dao = f.graph.database().downloadableFileDao()
            for (restricted in listOf(false, true)) {
                val basename = f.name(if (restricted) "restricted-variant.zip" else "disabled-variant.zip")
                val originalSource = if (restricted) f.hiddenSource else f.disabledSource
                val original = DownloadableFileEntity(name = "Original variant", fileName = "original/$basename",
                    consoleId = f.console, downloadUrl = originalSource + "/original/" + basename,
                    sourceUrl = originalSource, fileSize = 1024L)
                val replacement = original.copy(id = 0, name = "Different variant", fileName = "replacement/$basename",
                    downloadUrl = f.visibleSource + "/replacement/" + basename, sourceUrl = f.visibleSource)
                f.names += original.fileName
                f.names += replacement.fileName
                dao.insertFiles(listOf(original, replacement))
                if (restricted) {
                    val row = dao.filesByFileNames(listOf(original.fileName)).single()
                    dao.insertTags(listOf(FileTagEntity(row.id, "Adult")))
                }
                val plan = DownloadPlan(listOf(DownloadPlanItem(f.console, basename, "Original variant", f.condition(2))))
                assertEquals(DownloadPlanStatus.AMBIGUOUS, f.graph.plans().preview(plan).rows.single().status)
                assertEquals(0, f.graph.plans().confirm(plan).readyCount)
                assertEquals(null, f.graph.downloads().entityFor(replacement.fileName))
            }
        }
    }

    @Test fun portableLookupCrossesPageBoundariesAndFailsClosedForExcessiveCopies() = runBlocking {
        fixture { f ->
            val dao = f.graph.database().downloadableFileDao()
            val filler = (0 until 400).map { index ->
                DownloadableFileEntity(name = "Unrelated $index", fileName = "unrelated-$index.zip", consoleId = f.console,
                    downloadUrl = f.visibleSource + "/unrelated-$index.zip", sourceUrl = f.visibleSource)
            }
            dao.insertFiles(filler)
            val basename = f.name("after-page.zip")
            val actual = DownloadableFileEntity(name = "After page", fileName = "./$basename?token=RECEIVER_SECRET",
                consoleId = f.console, downloadUrl = f.visibleSource + "/" + basename + "?token=RECEIVER_SECRET",
                sourceUrl = f.visibleSource)
            dao.insertFiles(listOf(actual))
            val item = DownloadPlanItem(f.console, basename, "After page", f.condition(2))
            assertEquals(DownloadPlanStatus.READY, f.graph.plans().preview(DownloadPlan(listOf(item))).rows.single().status)
            val copies = (0 until 32).map { index -> actual.copy(id = 0, fileName = "$basename?token=OTHER_$index",
                downloadUrl = f.visibleSource + "/" + basename + "?token=OTHER_$index") }
            dao.insertFiles(copies)
            assertEquals(DownloadPlanStatus.AMBIGUOUS, f.graph.plans().preview(DownloadPlan(listOf(item))).rows.single().status)
            assertEquals(0, f.graph.plans().confirm(DownloadPlan(listOf(item))).readyCount)
        }
    }

    @Test fun duplicateExportKeepsMatchingMirrorsAndReportsConflictingGroupsWithoutLosingValidRows() = runBlocking {
        fixture { f ->
            val downloads = f.graph.downloads()
            val duplicate = f.name("matching-mirror.zip")
            val differentFolder = f.name("different-folder.zip")
            val differentSchedule = f.name("different-schedule.zip")
            val valid = f.name("valid.zip")
            fun row(raw: String, missingSource: Boolean = false): DownloadableFileEntity {
                f.names += raw
                return DownloadableFileEntity(name = "Export test", fileName = raw, consoleId = f.console,
                    downloadUrl = if (raw.startsWith("https://")) raw else f.visibleSource + "/" + raw,
                    sourceUrl = if (missingSource) "" else f.visibleSource, fileSize = 1024L)
            }
            // These different raw names describe the same source-relative file and schedule.
            // The missing sourceUrl case uses the configured source prefix, as old queue rows do.
            val first = row("./$duplicate", missingSource = true)
            val mirror = row(f.visibleSource + "/$duplicate?token=RECEIVER_SECRET", missingSource = true)
            val folderA = row("A/$differentFolder?token=RECEIVER_SECRET")
            val folderB = row("B/$differentFolder?token=RECEIVER_SECRET")
            val scheduleA = row("./$differentSchedule?token=A")
            val scheduleB = row(f.visibleSource + "/$differentSchedule?token=B")
            val last = row(valid)
            val rows = listOf(first, folderA, scheduleA, mirror, folderB, scheduleB, last)
            val conditions = rows.associate { it.fileName to if (it === scheduleB) f.condition(3) else f.condition(2) }
            downloads.startDownloads(rows, conditions)
            eventually { rows.all { it.fileName in downloads.itemWaits.value } }
            val selected = rows.mapTo(HashSet()) { it.fileName }
            val prepared = f.graph.plans().prepareExport(selected)
            assertEquals(listOf(duplicate, valid), prepared.plan.items.map { it.fileName })
            assertEquals(conditions[first.fileName], prepared.plan.items.first().condition)
            assertEquals(4, prepared.skippedCount)
            assertFalse(DownloadPlans.encode(prepared.plan).contains("RECEIVER_SECRET"))
            assertEquals(prepared.plan, f.graph.plans().export(selected))

            val model = DownloadPlanViewModel(f.graph.plans())
            var pickerCalls = 0
            model.exportFile(selected) { pickerCalls++ }
            eventually { !model.ui.value.busy && pickerCalls == 1 }
            assertEquals(4, model.ui.value.exportSkipped)
            model.savePending(null) // Cancelling the system picker must not hide the omissions.
            model.clearMessage()
            assertEquals(4, model.ui.value.exportSkipped)
            assertTrue(rows.none { downloads.isTransferring(it.fileName) })
            val unusable = runCatching {
                f.graph.plans().prepareExport(setOf(folderA.fileName, folderB.fileName, scheduleA.fileName, scheduleB.fileName))
            }
            assertTrue(unusable.exceptionOrNull() is IllegalArgumentException)
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
