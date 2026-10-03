package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommMarks
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RommLibraryService"
/** The server's game list is read again when older than this (and whenever the RomM settings change). */
private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

data class RommLibraryState(
    val refreshing: Boolean = false,
    /** When the server's game list was last read; 0 = never. */
    val updatedAt: Long = 0L,
    val games: Int = 0,
    val error: String? = null
)

/**
 * Knows which games the RomM server already has, so the library can mark them (like the green ✓
 * marks games on the device). Reads the game list of every mapped platform from the server,
 * keeps it in a small file so the marks are there at the next start, and refreshes it now and
 * then. Needs Settings → RomM to be set up and "Mark games in RomM" to be on.
 */
@OptIn(FlowPreview::class)
@Singleton
class RommLibraryService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val storeFile = File(context.filesDir, "romm_library.json")

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    /** `consoleId|name` keys of the games on the server (see [RommMarks]); empty when switched off. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    private val _state = MutableStateFlow(RommLibraryState())
    val state: StateFlow<RommLibraryState> = _state.asStateFlow()

    /** Fingerprint of the settings the stored list was made with: a change means read again. */
    private var storedConfig = ""

    init {
        loadStored()
        scope.launch {
            combine(
                settingsRepository.rommMarkGames, settingsRepository.rommUrl,
                settingsRepository.rommToken, settingsRepository.rommPlatformMap
            ) { on, url, token, map -> Config(on, url.trim().trimEnd('/'), token.isNotBlank(), map) }
                .distinctUntilChanged()
                .debounce(1_500)
                .collect { config ->
                    enabled = config.on && config.ready
                    when {
                        !config.on -> _keys.value = emptySet()
                        !config.ready -> _keys.value = emptySet()
                        else -> if (config.fingerprint != storedConfig || isStale()) refresh() else restoreKeys()
                    }
                }
        }
    }

    private data class Config(val on: Boolean, val url: String, val hasToken: Boolean, val map: Map<String, Int>) {
        val ready: Boolean get() = url.isNotEmpty() && hasToken && map.isNotEmpty()
        val fingerprint: String get() = url + "|" + map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    }

    private fun isStale() = System.currentTimeMillis() - _state.value.updatedAt > STALE_AFTER_MS

    @Volatile private var enabled = false
    private var stored: Set<String> = emptySet()
    private fun restoreKeys() { _keys.value = stored }

    /** Reads the game list of every mapped platform; keeps the old list when the server cannot be reached. */
    suspend fun refresh() {
        if (!settingsRepository.rommMarkGames.first()) return
        lock.withLock {
            val map = settingsRepository.rommPlatformMap.first()
            val url = rommClient.configuredBaseUrl()
            if (map.isEmpty() || url.isEmpty()) { _keys.value = emptySet(); return }
            _state.update { it.copy(refreshing = true, error = null) }
            val keys = HashSet<String>()
            var failures = 0
            var lastError: String? = null
            for ((consoleId, platformId) in map) {
                try {
                    keys += RommMarks.keys(consoleId, rommClient.roms(platformId).map { it.fsName })
                } catch (e: Exception) {
                    failures++
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "Could not list platform $platformId for $consoleId: ${e.message}")
                }
            }
            if (failures == map.size) {
                _state.update { it.copy(refreshing = false, error = lastError) }
                return
            }
            stored = keys
            _keys.value = keys
            storedConfig = Config(true, url, true, map).fingerprint
            val now = System.currentTimeMillis()
            _state.value = RommLibraryState(false, now, keys.size, if (failures > 0) lastError else null)
            persist(now, keys)
        }
    }

    /** A finished upload: the game is on the server now, no need to wait for the next refresh. */
    fun markUploaded(consoleId: String, fileName: String) {
        val key = RommMarks.key(consoleId, fileName)
        stored = stored + key
        if (enabled) _keys.update { it + key }
    }

    private fun loadStored() {
        runCatching {
            if (!storeFile.exists()) return
            val root = JsonParser.parseString(storeFile.readText()).asJsonObject
            stored = root.getAsJsonArray("keys").mapTo(HashSet()) { it.asString }
            storedConfig = root.get("config")?.asString.orEmpty()
            _state.value = RommLibraryState(updatedAt = root.get("updatedAt")?.asLong ?: 0L, games = stored.size)
        }.onFailure { Log.w(TAG, "Unreadable RomM game list; starting over", it) }
    }

    private fun persist(at: Long, keys: Set<String>) {
        runCatching {
            val array = JsonArray().also { a -> keys.forEach { a.add(it) } }
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(JsonObject().apply { addProperty("updatedAt", at); addProperty("config", storedConfig); add("keys", array) }.toString())
            if (!tmp.renameTo(storeFile)) { storeFile.delete(); tmp.renameTo(storeFile) }
        }.onFailure { Log.w(TAG, "Could not save the RomM game list", it) }
    }
}
