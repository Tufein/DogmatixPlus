package com.cortinadev.dogmatix.data.service

import android.content.SharedPreferences
import com.cortinadev.dogmatix.data.local.PersonalPreferences
import com.cortinadev.dogmatix.util.GameEmulatorOverrides
import com.google.gson.JsonObject

/** Uses the same preferences file as console choices, which full backups already preserve. */
class GameEmulatorPreferences(private val preferences: SharedPreferences, private val profileId: () -> String) {
    fun console(consoleId: String): String? = preferences.getString(PersonalPreferences.prefix(profileId()) + consoleId, null)
    fun game(consoleId: String, fileName: String): String? = preferences.getString(GameEmulatorOverrides.storageKey(profileId(), consoleId, fileName), null)

    fun setGame(consoleId: String, fileName: String, handlerKey: String?) {
        val key = GameEmulatorOverrides.storageKey(profileId(), consoleId, fileName)
        preferences.edit().apply {
            if (handlerKey.isNullOrBlank()) remove(key) else putString(key, handlerKey)
        }.apply()
    }

    companion object {
        fun export(preferences: SharedPreferences): JsonObject = JsonObject().apply {
            preferences.all.forEach { (key, value) -> if (value is String) addProperty(key, value) }
        }

        /** Called on IO as part of the backup's guarded restore. Invalid values cannot break reads. */
        fun restore(preferences: SharedPreferences, choices: JsonObject) {
            val editor = preferences.edit().clear()
            choices.entrySet().forEach { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) editor.putString(key, value.asString)
            }
            check(editor.commit()) { "Emulator preferences could not be restored" }
        }
    }
}
