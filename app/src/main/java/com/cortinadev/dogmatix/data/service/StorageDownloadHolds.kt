package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Durable root-specific parking reasons, keyed by game/source identity rather than a reused filename. */
@Singleton
class StorageDownloadHolds @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("storage_download_holds", Context.MODE_PRIVATE)
    @Synchronized fun snapshot(): Map<String, String> {
        val raw = preferences.getString("held", "{}") ?: throw IOException("Storage resume journal unavailable")
        if (raw.length > 1_048_576) throw IOException("Storage resume journal too large")
        return try {
            val objectValue = JsonParser.parseString(raw).asJsonObject
            if (objectValue.size() > 10000) throw IOException("Storage resume journal too large")
            objectValue.entrySet().associate { (identity, rootKey) ->
                check(identity.isNotBlank() && identity.length <= 4096 && rootKey.isJsonPrimitive && rootKey.asJsonPrimitive.isString)
                val key = rootKey.asString
                check(key.isNotBlank() && key.length <= 8192)
                identity to key
            }
        } catch (failure: Exception) { throw IOException("Storage resume journal unavailable", failure) }
    }
    @Synchronized fun hold(identities: Collection<String>, rootUri: String) {
        val key = StorageAvailabilityService.key(rootUri)
        val next = snapshot().toMutableMap()
        identities.forEach { next[it] = key }
        write(next)
    }
    @Synchronized fun release(identities: Collection<String>) {
        val before = snapshot()
        val next = before.filterKeys { it !in identities }
        if (next.size != before.size) write(next)
    }
    private fun write(values: Map<String, String>) {
        val json = JsonObject().apply { values.forEach { (name, key) -> addProperty(name, key) } }.toString()
        if (values.size > 10000 || json.length > 1_048_576 || !preferences.edit().putString("held", json).commit()) {
            throw IOException("Could not preserve storage resume journal")
        }
    }
}
