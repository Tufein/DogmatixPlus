package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.data.local.dataStore
import com.cortinadev.dogmatix.util.VersionPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionPreferenceService @Inject constructor(@param:ApplicationContext private val context: Context) {
    private fun key(consoleId: String, name: String) = stringPreferencesKey("fixed_version:" + VersionPreference.key(consoleId, name))
    fun observe(consoleId: String, name: String) = context.dataStore.data.map { it[key(consoleId, name)] }
    suspend fun preferred(consoleId: String, name: String): String? = observe(consoleId, name).first()
    suspend fun set(consoleId: String, name: String, fileName: String?) {
        context.dataStore.edit { if (fileName == null) it.remove(key(consoleId, name)) else it[key(consoleId, name)] = fileName }
    }
}
