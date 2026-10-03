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

    /** Document URI of [dir] itself, e.g. to delete it once it is empty. */
    fun uriOf(dir: DiskDir): Uri = DocumentsContract.buildDocumentUriUsingTree(dir.treeUri, dir.documentId)

    /** The directory behind a document URI built by [uriOf]; null for anything else. */
    fun dirOf(documentUri: Uri): DiskDir? = runCatching {
        DiskDir(
            DocumentsContract.buildTreeDocumentUri(documentUri.authority, DocumentsContract.getTreeDocumentId(documentUri)),
            DocumentsContract.getDocumentId(documentUri)
        )
    }.getOrNull()

    /**
     * A key that is the same for one physical file or folder however it was reached: the same
     * folder picked through "Internal storage" (`primary:ROMs/gba`) and through the Downloads
     * provider (`raw:/storage/emulated/0/...`) must not be walked, counted or offered for
     * deletion twice. Falls back to provider + document id when no path can be derived.
     */
    fun canonicalKey(treeUri: Uri, documentId: String): String =
        (canonicalPath(treeUri.authority, documentId) ?: "${treeUri.authority}|$documentId").lowercase()

    fun canonicalKey(dir: DiskDir): String = canonicalKey(dir.treeUri, dir.documentId)

    /** File-system path for the providers whose document ids encode one; null otherwise. */
    internal fun canonicalPath(authority: String?, documentId: String): String? = when (authority) {
        "com.android.externalstorage.documents" -> {
            val volume = documentId.substringBefore(':', "")
            val path = documentId.substringAfter(':', "").trim('/')
            when {
                volume.isEmpty() -> null
                volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0/$path".trimEnd('/')
                else -> "/storage/$volume/$path".trimEnd('/')
            }
        }
        "com.android.providers.downloads.documents" -> when {
            // raw: ids carry the real path, including the user (10 in a work profile); an app only
            // ever sees its own user's storage, so the user number carries no information here.
            documentId.startsWith("raw:") -> documentId.removePrefix("raw:").trimEnd('/').replace(otherUser, "/storage/emulated/0")
            documentId == "downloads" -> "/storage/emulated/0/Download"
            else -> null
        }
        else -> null
    }

    private val otherUser = Regex("^/storage/emulated/\\d+(?=/|$)")

    /** Children of [dir]; empty when the provider refuses or the folder vanished. */
    fun list(context: Context, dir: DiskDir): List<DiskEntry> = listOrNull(context, dir, strict = false).orEmpty()

    /**
     * Children of [dir], or null when the listing cannot be trusted to be complete: the provider
     * failed, is still loading, or (with [strict]) returned a row without id or name. Used before
     * deleting a folder, which providers do recursively.
     */
    fun listOrNull(context: Context, dir: DiskDir, strict: Boolean = true): List<DiskEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir.treeUri, dir.documentId)
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                if (strict && cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) return null
                val out = ArrayList<DiskEntry>(cursor.count)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1)
                    if (id == null || name == null) {
                        if (strict) return null else continue
                    }
                    val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    out += DiskEntry(name, size, isDir, DocumentsContract.buildDocumentUriUsingTree(dir.treeUri, id), id)
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list ${dir.documentId}: ${e.message}")
            null
        }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { Log.w(TAG, "Cannot delete $uri: ${it.message}") }
            .getOrDefault(false)
}
