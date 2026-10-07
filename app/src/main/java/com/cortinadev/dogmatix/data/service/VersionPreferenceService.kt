package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.data.local.dataStore
import com.cortinadev.dogmatix.util.VersionPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionPreferenceService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val actionLog: ActionLogService
) {
    /** One immutable read for a whole console, rather than a DataStore subscription per row. */
    class Snapshot internal constructor(private val preferences: Preferences) {
        private val pins = VersionPreference.storedPins(preferences.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith("fixed_version:") && value is String) key.name.removePrefix("fixed_version:") to value else null
        }.toMap())
        fun preferred(consoleId: String, name: String): String? = pins[VersionPreference.key(consoleId, name)]
    }

    suspend fun snapshot(): Snapshot = Snapshot(context.dataStore.data.first())

    fun observe(consoleId: String, name: String) = context.dataStore.data.map { Snapshot(it).preferred(consoleId, name) }
    suspend fun preferred(consoleId: String, name: String): String? = observe(consoleId, name).first()
    suspend fun set(consoleId: String, name: String, fileName: String?) {
        context.dataStore.edit { prefs ->
            val canonical = VersionPreference.key(consoleId, name)
            // Remove every legacy spelling of this pin so clearing it cannot revive an old value.
            prefs.asMap().keys.filter { stored ->
                val value = prefs[stored] as? String
                stored.name.startsWith("fixed_version:") && value != null &&
                    VersionPreference.storedPins(mapOf(stored.name.removePrefix("fixed_version:") to value)).containsKey(canonical)
            }.forEach { prefs.remove(it) }
            if (fileName != null) prefs[key(consoleId, name)] = fileName
        }
        actionLog.versionPin(consoleId, name, fileName)
    }

    private companion object {
        fun key(consoleId: String, name: String) = stringPreferencesKey("fixed_version:" + VersionPreference.key(consoleId, name))
    }
}
