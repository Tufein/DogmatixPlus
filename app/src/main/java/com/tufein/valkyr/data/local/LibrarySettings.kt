package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.LibraryBrowse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 5.1 Library tab settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class LibrarySettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val viewKey = stringPreferencesKey(LibraryBrowse.VIEW_KEY)

    /** The view the Library tab was left in; the overview (today's look) until the user picks another. */
    val view: Flow<LibraryBrowse.ViewMode> = context.dataStore.data
        .map { LibraryBrowse.parseView(it[viewKey]) }
        .distinctUntilChanged()

    suspend fun setView(mode: LibraryBrowse.ViewMode) = context.dataStore.edit { it[viewKey] = mode.key }
}
