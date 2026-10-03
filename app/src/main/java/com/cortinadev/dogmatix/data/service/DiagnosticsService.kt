package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.CertTrust
import com.cortinadev.dogmatix.util.DiagnosticsRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A text report for a bug report: app and device versions, what is set up (never the values of
 * tokens, passwords or server addresses), counts, and the app's own recent log. Everything goes
 * through [DiagnosticsRedactor] before it is written; the report is shared through the system
 * share sheet, so the user sees it go and chooses where.
 */
@Singleton
class DiagnosticsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleDao: ConsoleDao,
    private val fileDao: DownloadableFileDao,
    private val downloadService: DownloadService,
    private val rommLibraryService: RommLibraryService
) {

    suspend fun buildReport(): String = withContext(Dispatchers.IO) {
        val s = settingsRepository
        val rommUrl = s.rommUrl.first()
        val rommToken = s.rommToken.first()
        val secrets = buildList {
            add(rommToken)
            add(CertTrust.hostOf(rommUrl))
            add(rommUrl.removePrefix("https://").removePrefix("http://").substringBefore('/'))
            add(s.torboxApiKey.first())
            add(s.realDebridApiKey.first())
        }.filter { it.isNotBlank() }

        val downloads = downloadService.getDownloads().groupingBy { it.status.name }.eachCount()
        val consoles = consoleDao.getAllConsoles().first()
        val report = buildString {
            appendLine("DogmatixPlus diagnostics — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
            appendLine()
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine()
            appendLine("Setup")
            appendLine("  download folder set: ${s.downloadDirectory.first().isNotBlank()}; per-console folders: ${s.consoleDownloadDirectories.first().size}")
            appendLine("  separate by console: ${s.separateByConsole.first()}; auto unzip: ${s.autoUnzip.first()}; concurrent: ${s.concurrentDownloads.first()}")
            appendLine("  debrid: ${s.debridProvider.first().name}")
            appendLine("  schedule: wifi=${s.downloadWifiOnly.first()} charging=${s.downloadChargingOnly.first()} night=${s.downloadNightOnly.first()}")
            appendLine("  RomM: url set=${rommUrl.isNotBlank()} (https=${CertTrust.isHttps(rommUrl)}), token set=${rommToken.isNotBlank()}, pinned certificate=${s.rommTrustFingerprint.first().isNotBlank()}, mapped platforms=${s.rommPlatformMap.first().size}, auto upload=${s.rommAutoUpload.first()}, mark games=${s.rommMarkGames.first()}")
            appendLine("  save sync: saves folder=${s.saveSyncSavesDir.first().isNotBlank()} states folder=${s.saveSyncStatesDir.first().isNotBlank()} auto=${s.saveSyncAuto.first()} background=${s.saveSyncBackground.first()} deletions=${s.saveSyncDeletions.first()}")
            appendLine("  update channel: ${if (s.updatePreReleases.first()) "pre-releases" else "releases"}")
            appendLine()
            appendLine("Library")
            appendLine("  consoles: ${consoles.size}; indexed games: ${fileDao.getFilesCount()}")
            appendLine("  downloads: ${downloads.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}")
            val marks = rommLibraryService.state.value
            appendLine("  games known on RomM: ${marks.games}; last read: ${if (marks.updatedAt > 0) Date(marks.updatedAt) else "never"}${marks.error?.let { "; error: $it" } ?: ""}")
            appendLine()
            appendLine("Recent log (this app only)")
            appendLine(recentLog())
        }
        DiagnosticsRedactor.redact(report, secrets)
    }

    /** The app's own recent log lines; an app may read its own process only. */
    private fun recentLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=${Process.myPid()}", "-v", "time").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.ifBlank { "(empty)" }
    }.getOrElse { "(not available: ${it.message})" }

    /** Writes the report and opens the share sheet. */
    suspend fun share(chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, "dogmatixplus-diagnostics.txt").apply { writeText(buildReport()) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
