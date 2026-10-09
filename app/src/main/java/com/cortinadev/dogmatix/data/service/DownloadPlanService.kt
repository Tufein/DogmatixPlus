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
    suspend fun export(selected: Set<String>): DownloadPlan = withContext(Dispatchers.Default) {
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
        val result = ordered.mapNotNull { row -> downloads.entityFor(row.fileName)?.let { file ->
            DownloadPlanItem(file.consoleId, file.fileName, file.name, conditions[file.fileName])
        } }
        require(result.isNotEmpty()) { "No exportable downloads" }
        DownloadPlan(result).also { DownloadPlans.encode(it) }
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
        val conditions = eligible.mapNotNull { row -> row.item.condition?.let { row.item.fileName to it } }.toMap()
        if (entities.isNotEmpty()) downloads.startDownloads(entities, conditions)
        resolved.preview
    }

    private data class Resolution(
        val preview: DownloadPlanPreview,
        val entities: Map<Pair<String, String>, DownloadableFileEntity>
    )

    private suspend fun resolve(plan: DownloadPlan): Resolution {
        // No lookup by title, and no fallback to another console with a matching filename.
        val indexed = plan.items.map { it.fileName }.distinct().chunked(400)
            .flatMap { files.filesByFileNames(it) }.groupBy { it.consoleId to it.fileName }
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
            available.firstOrNull()?.let { entities[item.key] = it }
            item.key to DownloadPlanAvailability(
                indexed = candidates.isNotEmpty(),
                sourceAvailable = available.isNotEmpty(),
                allowed = restrictions.allows(item.consoleId, emptyList()) && allowed.isNotEmpty(),
                owned = candidates.any { library.isOwned(it, owned) }
            )
        }
        val statuses = DownloadPlans.statuses(plan, availability, existing)
        return Resolution(DownloadPlanPreview(plan, plan.items.mapIndexed { i, item -> DownloadPlanPreviewRow(item, statuses[i]) }), entities)
    }
}
