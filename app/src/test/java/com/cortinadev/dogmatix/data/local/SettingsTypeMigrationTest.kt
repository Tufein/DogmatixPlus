package com.cortinadev.dogmatix.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsTypeMigrationTest {
    @Test fun `old restored wrong types are removed before typed readers run`() = runBlocking {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("auto_retry_failed") to "false",
            stringPreferencesKey("personal:child:offline_collections_ids") to "wrong",
            booleanPreferencesKey("download_wifi_only") to true,
            stringPreferencesKey("future_setting") to "kept"
        )
        val migration = SettingsTypeMigration()
        assertTrue(migration.shouldMigrate(prefs))
        val repaired = migration.migrate(prefs)
        assertNull(repaired[booleanPreferencesKey("auto_retry_failed")])
        assertFalse(repaired.asMap().keys.any { it.name == "personal:child:offline_collections_ids" })
        assertEquals(true, repaired[booleanPreferencesKey("download_wifi_only")])
        assertEquals("kept", repaired[stringPreferencesKey("future_setting")])
        assertFalse(migration.shouldMigrate(repaired))
    }
}
