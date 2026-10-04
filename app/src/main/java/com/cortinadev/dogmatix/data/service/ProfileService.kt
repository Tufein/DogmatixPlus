package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.util.LibraryRestrictions
import com.cortinadev.dogmatix.util.Profile
import com.cortinadev.dogmatix.util.Profiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The profiles and which one is active. [restrictions] is what the library query leaves out;
 * switching away from a restricted profile asks for the PIN when one is set.
 */
@Singleton
class ProfileService @Inject constructor(private val settings: AppSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val profiles: StateFlow<List<Profile>> = settings.profiles.map { Profiles.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String> = settings.activeProfile.stateIn(scope, SharingStarted.Eagerly, "")

    private val loaded: StateFlow<LibraryRestrictions?> = combine(settings.profiles, settings.activeProfile) { json, id -> Profiles.restrictionsOf(Profiles.fromJson(json), id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** What the active profile hides; [LibraryRestrictions.NONE] until the settings are read (see [current]). */
    val restrictions: StateFlow<LibraryRestrictions> = loaded.map { it ?: LibraryRestrictions.NONE }
        .stateIn(scope, SharingStarted.Eagerly, LibraryRestrictions.NONE)

    /**
     * The restrictions once the stored settings are read — the library waits for this, so the
     * first screen after starting the app never shows what a profile hides (no blocking read at startup).
     */
    suspend fun current(): LibraryRestrictions = loaded.filterNotNull().first()

    val pinHash: StateFlow<String> = settings.profilePinHash.stateIn(scope, SharingStarted.Eagerly, "")

    suspend fun save(profile: Profile) {
        val others = profiles.value.filterNot { it.id == profile.id }
        settings.setProfiles(Profiles.toJson(others + profile))
    }

    suspend fun delete(id: String) {
        settings.setProfiles(Profiles.toJson(profiles.value.filterNot { it.id == id }))
        if (activeId.value == id) settings.setActiveProfile("")
    }

    /** Switches to [id] ("" = everything). Leaving a restricted profile needs [pin] when a PIN is set; false when it is wrong. */
    suspend fun switchTo(id: String, pin: String = ""): Boolean {
        val leavingRestricted = current().active && id != activeId.value
        if (leavingRestricted && !Profiles.pinMatches(pin, settings.profilePinHash.first())) return false
        settings.setActiveProfile(id)
        return true
    }

    /** Sets the PIN (empty removes it). Only allowed while no restricted profile is active. */
    suspend fun setPin(pin: String): Boolean {
        if (current().active) return false
        settings.setProfilePinHash(if (pin.isBlank()) "" else Profiles.pinHash(pin.trim()))
        return true
    }
}
