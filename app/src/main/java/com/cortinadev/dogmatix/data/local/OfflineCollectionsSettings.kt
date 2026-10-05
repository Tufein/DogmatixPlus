package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cortinadev.dogmatix.util.OfflineCollections
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 7.0 "Keep a collection on this device": which collections are switched on, how many games one
 * run may queue, and what the feature fetched (so games that left a collection can be offered for
 * removal) and how the last run went. In the shared preferences file, so backups carry it.
 */
@Singleton
class OfflineCollectionsSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private object Keys {
        val IDS = stringSetPreferencesKey("offline_collections_ids")
        val CAP = intPreferencesKey("offline_collections_cap")
        val WIFI = booleanPreferencesKey("offline_collections_wifi")
        val FETCHED = stringSetPreferencesKey("offline_collections_fetched")
        val LAST = stringPreferencesKey("offline_collections_last")
    }

    /** Ids of the collections kept on the device. */
    val collectionIds: Flow<Set<Long>> = context.dataStore.data.map { prefs -> prefs[Keys.IDS].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    /** The most games one run queues, so a huge collection never floods the queue. */
    val cap: Flow<Int> = context.dataStore.data.map { OfflineCollections.clampCap(it[Keys.CAP] ?: OfflineCollections.DEFAULT_CAP) }

    /** The games it queues wait for an unmetered network (the "download when" condition Wi-Fi). */
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI] ?: true }

    /** How the last run went; null before the first one. */
    val lastRun: Flow<OfflineCollections.RunInfo?> = context.dataStore.data.map { OfflineCollections.RunInfo.decode(it[Keys.LAST]) }

    /** What the feature has fetched so far, one entry per game and collection. */
    val fetched: Flow<Set<OfflineCollections.Fetched>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FETCHED].orEmpty().mapNotNull { OfflineCollections.decodeFetched(it) }.toSet()
    }

    suspend fun setKept(id: Long, on: Boolean) {
        context.dataStore.edit { it[Keys.IDS] = it[Keys.IDS].orEmpty().let { ids -> if (on) ids + id.toString() else ids - id.toString() } }
    }

    /** Keeps only [existing] of the switched-on ids (a collection that was deleted or not restored). */
    suspend fun retainKept(existing: Set<Long>) {
        context.dataStore.edit { prefs ->
            val ids = prefs[Keys.IDS].orEmpty()
            val left = ids.filter { it.toLongOrNull() in existing }.toSet()
            if (left.size != ids.size) prefs[Keys.IDS] = left
        }
    }

    suspend fun setCap(cap: Int) {
        context.dataStore.edit { it[Keys.CAP] = OfflineCollections.clampCap(cap) }
    }

    suspend fun setWifiOnly(on: Boolean) {
        context.dataStore.edit { it[Keys.WIFI] = on }
    }

    suspend fun setLastRun(run: OfflineCollections.RunInfo) {
        context.dataStore.edit { it[Keys.LAST] = run.encode() }
    }

    suspend fun addFetched(entries: Collection<OfflineCollections.Fetched>) {
        if (entries.isEmpty()) return
        context.dataStore.edit { it[Keys.FETCHED] = it[Keys.FETCHED].orEmpty() + entries.map(OfflineCollections::encode) }
    }

    suspend fun removeFetched(entries: Collection<OfflineCollections.Fetched>) {
        if (entries.isEmpty()) return
        context.dataStore.edit { it[Keys.FETCHED] = it[Keys.FETCHED].orEmpty() - entries.map(OfflineCollections::encode).toSet() }
    }

    /** Forgets what [collectionId] fetched (its switch went off): the games stay, nothing is offered for removal. */
    suspend fun forgetCollection(collectionId: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FETCHED] = prefs[Keys.FETCHED].orEmpty().filterNot { OfflineCollections.decodeFetched(it)?.collectionId == collectionId }.toSet()
        }
    }

    suspend fun keptNow(): Set<Long> = collectionIds.first()
}
