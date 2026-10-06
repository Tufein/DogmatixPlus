package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
    private val gate: StorageMoveGate
) {
    private val preferences = context.getSharedPreferences("library_recovery", Context.MODE_PRIVATE)
    var retentionDays: Int
        get() = preferences.getInt("days", 30)
        set(value) { preferences.edit().putInt("days", value.coerceIn(1, 365)).apply() }
    var autoClean: Boolean
        get() = preferences.getBoolean("auto_clean", false)
        set(value) { preferences.edit().putBoolean("auto_clean", value).apply() }
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { cleanExpired() } } }

    suspend fun move(files: List<RemovalFile>, title: String): Int = withContext(Dispatchers.IO) {
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
    } }

    /** Permanent purge: called only after explicit confirmation, or the opt-in retention policy. */
    suspend fun purge(id: String) = withContext(Dispatchers.IO) { gate.lock.withLock {
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
    } }

    suspend fun cleanExpired() {
        if (!autoClean) return
        val before = System.currentTimeMillis() - retentionDays * 86_400_000L
        history.entries.value.filter { it.kind == "trash" && it.phase == "stored" && it.time < before }.forEach { purge(it.id) }
    }
}
