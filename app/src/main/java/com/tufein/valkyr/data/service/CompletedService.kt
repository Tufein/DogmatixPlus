package com.tufein.valkyr.data.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.tufein.valkyr.util.CompletedEntry
import com.tufein.valkyr.util.CompletedGames
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Completed"

/**
 * The player's "completed" flags (5.5), per profile. Each profile's flags are a small JSON file under
 * `files/completed/` written atomically ([AtomicFile]); [keys] always holds the active profile's set,
 * read again on a profile switch. Deleting a profile forgets its flags. Edits go through one lock and
 * always land in the profile that is active when they run. Same shape as [UpNextService]; like the Up
 * next list the flags are device-local (not part of a settings backup or the device sync).
 */
@Singleton
class CompletedService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val dir = File(context.filesDir, "completed")

    private data class Loaded(val profileId: String, val list: List<CompletedEntry>)
    @Volatile private var loaded: Loaded? = null

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    /** `console|file` of every game the active profile marked as completed. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    init {
        scope.launch { profiles.activeId.collect { id -> lock.withLock { ensure(id) } } }
        scope.launch {
            // A deleted profile takes its flags with it. Only ids seen and then gone count, never a first read.
            var before: Set<String>? = null
            profiles.profiles.collect { list ->
                val now = list.mapTo(HashSet()) { it.id }
                before?.minus(now)?.forEach { gone -> lock.withLock { forget(gone) } }
                before = now
            }
        }
    }

    private fun file(profileId: String) = AtomicFile(File(dir, CompletedGames.fileNameFor(profileId)))

    /** Must hold [lock]. Reads [profileId]'s flags unless they are the ones in memory. */
    private fun ensure(profileId: String): List<CompletedEntry> {
        loaded?.takeIf { it.profileId == profileId }?.let { return it.list }
        val f = file(profileId)
        val list = if (!f.baseFile.exists()) emptyList() else runCatching { CompletedGames.fromJson(f.readFully().toString(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Flags unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
        publish(Loaded(profileId, list))
        return list
    }

    private fun publish(state: Loaded) {
        loaded = state
        _keys.value = state.list.mapTo(HashSet()) { it.key }
    }

    private fun forget(profileId: String) {
        if (profileId.isEmpty()) return
        runCatching { file(profileId).delete() }
        if (loaded?.profileId == profileId) loaded = null
    }

    /** Applies [change] to the active profile's flags and saves them; false when they could not be saved (they then stay as they were). */
    private suspend fun edit(change: (List<CompletedEntry>) -> List<CompletedEntry>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val profileId = profiles.currentIdNow()
            val before = ensure(profileId)
            val after = change(before)
            if (after == before) return@withLock true
            val f = file(profileId)
            try {
                dir.mkdirs()
                val stream = f.startWrite()
                try { stream.write(CompletedGames.toJson(after).toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
                catch (e: Exception) { f.failWrite(stream); throw e }
                publish(Loaded(profileId, after))
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Flags not saved: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    fun isCompleted(consoleId: String, fileName: String): Boolean = CompletedGames.key(consoleId, fileName) in _keys.value

    /**
     * Flips the flag of a game. Returns the new state (true = completed), or null when the change
     * could not be saved (the flag then stays as it was).
     */
    suspend fun toggle(consoleId: String, fileName: String): Boolean? {
        val key = CompletedGames.key(consoleId, fileName)
        val wasOn = key in _keys.value
        val ok = edit { CompletedGames.toggle(it, CompletedEntry(consoleId, fileName, System.currentTimeMillis())) }
        if (!ok) return null
        val isOn = key in _keys.value
        return if (isOn != wasOn) isOn else null
    }
}
