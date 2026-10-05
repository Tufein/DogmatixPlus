package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wishes already announced as "Now on your RomM server" (6.0), so each is announced once. Kept in
 * the shared preferences file (backups carry it). `null` = never written: the first check then
 * only records what the server already has, without a notification.
 */
@Singleton
class WishlistAlertSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val key = stringSetPreferencesKey("wishlist_romm_announced")

    suspend fun announced(): Set<String>? = context.dataStore.data.first()[key]

    suspend fun add(keys: Collection<String>) {
        context.dataStore.edit { it[key] = (it[key] ?: emptySet()) + keys }
    }
}
