package com.cortinadev.dogmatix.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.NewGames
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget shows: downloads in progress and the games recent rescans found. */
data class WidgetState(
    val active: Int,
    val percent: Int,
    val newGames: Int,
    val newTitles: List<String>
)

/**
 * Keeps the home-screen widget current: on every change of the downloads (at most every few
 * seconds) and after every scan. Does nothing while no widget is placed.
 */
@OptIn(FlowPreview::class)
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    downloadService: DownloadService,
    rescanStateHolder: RescanStateHolder,
    private val fileDao: DownloadableFileDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Downloads(val active: Int, val percent: Int)

    init {
        scope.launch {
            combine(
                downloadService.downloads.map { list ->
                    val running = list.filter { !it.isFinished && it.status != DownloadStatus.PAUSED }
                    val total = running.sumOf { it.fileSize }
                    val done = running.sumOf { it.downloadedBytes }
                    Downloads(running.size, if (total > 0) (done * 100 / total).toInt() else (running.map { it.progress }.average().takeIf { !it.isNaN() } ?: 0.0).times(100).toInt())
                }.distinctUntilChanged().sample(3_000),
                rescanStateHolder.lastRescanTime
            ) { downloads, _ -> downloads }
                .collect { push(it) }
        }
    }

    /** Called by the widget itself (placed, resized, after a reboot). */
    fun refresh() {
        scope.launch { push(null) }
    }

    @Volatile private var lastDownloads = Downloads(0, 0)

    private suspend fun push(downloads: Downloads?) {
        downloads?.let { lastDownloads = it }
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, DogmatixWidget::class.java))
        if (ids.isEmpty()) return
        val since = NewGames.since(System.currentTimeMillis())
        val state = WidgetState(
            active = lastDownloads.active, percent = lastDownloads.percent,
            newGames = runCatching { fileDao.countNewSince(since) }.getOrDefault(0),
            newTitles = runCatching { fileDao.newestSince(since, 3).map { it.name } }.getOrDefault(emptyList())
        )
        manager.updateAppWidget(ids, views(state))
    }

    private fun views(state: WidgetState): RemoteViews = RemoteViews(context.packageName, R.layout.widget_dogmatix).apply {
        val res = context.resources
        setTextViewText(
            R.id.widget_downloads,
            if (state.active == 0) context.getString(R.string.widget_no_downloads)
            else res.getQuantityString(R.plurals.widget_downloads, state.active, state.active, state.percent)
        )
        setViewVisibility(R.id.widget_progress, if (state.active == 0) View.GONE else View.VISIBLE)
        setProgressBar(R.id.widget_progress, 100, state.percent.coerceIn(0, 100), false)
        setTextViewText(
            R.id.widget_new,
            if (state.newGames == 0) context.getString(R.string.widget_no_new)
            else res.getQuantityString(R.plurals.widget_new_games, state.newGames, state.newGames)
        )
        setTextViewText(R.id.widget_new_titles, state.newTitles.joinToString("\n"))
        setViewVisibility(R.id.widget_new_titles, if (state.newTitles.isEmpty()) View.GONE else View.VISIBLE)
        setOnClickPendingIntent(R.id.widget_root, open(null, 10))
        setOnClickPendingIntent(R.id.widget_downloads, open(null, 11, route = "downloads"))
        setOnClickPendingIntent(R.id.widget_new, open("dogmatix://library?new=1", 12))
        setOnClickPendingIntent(R.id.widget_new_titles, open("dogmatix://library?new=1", 12))
    }

    private fun open(link: String?, requestCode: Int, route: String? = null): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .apply {
                if (link != null) { action = Intent.ACTION_VIEW; data = Uri.parse(link) }
                if (route != null) putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, route)
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetUpdater(): WidgetUpdater
}

/** The home-screen widget; its content comes from [WidgetUpdater]. */
class DogmatixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater().refresh()
    }
}
