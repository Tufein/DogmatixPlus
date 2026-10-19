package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.CloudSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ArchivePlan
import com.cortinadev.dogmatix.util.CopyConflictChoice
import com.cortinadev.dogmatix.util.DavException
import com.cortinadev.dogmatix.util.DavProblem
import com.cortinadev.dogmatix.util.DavStore
import com.cortinadev.dogmatix.util.DiskDir
import com.cortinadev.dogmatix.util.DiskEntry
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.GamePackages
import com.cortinadev.dogmatix.util.LibraryRestrictions
import com.cortinadev.dogmatix.util.PackageCopyPlan
import com.cortinadev.dogmatix.util.RemoteSend
import com.cortinadev.dogmatix.util.RemoteSend.Outcome
import com.cortinadev.dogmatix.util.RommPlatformMapper
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.StoredZip
import com.cortinadev.dogmatix.util.TargetFolderLayouts
import com.cortinadev.dogmatix.util.VerifiedCopy
import com.cortinadev.dogmatix.util.WebDavPaths
import com.cortinadev.dogmatix.util.WebDavXml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

/** Where game packages go: a folder (SD card, USB stick), the RomM server or the NAS (Settings → Cloud). */
enum class TransferTarget { FOLDER, ROMM, NAS }

/** The destination as the screen shows it: a folder's volume and path, the RomM or NAS address. */
data class TransferPlace(
    val target: TransferTarget,
    /** For a folder: "primary" for the internal storage, otherwise the card's or stick's id. */
    val volume: String? = null,
    val path: String,
    /** The folder is one of the app's own game or save folders (copies there show up in the library). */
    val appFolder: Boolean = false
)

/** A selected game whose files on the device are not all there or not the ones recorded; it is left out. */
data class TransferUnavailable(val title: String, val consoleId: String, val missing: Int, val changed: Int)

/** A selected game the chosen folder names have no folder for (3.5.0); it is left out. */
data class TransferUnsupported(val title: String, val consoleId: String)

data class TransferPreview(
    val profileId: String,
    val restrictions: LibraryRestrictions,
    val target: TransferTarget,
    /** The picked folder (tree URI) for [TransferTarget.FOLDER]. */
    val folderUri: String?,
    val place: TransferPlace,
    val choice: CopyConflictChoice,
    /** NAS: read every file back to compare its SHA-256 (otherwise its size is compared). */
    val readBack: Boolean,
    val unavailable: List<TransferUnavailable>,
    /** Folder and NAS. */
    val copy: PackageCopyPlan.Plan?,
    /** RomM. */
    val romm: List<RemoteSend.RommGame>?,
    val records: Map<String, GamePackages.Manifest>,
    /** Folder names at the destination (3.5.0; folder only, RomM and the NAS keep the library's). */
    val layout: TargetFolderLayouts.Layout = TargetFolderLayouts.Layout.LIBRARY,
    /** Another system's folder names were asked for but the folder is one of the app's library folders: the library's own names are used (see [layout]). */
    val layoutIgnored: Boolean = false,
    /** Games [layout] has no folder for; they are not copied. */
    val unsupported: List<TransferUnsupported> = emptyList(),
    /** Titles of games whose files sit deeper than the other system looks ([TargetFolderLayouts.Layout.maxSubfolders]). */
    val tooDeep: List<String> = emptyList(),
    /** Titles of games with an apostrophe in a subfolder name, which OnionOS does not open (3.6). */
    val apostrophes: List<String> = emptyList(),
    /** Planned folders that already exist there under other letter case; the copy goes into the existing one. */
    val caseDifferences: List<TargetFolderLayouts.CaseDifference> = emptyList(),
    /** For another system's folder names: the console folders the copy writes to, spelled as they are (or will be) there. */
    val consoleFolders: List<String> = emptyList()
) {
    val canStart: Boolean get() = when (target) {
        TransferTarget.ROMM -> romm.orEmpty().any { it.sends || it.decision == RemoteSend.RommDecision.PRESENT }
        else -> copy?.canStart == true
    }
    /** Bytes that will be written or sent. */
    val bytesToSend: Long get() = when (target) {
        TransferTarget.ROMM -> romm.orEmpty().filter { it.sends }.sumOf { it.bytes }
        else -> copy?.bytesToCopy ?: 0
    }
}

data class TransferFileResult(
    val gameKey: String,
    val game: String,
    /** The path at the destination (for RomM: inside [container] when packed). */
    val path: String,
    val bytes: Long,
    val outcome: Outcome,
    /** Why it failed, in words for the user; null when there is nothing to add. */
    val detail: String? = null,
    /** The ZIP a file travelled in (RomM). */
    val container: String? = null
)

data class TransferProgress(val file: String, val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long)

data class TransferRun(
    val preview: TransferPreview,
    val running: Boolean,
    val progress: TransferProgress?,
    val results: List<TransferFileResult>,
    /** Stopped by the user or because the profile changed. */
    val stopped: Boolean = false,
    /** The run could not go on (connection lost); the files not reached are NOT_SENT. */
    val error: String? = null
) {
    /** The games worth another try (a failed, unsent or not yet listed file). */
    val retryKeys: List<String> get() = results.filter { RemoteSend.canRetry(it.outcome) }.map { it.gameKey }.distinct()
}

/**
 * Copies or sends whole game packages, every recorded file with its folders, and checks each one
 * at the other end. Reads from the device only: source files are never changed, moved or deleted.
 * At the destination nothing is ever replaced; the only files removed there are this transfer's
 * own temporary files and a copy it just made that failed its check.
 *
 * The run lives here (not in the screen), so leaving the screen does not stop it; [stop] does.
 * Profiles: only the games the active profile shows can be picked, and a profile switch stops a
 * running transfer before the next file.
 */
@Singleton
class GameTransferService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageTransfer: PackageTransferService,
    private val packages: GamePackageService,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient,
    private val rommLibrary: RommLibraryService,
    private val cloudSettings: CloudSettings,
    private val cloudConnection: CloudConnection,
    private val storage: StorageAvailabilityService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _run = MutableStateFlow<TransferRun?>(null)
    val run: StateFlow<TransferRun?> = _run.asStateFlow()

    data class Availability(val romm: Boolean, val nas: Boolean)

    suspend fun availability(): Availability = Availability(
        romm = rommClient.configuredBaseUrl().isNotEmpty() && settingsRepository.rommToken.first().isNotBlank(),
        nas = cloudSettings.configured.first()
    )

    suspend fun snapshot(): PackageTransferSnapshot = packageTransfer.snapshot()

    /** Looks at the device files and the destination; writes nothing. */
    suspend fun preview(
        snapshot: PackageTransferSnapshot,
        keys: List<String>,
        target: TransferTarget,
        folderUri: String?,
        choice: CopyConflictChoice,
        readBack: Boolean,
        layout: TargetFolderLayouts.Layout = TargetFolderLayouts.Layout.LIBRARY
    ): TransferPreview = withContext(Dispatchers.IO) {
        val fresh = packageTransfer.snapshot()
        check(fresh.profileId == snapshot.profileId && fresh.restrictions == snapshot.restrictions) { "Profile changed" }
        // Only games the active profile still shows; the screen's list may be older.
        val wanted = keys.toSet()
        val records = fresh.records.filter { it.sourceIdentity in wanted }
        val inspections = packages.inspectBatch(records)
        val unavailable = inspections.filterNot { it.complete }.map {
            TransferUnavailable(it.manifest.fileName, it.manifest.consoleId, it.missing.size, it.changed.size)
        }
        val sources = inspections.filter { it.complete }.map { inspection ->
            val byPath = inspection.files.associateBy { it.path }
            val m = inspection.manifest
            PackageCopyPlan.Source(m.sourceIdentity, m.fileName, m.consoleId, m.subPath, m.parts.map { part ->
                PackageCopyPlan.Part(part.path, part.bytes, part.sha256, byPath.getValue(part.path).uri)
            })
        }
        val recordMap = records.associateBy { it.sourceIdentity }
        when (target) {
            TransferTarget.FOLDER -> {
                val uri = requireNotNull(folderUri) { "Pick a folder" }
                val root = DiskScanner.rootOf(uri) ?: error("Not a folder")
                val destination = SafDestination(context, root)
                val key = StorageAvailabilityService.key(uri)
                val volume = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringBefore(':') }.getOrNull()
                val path = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':') }.getOrDefault("")
                // Folder names for another system: every planned path (and so what is already there,
                // the space, the copy, its check and the results) follows from the games' new folders.
                val appFolder = storage.configuredLocations().any { it.key == key }
                // In one of the app's own library folders other names would put every game there a second time.
                val used = if (appFolder) TargetFolderLayouts.Layout.LIBRARY else layout
                val placement = TargetFolderLayouts.place(used, sources, path.trimEnd('/').substringAfterLast('/').ifEmpty { null }) {
                    destination.spelling(it)
                }
                val plan = PackageCopyPlan.plan(placement.sources, { destination.existing(it) }, choice, StorageHelper.getFreeBytes(context, uri))
                TransferPreview(fresh.profileId, fresh.restrictions, target, uri, TransferPlace(target, volume, path, appFolder),
                    choice, false, unavailable, plan, null, recordMap, used,
                    layoutIgnored = used != layout,
                    unsupported = placement.unsupported.map { TransferUnsupported(it.title, it.consoleId) },
                    tooDeep = placement.tooDeep.map { it.title },
                    apostrophes = placement.apostrophes.map { it.title },
                    caseDifferences = placement.caseDifferences,
                    consoleFolders = if (used == TargetFolderLayouts.Layout.LIBRARY) emptyList() else {
                        val copied = plan.games.filter { it.decision == PackageCopyPlan.Decision.COPY || it.decision == PackageCopyPlan.Decision.RENAMED }.map { it.key }.toSet()
                        placement.sources.filter { it.key in copied }.map { it.folder }.distinct()
                    })
            }
            TransferTarget.NAS -> {
                val session = cloudConnection.open()
                val nas = DavDestination(session.store, session.rootUrl)
                val plan = PackageCopyPlan.plan(sources, { nas.existing(it) }, choice, null)
                val place = TransferPlace(target, null, WebDavPaths.percentDecode(WebDavPaths.rawPathOf(
                    WebDavPaths.childCollection(session.rootUrl, RemoteSend.GAMES_FOLDER))))
                TransferPreview(fresh.profileId, fresh.restrictions, target, null, place, choice, readBack, unavailable, plan, null, recordMap)
            }
            TransferTarget.ROMM -> {
                val platforms = settingsRepository.rommPlatformMap.first()
                // What RomM already has, per platform of the selected games (one listing each).
                val listings = sources.mapNotNull { platforms[it.consoleId] }.distinct().associateWith { serverFiles(it) }
                val plan = RemoteSend.rommPlan(sources, { platforms[it] }, { listings[it] }, choice)
                val host = WebDavPaths.hostOf(rommClient.configuredBaseUrl())
                TransferPreview(fresh.profileId, fresh.restrictions, target, null, TransferPlace(target, null, host),
                    choice, false, unavailable, null, plan, recordMap)
            }
        }
    }

    /** Starts [preview] unless a transfer is running; false when one is. */
    fun start(preview: TransferPreview): Boolean {
        if (_run.value?.running == true) return false
        _run.value = TransferRun(preview, running = true, progress = null, results = emptyList())
        job = scope.launch {
            try {
                check(appSettings.activeProfile.first() == preview.profileId) { "Profile changed" }
                when (preview.target) {
                    TransferTarget.FOLDER -> runFolder(preview)
                    TransferTarget.NAS -> runNas(preview)
                    TransferTarget.ROMM -> runRomm(preview)
                }
                _run.update { it?.copy(running = false, progress = null) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finishUnsent(preview, stopped = true, error = null) }
                throw e
            } catch (e: ProfileChanged) {
                finishUnsent(preview, stopped = true, error = null)
            } catch (e: Exception) {
                Log.w(TAG, "Transfer stopped: ${e.javaClass.simpleName}")
                finishUnsent(preview, stopped = false, error = describe(e))
            }
        }
        return true
    }

    fun stop() { job?.cancel() }

    /** Forgets a finished run (its results). */
    fun clear() { if (_run.value?.running != true) _run.value = null }

    // ---- Folder ------------------------------------------------------------------------------

    private suspend fun runFolder(preview: TransferPreview) {
        val plan = requireNotNull(preview.copy)
        val root = DiskScanner.rootOf(requireNotNull(preview.folderUri)) ?: error("Not a folder")
        val destination = SafDestination(context, root)
        val total = plan.files.sumOf { it.bytes }
        var done = 0L
        plan.files.forEachIndexed { index, file ->
            guard(preview)
            progress(file.target, index, plan.files.size, done, total)
            val start = done
            val (outcome, detail) = try {
                copyToFolder(destination, file) { n -> done += n; progress(file.target, index, plan.files.size, done, total) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Outcome.FAILED to describe(e)
            }
            done = start + file.bytes
            record(file, outcome, detail)
        }
    }

    /** One verified copy: staged under a hidden name, read back, and only then given its name. */
    private suspend fun copyToFolder(destination: SafDestination, file: PackageCopyPlan.PlannedFile, progress: (Long) -> Unit): Pair<Outcome, String?> {
        val check = currentCheck()
        val resolver = context.contentResolver
        val name = file.target.substringAfterLast('/')
        val dir = destination.dir(file.target.substringBeforeLast('/', ""), create = true)
            ?: return Outcome.CONFLICT to context.getString(com.cortinadev.dogmatix.R.string.transfer31_detail_folder_blocked)
        destination.forget(dir)
        destination.child(dir, name)?.let { return compareInFolder(it, file, check) }
        var current: Uri = DocumentsContract.createDocument(resolver, DiskScanner.uriOf(dir), "application/octet-stream",
            ".dogmatix-copy-${UUID.randomUUID()}.part") ?: throw IOException("Cannot write to the folder")
        var published = false
        try {
            val input = resolver.openInputStream(Uri.parse(file.source)) ?: return Outcome.CHANGED to null
            val output = try { resolver.openOutputStream(current, "wt") ?: throw IOException("Cannot write to the folder") }
                catch (e: Exception) { input.close(); throw e }
            val hash = VerifiedCopy.transfer(input, output, check, progress)
            if (hash != file.sha256) return Outcome.CHANGED to null
            val written = VerifiedCopy.hash(resolver.openInputStream(current) ?: throw IOException("Copy cannot be read"), check)
            if (written != hash) return Outcome.FAILED to context.getString(com.cortinadev.dogmatix.R.string.transfer31_detail_verify)
            check()
            // Another app may have put a file there meanwhile: it is never replaced.
            destination.forget(dir)
            destination.child(dir, name)?.let { return compareInFolder(it, file, check) }
            current = DocumentsContract.renameDocument(resolver, current, name) ?: current
            destination.forget(dir)
            val placed = destination.children(dir).firstOrNull { it.documentId == DocumentsContract.getDocumentId(current) }
            if (placed?.name != name) return Outcome.FAILED to context.getString(com.cortinadev.dogmatix.R.string.transfer31_detail_rename)
            published = true
            return Outcome.SENT_HASH to null
        } finally {
            // Only this copy's own unfinished file is removed; never anything that was there before.
            if (!published) withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, current) } }
        }
    }

    private fun compareInFolder(entry: DiskEntry, file: PackageCopyPlan.PlannedFile, check: () -> Unit): Pair<Outcome, String?> {
        if (entry.isDirectory || entry.size != file.bytes) return Outcome.CONFLICT to null
        val hash = VerifiedCopy.hash(context.contentResolver.openInputStream(entry.uri) ?: throw IOException("File cannot be read"), check)
        return if (hash == file.sha256) Outcome.PRESENT_HASH to null else Outcome.CONFLICT to null
    }

    // ---- NAS ---------------------------------------------------------------------------------

    private suspend fun runNas(preview: TransferPreview) {
        val plan = requireNotNull(preview.copy)
        val session = cloudConnection.open()
        val store = session.store
        val root = session.rootUrl
        val ensured = HashSet<String>()
        val total = plan.files.sumOf { it.bytes }
        var done = 0L
        plan.files.forEachIndexed { index, file ->
            guard(preview)
            progress(file.target, index, plan.files.size, done, total)
            val start = done
            val (outcome, detail) = try {
                sendToNas(store, root, ensured, file, preview.readBack) { absolute -> done = start + absolute; progress(file.target, index, plan.files.size, done, total) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is DavException && CloudMessages.isConnectionProblem(e)) {
                    cloudConnection.noteFailure(e)
                    throw e
                }
                Outcome.FAILED to describe(e)
            }
            done = start + file.bytes
            record(file, outcome, detail)
        }
    }

    private suspend fun sendToNas(store: DavStore, root: String, ensured: MutableSet<String>, file: PackageCopyPlan.PlannedFile,
        readBack: Boolean, progress: (Long) -> Unit): Pair<Outcome, String?> {
        val check = currentCheck()
        val segments = RemoteSend.davSegments(file.target)
        val url = WebDavPaths.join(root, segments, asCollection = false)
        val parent = WebDavPaths.join(root, segments.dropLast(1), asCollection = true)
        if (ensured.add(parent)) store.ensureCollection(parent, root)
        stat(store, url)?.let { return compareOnNas(store, url, it, file, readBack, check) }
        val temp = WebDavPaths.join(root, segments.dropLast(1) + RemoteSend.tempName(segments.last()), asCollection = false)
        var moved = false
        var created = false
        try {
            if (upload(store, temp, file, check, progress, ifNoneMatch = false) != file.sha256) return Outcome.CHANGED to null
            moved = try { store.moveNoReplace(temp, url) } catch (e: DavException) {
                if (e.problem != DavProblem.PRECONDITION) throw e
                // Something got there meanwhile: never replaced, only compared.
                store.delete(temp)
                moved = true
                return stat(store, url)?.let { compareOnNas(store, url, it, file, readBack, check) } ?: (Outcome.FAILED to null)
            }
            if (!moved) {
                // A server without MOVE: the file is written under its name, only if there is none yet.
                store.delete(temp)
                moved = true
                if (upload(store, url, file, check, progress, ifNoneMatch = true) != file.sha256) {
                    store.delete(url)
                    return Outcome.CHANGED to null
                }
            }
            created = true
            val there = stat(store, url) ?: return Outcome.FAILED to context.getString(com.cortinadev.dogmatix.R.string.transfer31_detail_verify)
            val needsRead = readBack || there.size == null
            val sha = if (needsRead) readSha(store, url, check) else null
            // Nothing read back where it was needed counts as missing, never as "same size".
            val result = if (needsRead && sha == null) RemoteSend.Check.MISSING
                else RemoteSend.check(file.bytes, file.sha256, there.size ?: file.bytes, sha)
            return when (result) {
                RemoteSend.Check.HASH_OK -> Outcome.SENT_HASH to null
                RemoteSend.Check.SIZE_OK -> Outcome.SENT_SIZE to null
                else -> {
                    // The copy this transfer just made is wrong: it is removed so a retry can send it again.
                    created = false
                    withContext(NonCancellable) { runCatching { store.delete(url) } }
                    Outcome.FAILED to context.getString(com.cortinadev.dogmatix.R.string.transfer31_detail_verify)
                }
            }
        } finally {
            if (!moved) withContext(NonCancellable) { runCatching { store.delete(temp) } }
        }
    }

    /** Uploads the device file to [url]; returns the SHA-256 of what was sent. */
    private fun upload(store: DavStore, url: String, file: PackageCopyPlan.PlannedFile, check: () -> Unit,
        progress: (Long) -> Unit, ifNoneMatch: Boolean): String {
        var digest: MessageDigest? = null
        store.putStream(url, file.bytes, {
            val d = MessageDigest.getInstance("SHA-256").also { digest = it }
            val input = context.contentResolver.openInputStream(Uri.parse(file.source)) ?: throw IOException("File cannot be read")
            ProgressStream(input, check) { n, total -> d.update(n.first, 0, n.second); progress(total) }
        }, ifNoneMatch)
        return requireNotNull(digest).digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareOnNas(store: DavStore, url: String, entry: WebDavXml.Entry, file: PackageCopyPlan.PlannedFile,
        readBack: Boolean, check: () -> Unit): Pair<Outcome, String?> {
        if (entry.isCollection) return Outcome.CONFLICT to null
        if (entry.size != null && entry.size != file.bytes) return Outcome.CONFLICT to null
        if (!readBack && entry.size != null) return Outcome.PRESENT_SIZE to null
        return if (readSha(store, url, check) == file.sha256) Outcome.PRESENT_HASH to null else Outcome.CONFLICT to null
    }

    private fun readSha(store: DavStore, url: String, check: () -> Unit): String? = store.getStream(url) { VerifiedCopy.hash(it, check) }

    private fun stat(store: DavStore, url: String): WebDavXml.Entry? = try {
        store.propfind(url, 0).let { WebDavXml.selfOf(it, url) ?: it.firstOrNull() }
    } catch (e: DavException) {
        if (e.problem == DavProblem.NOT_FOUND) null else throw e
    }

    // ---- RomM --------------------------------------------------------------------------------

    private suspend fun runRomm(preview: TransferPreview) {
        val games = requireNotNull(preview.romm).filter { it.sends || it.decision == RemoteSend.RommDecision.PRESENT }
        val total = games.sumOf { it.bytes }
        var done = 0L
        games.forEachIndexed { index, game ->
            guard(preview)
            progress(game.name, index, games.size, done, total)
            val start = done
            val outcomes = try {
                sendToRomm(preview, game) { absolute -> done = start + absolute; progress(game.name, index, games.size, done, total) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                game.parts.map { Outcome.FAILED to describe(e) }
            }
            done = start + game.bytes
            game.parts.zip(outcomes).forEach { (part, result) ->
                _run.update { run -> run?.copy(results = run.results + TransferFileResult(game.key, game.title, part.path, part.bytes,
                    result.first, result.second, container = game.name.takeIf { game.zipped })) }
            }
        }
    }

    /** One game: its files checked on the device, sent as one file, then looked up on the server. */
    private suspend fun sendToRomm(preview: TransferPreview, game: RemoteSend.RommGame, progress: (Long) -> Unit): List<Pair<Outcome, String?>> {
        val check = currentCheck()
        val platform = requireNotNull(game.platformId)
        fun all(outcome: Outcome, detail: String? = null) = game.parts.map { outcome to detail }
        if (game.decision == RemoteSend.RommDecision.PRESENT) return all(verifyOnRomm(platform, game, null))
        // Every file is read and compared with the package record before anything is sent.
        val crcs = ArrayList<Long>()
        game.parts.forEachIndexed { i, part ->
            val crc = CRC32()
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(Uri.parse(part.source))
            if (input == null) return game.parts.mapIndexed { j, _ -> (if (j == i) Outcome.CHANGED else Outcome.NOT_SENT) to null }
            ProgressStream(input, check) { n, _ -> crc.update(n.first, 0, n.second); digest.update(n.first, 0, n.second) }.use { it.drain() }
            if (digest.digest().joinToString("") { "%02x".format(it) } != part.sha256)
                return game.parts.mapIndexed { j, _ -> (if (j == i) Outcome.CHANGED else Outcome.NOT_SENT) to null }
            crcs += crc.value
        }
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val raw: InputStream = if (game.zipped) {
            val entries = game.parts.mapIndexed { i, p -> StoredZip.Entry(p.path, p.bytes, crcs[i]) }
            check(StoredZip.size(entries) == game.bytes)
            StoredZip.stream(entries, preview.records[game.key]?.createdAt ?: 0L) { i ->
                context.contentResolver.openInputStream(Uri.parse(game.parts[i].source)) ?: throw IOException("File cannot be read")
            }
        } else context.contentResolver.openInputStream(Uri.parse(game.parts.single().source)) ?: throw IOException("File cannot be read")
        val chunks = RommPlatformMapper.chunkCount(game.bytes, CHUNK_SIZE)
        var uploadId: String? = null
        var completed = false
        try {
            val id = rommClient.uploadStart(platform, game.name, game.bytes, chunks)
            uploadId = id
            ProgressStream(raw, check) { n, total ->
                sha1.update(n.first, 0, n.second); md5.update(n.first, 0, n.second); sha256.update(n.first, 0, n.second); crc.update(n.first, 0, n.second)
                progress(total)
            }.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                for (index in 0 until chunks) {
                    var filled = 0
                    while (filled < buffer.size) {
                        val n = input.read(buffer, filled, buffer.size - filled)
                        if (n < 0) break
                        filled += n
                    }
                    var attempt = 0
                    while (true) {
                        try { rommClient.uploadChunk(id, index, buffer, filled); break }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (++attempt >= CHUNK_ATTEMPTS) throw e
                            delay(3_000L)
                        }
                    }
                }
                // The whole stream must have been read: the packed size was exact.
                if (input.read() >= 0) throw IOException("File changed while it was being sent")
            }
            if (!game.zipped && hex(sha256) != game.parts.single().sha256) return all(Outcome.CHANGED)
            rommClient.uploadComplete(id)
            completed = true
        } catch (e: JsonHttp.HttpException) {
            // RomM already has a file of this name (an earlier try did land, or another file): compare, never replace.
            if (e.code == 400 && e.body?.contains("already exists") == true) return all(verifyOnRomm(platform, game, null, present = true))
            throw e
        } finally {
            runCatching { raw.close() }
            val id = uploadId
            if (!completed && id != null) withContext(NonCancellable) { rommClient.uploadCancel(id) }
        }
        preview.records[game.key]?.let { rommLibrary.markUploaded(it.consoleId, it.fileName) }
        return all(verifyOnRomm(platform, game, Triple(hex(sha1), hex(md5), "%08x".format(crc.value))))
    }

    /** Looks the sent file up on the server: same size, and the checksum when RomM lists one. */
    private suspend fun verifyOnRomm(platform: Int, game: RemoteSend.RommGame, sent: Triple<String, String, String>?, present: Boolean = sent == null): Outcome {
        // Not listed yet: RomM shows new files after its next scan; checking again later tells.
        val listed = rommClient.roms(platform).firstOrNull { it.fsName.equals(game.name, ignoreCase = true) }
            ?: return Outcome.WAITING
        if (listed.fsSizeBytes > 0 && listed.fsSizeBytes != game.bytes) return if (present) Outcome.CONFLICT else Outcome.FAILED
        val hashOk = sent?.let { RemoteSend.rommHashMatches(listed.hash, it.first, it.second, it.third) } == true
        return when {
            present -> Outcome.PRESENT_SIZE
            hashOk -> Outcome.SENT_HASH
            listed.fsSizeBytes == game.bytes -> Outcome.SENT_SIZE
            else -> Outcome.WAITING
        }
    }

    private suspend fun serverFiles(platform: Int): Map<String, Long> =
        rommClient.roms(platform).associate { it.fsName.lowercase(java.util.Locale.ROOT) to it.fsSizeBytes }

    // ---- Shared ------------------------------------------------------------------------------

    private class ProfileChanged : IllegalStateException("Profile changed")

    /** Stops before the next file when the profile changed or the run was cancelled. */
    private suspend fun guard(preview: TransferPreview) {
        currentCoroutineContext().ensureActive()
        if (appSettings.activeProfile.first() != preview.profileId) throw ProfileChanged()
    }

    private suspend fun currentCheck(): () -> Unit {
        val coroutine = currentCoroutineContext()
        return { coroutine.ensureActive() }
    }

    private fun progress(file: String, index: Int, count: Int, done: Long, total: Long) {
        _run.update { it?.copy(progress = TransferProgress(file, index, count, done, total)) }
    }

    /** A shared file's result belongs to every game that uses it. */
    private fun record(file: PackageCopyPlan.PlannedFile, outcome: Outcome, detail: String?) {
        _run.update { run ->
            run?.copy(results = run.results + file.keys.zip(file.games).map { (key, title) ->
                TransferFileResult(key, title, file.target, file.bytes, outcome, detail)
            })
        }
    }

    /** Everything the run did not reach is listed as not sent. */
    private fun finishUnsent(preview: TransferPreview, stopped: Boolean, error: String?) {
        _run.update { run ->
            run ?: return@update null
            val unsent = when (preview.target) {
                TransferTarget.ROMM -> preview.romm.orEmpty().filter { it.sends || it.decision == RemoteSend.RommDecision.PRESENT }
                    .filter { game -> run.results.none { it.gameKey == game.key } }
                    .flatMap { game -> game.parts.map { TransferFileResult(game.key, game.title, it.path, it.bytes, Outcome.NOT_SENT, container = game.name.takeIf { game.zipped }) } }
                else -> preview.copy?.files.orEmpty().filter { file -> run.results.none { it.path == file.target } }.flatMap { file ->
                    file.keys.zip(file.games).map { (key, title) -> TransferFileResult(key, title, file.target, file.bytes, Outcome.NOT_SENT) }
                }
            }
            run.copy(running = false, progress = null, results = run.results + unsent, stopped = stopped, error = error)
        }
    }

    /** A failure in words for the user (WebDAV problems in the words of Settings → Cloud). */
    fun describe(e: Throwable): String = when (e) {
        is DavException -> CloudMessages.of(context, e)
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    /**
     * Passes bytes through, checks for cancellation and reports each block ([onBlock] gets the
     * buffer, its length and the total so far).
     */
    private class ProgressStream(input: InputStream, private val check: () -> Unit,
        private val onBlock: (Pair<ByteArray, Int>, Long) -> Unit) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check()
            val n = super.read(buffer, offset, length)
            if (n > 0) {
                total += n
                val block = if (offset == 0) buffer else buffer.copyOfRange(offset, offset + n)
                onBlock(block to n, total)
            }
            return n
        }
        override fun skip(n: Long): Long = 0
        override fun markSupported(): Boolean = false
        fun drain() {
            val buffer = ByteArray(256 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    /** A picked folder, read with one query per folder; names compared like a memory card does (case-insensitive). */
    private class SafDestination(private val context: Context, private val root: DiskDir) {
        private val listings = HashMap<String, List<DiskEntry>>()

        fun children(dir: DiskDir): List<DiskEntry> = listings.getOrPut(dir.documentId) {
            DiskScanner.listOrNull(context, dir, strict = false) ?: throw IOException("The folder cannot be read")
        }

        fun forget(dir: DiskDir) { listings.remove(dir.documentId) }

        fun child(dir: DiskDir, name: String): DiskEntry? {
            val list = children(dir)
            return list.firstOrNull { it.name == name } ?: list.firstOrNull { ArchivePlan.portableKey(it.name) == ArchivePlan.portableKey(name) }
        }

        /** The folder at [path] (created when missing and [create]); null when a file is in the way. */
        fun dir(path: String, create: Boolean): DiskDir? {
            var dir = root
            for (name in path.split('/').filter { it.isNotEmpty() }) {
                val found = child(dir, name)
                dir = when {
                    found == null && create -> {
                        val uri = DocumentsContract.createDocument(context.contentResolver, DiskScanner.uriOf(dir),
                            DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("Cannot create a folder")
                        forget(dir)
                        val made = children(dir).firstOrNull { it.documentId == DocumentsContract.getDocumentId(uri) }
                        if (made == null || !made.isDirectory || ArchivePlan.portableKey(made.name) != ArchivePlan.portableKey(name))
                            throw IOException("Cannot create a folder")
                        DiskScanner.dirOf(dir, made)
                    }
                    found == null -> return null
                    !found.isDirectory -> return null
                    else -> DiskScanner.dirOf(dir, found)
                }
            }
            return dir
        }

        fun existing(path: String): PackageCopyPlan.Existing? {
            val parent = dir(path.substringBeforeLast('/', ""), create = false) ?: return null
            val entry = child(parent, path.substringAfterLast('/')) ?: return null
            return PackageCopyPlan.Existing(entry.isDirectory, entry.size)
        }

        /**
         * [path] as the folders along it are really spelled here (they are found whatever their
         * letter case, like [dir] finds them); the part that does not exist yet stays as given.
         */
        fun spelling(path: String): String {
            val names = path.split('/').filter { it.isNotEmpty() }
            val out = ArrayList<String>()
            var dir = root
            for ((i, name) in names.withIndex()) {
                val found = child(dir, name)
                if (found == null || !found.isDirectory) {
                    out += names.drop(i)
                    break
                }
                out += found.name
                dir = DiskScanner.dirOf(dir, found)
            }
            return out.joinToString("/")
        }
    }

    /** The NAS games folder, listed once per folder. */
    private class DavDestination(private val store: DavStore, private val root: String) {
        private val listings = HashMap<String, List<WebDavXml.Entry>>()

        fun existing(path: String): PackageCopyPlan.Existing? {
            val segments = RemoteSend.davSegments(path)
            val parent = WebDavPaths.join(root, segments.dropLast(1), asCollection = true)
            val entries = listings.getOrPut(parent) { store.list(parent) }
            val name = segments.last()
            val entry = entries.firstOrNull { it.name == name } ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
            return PackageCopyPlan.Existing(entry.isCollection, entry.size ?: -1)
        }
    }

    private companion object {
        const val TAG = "GameTransfer"
        const val CHUNK_SIZE = 8 * 1024 * 1024
        const val CHUNK_ATTEMPTS = 3
    }
}
