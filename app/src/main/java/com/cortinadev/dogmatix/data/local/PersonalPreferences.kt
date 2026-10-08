package com.cortinadev.dogmatix.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** The empty profile keeps every legacy key. New profiles start with their own defaults. */
object PersonalPreferences {
    val active = stringPreferencesKey("active_profile")
    fun prefix(id: String) = if (id.isBlank()) "" else "personal:$id:"
    fun prefix(prefs: Preferences) = prefix(prefs[active].orEmpty())
    fun stringKey(prefs: Preferences, name: String) = stringPreferencesKey(prefix(prefs) + name)
}
