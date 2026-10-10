package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.AtomicFile
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PackageInspection(val manifest: GamePackages.Manifest, val files: List<RemovalFile>,
    val missing: List<String>, val changed: List<String>, val hashesChecked: Boolean) {
    val complete: Boolean get() = missing.isEmpty() && changed.isEmpty() && files.size == manifest.parts.size
}

/** Receipts live in private app storage; resolving them only reads within the original selected root. */
@Singleton
class GamePackageService @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val folder = File(context.filesDir, "game-packages")
    private val receiptLock = Any()
    fun identity(file: DownloadableFileEntity): String = GamePackages.identity(file.consoleId,
        file.downloadUrl, file.fileName, file.torrentMagnet, file.torrentFileIndex)

    /** Persist before discarding the source archive; an unsuccessful receipt never authorizes cleanup. */
    suspend fun record(file: DownloadableFileEntity, rootUri: String, subPath: String, paths: List<String>): GamePackages.Manifest = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        require(DiskScanner.rootOf(rootUri) != null) { "Package root must be a selected storage tree" }
        require(file.isTorrent || java.net.URI(file.downloadUrl).let {
            it.isAbsolute && it.scheme.lowercase() in setOf("https", "http") && !it.host.isNullOrBlank()
        }) { "Package source must have a durable download identity" }
        require(paths.isNotEmpty() && paths.size <= Constants.MAX_ARCHIVE_ENTRIES)
        val placeholder = GamePackages.Manifest(sourceIdentity = identity(file), consoleId = file.consoleId,
            fileName = file.fileName, rootUri = rootUri, subPath = subPath, createdAt = System.currentTimeMillis(),
            parts = paths.map { GamePackages.Part(it, 0, "0".repeat(64)) })
        GamePackages.validate(placeholder)
        val resolver = Resolver(placeholder, check)
        val parts = paths.map { path ->
            check()
            val located = resolver.file(path) ?: error("Package file unavailable: $path")
            var bytes = 0L
            val input = context.contentResolver.openInputStream(located.entry.uri) ?: error("Package file unreadable")
            val counted = object : java.io.FilterInputStream(input) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) bytes = Math.addExact(bytes, it.toLong()) }
            }
            val hash = VerifiedCopy.hash(counted, check)
            GamePackages.Part(path, bytes, hash)
        }
        val manifest = GamePackages.validate(placeholder.copy(parts = parts))
        check()
        persist(manifest, check)
        manifest
    }

    /** Tracking survives a vanished/corrupt receipt and a changed root; it grants no filenames. */
    fun hasRecordedIdentity(file: DownloadableFileEntity, currentRoots: Set<String>): Boolean = synchronized(receiptLock) {
        val identity = identity(file)
        val marker = File(folder, "$identity.tracked")
        marker.exists() || File(marker.path + ".bak").exists() || currentRoots.any { root ->
            val receipt = File(folder, GamePackages.receiptKey(identity, root) + ".json")
            receipt.exists() || File(receipt.path + ".bak").exists()
        }
    }

    private fun persist(manifest: GamePackages.Manifest, check: () -> Unit,
        preserveExisting: Boolean = false): GamePackages.Manifest = synchronized(receiptLock) {
        val bytes = GamePackages.encode(manifest)
        kotlin.check(folder.isDirectory || folder.mkdirs())
        val target = File(folder, GamePackages.receiptKey(manifest.sourceIdentity, manifest.rootUri) + ".json")
        if (preserveExisting && (target.exists() || File(target.path + ".bak").exists())) {
            val existing = readRecord(target) ?: error("Existing package receipt unavailable")
            kotlin.check(existing.copy(createdAt = manifest.createdAt) == manifest) { "Existing package receipt differs" }
            return@synchronized existing
        }
        kotlin.check(target.exists() || folder.listFiles().orEmpty().count { it.name.endsWith(".json") } < GamePackages.MAX_PACKAGES)
        // The tracked marker is committed first. A failed manifest write may suppress a legacy
        // basename guess, but it can never broaden read grants to unrelated same-named files.
        val marker = AtomicFile(File(folder, "${manifest.sourceIdentity}.tracked"))
        val markerOutput = marker.startWrite()
        try { markerOutput.write(byteArrayOf(1)); check(); marker.finishWrite(markerOutput) }
        catch (error: Throwable) { marker.failWrite(markerOutput); throw error }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(bytes); check(); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        manifest
    }

    /** A storage move clones ownership only after the target matches every original SHA-256. */
    suspend fun rebase(original: GamePackages.Manifest, newRootUri: String, newSubPath: String): GamePackages.Manifest = withContext(Dispatchers.IO) {
        GamePackages.validate(original)
        require(DiskScanner.rootOf(newRootUri) != null)
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        val proposed = GamePackages.validate(original.copy(rootUri = newRootUri, subPath = newSubPath,
            createdAt = System.currentTimeMillis()))
        kotlin.check(inspect(proposed, verifyHashes = true).complete) { "Moved package files differ from the original" }
        check()
        persist(proposed, check, preserveExisting = true)
    }

    /** [movedPaths] describes the verified move: original document URI to its path below the source. */
    suspend fun rebaseMovedFiles(oldRoots: Set<String>, movedPaths: Map<String, String>, newRootUri: String,
        newPrefix: String = "", consoleId: String? = null): Int = withContext(Dispatchers.IO) {
        require(newPrefix.isEmpty() || ArchivePlan.safeRelativePath(newPrefix) == newPrefix)
        val manifests = records(oldRoots).filter { consoleId == null || it.consoleId == consoleId }
        var count = 0
        for (inspection in inspectBatch(manifests)) {
            currentCoroutineContext().ensureActive()
            if (inspection.files.none { it.uri in movedPaths }) continue
            kotlin.check(inspection.complete && inspection.files.all { it.uri in movedPaths }) { "Move contains only part of a game package" }
            val prefixes = inspection.files.map { part ->
                val moved = requireNotNull(movedPaths[part.uri])
                require(ArchivePlan.safeRelativePath(moved) == moved)
                kotlin.check(moved == part.path || moved.endsWith("/${part.path}")) { "Move changed package layout" }
                if (moved == part.path) "" else moved.removeSuffix("/${part.path}")
            }.distinct()
            kotlin.check(prefixes.size == 1) { "Move changed package layout" }
            val subPath = listOf(newPrefix, prefixes.single()).filter(String::isNotEmpty).joinToString("/")
            rebase(inspection.manifest, newRootUri, subPath)
            count++
        }
        count
    }

    /** Unknown/corrupt schemas are ignored, never repaired or used as filename ownership. */
    fun records(activeRoots: Set<String>): List<GamePackages.Manifest> = synchronized(receiptLock) {
        var remaining = MAX_SCAN_BYTES
        val result = ArrayList<GamePackages.Manifest>()
        for (file in folder.listFiles().orEmpty().map { File(folder, it.name.removeSuffix(".bak")) }.distinctBy { it.name }
            .filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.sortedBy { it.name }.take(GamePackages.MAX_PACKAGES)) {
            val size = receiptSize(file)
            if (size > GamePackages.MAX_MANIFEST_BYTES) continue
            check(size <= remaining) { "Package receipt scan is too large" }
            remaining -= size
            readRecord(file)?.takeIf { it.rootUri in activeRoots }?.let(result::add)
        }
        result
    }

    /** Exact lookup is O(selected roots), even with thousands of unrelated package receipts. */
    fun recordsFor(file: DownloadableFileEntity, activeRoots: Set<String>): List<GamePackages.Manifest> = synchronized(receiptLock) {
        val identity = identity(file)
        activeRoots.filter { it.isNotBlank() }.mapNotNull { root ->
            readRecord(File(folder, GamePackages.receiptKey(identity, root) + ".json"))?.takeIf {
                it.sourceIdentity == identity && it.rootUri == root && it.consoleId == file.consoleId && it.fileName == file.fileName
            }
        }.sortedByDescending { it.createdAt }
    }

    private fun receiptSize(file: File): Long = maxOf(file.length(), File(file.path + ".bak").length())
    private fun readRecord(file: File): GamePackages.Manifest? = runCatching {
        require(receiptSize(file) <= GamePackages.MAX_MANIFEST_BYTES)
        val bytes = AtomicFile(file).openRead().use { stream -> BoundedStreams.read(stream, GamePackages.MAX_MANIFEST_BYTES + 1) }
        require(bytes.size <= GamePackages.MAX_MANIFEST_BYTES)
        val manifest = GamePackages.decode(bytes)
        require(DiskScanner.rootOf(manifest.rootUri) != null)
        require(file.name == GamePackages.receiptKey(manifest.sourceIdentity, manifest.rootUri) + ".json")
        manifest
    }.getOrNull()

    fun recordFor(file: DownloadableFileEntity, activeRoots: Set<String>): GamePackages.Manifest? =
        recordsFor(file, activeRoots).firstOrNull()

    /** A generated playlist joins the receipt only inside this exact source/root/subfolder. */
    suspend fun includeGenerated(file: DownloadableFileEntity, rootUri: String, subPath: String,
        generatedPaths: List<String>): GamePackages.Manifest {
        val previous = recordFor(file, setOf(rootUri)) ?: error("Package not yet recorded")
        require(previous.subPath == subPath)
        return record(file, rootUri, subPath, (previous.parts.map { it.path } + generatedPaths).distinct())
    }

    suspend fun inspect(manifest: GamePackages.Manifest, verifyHashes: Boolean = false): PackageInspection = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        inspectNow(manifest, verifyHashes, check, Listings(check))
    }

    /** One refresh shares directory queries across receipts; hashes remain an explicit check. */
    suspend fun inspectBatch(manifests: List<GamePackages.Manifest>): List<PackageInspection> = withContext(Dispatchers.IO) {
        require(manifests.size <= GamePackages.MAX_PACKAGES)
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        val listings = Listings(check)
        manifests.map { manifest ->
            check()
            try { inspectNow(manifest, false, check, listings) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { PackageInspection(manifest, emptyList(), manifest.parts.map { it.path }, emptyList(), false) }
        }
    }

    private fun inspectNow(manifest: GamePackages.Manifest, verifyHashes: Boolean, check: () -> Unit,
        listings: Listings): PackageInspection {
        GamePackages.validate(manifest)
        val resolver = Resolver(manifest, check, listings)
        val files = ArrayList<RemovalFile>()
        val missing = ArrayList<String>()
        val changed = ArrayList<String>()
        for (part in manifest.parts) {
            check()
            val located = resolver.file(part.path)
            if (located == null) { missing += part.path; continue }
            val entry = located.entry
            files += RemovalFile(entry.uri.toString(), DiskScanner.uriOf(located.parent).toString(), entry.name, entry.size, part.path)
            if (verifyHashes) {
                val actual = try { context.contentResolver.openInputStream(entry.uri)?.let { VerifiedCopy.hash(it, check) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (actual != part.sha256) changed += part.path
            } else if (entry.size > 0 && entry.size != part.bytes) changed += part.path
        }
        return PackageInspection(manifest, files, missing, changed, verifyHashes)
    }

    private data class Located(val parent: DiskDir, val entry: DiskEntry)
    private inner class Listings(private val check: () -> Unit) {
        private val lists = HashMap<String, Map<String, List<DiskEntry>>?>()
        private var cachedEntries = 0
        fun matches(dir: DiskDir, name: String): List<DiskEntry> {
            check()
            // Opaque document ids may distinguish Games from games. They are never case-folded
            // for resolving a receipt, even on a provider backed by a case-sensitive filesystem.
            val key = "${dir.treeUri}|${dir.documentId}"
            if (key !in lists) {
                kotlin.check(lists.size < Constants.MAX_ARCHIVE_ENTRIES) { "Too many package folders" }
                val entries = DiskScanner.listOrNull(context, dir, true)?.takeIf { it.size <= Constants.MAX_ARCHIVE_ENTRIES }
                kotlin.check(cachedEntries.toLong() + (entries?.size ?: 0) <= MAX_CACHED_ENTRIES) { "Package folders are too large" }
                cachedEntries += entries?.size ?: 0
                lists[key] = entries?.groupBy { it.name }
            }
            return lists[key]?.get(name).orEmpty()
        }
    }
    private inner class Resolver(manifest: GamePackages.Manifest, private val check: () -> Unit,
        private val listings: Listings = Listings(check)) {
        private val roots = DiskScanner.rootOf(manifest.rootUri)
        private val base = roots?.let { descend(it, manifest.subPath) }
        private fun child(dir: DiskDir, name: String): DiskEntry? = listings.matches(dir, name).singleOrNull()?.takeIf { entry ->
            if (entry.name != name) return@takeIf false
            // Recognized providers must return a direct child. Generic providers enforce their
            // tree grant themselves; opaque ids cannot be interpreted as filesystem paths.
            val parent = DiskScanner.canonicalPath(dir.treeUri.authority, dir.documentId)
            val child = DiskScanner.canonicalPath(entry.uri.authority, entry.documentId)
            parent == null || child != null && child == "$parent/${entry.name}"
        }
        private fun descend(start: DiskDir, path: String): DiskDir? {
            var dir = start
            val visited = hashSetOf(DiskScanner.canonicalKey(dir))
            for (name in path.split('/').filter { it.isNotEmpty() }) {
                val found = child(dir, name)?.takeIf { it.isDirectory } ?: return null
                dir = DiskScanner.dirOf(dir, found)
                if (!visited.add(DiskScanner.canonicalKey(dir))) return null
            }
            return dir
        }
        fun file(path: String): Located? {
            val root = base ?: return null
            val parent = descend(root, path.substringBeforeLast('/', "")) ?: return null
            return child(parent, path.substringAfterLast('/'))?.takeIf { !it.isDirectory }?.let { Located(parent, it) }
        }
    }
    private companion object {
        const val MAX_SCAN_BYTES = 16L * 1024 * 1024
        const val MAX_CACHED_ENTRIES = 50_000
    }
}
