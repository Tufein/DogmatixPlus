package com.cortinadev.dogmatix.data.local

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ConsoleOverride
import com.cortinadev.dogmatix.util.VersionPreference
import com.cortinadev.dogmatix.util.VersionPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's version preference (2.4.0): which languages and regions, in which order, and the rules
 * for betas, revisions, dumps and hacks. Every automatic "best version" asks it ([effectiveFlow] /
 * [snapshot]) and hands the answer to [VersionPreference.pick], where a version fixed for one game
 * (VersionPreferenceService) still wins first.
 *
 * Nothing pinned = [VersionPreferences.defaultFor] the favourite languages, which picks as the app
 * always did, and follows those languages live. A pinned preference applies to every console unless
 * that console has its own order of regions and/or languages ([overrides]). Kept in the shared
 * preferences file (backups carry it).
 */
@Singleton
class VersionPreferenceSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository
) {
    private object Keys {
        val PINNED = stringPreferencesKey("version_pref_pinned")
        val OVERRIDES = stringPreferencesKey("version_pref_overrides")
    }

    /** The pinned preference for all consoles; null = nothing pinned, the default applies. */
    val pinned: Flow<VersionPreference?> = context.dataStore.data.map { VersionPreferences.fromJson(it[Keys.PINNED]) }.distinctUntilChanged()

    /** Console id → that console's own regions / languages. */
    val overrides: Flow<Map<String, ConsoleOverride>> =
        context.dataStore.data.map { VersionPreferences.overridesFromJson(it[Keys.OVERRIDES]) }.distinctUntilChanged()

    /** What applies without anything pinned: the favourite languages, as the app always chose. */
    val default: Flow<VersionPreference> = settingsRepository.favoriteLanguages.map { favourites ->
        val device = runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull() ?: Locale.getDefault()
        val app = runCatching { AppCompatDelegate.getApplicationLocales()[0] }.getOrNull()
        VersionPreferences.defaultFor(favourites, app?.language, device?.language)
    }.distinctUntilChanged()

    /** The preference for all consoles: the pinned one, else the default. */
    val global: Flow<VersionPreference> = combine(pinned, default) { pinned, default -> pinned ?: default }.distinctUntilChanged()

    /** The preference for [consoleId] (null = no console in mind: the global one), live. */
    fun effectiveFlow(consoleId: String?): Flow<VersionPreference> =
        combine(global, overrides) { global, overrides -> VersionPreferences.withOverride(global, consoleId?.let { overrides[it] }) }
            .distinctUntilChanged()

    suspend fun effective(consoleId: String?): VersionPreference = effectiveFlow(consoleId).first()

    /** Everything as it is now, so a loop over many games and consoles asks it without suspending. */
    class Snapshot internal constructor(private val global: VersionPreference, private val overrides: Map<String, ConsoleOverride>) {
        fun of(consoleId: String?): VersionPreference = VersionPreferences.withOverride(global, consoleId?.let { overrides[it] })
    }

    suspend fun snapshot(): Snapshot = Snapshot(global.first(), overrides.first())

    // ---- Changing it -----------------------------------------------------------------------------

    /** Pins [preference] for every console. */
    suspend fun pin(preference: VersionPreference) {
        context.dataStore.edit { it[Keys.PINNED] = VersionPreferences.toJson(preference) }
    }

    /** Back to the default from the favourite languages. */
    suspend fun clearPinned() {
        context.dataStore.edit { it.remove(Keys.PINNED) }
    }

    /** Gives [consoleId] its own order; null or an empty [ConsoleOverride] removes it. */
    suspend fun setOverride(consoleId: String, override: ConsoleOverride?) {
        context.dataStore.edit { prefs ->
            val all = VersionPreferences.overridesFromJson(prefs[Keys.OVERRIDES]).toMutableMap()
            if (override == null || override.isEmpty) all.remove(consoleId) else all[consoleId] = override
            if (all.isEmpty()) prefs.remove(Keys.OVERRIDES) else prefs[Keys.OVERRIDES] = VersionPreferences.overridesToJson(all)
        }
    }
}
