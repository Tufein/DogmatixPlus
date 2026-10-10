package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.EsdeArtwork
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.LibretroThumbnails
import com.cortinadev.dogmatix.util.FrontendArtwork
import com.cortinadev.dogmatix.util.PlaylistPlanner
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happens after a download is on disk:
 *  - a multi-disc game that is now complete gets its `.m3u` playlist (when that setting is on);
 *  - with *Covers for ES-DE* on, the game's cover goes to ES-DE's `downloaded_media/<system>/covers`
 *    and a gamelist entry with name, description, date, developer and genre is added, unless ES-DE
 *    already has one (ES-DE's own scraping and the user's edits win). Cocoon's ES-DE link reads them too;
 *  - with *Descriptions for your launcher → after every download* on, the game's details are merged into
 *    ES-DE's gamelist and Pegasus' metadata ([FrontendMetadataService]; only empty fields are filled).
 */
@Singleton
class PostDownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val downloadFileManager: DownloadFileManager,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val libraryTools: LibraryToolsService,
    private val metadata: GameMetadataService,
    private val thumbnails: ThumbnailService,
    private val frontendMetadata: FrontendMetadataService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            downloadService.finished.collect { name -> runCatching { handle(name) }.onFailure { Log.w(TAG, "After-download step failed for $name: ${it.message}") } }
        }
    }

    private suspend fun handle(fileName: String) {
        val entity = downloadService.entityFor(fileName) ?: return
        val location = downloadService.downloadedPackageLocation(entity) ?: return
        val base = location.rootUri
        val subPath = location.subPath
        val root = StorageHelper.getDocumentFile(context, base) ?: return
        val dir = if (subPath.isBlank()) root else StorageHelper.findFile(root, subPath)?.takeIf { it.isDirectory } ?: return
        val candidates = location.paths.filter(StorageHelper::safeRelativePath)
        val generated = mutableListOf<String>()
        val priority = listOf("m3u", "cue", "gdi", "chd", "iso", "pbp", "ccd", "mds")
        var romName = candidates.sortedBy { path -> priority.indexOf(path.substringAfterLast('.').lowercase()).takeIf { it >= 0 } ?: priority.size }.firstOrNull() ?: return

        if (appSettings.autoM3u.first()) {
            // Retain each archive folder's disc relationships. Never combine unrelated games
            // from separate folders merely because an archive happened to contain them both.
            for ((parentPath, paths) in candidates.groupBy { it.substringBeforeLast('/', "") }) {
                val parent = if (parentPath.isEmpty()) dir else StorageHelper.findFile(dir, parentPath)?.takeIf { it.isDirectory } ?: continue
                val children = parent.listFiles().filter { it.isFile }
                val files = children.map {
                    DiskFile(scope = "", consoleId = entity.consoleId, folder = listOf(subPath, parentPath).filter(String::isNotEmpty).joinToString("/"), name = it.name.orEmpty(), size = it.length(),
                        uri = it.uri.toString(), dirId = parent.uri.toString(), dirUri = parent.uri.toString())
                }
                val mine = paths.map { it.substringAfterLast('/').lowercase() }.toSet()
                PlaylistPlanner.plan(files).filter { plan -> plan.discs.any { it.lowercase() in mine } }.forEach { plan ->
                    if (libraryTools.createPlaylist(plan)) {
                        generated += listOf(parentPath, plan.fileName).filter(String::isNotEmpty).joinToString("/")
                        Log.i(TAG, "Wrote ${plan.fileName}")
                        if (romName.substringBeforeLast('/', "") == parentPath &&
                            plan.discs.any { it.equals(romName.substringAfterLast('/'), ignoreCase = true) }) {
                            romName = listOf(parentPath, plan.fileName).filter(String::isNotEmpty).joinToString("/")
                        }
                    }
                }
            }
        }

        if (generated.isNotEmpty()) downloadService.recordPackageAfterPlaylist(entity, base, subPath, candidates + generated)

        // ES-DE names its systems after the console folders of the shared ROM tree.
        if (appSettings.esdeArtwork.first()) writeEsdeArtwork(entity.name, entity.consoleId, entity.fileName, dir.name.orEmpty(), romName)
        if (appSettings.pegasusArtwork.first()) writePegasusCover(entity.name, entity.consoleId, entity.fileName, dir, romName)
        appSettings.retroArchThumbnailsDir.first().takeIf { it.isNotBlank() }?.let { writeRetroArchCover(it, entity.consoleId, entity.fileName, romName.substringAfterLast('/')) }
        // 7.0: description, genre, year, developer and rating into ES-DE's gamelist / Pegasus' metadata (only with "after every download" on).
        frontendMetadata.writeAfterDownload(entity.name, entity.consoleId, romName, dir, dir.name.orEmpty())
    }

    /** Pegasus: `media/<game>/boxFront.<ext>` next to the game, unless a cover is already there. */
    private suspend fun writePegasusCover(name: String, consoleId: String, fileName: String, dir: DocumentFile, romName: String) {
        val url = metadata.lookup(name, consoleId, FileParsingUtils.decodeUrlEncodedFileName(fileName))
            ?.imageUrl?.takeIf { it.isNotBlank() } ?: return
        val media = "media/${com.cortinadev.dogmatix.util.LibraryKeys.baseName(romName)}"
        val existing = StorageHelper.findFile(dir, media)?.listFiles().orEmpty()
        if (existing.any { it.name.orEmpty().substringBeforeLast('.').equals("boxFront", ignoreCase = true) }) return
        val path = FrontendArtwork.pegasusCoverPath(romName, EsdeArtwork.imageExtension(url))
        downloadImage(url)?.let { StorageHelper.writeBytesSafely(context, dir, path.substringBeforeLast('/'), path.substringAfterLast('/'), it) }
    }

    /** RetroArch: the libretro-thumbnails box art (PNG) under its thumbnails folder, unless one is already there. */
    private suspend fun writeRetroArchCover(thumbnailsUri: String, consoleId: String, fileName: String, romName: String) {
        val root = StorageHelper.getDocumentFile(context, thumbnailsUri)?.takeIf { it.isDirectory && it.canWrite() } ?: return
        val system = LibretroThumbnails.systemFor(consoleId) ?: return
        val path = FrontendArtwork.retroArchCoverPath(system, romName)
        if (StorageHelper.findFile(root, path) != null) return
        val url = thumbnails.boxart(consoleId, FileParsingUtils.decodeUrlEncodedFileName(fileName)) ?: return
        downloadImage(url)?.let { StorageHelper.writeBytesSafely(context, root, path.substringBeforeLast('/'), path.substringAfterLast('/'), it) }
    }

    private fun downloadImage(url: String): ByteArray? =
        runCatching { JsonHttp.download(url, maxBytes = 15L * 1024 * 1024) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private suspend fun writeEsdeArtwork(name: String, consoleId: String, fileName: String, folder: String, romName: String) {
        val esdeUri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return
        val system = folder.takeIf { it.isNotBlank() } ?: return
        val esdeDir = StorageHelper.getDocumentFile(context, esdeUri)?.takeIf { it.isDirectory && it.canWrite() } ?: return
        val details = metadata.lookup(name, consoleId, FileParsingUtils.decodeUrlEncodedFileName(fileName)) ?: return

        details.imageUrl.takeIf { it.isNotBlank() }?.let { url ->
            val path = EsdeArtwork.coverPath(system, romName, EsdeArtwork.imageExtension(url))
            if (StorageHelper.findFile(esdeDir, path) == null) {
                val bytes = runCatching { JsonHttp.download(url, maxBytes = 15L * 1024 * 1024) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) StorageHelper.writeBytesSafely(context, esdeDir, path.substringBeforeLast('/'), path.substringAfterLast('/'), bytes)
            }
        }
        // A cover alone (no database description) is enough for ES-DE; no gamelist entry is needed then.
        if (details.description.isBlank() && details.released.isBlank() && details.developer.isBlank()) return
        val gamelistPath = "gamelists/$system/gamelist.xml"
        val existing = StorageHelper.findFile(esdeDir, gamelistPath)?.takeIf { it.isFile }?.let { runCatching { StorageHelper.readText(context, it) }.getOrNull() ?: return }
        EsdeArtwork.gamelistWithGame(
            existing, romName, details.title.ifBlank { name }, details.description,
            details.released, details.developer, details.genres.joinToString(", ")
        )?.let { StorageHelper.writeTextSafely(context, esdeDir, "gamelists/$system", "gamelist.xml", it) }
    }

    private companion object { const val TAG = "PostDownload" }
}
