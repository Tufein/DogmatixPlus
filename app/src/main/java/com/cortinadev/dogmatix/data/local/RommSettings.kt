package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 5.0 RomM settings, in the same preferences file as the rest (so backups carry them). No secrets here. */
@Singleton
class RommSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val FAVOURITES_TWO_WAY = booleanPreferencesKey("romm5_favourites_two_way")
    }

    /**
     * "Keep favourites in step with RomM": stars go up to RomM's favourites collection and hearts
     * (and their removal) come down. Off = the 4.x behaviour (hearts only come down with
     * Collections → From RomM, stars never go up).
     */
    val favouritesTwoWay: Flow<Boolean> = context.dataStore.data.map { it[Keys.FAVOURITES_TWO_WAY] ?: false }

    suspend fun setFavouritesTwoWay(on: Boolean) = context.dataStore.edit { it[Keys.FAVOURITES_TWO_WAY] = on }
}
