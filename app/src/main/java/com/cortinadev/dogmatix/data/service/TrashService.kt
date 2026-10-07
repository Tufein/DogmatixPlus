package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.ActionKind
import com.cortinadev.dogmatix.util.ActionReason
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class RemovalFile(val uri: String, val parent: String, val name: String, val bytes: Long, val path: String = name)

/** Backups stay on the original volume. Their durable receipt is saved before the source is unlinked. */
@Singleton
class TrashService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val copier: VerifiedDocumentCopy,
    private val history: OperationHistoryService,
    private val gate: StorageMoveGate,
    private val actionLog: ActionLogService
) {
    private val preferences = context.getSharedPreferences("library_recovery", Context.MODE_PRIVATE)
    var retentionDays: Int
        get() = preferences.getInt("days", 30)
        set(value) { preferences.edit().putInt("days", value.coerceIn(1, 365)).apply() }
    var autoClean: Boolean
        get() = preferences.getBoolean("auto_clean", false)
        set(value) { preferences.edit().putBoolean("auto_clean", value).apply() }
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { cleanExpired() } } }

    /**
     * [consoleId], [fileName] (the library file name when known, else the first file's) and
     * [reason] (an [ActionReason] code) only go to the action history, so it can say why a game
     * went and offer Download again once the trash is emptied.
     */
    suspend fun move(
        files: List<RemovalFile>,
        title: String,
        consoleId: String? = null,
        fileName: String? = null,
        reason: String = ActionReason.BY_USER
    ): Int = withContext(Dispatchers.IO) {
        gate.lock.withLock {
            if (files.isEmpty()) return@withLock 0
            var operation = LibraryOperation(kind = "trash", title = title)
            history.put(operation)
            gate.hold("*")
            try {
                for (file in files.distinctBy { it.uri }) {
                    val parent = StorageHelper.getDocumentFile(context, file.parent) ?: error("Original folder unavailable")
                    val trash = StorageHelper.createDirectory(parent, ".dogmatix-trash/${operation.id}") ?: error("Trash unavailable")
                    val target = copier.copy(Uri.parse(file.uri), trash, file.name)
                    val hash = copier.hash(Uri.parse(file.uri))
                    check(copier.mayRemove(Uri.parse(file.uri), target.uri, hash)) { "Source changed" }
                    operation = operation.copy(files = operation.files + OperationFile(file.uri, target.uri.toString(), file.path, file.bytes, hash, file.parent))
                    history.put(operation)
                }
                operation = operation.copy(phase = "ready")
                history.put(operation)
                var count = 0
                for (receipt in operation.files) {
                    if (copier.mayRemove(Uri.parse(receipt.source), Uri.parse(receipt.target), receipt.hash) && DiskScanner.delete(context, Uri.parse(receipt.source))) count++
                    else error("Original could not be removed; backup retained")
                }
                history.put(operation.copy(phase = "stored"))
                actionLog.record(
                    ActionKind.REMOVED, title, consoleId = consoleId, fileName = fileName ?: files.first().name,
                    opId = operation.id, reason = reason, count = count, bytes = operation.files.sumOf { it.bytes }
                )
                count
            } catch (e: Exception) {
                history.put(operation.copy(phase = "interrupted"))
                throw e
            } finally { gate.release("*") }
        }
    }

    suspend fun restore(id: String) = withContext(Dispatchers.IO) { gate.lock.withLock {
        var operation = history.get(id)?.takeIf { it.kind == "trash" && it.phase !in setOf("done", "purging") } ?: return@withLock
        history.put(operation.copy(phase = "restoring"))
        for (receipt in operation.files) {
            val parent = StorageHelper.getDocumentFile(context, receipt.parent) ?: error("Original folder unavailable")
            val source = DocumentFile.fromSingleUri(context, Uri.parse(receipt.target)) ?: error("Backup unavailable")
            if (!source.exists()) {
                val restored = parent.findFile(receipt.name.substringAfterLast('/')) ?: error("Backup unavailable")
                check(copier.hash(restored.uri) == receipt.hash)
                continue
            }
            check(copier.hash(source.uri) == receipt.hash) { "Backup changed" }
            val target = copier.copy(source.uri, parent, receipt.name.substringAfterLast('/'))
            check(copier.mayRemove(source.uri, target.uri, receipt.hash))
            check(source.delete()) { "Restored, but backup cleanup failed" }
        }
        history.put(operation.copy(phase = "done"))
        val removal = actionLog.entries.value?.lastOrNull { it.opId == id && it.kind == ActionKind.REMOVED }
        actionLog.record(
            ActionKind.RESTORED, operation.title, consoleId = removal?.consoleId, fileName = removal?.fileName,
            opId = id, count = operation.files.size, bytes = operation.files.sumOf { it.bytes }
        )
    } }

    /** Permanent purge: called only after explicit confirmation, or the opt-in retention policy. */
    suspend fun purge(id: String): Unit = purge(id, ActionReason.BY_USER)

    /** [reason]: [ActionReason.BY_USER] or [ActionReason.EXPIRED], for the action history; [record] false leaves the line to a summary. */
    private suspend fun purge(id: String, reason: String, record: Boolean = true): Unit = withContext(Dispatchers.IO) { gate.lock.withLock {
        val operation = history.get(id)?.takeIf { it.kind == "trash" && it.phase in setOf("stored", "purging") } ?: return@withLock
        history.put(operation.copy(phase = "purging"))
        for (receipt in operation.files) {
            val target = DocumentFile.fromSingleUri(context, Uri.parse(receipt.target)) ?: error("Trash unavailable")
            if (target.exists()) {
                check(copier.hash(target.uri) == receipt.hash) { "Trash changed" }
                check(target.delete()) { "Trash could not be emptied" }
            }
        }
        history.put(operation.copy(phase = "done"))
        if (!record) return@withLock
        // The removal line knows the game; the purge line repeats it so Download again can find it.
        val removal = actionLog.entries.value?.lastOrNull { it.opId == id && it.kind == ActionKind.REMOVED }
        actionLog.record(
            ActionKind.PURGED, operation.title, consoleId = removal?.consoleId, fileName = removal?.fileName,
            opId = id, reason = reason, count = operation.files.size, bytes = operation.files.sumOf { it.bytes }
        )
    } }

    suspend fun cleanExpired() {
        if (!autoClean) return
        val before = System.currentTimeMillis() - retentionDays * 86_400_000L
        val expired = history.entries.value.filter { it.kind == "trash" && it.phase == "stored" && it.time < before }
        // A big clean-up is a few lines and one "N more" line, so it cannot push the rest out of the history.
        var folded = 0
        var foldedBytes = 0L
        try {
            expired.forEachIndexed { i, op ->
                purge(op.id, ActionReason.EXPIRED, record = i < EXPIRED_LINES)
                if (i >= EXPIRED_LINES) { folded++; foldedBytes += op.files.sumOf { it.bytes } }
            }
        } finally {
            if (folded > 0) actionLog.record(ActionKind.PURGED, reason = ActionReason.EXPIRED, count = folded, bytes = foldedBytes)
        }
    }

    private companion object {
        const val EXPIRED_LINES = 8
    }
}
