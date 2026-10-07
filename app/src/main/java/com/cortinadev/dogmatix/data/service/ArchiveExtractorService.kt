package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DownloadExtractionException
import com.cortinadev.dogmatix.util.StorageException
import com.cortinadev.dogmatix.util.StorageAccessException
import com.cortinadev.dogmatix.util.StorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ArchiveExtractorService"

@Singleton
class ArchiveExtractorService @Inject constructor() {

    /**
     * Extracts an archive from a SAF URI (used for HTTP downloads where the file
     * lands in SAF storage directly).
     */
    suspend fun extractArchive(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        subPath: String = "",
        failOnError: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        var extractDir: File? = null
        val extractionContext = currentCoroutineContext()

        try {
            val currentExtractDir = createExtractionDirectory(context).also { extractDir = it }
            // Open the SAF archive as a seekable FileChannel — no need to copy the archive
            // to a temp file first, saving potentially gigabytes of cache space.
            val pfd = context.contentResolver.openFileDescriptor(archiveUri, "r")
                ?: throw StorageAccessException()

            val cachedFiles = pfd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    extractToCache(fis, currentExtractDir, onProgress, failOnError) { extractionContext.ensureActive() }
                }
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            copyToSaf(context, cachedFiles, destinationUri, subPath, failOnError) { extractionContext.ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extractionContext.ensureActive()
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            if (failOnError) throw if (e is StorageException || e is SecurityException || e is DownloadExtractionException) e else DownloadExtractionException(e)
            emptyList()
        } finally {
            extractDir?.deleteRecursively()
        }
    }

    /**
     * Extracts an archive that is already on the local filesystem (used for torrent
     * downloads where the file lives in cacheDir). Avoids writing the compressed
     * archive to SAF at all — extracts straight to a temp dir then copies to SAF.
     */
    suspend fun extractArchiveFile(
        context: Context,
        archiveFile: File,
        destinationUri: Uri,
        subPath: String = "",
        failOnError: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        var extractDir: File? = null
        val extractionContext = currentCoroutineContext()

        try {
            val currentExtractDir = createExtractionDirectory(context).also { extractDir = it }
            val cachedFiles = FileInputStream(archiveFile).use { fis ->
                extractToCache(fis, currentExtractDir, onProgress, failOnError) { extractionContext.ensureActive() }
            }
            if (cachedFiles.isEmpty()) return@withContext emptyList<String>()

            copyToSaf(context, cachedFiles, destinationUri, subPath, failOnError) { extractionContext.ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            extractionContext.ensureActive()
            Log.e(TAG, "Extraction of ${archiveFile.name} failed: ${e.message}", e)
            if (failOnError) throw if (e is StorageException || e is SecurityException || e is DownloadExtractionException) e else DownloadExtractionException(e)
            emptyList()
        } finally {
            extractDir?.deleteRecursively()
        }
    }

    private fun createExtractionDirectory(context: Context): File {
        // A timestamp is shared by simultaneous bulk completions; each extraction needs its
        // own directory so another worker cannot overwrite/delete its temporary files.
        val parent = File(context.cacheDir, "extraction_temp")
        if (!parent.mkdirs() && !parent.isDirectory) throw StorageException("Could not create extraction cache")
        return try {
            Files.createTempDirectory(parent.toPath(), "archive-").toFile()
        } catch (e: java.io.IOException) {
            throw StorageException("Could not create extraction cache", e)
        }
    }

    private fun extractToCache(
        fis: FileInputStream,
        extractDir: File,
        onProgress: (Float) -> Unit,
        failOnError: Boolean,
        checkActive: () -> Unit
    ): List<File> {
        val results = mutableListOf<File>()
        val channel = fis.channel
        val channelSize = channel.size()

        // Implement IInStream over a FileChannel so SevenZip can seek through the archive
        // without requiring a copy to a RandomAccessFile.
        val inStream = object : IInStream {
            override fun read(data: ByteArray): Int {
                checkActive()
                val n = channel.read(ByteBuffer.wrap(data))
                return if (n == -1) 0 else n
            }

            override fun seek(offset: Long, seekOrigin: Int): Long {
                checkActive()
                val newPos = when (seekOrigin) {
                    ISeekableStream.SEEK_SET -> offset
                    ISeekableStream.SEEK_CUR -> channel.position() + offset
                    ISeekableStream.SEEK_END -> channelSize + offset
                    else -> throw SevenZipException("Unknown seek origin: $seekOrigin")
                }
                channel.position(newPos)
                return channel.position()
            }

            override fun close() {
                // The channel is closed by the FileInputStream.use block
            }
        }

        try {
            SevenZip.openInArchive(null, inStream).use { archive ->
                val total = archive.numberOfItems

                val callback = object : IArchiveExtractCallback {
                    var out: OutputStream? = null
                    var dest: File? = null
                    var skip = false
                    var done = 0

                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        checkActive()
                        val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                        if (isFolder) { skip = true; return null }

                        val rawPath = (archive.getProperty(index, PropID.PATH) as? String) ?: "file_$index"
                        val name = rawPath.replace('\\', '/').split("/").last()
                            .replace(Regex("[<>:\"|?*\u0000]"), "_")
                            .ifBlank { "file_$index" }

                        val file = File(extractDir, name)
                        dest = file
                        val stream = try {
                            BufferedOutputStream(FileOutputStream(file), Constants.EXTRACTION_BUFFER_SIZE)
                        } catch (e: java.io.IOException) {
                            throw StorageException("Could not create extracted cache file", e)
                        }
                        out = stream
                        skip = false

                        return ISequentialOutStream { data ->
                            checkActive()
                            try { stream.write(data) } catch (e: java.io.IOException) {
                                throw StorageException("Could not write extracted cache file", e)
                            }
                            data.size
                        }
                    }

                    override fun prepareOperation(mode: ExtractAskMode) {}

                    override fun setOperationResult(result: ExtractOperationResult) {
                        checkActive()
                        try { out?.close() } catch (e: java.io.IOException) {
                            if (failOnError) throw StorageException("Could not finish extracted cache file", e)
                        }
                        out = null

                        if (!skip && result == ExtractOperationResult.OK) {
                            dest?.let { results.add(it); done++ }
                            onProgress(if (total > 0) done.toFloat() / total else 1f)
                        } else if (!skip) {
                            Log.w(TAG, "Entry result: $result for ${dest?.name}")
                            if (failOnError) throw DownloadExtractionException()
                        }
                        dest = null
                        skip = false
                    }

                    override fun setCompleted(complete: Long) {}
                    override fun setTotal(total: Long) {}
                }
                try {
                    archive.extract(null, false, callback)
                } finally {
                    // Native extraction can abort before setOperationResult. Close its current
                    // output even on a stopped download or a damaged archive.
                    try { callback.out?.close() } catch (e: Exception) {
                        Log.w(TAG, "Could not close interrupted extraction output", e)
                    }
                    callback.out = null
                }
            }
        } catch (e: SevenZipException) {
            checkActive()
            Log.e(TAG, "7-zip extraction error: ${e.message}")
            if (failOnError) throw DownloadExtractionException(e)
        }

        return results
    }

    private fun copyToSaf(
        context: Context,
        files: List<File>,
        destinationUri: Uri,
        subPath: String,
        failOnError: Boolean,
        checkActive: () -> Unit
    ): List<String> {
        val destUri = ArchiveExtractionUtils.prepareExtractionDestination(context, destinationUri, subPath)
        val copied = mutableListOf<String>()
        val buffer = ByteArray(Constants.EXTRACTION_BUFFER_SIZE)

        for (file in files) {
            checkActive()
            val outUri = StorageHelper.createFile(
                context = context,
                uriString = destUri.toString(),
                subPath = "",
                fileName = file.name,
                overwrite = true
            )?.uri ?: if (failOnError) throw StorageException("Could not create extracted destination file") else continue

            val copiedFile = try {
                context.contentResolver.openOutputStream(outUri)?.use { raw ->
                    BufferedOutputStream(raw, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                        file.inputStream().use { input ->
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                checkActive()
                                buffOut.write(buffer, 0, n)
                            }
                        }
                    }
                    true
                }
            } catch (e: java.io.IOException) {
                throw StorageException("Could not copy extracted file to storage", e)
            }
            if (copiedFile != true) {
                if (failOnError) throw StorageException("Could not open extracted destination file")
                continue
            }
            checkActive()
            copied.add(file.name)
            Log.d(TAG, "Copied to SAF: ${file.name}")
        }

        return copied
    }
}
