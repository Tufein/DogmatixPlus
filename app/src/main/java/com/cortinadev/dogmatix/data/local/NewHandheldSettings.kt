package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cortinadev.dogmatix.util.NewHandheldPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Set up a new handheld" checklist's own progress on this handheld: the steps the user
 * skipped or ticked off by hand, whether a save sync ever finished here, and the dismissed hint on
 * the library. Its own file, like the cloud's device records: a settings backup never carries it
 * (a backup from another handheld must not mark this one as set up) and a restore, which clears
 * `user_settings`, never wipes it in the middle of the checklist.
 */
private val Context.newHandheldStore by preferencesDataStore(name = "new_handheld")

@Singleton
class NewHandheldSettings @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val marksKey = stringSetPreferencesKey(NewHandheldPlan.KEY_MARKS)
    private val savesSyncedKey = longPreferencesKey(NewHandheldPlan.KEY_SAVES_SYNCED_AT)
    private val hintDismissedKey = booleanPreferencesKey(NewHandheldPlan.KEY_HINT_DISMISSED)

    val marks: Flow<Map<NewHandheldPlan.Step, NewHandheldPlan.Mark>> =
        context.newHandheldStore.data.map { NewHandheldPlan.decodeMarks(it[marksKey]) }.distinctUntilChanged()

    /** Epoch ms of the first finished save sync the checklist saw here; 0 = none yet. */
    val savesSyncedAt: Flow<Long> = context.newHandheldStore.data.map { it[savesSyncedKey] ?: 0L }.distinctUntilChanged()

    val homeHintDismissed: Flow<Boolean> = context.newHandheldStore.data.map { it[hintDismissedKey] ?: false }.distinctUntilChanged()

    /** Sets or (with null) clears the user's mark on [step]. */
    suspend fun setMark(step: NewHandheldPlan.Step, mark: NewHandheldPlan.Mark?) {
        context.newHandheldStore.edit { prefs ->
            val current = NewHandheldPlan.decodeMarks(prefs[marksKey]).toMutableMap()
            if (mark == null) current.remove(step) else current[step] = mark
            prefs[marksKey] = NewHandheldPlan.encodeMarks(current)
        }
    }

    /** "Start over": forgets every skip and every step ticked off by hand. */
    suspend fun clearMarks() {
        context.newHandheldStore.edit { it.remove(marksKey) }
    }

    suspend fun recordSavesSynced(at: Long) {
        context.newHandheldStore.edit { if ((it[savesSyncedKey] ?: 0L) <= 0L) it[savesSyncedKey] = at }
    }

    suspend fun dismissHomeHint() {
        context.newHandheldStore.edit { it[hintDismissedKey] = true }
    }
}
