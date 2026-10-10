package com.cortinadev.dogmatix.util

/** Recovery never turns a legacy receipt without an owner into a way around a profile. */
object RecoveryScope {
    fun allows(
        profileId: String,
        restrictions: LibraryRestrictions,
        recordedProfileId: String?,
        consoleId: String?,
        tags: Collection<String>?
    ): Boolean {
        if (recordedProfileId != null && recordedProfileId != profileId) return false
        if (recordedProfileId == null && profileId.isNotBlank()) return false
        if (!restrictions.active) return true
        if (consoleId.isNullOrBlank() || consoleId in restrictions.hiddenConsoles) return false
        if (restrictions.hiddenTags.isNotEmpty() && tags == null) return false
        return restrictions.allows(consoleId, tags.orEmpty())
    }
}
