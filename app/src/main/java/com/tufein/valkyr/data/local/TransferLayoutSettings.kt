package com.tufein.valkyr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tufein.valkyr.util.TargetFolderLayouts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tools → Copy or send games (3.5.0): the folder names chosen for copies to a folder, memory card
 * or USB stick, in the shared preferences file so backups carry it. RomM and the NAS keep their
 * own naming and have no choice.
 */
@Singleton
class TransferLayoutSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val folderKey = stringPreferencesKey(TargetFolderLayouts.FOLDER_SETTING_KEY)

    val folderLayout: Flow<TargetFolderLayouts.Layout> = context.dataStore.data.map { TargetFolderLayouts.Layout.of(it[folderKey]) }.distinctUntilChanged()

    suspend fun setFolderLayout(layout: TargetFolderLayouts.Layout) = context.dataStore.edit { it[folderKey] = layout.key }
}
