package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class OperationFile(val source: String, val target: String, val name: String, val bytes: Long, val hash: String, val parent: String = "", val removed: Boolean = false)
data class LibraryOperation(
    val id: String = UUID.randomUUID().toString(), val kind: String, val title: String,
    val time: Long = System.currentTimeMillis(), val phase: String = "copying",
    val source: String = "", val target: String = "", val files: List<OperationFile> = emptyList(), val consoleId: String = "", val totalFiles: Int = 0, val destinationPlace: String = ""
)

/** Atomic durable journal. Save failures propagate before any destructive step. */
@Singleton
class OperationHistoryService @Inject constructor(@param:ApplicationContext context: Context) {
    private val disk = AtomicFile(File(context.filesDir, "library_operations.json"))
    private val gson = Gson()
    private var readFailure: Exception? = null
    val unreadable: Boolean get() = readFailure != null
    private val _entries = MutableStateFlow(runCatching { read() }.getOrElse { readFailure = it as? Exception ?: java.io.IOException("Journal unavailable"); emptyList() })
    val entries = _entries.asStateFlow()
    private fun read(): List<LibraryOperation> = if (!disk.baseFile.exists()) emptyList() else
        gson.fromJson(disk.openRead().use { it.reader().readText() }, object : TypeToken<List<LibraryOperation>>() {}.type) ?: emptyList()

    @Synchronized fun put(entry: LibraryOperation) {
        if (readFailure != null) throw java.io.IOException("Recovery journal cannot be read", readFailure)
        val stored = if (entry.phase == "done" && entry.files.isNotEmpty()) entry.copy(totalFiles = maxOf(entry.totalFiles, entry.files.size), files = entry.files.take(100).map { it.copy(source = "", target = "", hash = "", parent = "") }) else entry
        val next = (_entries.value.filterNot { it.id == stored.id } + stored).sortedByDescending { it.time }
        fun recovery(item: LibraryOperation) = item.kind in setOf("trash", "library_move", "smart_move") && item.phase != "done"
        val retained = next.filter(::recovery).plus(next.filterNot(::recovery).take(300)).sortedByDescending { it.time }
        val stream = disk.startWrite()
        try { stream.write(gson.toJson(retained).toByteArray()); disk.finishWrite(stream) }
        catch (e: Exception) { disk.failWrite(stream); throw e }
        _entries.value = retained
    }
    fun get(id: String) = _entries.value.firstOrNull { it.id == id }
    fun event(kind: String, title: String, phase: String = "done") { runCatching { put(LibraryOperation(kind = kind, title = title, phase = phase)) } }
    /** Public report contains no document URIs, server addresses, credentials or arbitrary errors. */
    fun report(): String = _entries.value.joinToString("\n") { "${it.time} | ${it.kind} | ${it.phase} | ${maxOf(it.totalFiles, it.files.size)} files" }
}
