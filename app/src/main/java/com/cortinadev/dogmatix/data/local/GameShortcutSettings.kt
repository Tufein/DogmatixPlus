package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.cortinadev.dogmatix.util.GameShortcuts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 3.5 game shortcuts settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class GameShortcutSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val recentMenuKey = booleanPreferencesKey(GameShortcuts.RECENT_MENU_KEY)

    /** The recently played games in the long-press menu of the app icon (default on). */
    val recentMenu: Flow<Boolean> = context.dataStore.data.map { it[recentMenuKey] ?: true }

    suspend fun setRecentMenu(on: Boolean) = context.dataStore.edit { it[recentMenuKey] = on }
}
