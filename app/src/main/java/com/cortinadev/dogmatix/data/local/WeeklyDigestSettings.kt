package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 7.0 weekly digest: whether the opt-in notification is on (off by default) and when the last one
 * was sent. Same preferences file as the other settings, so backups carry them.
 */
@Singleton
class WeeklyDigestSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("upg7_digest_enabled")
        val LAST_SENT = longPreferencesKey("upg7_digest_last_sent")
    }

    /** A weekly summary notification; default off. */
    val enabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ENABLED] ?: false }

    /** Epoch millis of the last digest sent; 0 = never. */
    val lastSent: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SENT] ?: 0L }

    suspend fun setEnabled(on: Boolean) {
        context.dataStore.edit { it[Keys.ENABLED] = on }
    }

    suspend fun setLastSent(at: Long) {
        context.dataStore.edit { it[Keys.LAST_SENT] = at }
    }
}
