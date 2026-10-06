package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dataStore
import com.cortinadev.dogmatix.util.GameHit
import com.cortinadev.dogmatix.util.GameHits
import com.cortinadev.dogmatix.util.RecentSearches
import com.cortinadev.dogmatix.util.SearchNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library side of "search everything" (8.0): a few games matching the query, with the same
 * lenient LIKE pattern as the library search and the active profile's hidden consoles and tags
 * left out. Also keeps the last few searches of that screen.
 */
@Singleton
class SearchAllService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DownloadableFileDao,
    private val profiles: ProfileService
) {
    private val recentKey = stringPreferencesKey("search_all_recent")

    val recent: Flow<List<String>> = context.dataStore.data.map { RecentSearches.decode(it[recentKey].orEmpty()) }

    suspend fun remember(query: String) {
        context.dataStore.edit { it[recentKey] = RecentSearches.encode(RecentSearches.add(RecentSearches.decode(it[recentKey].orEmpty()), query)) }
    }

    suspend fun clearRecent() {
        context.dataStore.edit { it.remove(recentKey) }
    }

    /** Up to [limit] games for [query]; empty for a blank query. */
    suspend fun games(query: String, limit: Int = GAMES): List<GameHit> = withContext(Dispatchers.IO) {
        val pattern = SearchNormalizer.likePattern(query)
        if (pattern.isEmpty()) return@withContext emptyList()
        val restrictions = profiles.current()
        val rows = dao.filesMatching(pattern, null, CANDIDATES).filter { it.consoleId !in restrictions.hiddenConsoles }
        val allowed = if (restrictions.hiddenTags.isEmpty() || rows.isEmpty()) rows else {
            val tags = dao.tagsOfFiles(rows.map { it.id }).groupBy({ it.fileId }, { it.tag })
            rows.filter { restrictions.allows(it.consoleId, tags[it.id].orEmpty()) }
        }
        GameHits.pick(query, allowed.map { it.name to it.consoleId }, limit)
    }

    private companion object {
        const val GAMES = 5
        /** Rows read to find [GAMES] distinct games (versions and discs repeat a title). */
        const val CANDIDATES = 120
    }
}
