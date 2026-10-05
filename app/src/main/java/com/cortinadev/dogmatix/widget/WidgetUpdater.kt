package com.cortinadev.dogmatix.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.NewGames
import com.cortinadev.dogmatix.util.QueueGlance
import com.cortinadev.dogmatix.util.WidgetLayout
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
import kotlinx.coroutines.flow.first
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
 * seconds), after every scan and when the theme or accent changes. Does nothing while no widget
 * is placed. The widget wears the app's own theme (light, dark or true black; "system" follows
 * the device) and accent, resolved here because RemoteViews cannot take them from the app.
 */
@OptIn(FlowPreview::class)
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    downloadService: DownloadService,
    rescanStateHolder: RescanStateHolder,
    private val fileDao: DownloadableFileDao,
    private val settings: SettingsDataStore,
    private val continueWidget: ContinueWidgetUpdater
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Downloads(val active: Int, val percent: Int)

    init {
        scope.launch {
            combine(
                downloadService.downloads.map { list ->
                    val glance = QueueGlance.of(list)
                    Downloads(glance.active, glance.percent)
                }.distinctUntilChanged().sample(3_000),
                rescanStateHolder.lastRescanTime,
                settings.themeMode.distinctUntilChanged(),
                settings.accentColor.distinctUntilChanged()
            ) { downloads, _, _, _ -> downloads }
                .collect { push(it) }
        }
    }

    /** Called by the widget itself (placed, resized, after a reboot). */
    fun refresh() {
        continueWidget.refresh()
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
            newTitles = runCatching { fileDao.newestSince(since, WidgetLayout.MAX_TITLES).map { it.name } }.getOrDefault(emptyList())
        )
        val look = look()
        ids.forEach { id ->
            // Taller widgets list more of the new games (a resize triggers another update).
            val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            manager.updateAppWidget(id, views(state, look, WidgetLayout.titleLines(height, state.newTitles.size)))
        }
    }

    private suspend fun look(): WidgetLook = WidgetLook.resolve(context, settings)

    private fun views(state: WidgetState, look: WidgetLook, titleLines: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_dogmatix).apply {
            val res = context.resources
            setInt(R.id.widget_root, "setBackgroundResource", look.background)
            setTextColor(R.id.widget_title, look.text)
            setTextColor(R.id.widget_plus, look.accentText)

            // Downloads: count and percentage, with a progress line while something runs.
            if (state.active == 0) {
                setTextViewText(R.id.widget_downloads, context.getString(R.string.widget_no_downloads))
                setTextColor(R.id.widget_downloads, look.muted)
                setViewVisibility(R.id.widget_percent, View.GONE)
                setViewVisibility(R.id.widget_progress, View.GONE)
            } else {
                setTextViewText(R.id.widget_downloads, res.getQuantityString(R.plurals.widget_downloads_count, state.active, state.active))
                setTextColor(R.id.widget_downloads, look.text)
                setTextViewText(R.id.widget_percent, context.getString(R.string.status_downloading, state.percent))
                setTextColor(R.id.widget_percent, look.accentText)
                setViewVisibility(R.id.widget_percent, View.VISIBLE)
                setImageViewBitmap(R.id.widget_progress, progressLine(state.percent, look))
                setContentDescription(R.id.widget_progress, context.getString(R.string.widget_progress_description, state.percent))
                setViewVisibility(R.id.widget_progress, View.VISIBLE)
            }

            // New games: the marker and the count, then the newest titles that fit.
            if (state.newGames == 0) {
                setViewVisibility(R.id.widget_new_chip, View.GONE)
                setTextViewText(R.id.widget_new, context.getString(R.string.widget_no_new))
                setTextColor(R.id.widget_new, look.muted)
            } else {
                setViewVisibility(R.id.widget_new_chip, View.VISIBLE)
                setTextColor(R.id.widget_new_chip, look.accentText)
                setTextViewText(R.id.widget_new, res.getQuantityString(R.plurals.widget_new_games, state.newGames, state.newGames))
                setTextColor(R.id.widget_new, look.text)
            }
            for (i in 0 until WidgetLayout.MAX_TITLES) {
                val show = i < titleLines && i < state.newTitles.size
                setViewVisibility(ROWS[i], if (show) View.VISIBLE else View.GONE)
                if (!show) continue
                setTextViewText(GAMES[i], state.newTitles[i])
                setTextColor(GAMES[i], look.muted)
                setInt(DOTS[i], "setColorFilter", look.accent)
            }

            setOnClickPendingIntent(R.id.widget_root, open(null, 10))
            setOnClickPendingIntent(R.id.widget_downloads_block, open(null, 11, route = "downloads"))
            setOnClickPendingIntent(R.id.widget_new_block, open("dogmatix://library?new=1", 12))
        }

    /**
     * The progress line as a picture: a rounded track with the accent filling it. A bar made by
     * RemoteViews could only take the accent on Android 12 and later; a bitmap works everywhere.
     */
    private fun progressLine(percent: Int, look: WidgetLook): Bitmap {
        val w = 600
        val h = 16
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = h / 2f
        paint.color = look.track
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        if (percent > 0) {
            val fill = (w * percent.coerceIn(0, 100) / 100f).coerceAtLeast(h.toFloat())
            paint.color = look.accent
            canvas.drawRoundRect(RectF(0f, 0f, fill, h.toFloat()), radius, radius, paint)
        }
        return bitmap
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

    private companion object {
        val ROWS = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
        val DOTS = intArrayOf(R.id.widget_dot1, R.id.widget_dot2, R.id.widget_dot3)
        val GAMES = intArrayOf(R.id.widget_game1, R.id.widget_game2, R.id.widget_game3)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetUpdater(): WidgetUpdater
}

/** The home-screen widget; its content comes from [WidgetUpdater]. */
class DogmatixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    /** A resize changes how many titles fit. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refresh(context)
    }

    private fun refresh(context: Context) {
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater().refresh()
    }
}
