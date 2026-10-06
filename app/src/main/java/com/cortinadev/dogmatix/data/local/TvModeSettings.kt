package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.util.TvModeSetting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 8.0 TV mode (auto / on / off), in the shared preferences file so backups carry it. */
@Singleton
class TvModeSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val key = stringPreferencesKey("tv8_mode")

    val mode: Flow<TvModeSetting> = context.dataStore.data.map { TvModeSetting.fromKey(it[key]) }

    suspend fun setMode(mode: TvModeSetting) = context.dataStore.edit { it[key] = mode.name }
}
