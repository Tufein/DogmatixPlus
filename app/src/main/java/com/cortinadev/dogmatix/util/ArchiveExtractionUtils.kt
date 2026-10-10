package com.cortinadev.dogmatix.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.Constants.EXTRACTION_BUFFER_SIZE
import com.cortinadev.dogmatix.util.Constants.MAX_ARCHIVE_ENTRIES
import java.io.InputStream
import java.io.OutputStream

object ArchiveExtractionUtils {

    private const val TAG = "ArchiveExtractionUtils"

    fun validateArchiveFile(context: Context, archiveUri: Uri): DocumentFile? {
        val archiveFile = DocumentFile.fromSingleUri(context, archiveUri)
        if (archiveFile == null || !archiveFile.exists()) {
            Log.e(TAG, "Archive file not found: $archiveUri")
            return null
        }
        return archiveFile
    }

    fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast(".", "")
    }

    fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
    }

    fun copyStream(
        inputStream: InputStream, 
        outputStream: OutputStream, 
        bufferSize: Int = EXTRACTION_BUFFER_SIZE
    ): Long {
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L
        var bytesRead: Int
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        
        return totalBytes
    }
    
    fun validateEntryCount(entryCount: Int): Boolean {
        if (entryCount > MAX_ARCHIVE_ENTRIES) {
            Log.w(TAG, "Archive has too many entries: $entryCount (max: $MAX_ARCHIVE_ENTRIES)")
            return false
        }
        return true
    }
    
    fun calculateProgress(current: Long, total: Long): Float {
        return if (total > 0) {
            (current.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    fun logProgress(extractedCount: Int, totalCount: Int, fileName: String) {
        if (extractedCount % 10 == 0 || extractedCount == totalCount) {
            Log.d(TAG, "Extracted $extractedCount/$totalCount files. Current: $fileName")
        }
    }
    
    fun prepareExtractionDestination(
        context: Context,
        destinationUri: Uri,
        subPath: String
    ): Uri {
        val root = StorageHelper.getDocumentFile(context, destinationUri.toString())
            ?: throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
        if (!root.isDirectory || !root.exists() || !root.canWrite()) {
            throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
        }
        if (subPath.isEmpty()) return root.uri
        // Never redirect a configured console folder into its parent on a storage failure.
        val path = ArchivePlan.safeRelativePath(subPath, directory = true)
        if (path != subPath.replace('\\', '/').trimEnd('/')) {
            throw ArchiveSafetyException(ArchiveSafetyException.Reason.UNSAFE_PATH)
        }
        var current = root
        for (part in path.split('/')) {
            val sameNames = current.listFiles().filter { ArchivePlan.portableKey(it.name.orEmpty()) == ArchivePlan.portableKey(part) }
            if (sameNames.size > 1 || sameNames.any { it.name != part || !it.isDirectory }) {
                throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
            }
            current = sameNames.singleOrNull() ?: current.createDirectory(part)
                ?: throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
            if (current.name != part || !current.isDirectory) {
                throw ArchiveSafetyException(ArchiveSafetyException.Reason.DESTINATION_UNAVAILABLE)
            }
        }
        return current.uri
    }
}
