package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 5.0 "Look" settings, in the same preferences file as the rest (so backups carry them). */
@Singleton
class LookSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val ANIMATIONS = booleanPreferencesKey("look_animations")
        val GLOW = booleanPreferencesKey("look_glow")
        val LIST_COVERS = booleanPreferencesKey("look_list_covers")
        val COMPACT_LISTS = booleanPreferencesKey("look_compact_lists")
    }

    /** Off = every animation snaps (also follows the system's "remove animations"). */
    val animations: Flow<Boolean> = context.dataStore.data.map { it[Keys.ANIMATIONS] ?: true }
    /** The soft accent glow behind the screens. */
    val glow: Flow<Boolean> = context.dataStore.data.map { it[Keys.GLOW] ?: true }
    /** Small covers in front of the games in the library list. */
    val listCovers: Flow<Boolean> = context.dataStore.data.map { it[Keys.LIST_COVERS] ?: true }

    /** 6.0: tighter library rows (less height and padding, smaller covers) so more games fit on a small screen. */
    val compactLists: Flow<Boolean> = context.dataStore.data.map { it[Keys.COMPACT_LISTS] ?: false }

    suspend fun setAnimations(on: Boolean) = context.dataStore.edit { it[Keys.ANIMATIONS] = on }
    suspend fun setGlow(on: Boolean) = context.dataStore.edit { it[Keys.GLOW] = on }
    suspend fun setListCovers(on: Boolean) = context.dataStore.edit { it[Keys.LIST_COVERS] = on }
    suspend fun setCompactLists(on: Boolean) = context.dataStore.edit { it[Keys.COMPACT_LISTS] = on }
}
