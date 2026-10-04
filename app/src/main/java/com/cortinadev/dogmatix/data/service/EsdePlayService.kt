package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.EsdePlay
import com.cortinadev.dogmatix.util.EsdePlayStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What ES-DE records about playing: how often and when last, per game (`<playcount>`,
 * `<lastplayed>` in `gamelists/<system>/gamelist.xml`). ES-DE does not count play time, and
 * Cocoon keeps its play time in its own private database, so play counts are what can be shown.
 * Null when ES-DE is not set up in Dogmatix+.
 */
@Singleton
class EsdePlayService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    suspend fun plays(): List<EsdePlay>? = withContext(Dispatchers.IO) {
        val root = settings.esdeDirectory.first().takeIf { it.isNotBlank() }?.let { DiskScanner.rootOf(it) } ?: return@withContext null
        val gamelists = DiskScanner.list(context, root).firstOrNull { it.isDirectory && it.name.equals("gamelists", true) }
            ?.let { DiskScanner.dirOf(root, it) } ?: return@withContext emptyList()
        DiskScanner.list(context, gamelists).filter { it.isDirectory }.flatMap { system ->
            val xml = DiskScanner.list(context, DiskScanner.dirOf(gamelists, system)).firstOrNull { it.name.equals("gamelist.xml", true) }
                ?.let { e -> runCatching { context.contentResolver.openInputStream(e.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
                ?: return@flatMap emptyList()
            EsdePlayStats.parse(system.name, xml)
        }
    }
}
