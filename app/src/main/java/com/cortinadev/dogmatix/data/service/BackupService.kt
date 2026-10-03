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
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.util.BackupJson
import com.cortinadev.dogmatix.util.SourcesJson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * user is asked to pick the folder again. Sources that are local `.torrent` copies cannot travel
 * in a backup; a restore keeps the ones this install already has.
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

    /** The file was written by a newer Dogmatix whose format this version does not know. */
    class NewerBackupException(val version: Int) : IllegalArgumentException("Backup format $version is newer than ${BackupJson.VERSION}")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    suspend fun export(): Pair<String, Summary> = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first().asMap()
        val settings = JsonObject()
        prefs.forEach { (key, value) -> BackupJson.encodeSetting(value)?.let { settings.add(key.name, it) } }

        val sources = JsonParser.parseString(sourcesRepository.exportDocument()).asJsonObject
        val favourites = favouriteDao.getAll()
        val history = downloadHistoryDao.getAll()

        val root = JsonObject().apply {
            addProperty("format", BackupJson.FORMAT)
            addProperty("version", BackupJson.VERSION)
            addProperty("createdAt", System.currentTimeMillis())
            addProperty("appVersion", BuildConfig.VERSION_NAME)
            add("settings", settings)
            add("sources", sources)
            add("favourites", BackupJson.favouritesToJson(favourites))
            add("downloadHistory", BackupJson.historyToJson(history))
        }
        gson.toJson(root) to Summary(settings.size(), consoleCount(sources), favourites.size, history.size)
    }

    /**
     * Reads the document at [uri] and checks it is a backup this version understands; nothing is
     * changed yet. The picker accepts any file, and ROM folders hold multi-GB images: anything
     * larger than [MAX_BACKUP_BYTES] (far above any real backup) is rejected without reading it all.
     */
    suspend fun read(uri: String): JsonObject = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri.toUri())?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BACKUP_BYTES) throw InvalidBackupException()
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Cannot open $uri")
        val text = bytes.toString(Charsets.UTF_8)
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val format = runCatching { root?.get("format")?.asString }.getOrNull()
        if (root == null || format != BackupJson.FORMAT) throw InvalidBackupException()
        val version = runCatching { root.get("version").asInt }.getOrDefault(0)
        if (version > BackupJson.VERSION) throw NewerBackupException(version)
        root
    }

    /**
     * Restores [backup] in two phases. First every section is read and checked, so a damaged
     * file fails before anything is touched. Then everything is applied in one go that cannot be
     * cancelled (leaving the screen half-way must not leave a half-restored app); the sources are
     * replaced in a single database transaction.
     */
    suspend fun restore(backup: JsonObject): Summary = withContext(Dispatchers.IO) {
        // Null when the file has no settings section: then the current settings stay untouched.
        val settings = (backup.get("settings") as? JsonObject)?.entrySet()
            ?.mapNotNull { (name, element) -> BackupJson.decodeSetting(name, element)?.let { name to it } }
        val sourcesText = (backup.get("sources") as? JsonObject)
            ?.takeIf { consoleCount(it) > 0 }
            ?.toString()
            ?.also { require(SourcesJson.parseDocument(it).isNotEmpty()) { "No sources found in file" } }
        val favourites = BackupJson.favouritesFromJson(backup.get("favourites"))
        val downloads = BackupJson.historyFromJson(backup.get("downloadHistory"))

        withContext(NonCancellable) {
            val (restored, repick) = settings?.let { restoreSettings(it) } ?: (0 to 0)
            val consoles = sourcesText?.let { sourcesRepository.importFromText(it, keepLocalTorrents = true) } ?: 0
            // The sources are committed by now: a failure of the smaller parts (a full disk…) must
            // not hide that, or the restored sources would never be scanned.
            val favouritesDone = runCatching { favouriteDao.upsertAll(favourites) }.isSuccess
            val downloadsDone = runCatching { downloadHistoryDao.insertMissing(downloads) }.isSuccess
            Summary(restored, consoles, if (favouritesDone) favourites.size else 0, if (downloadsDone) downloads.size else 0, repick)
        }
    }

    /** Replaces the settings with the backed-up ones; returns (restored, folders the user must pick again). */
    private suspend fun restoreSettings(settings: List<Pair<String, Any>>): Pair<Int, Int> {
        val granted = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        var restored = 0
        var repick = 0
        context.dataStore.edit { prefs ->
            // A folder the backup cannot bring back keeps whatever this install already had.
            val currentFolders = FOLDER_KEYS.associateWith { prefs[stringPreferencesKey(it)] }
            val currentConsoleDirs = prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES].orEmpty()
            prefs.clear()
            settings.forEach { (name, value) ->
                when (name) {
                    in FOLDER_KEYS -> {
                        val uri = value as String
                        if (uri.isEmpty() || uri in granted) {
                            prefs[stringPreferencesKey(name)] = uri
                            restored++
                        } else {
                            repick++
                        }
                    }
                    SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES.name -> {
                        @Suppress("UNCHECKED_CAST")
                        val entries = value as Set<String>
                        val usable = entries.filter { it.substringAfter(':', "") in granted }
                        repick += entries.size - usable.size
                        val usableIds = usable.map { it.substringBefore(':') }.toSet()
                        prefs[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] =
                            usable.toSet() + currentConsoleDirs.filter { it.substringBefore(':') !in usableIds }
                        restored++
                    }
                    else -> {
                        prefs.put(name, value)
                        restored++
                    }
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

    /** Writes a value decoded by [BackupJson.decodeSetting] under a key of its own type. */
    private fun MutablePreferences.put(name: String, value: Any) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is Double -> this[doublePreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value as Set<String>
        }
    }

    /** Consoles in a sources document (manufacturer keys starting with `_` are metadata). */
    private fun consoleCount(sources: JsonObject): Int =
        sources.entrySet().sumOf { (_, m) -> if (m.isJsonObject) m.asJsonObject.entrySet().count { (k, v) -> !k.startsWith("_") && v.isJsonObject } else 0 }

    companion object {
        private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
        private val FOLDER_KEYS = setOf(
            SettingsKeys.DOWNLOAD_DIRECTORY.name,
            SettingsKeys.ESDE_DIRECTORY.name,
            SettingsKeys.IISU_DIRECTORY.name,
            SettingsKeys.SAVE_SYNC_SAVES_DIR.name,
            SettingsKeys.SAVE_SYNC_STATES_DIR.name
        )
    }
}
