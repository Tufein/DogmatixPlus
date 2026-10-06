package com.cortinadev.dogmatix.data.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.CollectionGoalsSettings
import com.cortinadev.dogmatix.data.local.WeeklyDigestSettings
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DigestFile
import com.cortinadev.dogmatix.util.DigestGoal
import com.cortinadev.dogmatix.util.DigestSummary
import com.cortinadev.dogmatix.util.DigestWish
import com.cortinadev.dogmatix.util.WeeklyDigest
import com.cortinadev.dogmatix.util.WishlistMatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WeeklyDigest"
private const val NOTIFICATION_ID = 7402

/**
 * Works out and sends the weekly digest (7.0, see [WeeklyDigest]); [WeeklyDigestScheduler] wakes
 * it up. Everything it reads is local (the library table, the on-disk index, the wishlist, the
 * goals, the download log), so it needs no network. Nothing is sent when the digest is off, when
 * one went out less than five days ago, or when the week held no news.
 */
@Singleton
class WeeklyDigestService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: WeeklyDigestSettings,
    private val fileDao: DownloadableFileDao,
    private val consoleRepository: ConsoleRepository,
    private val libraryIndex: LibraryIndexService,
    private val wishlist: WishlistRepository,
    private val goalsSettings: CollectionGoalsSettings,
    private val goalsService: CollectionGoalsService,
    private val downloadLog: DownloadLog
) {

    /** Sends the digest when it is on and due ([force]: when it is on, whatever the last time was); true when a notification went out. */
    suspend fun runIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!settings.enabled.first()) return@withContext false
        val now = System.currentTimeMillis()
        if (!force && !WeeklyDigest.isDue(now, settings.lastSent.first())) return@withContext false
        val summary = summary(now)
        // A quiet week stays quiet: it still counts as this week's digest.
        settings.setLastSent(now)
        Log.i(TAG, "Weekly digest: ${summary.newGames} new, ${summary.wishlistHits.size} wished, ${summary.downloads} downloaded")
        if (!summary.hasNews) return@withContext false
        notify(summary)
    }

    /** The last seven days as the notification will say them. */
    suspend fun summary(now: Long = System.currentTimeMillis()): DigestSummary {
        val newRows = fileDao.newestSince(WeeklyDigest.since(now), MAX_NEW_ROWS)
        val files = newRows.map { DigestFile(it.consoleId, it.fileName) }

        // The on-disk index is only filled while the app has run for a moment; read it when it is not.
        if (files.isNotEmpty() && libraryIndex.ownedKeys.value.isEmpty()) {
            withTimeoutOrNull(INDEX_WAIT_MS) { libraryIndex.refresh() }
        }
        val consoles = consoleRepository.getAllConsoles().first().map { it.id }
        val owned = WeeklyDigest.consolesWithGames(consoles, libraryIndex.ownedKeys.value)

        val wishes = runCatching {
            wishlist.statuses().filter { WishlistMatch.stillWanted(it.state) }.map { DigestWish(it.item.title, it.item.consoleId) }
        }.getOrDefault(emptyList())

        return WeeklyDigest.build(
            newFiles = files,
            ownedConsoles = owned,
            wishes = wishes,
            goals = goals(),
            downloads = runCatching { downloadLog.entries() }.getOrDefault(emptyList()),
            now = now
        )
    }

    /** Progress of the goal consoles, only when the collection is (or soon is) worked out: it is not worth waiting long for. */
    private suspend fun goals(): List<DigestGoal> {
        val wanted = goalsSettings.goals.first()
        if (wanted.isEmpty()) return emptyList()
        goalsService.start()
        val state = withTimeoutOrNull(GOALS_WAIT_MS) { goalsService.state.first { !it.loading } } ?: return emptyList()
        return state.rows.filter { it.consoleId in wanted && it.total > 0 }
            .map { DigestGoal(it.consoleId, it.name, it.owned, it.total) }
    }

    private fun notify(summary: DigestSummary): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val res = context.resources
        val lines = buildList {
            if (summary.newGames > 0) {
                val top = summary.topConsoles.joinToString(", ") { "${ConsoleFormatter.getConsoleShortName(it.consoleId)} ${it.count}" }
                add(res.getQuantityString(R.plurals.upg7_digest_new, summary.newGames, summary.newGames) + if (top.isEmpty()) "" else " ($top)")
            }
            if (summary.wishlistHits.isNotEmpty()) {
                val titles = summary.wishlistHits.take(WeeklyDigest.MAX_WISHES_SHOWN).joinToString(", ") +
                    if (summary.wishlistHits.size > WeeklyDigest.MAX_WISHES_SHOWN) " …" else ""
                add(res.getQuantityString(R.plurals.upg7_digest_wishes, summary.wishlistHits.size, summary.wishlistHits.size, titles))
            }
            if (summary.goals.isNotEmpty()) {
                add(context.getString(R.string.upg7_digest_goals, summary.goals.joinToString(" · ") { "${it.name} ${it.percent}%" }))
            }
            if (summary.completedGoals > 0) add(res.getQuantityString(R.plurals.upg7_digest_goals_done, summary.completedGoals, summary.completedGoals))
            if (summary.downloads > 0) {
                add(res.getQuantityString(R.plurals.upg7_digest_downloads, summary.downloads, summary.downloads, Formatter.formatShortFileSize(context, summary.downloadedBytes)))
            }
        }
        if (lines.isEmpty()) return false
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DogmatixApplication.SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(context.getString(R.string.upg7_digest_title))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        return true
    }

    private companion object {
        const val MAX_NEW_ROWS = 5_000
        const val INDEX_WAIT_MS = 60_000L
        const val GOALS_WAIT_MS = 20_000L
    }
}
