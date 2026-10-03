package com.cortinadev.dogmatix.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri

/** One child of a SAF directory, read in a single query together with its siblings. */
data class DiskEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    /** Document URI under the picked tree; usable for [DiskScanner.delete] and as a child [DiskDir]. */
    val uri: Uri,
    val documentId: String
)

/** A directory inside a SAF tree: the tree it was granted through plus its own document id. */
data class DiskDir(val treeUri: Uri, val documentId: String)

/**
 * Lists SAF directories with one `ContentResolver.query` per directory, asking for name, type
 * and size in the same cursor. `DocumentFile.listFiles()` + `length()` costs a query per file,
 * which turns a few thousand ROMs into a long wait.
 */
object DiskScanner {
    private const val TAG = "DiskScanner"

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )

    /** The root directory of a persisted tree URI string, or null when it is not a tree. */
    fun rootOf(treeUriString: String): DiskDir? = runCatching {
        val uri = treeUriString.toUri()
        if (!DocumentsContract.isTreeUri(uri)) return null
        DiskDir(uri, DocumentsContract.getTreeDocumentId(uri))
    }.getOrNull()

    fun dirOf(parent: DiskDir, entry: DiskEntry): DiskDir = DiskDir(parent.treeUri, entry.documentId)

    /** Children of [dir]; empty when the provider refuses or the folder vanished. */
    fun list(context: Context, dir: DiskDir): List<DiskEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir.treeUri, dir.documentId)
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val out = ArrayList<DiskEntry>(cursor.count)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    out += DiskEntry(name, size, isDir, DocumentsContract.buildDocumentUriUsingTree(dir.treeUri, id), id)
                }
                out
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list ${dir.documentId}: ${e.message}")
            emptyList()
        }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Log.w(TAG, "Cannot delete $uri: ${it.message}") }
            .getOrDefault(false)
}
