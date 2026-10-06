package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.ListImport
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.VersionPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a list of wanted games comes to in the library. */
data class ListMatch(
    /** The best version of each listed game the sources have, one per console. */
    val toDownload: List<DownloadableFileEntity>,
    /** Listed games already on the device or downloading. */
    val have: List<String>,
    /** Listed games no source lists. */
    val missing: List<String>
) {
    val found: Int get() = toDownload.map { GameTitleCleaner.words(it.name) }.distinct().size
    val totalBytes: Long get() = toDownload.sumOf { it.fileSize }
}

/**
 * Finds the games of a list (a text file, the clipboard, or the missing games of a DAT check) in
 * the library: exactly the same title, the best version by the user's languages, skipping what is
 * already there.
 */
@Singleton
class ListImportService @Inject constructor(
    private val fileDao: DownloadableFileDao,
    private val settingsRepository: SettingsRepository,
    private val libraryIndex: LibraryIndexService,
    private val downloadService: DownloadService,
    private val wishlist: WishlistRepository,
    private val versionPreference: VersionPreferenceService
) {
    suspend fun match(titles: List<String>, consoleId: String?, onProgress: (Int) -> Unit = {}): ListMatch = withContext(Dispatchers.IO) {
        val languages = settingsRepository.favoriteLanguages.first()
        val regions = VersionPicker.regionPreference(languages)
        val picks = mutableListOf<DownloadableFileEntity>()
        val have = mutableListOf<String>()
        val missing = mutableListOf<String>()
        titles.forEachIndexed { i, title ->
            onProgress(i)
            val word = ListImport.searchWord(title)
            val files = if (word == null) emptyList()
            else fileDao.filesMatching(SearchNormalizer.likePattern(word), consoleId, CANDIDATES)
                .filter { GameTitleCleaner.sameTitle(title, it.name) }
            when {
                files.isEmpty() -> missing += title
                files.any { libraryIndex.isOwned(it) || downloadService.isActive(it.fileName) } -> have += title
                else -> files.groupBy { it.consoleId }.values.forEach { perConsole ->
                    val best = com.cortinadev.dogmatix.util.VersionPreference.pick(
                        perConsole.map { VersionPicker.Candidate(it.fileName, it.fileName, fileDao.tagsOf(it.id), it.fileSize) },
                        regions, languages, versionPreference.preferred(perConsole.first().consoleId, perConsole.first().fileName)
                    )
                    perConsole.firstOrNull { it.fileName == best?.id }?.let(picks::add)
                }
            }
        }
        ListMatch(picks.distinctBy { it.fileName }, have, missing)
    }

    fun download(match: ListMatch) = downloadService.startDownloads(match.toDownload)

    /** Puts the games no source lists on the wishlist; returns how many were new there. */
    suspend fun wishMissing(match: ListMatch, consoleId: String?): Int = match.missing.count { wishlist.add(it, consoleId) }

    private companion object {
        /** Library rows read per listed game before the exact title check. */
        const val CANDIDATES = 400
    }
}
