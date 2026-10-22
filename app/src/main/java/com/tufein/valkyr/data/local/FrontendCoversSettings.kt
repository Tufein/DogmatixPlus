package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.FrontendCovers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers for launchers (3.1.0, "another launcher" since 3.2.0, own pattern since 3.3.0): per launcher, the media folder
 * the user picked (a SAF tree), how cover files are named and arranged, and whether covers
 * already there may be replaced (off).
 */
@Singleton
class FrontendCoversSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** [pattern] is the user's own cover path, used when [layout] is [FrontendCovers.Layout.CUSTOM]; it is kept while another layout is chosen. */
    data class TargetSettings(val folder: String, val layout: FrontendCovers.Layout, val overwrite: Boolean, val pattern: String = "",
        /** 4.8.0: extra image types written next to the covers (only with [FrontendCovers.Layout.COVERS_FOLDER]); none by default. */
        val extras: Set<FrontendCovers.Extra> = emptySet()
    )

    private fun folderKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.folderKey(t))
    private fun layoutKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.layoutKey(t))
    private fun overwriteKey(t: FrontendCovers.Target) = booleanPreferencesKey(FrontendCovers.overwriteKey(t))
    private fun patternKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.patternKey(t))
    private fun extrasKey(t: FrontendCovers.Target) = stringPreferencesKey(FrontendCovers.extrasKey(t))

    fun of(target: FrontendCovers.Target): Flow<TargetSettings> = context.dataStore.data.map {
        TargetSettings(
            folder = it[folderKey(target)].orEmpty(),
            layout = FrontendCovers.layoutOf(target, it[layoutKey(target)]),
            overwrite = it[overwriteKey(target)] ?: false,
            pattern = it[patternKey(target)].orEmpty(),
            extras = FrontendCovers.extrasOf(it[extrasKey(target)])
        )
    }

    suspend fun setFolder(target: FrontendCovers.Target, uri: String) = context.dataStore.edit { it[folderKey(target)] = uri }
    suspend fun setLayout(target: FrontendCovers.Target, layout: FrontendCovers.Layout) =
        context.dataStore.edit { it[layoutKey(target)] = FrontendCovers.layoutOf(target, layout.key).key }
    suspend fun setOverwrite(target: FrontendCovers.Target, on: Boolean) = context.dataStore.edit { it[overwriteKey(target)] = on }
    /** Turns one extra image type on or off; the others stay as they are. */
    suspend fun setExtra(target: FrontendCovers.Target, extra: FrontendCovers.Extra, on: Boolean) = context.dataStore.edit {
        val now = FrontendCovers.extrasOf(it[extrasKey(target)])
        it[extrasKey(target)] = FrontendCovers.storeExtras(if (on) now + extra else now - extra)
    }
    /** Saves the user's own cover path and chooses it as the layout; a pattern that is not usable is refused. */
    suspend fun setPattern(target: FrontendCovers.Target, pattern: String): Boolean {
        val text = pattern.trim()
        if (FrontendCovers.Pattern.check(text) != null) return false
        context.dataStore.edit {
            it[patternKey(target)] = text
            it[layoutKey(target)] = FrontendCovers.Layout.CUSTOM.key
        }
        return true
    }
}
