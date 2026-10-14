package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.util.FrontendCovers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers for launchers (3.1.0, "another launcher" since 3.2.0): per launcher, the media folder
 * the user picked (a SAF tree), how cover files are named and arranged, and whether covers
 * already there may be replaced (off).
 */
@Singleton
class FrontendCoversSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    data class TargetSettings(val folder: String, val layout: FrontendCovers.Layout, val overwrite: Boolean)

    private fun folderKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.folderKey(t))
    private fun layoutKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.layoutKey(t))
    private fun overwriteKey(t: FrontendCovers.Target) = booleanPreferencesKey(FrontendCovers.overwriteKey(t))

    fun of(target: FrontendCovers.Target): Flow<TargetSettings> = context.dataStore.data.map {
        TargetSettings(
            folder = it[folderKey(target)].orEmpty(),
            layout = FrontendCovers.layoutOf(target, it[layoutKey(target)]),
            overwrite = it[overwriteKey(target)] ?: false
        )
    }

    suspend fun setFolder(target: FrontendCovers.Target, uri: String) = context.dataStore.edit { it[folderKey(target)] = uri }
    suspend fun setLayout(target: FrontendCovers.Target, layout: FrontendCovers.Layout) =
        context.dataStore.edit { it[layoutKey(target)] = FrontendCovers.layoutOf(target, layout.key).key }
    suspend fun setOverwrite(target: FrontendCovers.Target, on: Boolean) = context.dataStore.edit { it[overwriteKey(target)] = on }
}
