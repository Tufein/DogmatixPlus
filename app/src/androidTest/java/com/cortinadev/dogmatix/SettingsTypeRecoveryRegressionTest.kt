package com.cortinadev.dogmatix

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.SettingsTypeMigration
import com.cortinadev.dogmatix.data.local.dataStore
import com.google.gson.JsonParser
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SettingsTypeRecoveryRegressionTest {
    @Test fun restoringWrongTypedSettingsKeepsTheCurrentValueAndReportsTheSkip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, SaveSafetyEntryPoint::class.java)
        val before = context.dataStore.data.first().asMap()
        try {
            graph.saveSafetyAppSettings().setAutoRetryFailed(false)
            val backup = JsonParser.parseString("""{
                "format":"dogmatix-backup","version":1,
                "settings":{
                    "auto_retry_failed":{"t":"s","v":"true"},
                    "download_wifi_only":{"t":"b","v":true},
                    "offline_collections_ids":{"t":"s","v":"wrong"}
                }
            }""").asJsonObject
            val result = graph.saveSafetyBackupService().restore(backup)
            assertEquals(2, result.skippedSettings)
            assertFalse(graph.saveSafetyAppSettings().autoRetryFailed.first())
            val restored = context.dataStore.data.first()
            assertTrue(restored[booleanPreferencesKey("download_wifi_only")] ?: false)
            assertNull(restored[stringSetPreferencesKey("offline_collections_ids")])
        } finally {
            context.dataStore.edit { prefs ->
                prefs.clear()
                before.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    prefs[key as Preferences.Key<Any>] = value
                }
            }
        }
    }

    @Test fun oldMalformedSettingsAreRepairedBeforeTheFirstTypedEmission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "settings-repair-${UUID.randomUUID()}.preferences_pb")
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val old = PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { file })
            old.edit {
                it[stringPreferencesKey("auto_retry_failed")] = "false"
                it[stringPreferencesKey("personal:child:offline_collections_ids")] = "wrong"
                it[booleanPreferencesKey("download_wifi_only")] = true
            }
            oldScope.coroutineContext[Job]!!.cancelAndJoin()
            val repaired = PreferenceDataStoreFactory.create(migrations = listOf(SettingsTypeMigration()), scope = newScope, produceFile = { file })
            val first = repaired.data.first()
            assertTrue(first[booleanPreferencesKey("auto_retry_failed")] ?: true)
            assertNull(first[stringSetPreferencesKey("personal:child:offline_collections_ids")])
            assertEquals(true, first[booleanPreferencesKey("download_wifi_only")])
        } finally {
            oldScope.coroutineContext[Job]!!.cancelAndJoin()
            newScope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
