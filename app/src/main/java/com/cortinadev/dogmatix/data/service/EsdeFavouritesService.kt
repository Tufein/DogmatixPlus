package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.EsdeFavourites
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Takes the favourites over from ES-DE: reads `gamelists/<system>/gamelist.xml` in the ES-DE folder
 * of Settings and stars the library games they point at (matched per console by file name without
 * extension). Nothing is unstarred; running it again only adds what is new.
 */
@Singleton
class EsdeFavouritesService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val favouriteDao: FavouriteDao
) {
    /** ES-DE favourites found, and how many of them got a new star. Null when no ES-DE folder is set. */
    data class Result(val found: Int, val starred: Int, val unmatched: Int)

    suspend fun import(): Result? = withContext(Dispatchers.IO) {
        val root = settings.esdeDirectory.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@withContext null
        val gamelists = DiskScanner.list(context, root).firstOrNull { it.isDirectory && it.name.equals("gamelists", true) }
            ?.let { DiskScanner.dirOf(root, it) } ?: return@withContext Result(0, 0, 0)
        val consoles = consoleDao.getAllConsoles().first().map { it.id }
        val existing = favouriteDao.getAll().map { it.consoleId to it.fileName }.toSet()
        var found = 0
        var unmatched = 0
        val add = LinkedHashSet<Pair<String, String>>()
        for (system in DiskScanner.list(context, gamelists).filter { it.isDirectory }) {
            val xmlEntry = DiskScanner.list(context, DiskScanner.dirOf(gamelists, system)).firstOrNull { it.name.equals("gamelist.xml", true) } ?: continue
            val xml = runCatching { context.contentResolver.openInputStream(xmlEntry.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
            val favourites = EsdeFavourites.favouriteFiles(xml)
            if (favourites.isEmpty()) continue
            found += favourites.size
            val matched = HashSet<String>()
            consoles.filter { ConsoleFolderAliases.matches(it, system.name) }.forEach { consoleId ->
                val hits = EsdeFavourites.matchEach(favourites, fileDao.filesOf(consoleId).map { it.fileName })
                hits.forEach { (favourite, fileName) -> add += consoleId to fileName; matched += favourite }
            }
            unmatched += (favourites.size - matched.size).coerceAtLeast(0)
        }
        val fresh = add.filter { it !in existing }
        favouriteDao.upsertAll(fresh.map { (c, f) -> FavouriteEntity(c, f) })
        Result(found, fresh.size, unmatched)
    }
}
