package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.FrontendCoversSettings
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.FrontendCovers
import com.cortinadev.dogmatix.util.LibraryRestrictions
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FrontendCovers"

/** What a run would do for one frontend: the folder, the plan, and what was left out. */
data class CoversPreview(
    val target: FrontendCovers.Target,
    val folderSet: Boolean,
    /** The folder is set, still reachable and writable. */
    val writable: Boolean,
    /** Readable path of the picked folder (empty when none). */
    val folderDisplay: String,
    val layout: FrontendCovers.Layout,
    val overwrite: Boolean,
    /** The user's own cover path (used with [FrontendCovers.Layout.CUSTOM]). */
    val pattern: String = "",
    /** What is wrong with the own cover path when that layout is chosen; null when it is fine or not in use. */
    val patternProblem: FrontendCovers.Pattern.Problem? = null,
    /** Null when there is no usable folder (nothing was planned). */
    val plan: FrontendCovers.Plan?,
    /** Games found on the device for this profile. */
    val games: Int,
    /** Games outside any console folder (no system to put a cover in). */
    val outside: Int,
    /** The active profile hides some consoles or tags; their games are not counted. */
    val profileLimited: Boolean,
    /** A game of the library to show what each layout's names look like; null without games. */
    val sample: FrontendCovers.Game? = null
)

/** A run's progress and, when it is over, its outcome. */
data class CoversRunState(
    val running: Boolean = false,
    val target: FrontendCovers.Target? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** New covers written. */
    val written: Int = 0,
    /** Covers that replaced one of the same name (only with "replace" on). */
    val replaced: Int = 0,
    /** Games no cover source knew. */
    val noCover: Int = 0,
    val failed: Int = 0,
    /** Games that already had a cover (planned, or one appeared during the run). */
    val kept: Int = 0,
    val finished: Boolean = false,
    val problem: Problem? = null
) {
    enum class Problem { NO_FOLDER, NOT_WRITABLE, NOTHING_ON_DEVICE, FAILED, BAD_PATTERN }
}

/**
 * Tools -> Covers for launchers (3.1.0 for iiSU and Daijishō, 3.2.0 for any other launcher). Puts a
 * cover image for every game on the device into the media folder the user picked for that
 * launcher, named and arranged the way the user chose ([FrontendCovers.Layout]); folders that are
 * already there are used as they are spelled ("GBA" for "gba"). The covers come from the same places
 * as everywhere else in the app ([CoverRepository]: the RomM server's own cover, libretro
 * thumbnails, or an image an earlier lookup found) and are written as PNG. An image that is
 * already there is never replaced unless the user switched that on, and every write goes through
 * [StorageHelper.writeBytesSafely]. Profiles: hidden consoles and games with hidden tags are left out.
 */
@Singleton
class FrontendCoversService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: FrontendCoversSettings,
    private val libraryScanService: LibraryScanService,
    private val covers: CoverRepository,
    private val rommClient: RommClient,
    private val fileDao: DownloadableFileDao,
    private val profiles: ProfileService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CoversRunState())
    val state: StateFlow<CoversRunState> = _state.asStateFlow()
    private var job: Job? = null

    /** The plan for [target] as things are now: reads the library and the picked folder, writes nothing. */
    suspend fun preview(target: FrontendCovers.Target): CoversPreview = withContext(Dispatchers.IO) { prepare(target).preview }

    fun start(target: FrontendCovers.Target) {
        if (_state.value.running) return
        _state.value = CoversRunState(running = true, target = target)
        job = scope.launch { run(target) }
    }

    fun cancel() { job?.cancel() }

    /** The last run's outcome is dismissed. */
    fun clear() { if (!_state.value.running) _state.value = CoversRunState() }

    // ---- Planning ------------------------------------------------------------------------------

    private class Prepared(val preview: CoversPreview, val root: DocumentFile?, val media: MediaFolder?)

    private suspend fun prepare(target: FrontendCovers.Target): Prepared {
        val s = settings.of(target).first()
        val root = s.folder.takeIf { it.isNotBlank() }?.let { StorageHelper.getDocumentFile(context, it) }
            ?.takeIf { runCatching { it.isDirectory && it.canWrite() }.getOrDefault(false) }
        val display = s.folder.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) }.orEmpty()
        val restrictions = profiles.current()
        val found = FrontendCovers.gamesOf(libraryScanService.scan().files, restrictions.hiddenConsoles)
        currentCoroutineContext().ensureActive()
        val games = withoutHiddenTags(found.games, restrictions)
        val media = root?.let { DiskScanner.rootOf(s.folder) }?.let { MediaFolder(it) }
        val patternProblem = if (s.layout == FrontendCovers.Layout.CUSTOM) FrontendCovers.Pattern.check(s.pattern) else null
        val plan = media?.takeIf { patternProblem == null }?.let { m ->
            // One listing per cover folder: the console folders, or the game folders of the Pegasus layout.
            val folders = games.mapNotNull { FrontendCovers.place(it, s.layout, s.pattern)?.dir }.distinctBy { it.lowercase() }
            val existing = folders.associateWith { dir ->
                currentCoroutineContext().ensureActive()
                m.children(dir).values.filterNot { it.isDirectory }.map { it.name.lowercase() }.toSet()
            }
            val planned = FrontendCovers.plan(games, s.layout, existing, s.overwrite, s.pattern)
            // A folder that is already there is written to as it is spelled ("GBA" for "gba").
            planned.copy(items = planned.items.map { it.copy(place = it.place.copy(dir = m.spelled(it.place.dir))) })
        }
        val sample = FrontendCovers.sampleOf(games, s.pattern)?.let { g -> media?.let { g.copy(system = it.spelled(g.system)) } ?: g }
        return Prepared(
            CoversPreview(target, s.folder.isNotBlank(), root != null, display, s.layout, s.overwrite, s.pattern, patternProblem, plan, games.size, found.outside, restrictions.active, sample),
            root,
            media
        )
    }

    /**
     * The picked media folder as it is on storage: each folder below it is listed at most once, in
     * one provider query, and folder names are matched in any case (shared storage ignores case).
     */
    private inner class MediaFolder(private val root: DiskDir) {
        private val dirs = HashMap<String, DiskDir?>()
        private val listings = HashMap<String, Map<String, DiskEntry>>()

        /** The folder at [path] ("" = the media folder), or null when it is not there. */
        fun dir(path: String): DiskDir? {
            if (path.isEmpty()) return root
            val key = path.lowercase()
            if (key in dirs) return dirs[key]
            val parentPath = path.substringBeforeLast('/', "")
            val found = dir(parentPath)?.let { parent ->
                children(parentPath)[path.substringAfterLast('/').lowercase()]?.takeIf { it.isDirectory }?.let { DiskScanner.dirOf(parent, it) }
            }
            dirs[key] = found
            return found
        }

        /** What is in the folder at [path], by lower-cased name; empty when it is not there. */
        fun children(path: String): Map<String, DiskEntry> = listings.getOrPut(path.lowercase()) {
            dir(path)?.let { d -> DiskScanner.list(context, d).associateBy { it.name.lowercase() } }.orEmpty()
        }

        /** [path] spelled the way its folders already are on storage; folders not there yet keep the given spelling. */
        fun spelled(path: String): String {
            if (path.isEmpty()) return path
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val actual = children(parentPath)[name.lowercase()]?.takeIf { it.isDirectory }?.name ?: name
            return if (parentPath.isEmpty()) actual else "${spelled(parentPath)}/$actual"
        }
    }

    /** The cover folders of a run as documents: found through [media] or made, once each. */
    private inner class RunFolders(private val root: DocumentFile, private val media: MediaFolder) {
        private val docs = HashMap<String, DocumentFile>()

        /** The folder at [path] when it is there. */
        fun find(path: String): DocumentFile? {
            if (path.isEmpty()) return root
            docs[path.lowercase()]?.let { return it }
            val dir = media.dir(path) ?: return null
            return DocumentFile.fromTreeUri(context, DiskScanner.uriOf(dir))?.also { docs[path.lowercase()] = it }
        }

        /** The folder at [path], made (with its parents) when it is not there; null when storage refuses. */
        fun make(path: String): DocumentFile? {
            find(path)?.let { return it }
            val parent = make(path.substringBeforeLast('/', "")) ?: return null
            val name = path.substringAfterLast('/')
            val made = parent.createDirectory(name) ?: return null
            // A provider that renames ("Game (1)") means something else already has this name.
            if (made.name != name || !made.isDirectory) { runCatching { made.delete() }; return null }
            docs[path.lowercase()] = made
            return made
        }

        /**
         * The files in [dir] right now: lower-cased name to the name as it is spelled. Null when
         * the listing cannot be trusted to be complete (the provider failed or is still loading):
         * an image could then be replaced without the user having asked for that.
         */
        fun names(dir: DocumentFile): Map<String, String>? {
            val d = DiskScanner.dirOf(dir.uri) ?: return null
            return DiskScanner.listOrNull(context, d)?.filterNot { it.isDirectory || it.name.startsWith(".dogmatix-") }
                ?.associate { it.name.lowercase() to it.name }
        }
    }

    /** Games whose library row carries a tag the active profile hides are left out. */
    private suspend fun withoutHiddenTags(games: List<FrontendCovers.Game>, restrictions: LibraryRestrictions): List<FrontendCovers.Game> {
        if (restrictions.hiddenTags.isEmpty() || games.isEmpty()) return games
        val wanted = games.groupBy({ it.consoleId }, { it.fileName.lowercase() }).mapValues { it.value.toSet() }
        val rowKeys = HashMap<Long, String>()
        for (chunk in games.map { it.fileName }.distinct().chunked(LOOKUP_CHUNK)) {
            currentCoroutineContext().ensureActive()
            // Listings often keep the URL spelling of a name (`Game%20(USA).zip`).
            val query = (chunk + chunk.map { it.replace(" ", "%20") }).distinct()
            for (row in fileDao.filesByFileNames(query)) {
                val stored = runCatching { FileParsingUtils.storageFileName(row.fileName) }.getOrNull()?.lowercase() ?: continue
                if (stored in wanted[row.consoleId].orEmpty()) rowKeys[row.id] = "${row.consoleId}|$stored"
            }
        }
        val hidden = HashSet<String>()
        for (chunk in rowKeys.keys.toList().chunked(TAG_CHUNK)) {
            for (t in fileDao.tagsOfFiles(chunk)) {
                if (restrictions.hiddenTags.any { it.equals(t.tag, ignoreCase = true) }) rowKeys[t.fileId]?.let(hidden::add)
            }
        }
        return games.filterNot { "${it.consoleId}|${it.fileName.lowercase()}" in hidden }
    }

    // ---- The run -------------------------------------------------------------------------------

    private suspend fun run(target: FrontendCovers.Target) {
        try {
            val prepared = prepare(target)
            val preview = prepared.preview
            val root = prepared.root
            val media = prepared.media
            val plan = preview.plan
            if (!preview.folderSet) return finish(target, CoversRunState.Problem.NO_FOLDER)
            if (preview.patternProblem != null) return finish(target, CoversRunState.Problem.BAD_PATTERN)
            if (root == null || media == null || plan == null) return finish(target, CoversRunState.Problem.NOT_WRITABLE)
            if (preview.games == 0) return finish(target, CoversRunState.Problem.NOTHING_ON_DEVICE)
            _state.update { it.copy(total = plan.items.size, kept = plan.kept) }
            val rommBase = runCatching { rommClient.configuredBaseUrl() }.getOrDefault("")
            val rommHeaders = if (rommBase.isNotEmpty()) runCatching { rommClient.downloadHeaders() }.getOrDefault(emptyMap()) else emptyMap()
            val folders = RunFolders(root, media)
            for (item in plan.items) {
                currentCoroutineContext().ensureActive()
                val outcome = write(folders, item, preview.overwrite, rommBase, rommHeaders)
                _state.update {
                    when (outcome) {
                        Outcome.WRITTEN -> it.copy(written = it.written + 1)
                        Outcome.REPLACED -> it.copy(replaced = it.replaced + 1)
                        Outcome.NO_COVER -> it.copy(noCover = it.noCover + 1)
                        Outcome.KEPT -> it.copy(kept = it.kept + 1)
                        Outcome.FAILED -> it.copy(failed = it.failed + 1)
                    }.copy(done = it.done + 1)
                }
                delay(ITEM_DELAY_MS)
            }
            _state.update { it.copy(running = false, finished = true) }
        } catch (e: CancellationException) {
            // Stopped by the user: what was written stays, the counts so far are shown.
            _state.update { it.copy(running = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cover run failed", e)
            finish(target, CoversRunState.Problem.FAILED)
        }
    }

    private fun finish(target: FrontendCovers.Target, problem: CoversRunState.Problem) {
        _state.value = CoversRunState(target = target, problem = problem)
    }

    private enum class Outcome { WRITTEN, REPLACED, NO_COVER, KEPT, FAILED }

    private suspend fun write(folders: RunFolders, item: FrontendCovers.Item, overwrite: Boolean, rommBase: String, rommHeaders: Map<String, String>): Outcome {
        val game = item.game
        val url = try {
            covers.coverUrl(game.consoleId, game.fileName, game.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Outcome.NO_COVER
        return withContext(Dispatchers.IO) {
            try {
                // Checked again right before writing: a cover that appeared meanwhile is kept.
                val folder = folders.find(item.place.dir)
                val present = if (folder == null) emptyMap() else folders.names(folder) ?: return@withContext Outcome.FAILED
                // With replacing on, only an image of the exact cover name stands in the way: an image
                // with another extension stays and the PNG is added next to it.
                val there = if (overwrite) item.place.name.lowercase() in present.keys else FrontendCovers.hasCover(present.keys, item.place.name)
                if (!item.replaces && there) return@withContext Outcome.KEPT
                // The image being replaced keeps its own spelling ("game.png" for "Game.png").
                val fileName = if (item.replaces) present[item.place.name.lowercase()] ?: item.place.name else item.place.name
                val headers = if (rommBase.isNotEmpty() && url.startsWith(rommBase)) rommHeaders else emptyMap()
                val bytes = JsonHttp.download(url, headers, MAX_COVER_BYTES).takeIf { it.isNotEmpty() } ?: return@withContext Outcome.FAILED
                val png = toPng(bytes) ?: return@withContext Outcome.FAILED
                val dir = folders.make(item.place.dir) ?: return@withContext Outcome.FAILED
                StorageHelper.writeBytesSafely(context, dir, "", fileName, png)
                if (item.replaces) Outcome.REPLACED else Outcome.WRITTEN
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover ${item.path} failed: ${e.message}")
                Outcome.FAILED
            }
        }
    }

    /** The image as PNG, at most [FrontendCovers.MAX_SIDE] pixels on its longer side; null when it is no image. */
    private fun toPng(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = FrontendCovers.sampleSize(bounds.outWidth, bounds.outHeight)
        if (sample == 1 && FrontendCovers.isPng(bytes)) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** Covers are small; anything bigger is not one. */
        const val MAX_COVER_BYTES = 15L * 1024 * 1024
        /** A short pause between covers: polite to the image servers. */
        const val ITEM_DELAY_MS = 60L
        const val LOOKUP_CHUNK = 400
        const val TAG_CHUNK = 500
    }
}
