package com.cortinadev.dogmatix.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.cortinadev.dogmatix.util.SettingSchema

/** Repairs settings restored by older versions before any typed preference reader observes them. */
internal class SettingsTypeMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().any { (key, value) -> !SettingSchema.compatible(key.name, value) }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        currentData.asMap().forEach { (key, value) ->
            if (!SettingSchema.compatible(key.name, value)) {
                @Suppress("UNCHECKED_CAST")
                remove(key as Preferences.Key<Any>)
            }
        }
    }

    override suspend fun cleanUp() = Unit
}
