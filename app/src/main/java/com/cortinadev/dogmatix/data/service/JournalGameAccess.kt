package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.util.JournalKey
import com.cortinadev.dogmatix.util.Profiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Call within withActiveProfile before a mutation; settings/restrictions use the same lock. */
@Singleton
class JournalGameAccess @Inject constructor(
    private val settings: AppSettings,
    private val repository: DownloadableFileRepository
) {
    suspend fun check(key: JournalKey) {
        val restrictions = Profiles.restrictionsOf(Profiles.fromJson(settings.profiles.first()), key.profileId)
        val games = if (restrictions.active) repository.exactMatches(key.consoleId, key.fileName) else emptyList()
        check(!restrictions.active || (games.isNotEmpty() && games.all { restrictions.allows(key.consoleId, it.tags) })) {
            "This game is not available in the current profile"
        }
    }
}
