package com.cortinadev.dogmatix.data.service

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.SettingsKeys
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.dataStore
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One file with everything worth keeping: settings, sources, favourites and the downloads list.
 *
 * ```
 * { "format": "dogmatix-backup", "version": 1, "createdAt": 1759400000000, "appVersion": "1.2",
 *   "settings": { "theme_mode": { "t": "s", "v": "DARK" }, … },
 *   "sources": { …the sources export document… },
 *   "favourites": [ … ], "downloadHistory": [ … ] }
 * ```
 * Folder settings are SAF grants that belong to this installation: on restore they are only
 * applied when the app still holds the permission, otherwise the current value is kept and the
 * user is asked to pick the folder again.
 */
@Singleton
class BackupService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sourcesRepository: SourcesRepository,
    private val favouriteDao: FavouriteDao,
    private val downloadHistoryDao: DownloadHistoryDao
) {
    data class Summary(
        val settings: Int,
        val consoles: Int,
        val favourites: Int,
        val downloads: Int,
        /** Folder settings that could not be restored because this install has no access to them. */
        val foldersToRepick: Int = 0
    )

    class InvalidBackupException : IllegalArgumentException("Not a Dogmatix backup")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    suspend fun export(): Pair<String, Summary> = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first().asMap()
        val settings = JsonObject()
        prefs.forEach { (key, value) -> encode(value)?.let { settings.add(key.name, it) } }

        val sources = JsonParser.parseString(sourcesRepository.exportDocument()).asJsonObject
        val favourites = favouriteDao.getAll()
        val history = downloadHistoryDao.getAll().map { it.copy(debridProvider = null, debridTorrentId = null, debridFileId = null) }

        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("appVersion", BuildConfig.VERSION_NAME)
            add("settings", settings)
            add("sources", sources)
            add("favourites", gson.toJsonTree(favourites))
            add("downloadHistory", gson.toJsonTree(history))
        }
        gson.toJson(root) to Summary(settings.size(), sources.entrySet().sumOf { (_, m) ->
            m.asJsonObject.keySet().count { !it.startsWith("_") }
        }, favourites.size, history.size)
    }

    /** Reads the document at [uri] and checks it is a backup; nothing is changed yet. */
    suspend fun read(uri: String): JsonObject = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri.toUri())?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Cannot open $uri")
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        if (root == null || root.get("format")?.asString != FORMAT) throw InvalidBackupException()
        root
    }

    suspend fun restore(backup: JsonObject): Summary = withContext(Dispatchers.IO) {
        val (settingsCount, repick) = backup.getAsJsonObject("settings")?.let { restoreSettings(it) } ?: (0 to 0)

        val consoles = backup.getAsJsonObject("sources")
            ?.takeIf { doc -> doc.entrySet().any { (_, m) -> m.isJsonObject && m.asJsonObject.keySet().any { !it.startsWith("_") } } }
            ?.let { sourcesRepository.importFromText(it.toString()) } ?: 0

        val favourites = backup.getAsJsonArray("favourites")?.let { array ->
            val rows = gson.fromJson<List<FavouriteEntity>>(array, object : TypeToken<List<FavouriteEntity>>() {}.type)
                .filter { (it.consoleId as String?) != null && (it.fileName as String?) != null }
            favouriteDao.upsertAll(rows)
            rows.size
        } ?: 0

        val downloads = backup.getAsJsonArray("downloadHistory")?.let { array ->
            val rows = gson.fromJson<List<DownloadHistoryEntity>>(array, object : TypeToken<List<DownloadHistoryEntity>>() {}.type)
                .filter { it.isComplete() }
            downloadHistoryDao.insertMissing(rows)
            rows.size
        } ?: 0

        Summary(settingsCount, consoles, favourites, downloads, repick)
    }

    /** Replaces the settings with the backed-up ones; returns (restored, folders the user must pick again). */
    private suspend fun restoreSettings(settings: JsonObject): Pair<Int, Int> {
        val granted = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        var restored = 0
        var repick = 0
        context.dataStore.edit { prefs ->
            // A folder the backup cannot bring back keeps whatever this install already had.
            val currentFolders = FOLDER_KEYS.associateWith { prefs[stringPreferencesKey(it)] }
            val currentConsoleDirs = prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES].orEmpty()
            prefs.clear()
            settings.entrySet().forEach { (name, element) ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                when (name) {
                    in FOLDER_KEYS -> {
                        val value = runCatching { obj.get("v").asString }.getOrDefault("")
                        if (value.isEmpty() || value in granted) {
                            prefs[stringPreferencesKey(name)] = value
                            restored++
                        } else {
                            repick++
                        }
                    }
                    SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES.name -> {
                        val entries = runCatching { obj.getAsJsonArray("v").map { it.asString } }.getOrDefault(emptyList())
                        val usable = entries.filter { it.substringAfter(':', "") in granted }
                        repick += entries.size - usable.size
                        val usableIds = usable.map { it.substringBefore(':') }.toSet()
                        prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] =
                            usable.toSet() + currentConsoleDirs.filter { it.substringBefore(':') !in usableIds }
                        restored++
                    }
                    else -> if (decodeInto(prefs, name, obj)) restored++
                }
            }
            currentFolders.forEach { (name, value) ->
                val key = stringPreferencesKey(name)
                if (prefs[key].isNullOrEmpty() && !value.isNullOrEmpty()) prefs[key] = value
            }
            if (prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] == null && currentConsoleDirs.isNotEmpty()) {
                prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] = currentConsoleDirs
            }
            // Restoring must never send the user back through the first-run tour.
            prefs[SettingsKeys.ONBOARDING_DONE] = true
        }
        return restored to repick
    }

    private fun encode(value: Any): JsonObject? {
        val (type, json) = when (value) {
            is Boolean -> "b" to JsonPrimitive(value)
            is Int -> "i" to JsonPrimitive(value)
            is Long -> "l" to JsonPrimitive(value)
            // As text: the speed limit is +Infinity, which JSON numbers cannot hold.
            is Float -> "f" to JsonPrimitive(value.toString())
            is Double -> "d" to JsonPrimitive(value.toString())
            is String -> "s" to JsonPrimitive(value)
            is Set<*> -> "ss" to JsonArray().apply { value.filterIsInstance<String>().forEach { add(it) } }
            else -> return null
        }
        return JsonObject().apply { addProperty("t", type); add("v", json) }
    }

    private fun decodeInto(prefs: MutablePreferences, name: String, obj: JsonObject): Boolean {
        val v = obj.get("v") ?: return false
        return runCatching {
            when (obj.get("t")?.asString) {
                "b" -> prefs[booleanPreferencesKey(name)] = v.asBoolean
                "i" -> prefs[intPreferencesKey(name)] = v.asInt
                "l" -> prefs[longPreferencesKey(name)] = v.asLong
                "f" -> prefs[floatPreferencesKey(name)] = v.asString.toFloat()
                "d" -> prefs[doublePreferencesKey(name)] = v.asString.toDouble()
                "s" -> prefs[stringPreferencesKey(name)] = v.asString
                "ss" -> prefs[stringSetPreferencesKey(name)] = v.asJsonArray.map { it.asString }.toSet()
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    /** Gson fills fields without constructors, so rows from a hand-edited file may hold nulls. */
    @Suppress("SENSELESS_COMPARISON")
    private fun DownloadHistoryEntity.isComplete(): Boolean =
        fileName != null && name != null && consoleId != null && downloadUrl != null &&
            fileExtension != null && status != null && runCatching { DownloadStatus.valueOf(status) }.isSuccess

    companion object {
        const val FORMAT = "dogmatix-backup"
        const val VERSION = 1
        private val FOLDER_KEYS = setOf(
            SettingsKeys.DOWNLOAD_DIRECTORY.name,
            SettingsKeys.ESDE_DIRECTORY.name,
            SettingsKeys.IISU_DIRECTORY.name
        )
    }
}
