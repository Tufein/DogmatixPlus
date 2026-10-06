package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.VerifiedCopy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VerifiedDocumentCopy @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun hash(uri: Uri): String = VerifiedCopy.hash(context.contentResolver.openInputStream(uri) ?: throw IOException("File cannot be read"))

    /** Stages, re-reads, then publishes. Conflicting originals and targets are always preserved. */
    suspend fun copy(source: Uri, directory: DocumentFile, name: String, progress: (Long) -> Unit = {}): DocumentFile {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        fun checkedHash(uri: Uri): String = VerifiedCopy.hash(context.contentResolver.openInputStream(uri) ?: throw IOException("File cannot be read"), check)
        val existing = directory.findFile(name)
        if (existing != null) {
            if (!existing.isFile) throw IOException("A folder already has this name")
            VerifiedCopy.requireSame(checkedHash(source), checkedHash(existing.uri))
            return existing
        }
        val staging = directory.createFile("application/octet-stream", ".dogmatix-copy-${UUID.randomUUID()}.part") ?: throw IOException("Cannot stage copy")
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Source cannot be read")
            val output = try { context.contentResolver.openOutputStream(staging.uri, "wt") ?: throw IOException("Copy cannot be written") }
                catch (e: Exception) { input.close(); throw e }
            val expected = VerifiedCopy.transfer(input, output, check, progress)
            VerifiedCopy.requireSame(expected, checkedHash(staging.uri))
            VerifiedCopy.requireSame(expected, checkedHash(source))
            check()
            // Another writer may have created a file during the transfer. Never overwrite it.
            directory.findFile(name)?.let { target ->
                if (!target.isFile) throw IOException("Target conflict")
                VerifiedCopy.requireSame(expected, checkedHash(target.uri))
                return target
            }
            if (!staging.renameTo(name) || staging.name != name) throw IOException("Storage does not support a safe rename")
            VerifiedCopy.requireSame(expected, checkedHash(staging.uri))
            return staging
        } finally {
            // Once renamed, keep a published file even if the final read failed. The source remains.
            if (staging.name?.startsWith(".dogmatix-copy-") == true) runCatching { staging.delete() }
        }
    }
    fun mayRemove(source: Uri, target: Uri, expected: String): Boolean =
        runCatching { hash(source) == expected && hash(target) == expected }.getOrDefault(false)
}
