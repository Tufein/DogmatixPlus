package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.DownloadPlan
import com.cortinadev.dogmatix.util.DownloadPlanAvailability
import com.cortinadev.dogmatix.util.DownloadPlanItem
import com.cortinadev.dogmatix.util.DownloadPlanStatus
import com.cortinadev.dogmatix.util.DownloadPlans
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadPlanPreviewRow(val item: DownloadPlanItem, val status: DownloadPlanStatus)
data class DownloadPlanPreview(val plan: DownloadPlan, val rows: List<DownloadPlanPreviewRow>) {
    val readyCount: Int get() = rows.count { it.status == DownloadPlanStatus.READY }
}
data class DownloadPlanExport(val plan: DownloadPlan, val skippedCount: Int)

/** Local source resolution and bounded document IO; receiving a plan never starts a transfer. */
@Singleton
class DownloadPlanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloads: DownloadService,
    private val files: DownloadableFileDao,
    private val consoles: ConsoleDao,
    private val profiles: ProfileService,
    private val library: LibraryIndexService
) {
    suspend fun export(selected: Set<String>): DownloadPlan = prepareExport(selected).plan

    private data class ExportRow(val item: DownloadPlanItem, val location: String?)

    suspend fun prepareExport(selected: Set<String>): DownloadPlanExport = withContext(Dispatchers.Default) {
        val queue = downloads.queued.value
        val waiting = queue.toSet() + downloads.waitingFiles.value + downloads.itemWaits.value.keys
        val rows = downloads.downloads.value.filter { row ->
            (selected.isEmpty() || row.fileName in selected) && when (row.status) {
                DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.STOPPED, DownloadStatus.QUEUED -> true
                DownloadStatus.DOWNLOADING -> row.fileName in waiting && !downloads.isTransferring(row.fileName)
                else -> false
            }
        }
        val positions = queue.withIndex().associate { it.value to it.index }
        val conditions = downloads.itemConditions.value
        // Actual slot order first; condition waiters and parked rows retain their visible order.
        val ordered = rows.sortedBy { positions[it.fileName] ?: Int.MAX_VALUE }
        val entities = ordered.mapNotNull { downloads.entityFor(it.fileName) }
        // Older/history entities can lack sourceUrl. Infer only a matching configured prefix;
        // an unrelated address never proves that two different queue rows are the same file.
        val sourceBases = entities.filter { it.sourceUrl.isBlank() }.map { it.consoleId }.distinct().associateWith { id ->
            consoles.getConsoleById(id)?.urls?.let(SourcesJson::parseUrlEntries).orEmpty().map { it.url }
        }
        val candidates = entities.mapNotNull { file ->
            DownloadPlans.portableFileName(file.fileName)?.let { name ->
                val source = file.sourceUrl.ifBlank {
                    sourceBases[file.consoleId].orEmpty().filter { base ->
                        val prefix = base.substringBefore('?').substringBefore('#').trimEnd('/')
                        file.downloadUrl.startsWith("$prefix/") || file.downloadUrl.substringBefore('?').substringBefore('#') == prefix
                    }.maxByOrNull { it.substringBefore('?').length }.orEmpty()
                }
                ExportRow(DownloadPlanItem(file.consoleId, name,
                    DownloadPlans.portableDisplayName(file.name, file.fileName, name), conditions[file.fileName]),
                    DownloadPlans.localLocation(file.fileName, source))
            }
        }
        var skippedCount = ordered.size - candidates.size
        val result = candidates.groupBy { it.item.key }.values.mapNotNull { copies ->
            val first = copies.first()
            if (copies.size == 1 || first.location != null && copies.all {
                    it.location == first.location && it.item.condition == first.item.condition
                }) first.item
            else {
                // Different folders or schedules cannot be represented by one portable key.
                // Skip the whole group, keeping unrelated valid rows and their relative order.
                skippedCount += copies.size
                null
            }
        }
        require(result.isNotEmpty()) { "No exportable downloads" }
        DownloadPlanExport(DownloadPlan(result).also { DownloadPlans.encode(it) }, skippedCount)
    }

    suspend fun save(plan: DownloadPlan, uri: Uri) = withContext(Dispatchers.IO) {
        val text = DownloadPlans.encode(plan)
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    suspend fun shareUri(plan: DownloadPlan): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Only this feature's old temporary documents are removed; each share has its own URI.
        val staleBefore = System.currentTimeMillis() - 86_400_000L
        dir.listFiles()?.filter { it.name.startsWith("dogmatix-download-plan-") && it.lastModified() < staleBefore }
            ?.forEach { it.delete() }
        val file = File(dir, "dogmatix-download-plan-${UUID.randomUUID()}.json")
        file.writeText(DownloadPlans.encode(plan), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    suspend fun read(uri: Uri): DownloadPlanPreview = withContext(Dispatchers.IO) {
        val plan = requireNotNull(context.contentResolver.openInputStream(uri)).use(DownloadPlans::read)
        preview(plan)
    }

    suspend fun preview(plan: DownloadPlan): DownloadPlanPreview = withContext(Dispatchers.IO) {
        resolve(plan).preview
    }

    /** Re-resolve under the receiving device's current sources, profile and disk index. */
    suspend fun confirm(plan: DownloadPlan): DownloadPlanPreview = withContext(Dispatchers.IO) {
        val resolved = resolve(plan)
        val eligible = resolved.preview.rows.filter { it.status == DownloadPlanStatus.READY }
        val entities = eligible.mapNotNull { resolved.entities[it.item.key] }
        val conditions = eligible.mapNotNull { row ->
            row.item.condition?.let { condition -> resolved.entities[row.item.key]?.fileName?.let { it to condition } }
        }.toMap()
        if (entities.isNotEmpty()) downloads.startDownloads(entities, conditions)
        resolved.preview
    }

    private data class Resolution(
        val preview: DownloadPlanPreview,
        val entities: Map<Pair<String, String>, DownloadableFileEntity>
    )

    private data class PortableIndex(
        val rows: Map<Pair<String, String>, List<DownloadableFileEntity>>,
        val overflow: Set<Pair<String, String>>
    )

    /**
     * The index stores raw hrefs, not just basenames. Scan one console at a time without loading
     * its catalogue into memory, and retain only identities requested by this bounded plan.
     * Excessive copies are unavailable rather than selecting from an incomplete candidate list.
     */
    private suspend fun portableIndex(plan: DownloadPlan): PortableIndex {
        val rows = mutableMapOf<Pair<String, String>, MutableList<DownloadableFileEntity>>()
        val overflow = mutableSetOf<Pair<String, String>>()
        var retained = 0
        plan.items.groupBy { it.consoleId }.forEach { (console, items) ->
            val wanted = items.mapTo(HashSet()) { it.fileName }
            var afterId = 0L
            do {
                val page = files.planIdentityRows(console, afterId, 400)
                page.forEach rowLoop@ { row ->
                    val name = DownloadPlans.portableFileName(row.fileName) ?: return@rowLoop
                    if (name !in wanted) return@rowLoop
                    val key = console to name
                    if (key in overflow) return@rowLoop
                    val copies = rows.getOrPut(key) { mutableListOf() }
                    if (copies.size >= 32 || retained >= 12_000) {
                        overflow += key
                        retained -= copies.size
                        copies.clear()
                    } else {
                        copies += row
                        retained++
                    }
                }
                if (page.isNotEmpty()) afterId = page.last().id
            } while (page.size == 400)
        }
        return PortableIndex(rows, overflow)
    }

    private suspend fun resolve(plan: DownloadPlan): Resolution {
        // No lookup by title, URI credentials or another console with a matching filename.
        val localIndex = portableIndex(plan)
        val indexed = localIndex.rows
        val tags = indexed.values.flatten().map { it.id }.distinct().chunked(400)
            .flatMap { files.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
        val enabled = plan.items.map { it.consoleId }.distinct().associateWith { id ->
            consoles.getConsoleById(id)?.urls?.let(SourcesJson::parseUrlEntries)
                .orEmpty().filter { it.enabled }.map { it.url }.toSet()
        }
        // Read profile last: no further Room suspension can leave a switched profile's old
        // permissions cached between confirmation and synchronous queue registration.
        val restrictions = profiles.current()
        val owned = library.ownedKeys.value
        val existing = downloads.downloads.value.mapTo(HashSet()) { it.fileName }
        val entities = mutableMapOf<Pair<String, String>, DownloadableFileEntity>()
        val availability = plan.items.associate { item ->
            val candidates = indexed[item.key].orEmpty()
            val allowed = candidates.filter { restrictions.allows(it.consoleId, tags[it.id].orEmpty()) }
            val sources = enabled[item.consoleId].orEmpty()
            val available = allowed.filter { row ->
                row.downloadUrl.isNotBlank() && if (row.sourceUrl.isNotBlank()) row.sourceUrl in sources
                else sources.any { row.downloadUrl.startsWith(it.trimEnd('/') + "/") || row.downloadUrl == it }
            }
            // A disabled/hidden copy in another folder still proves that this basename does
            // not identify one file. Enabling a different copy must not silently change a plan.
            val ambiguous = item.key in localIndex.overflow || candidates.map {
                DownloadPlans.localLocation(it.fileName, it.sourceUrl)
            }.distinct().size > 1
            val selected = available.firstOrNull()?.takeUnless { ambiguous }
            selected?.let { entities[item.key] = it }
            item.key to DownloadPlanAvailability(
                indexed = candidates.isNotEmpty() || item.key in localIndex.overflow,
                sourceAvailable = available.isNotEmpty(),
                allowed = restrictions.allows(item.consoleId, emptyList()) &&
                    (allowed.isNotEmpty() || item.key in localIndex.overflow),
                owned = candidates.any { library.isOwned(it, owned) },
                queueFileName = selected?.fileName,
                alreadyQueued = candidates.any { it.fileName in existing },
                ambiguous = ambiguous
            )
        }
        val statuses = DownloadPlans.statuses(plan, availability, existing)
        return Resolution(DownloadPlanPreview(plan, plan.items.mapIndexed { i, item -> DownloadPlanPreviewRow(item, statuses[i]) }), entities)
    }
}
