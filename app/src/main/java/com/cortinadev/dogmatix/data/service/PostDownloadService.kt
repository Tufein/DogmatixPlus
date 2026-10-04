package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.EsdeArtwork
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
 *    already has one (ES-DE's own scraping and the user's edits win). Cocoon's ES-DE link reads them too.
 */
@Singleton
class PostDownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val downloadFileManager: DownloadFileManager,
    private val appSettings: AppSettings,
    private val settingsRepository: SettingsRepository,
    private val libraryTools: LibraryToolsService,
    private val metadata: GameMetadataService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            downloadService.finished.collect { name -> runCatching { handle(name) }.onFailure { Log.w(TAG, "After-download step failed for $name: ${it.message}") } }
        }
    }

    private suspend fun handle(fileName: String) {
        val entity = downloadService.entityFor(fileName) ?: return
        val base = downloadFileManager.getDownloadDirectoryUri(entity).toString().takeIf { it.isNotEmpty() } ?: return
        val subPath = downloadFileManager.getSubPath(entity)
        val dir = StorageHelper.createDirectory(context, base, subPath) ?: return
        var romName = downloadService.uploadCandidates(fileName).firstOrNull() ?: return

        if (appSettings.autoM3u.first()) {
            val children = dir.listFiles().filter { it.isFile }
            val files = children.map {
                DiskFile(scope = "", consoleId = entity.consoleId, folder = subPath, name = it.name.orEmpty(), size = it.length(),
                    uri = it.uri.toString(), dirId = dir.uri.toString(), dirUri = dir.uri.toString())
            }
            val mine = downloadService.uploadCandidates(fileName).map { it.lowercase() }.toSet()
            PlaylistPlanner.plan(files).filter { plan -> plan.discs.any { it.lowercase() in mine } }.forEach { plan ->
                if (libraryTools.createPlaylist(plan)) {
                    Log.i(TAG, "Wrote ${plan.fileName}")
                    romName = plan.fileName
                }
            }
        }

        // ES-DE names its systems after the console folders of the shared ROM tree.
        if (appSettings.esdeArtwork.first()) writeEsdeArtwork(entity.name, entity.consoleId, entity.fileName, dir.name.orEmpty(), romName)
    }

    private suspend fun writeEsdeArtwork(name: String, consoleId: String, fileName: String, folder: String, romName: String) {
        val esdeUri = settingsRepository.esdeDirectory.first().takeIf { it.isNotBlank() } ?: return
        val system = folder.takeIf { it.isNotBlank() } ?: return
        val esdeDir = StorageHelper.getDocumentFile(context, esdeUri)?.takeIf { it.isDirectory && it.canWrite() } ?: return
        val details = metadata.lookup(name, consoleId, com.cortinadev.dogmatix.util.FileParsingUtils.decodeUrlEncodedFileName(fileName)) ?: return

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
