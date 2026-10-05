package com.cortinadev.dogmatix.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.service.ContinueItem
import com.cortinadev.dogmatix.data.service.ContinuePlayingService
import com.cortinadev.dogmatix.data.service.CoverRepository
import com.cortinadev.dogmatix.util.ConsoleFamily
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.ContinueWidgetLayout
import com.cortinadev.dogmatix.util.GameShareText
import com.cortinadev.dogmatix.util.GameTitleCleaner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One game as the widget draws it. */
private class Row(val item: ContinueItem, val title: String, val subtitle: String, val link: String?)

/**
 * Keeps the "Continue playing" widget current: when the shelf changes (a save sync, ES-DE plays, a
 * refresh on Home), when the theme or accent changes and when the widget is placed or resized.
 * No polling and no periodic job: the widget's own `updatePeriodMillis` is 0. Does nothing while
 * no such widget is placed.
 */
@OptIn(FlowPreview::class)
@Singleton
class ContinueWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shelf: ContinuePlayingService,
    private val covers: CoverRepository,
    private val settings: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(
                shelf.items.map { list -> list.take(ContinueWidgetLayout.MAX_ROWS).map { Triple(it.row.file.consoleId, it.row.file.fileName, it.at) } }.distinctUntilChanged(),
                settings.themeMode.distinctUntilChanged(),
                settings.accentColor.distinctUntilChanged()
            ) { _, _, _ -> Unit }
                .debounce(800)
                .collect { push() }
        }
    }

    /** Called by the widget (placed, resized, after a reboot): shows what is stored at once and asks the shelf to catch up. */
    fun refresh() {
        scope.launch {
            if (ids().isNotEmpty()) shelf.onShown()
            push()
        }
    }

    private fun ids(): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))

    private suspend fun push() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ids()
        if (ids.isEmpty()) return
        val look = WidgetLook.resolve(context, settings)
        val items = shelf.items.value.take(ContinueWidgetLayout.MAX_ROWS)
        val thumbs = coroutineScope { items.map { async { thumbnail(it) } }.awaitAll() }
        val now = System.currentTimeMillis()
        val rows = items.map { item ->
            val file = item.row.file
            val title = GameTitleCleaner.clean(file.name).ifEmpty { file.name }
            val ago = item.at.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString() }
            Row(item, title, ContinueWidgetLayout.subtitle(ago, item.via), GameShareText.deepLink(file.consoleId, title))
        }
        ids.forEach { id ->
            val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            manager.updateAppWidget(id, views(rows, thumbs, look, ContinueWidgetLayout.rows(height, rows.size)))
        }
    }

    private fun views(rows: List<Row>, thumbs: List<Bitmap>, look: WidgetLook, shown: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_continue).apply {
            setInt(R.id.cw_root, "setBackgroundResource", look.background)
            setTextColor(R.id.cw_title, look.text)
            setTextColor(R.id.cw_plus, look.accentText)
            setTextColor(R.id.cw_empty, look.muted)
            setViewVisibility(R.id.cw_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            for (i in 0 until ContinueWidgetLayout.MAX_ROWS) {
                val show = i < shown && i < rows.size
                setViewVisibility(ROWS[i], if (show) View.VISIBLE else View.GONE)
                if (!show) continue
                val row = rows[i]
                setImageViewBitmap(COVERS[i], thumbs[i])
                setTextViewText(TITLES[i], row.title)
                setTextColor(TITLES[i], look.text)
                setTextViewText(SUBS[i], row.subtitle)
                setTextColor(SUBS[i], look.muted)
                setOnClickPendingIntent(ROWS[i], open(row.link, 20 + i))
            }
            setOnClickPendingIntent(R.id.cw_root, open(null, 19))
        }

    /** A rounded thumbnail: the cover when it loads in time, else the console-coloured placeholder with the console's short name. */
    private suspend fun thumbnail(item: ContinueItem): Bitmap {
        val file = item.row.file
        val w = ContinueWidgetLayout.thumbPx(context.resources.displayMetrics.density)
        val h = w * 4 / 3
        val cover = withTimeoutOrNull(4_000) {
            runCatching {
                val url = covers.cached(file.consoleId, file.fileName) ?: covers.coverUrl(file.consoleId, file.fileName, file.name) ?: return@runCatching null
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(w * 2, h * 2).build()
                (Coil.imageLoader(context).execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }.getOrNull()
        }
        return rounded(file.consoleId, cover, w, h)
    }

    private fun rounded(consoleId: String, cover: Bitmap?, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val radius = w * 0.18f
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        if (cover != null) {
            val src = GameShareText.centerCrop(cover.width, cover.height, GameShareText.Box(0f, 0f, w.toFloat(), h.toFloat()))
            canvas.drawBitmap(cover, Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()), dst, paint)
        } else {
            val base = ConsoleFamily.of(consoleId).argb.toInt()
            val ground = 0xFF1E1B24.toInt()
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), ColorUtils.blendARGB(ground, base, 0.7f), ColorUtils.blendARGB(ground, base, 0.3f), Shader.TileMode.CLAMP)
            canvas.drawRect(dst, paint)
            paint.shader = null
            paint.color = Color.argb(235, 255, 255, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val label = ConsoleFormatter.getConsoleShortName(consoleId).take(6)
            paint.textSize = w * 0.3f
            val fit = GameShareText.fitSize(paint.textSize, paint.measureText(label), w * 0.84f, w * 0.14f)
            paint.textSize = fit
            canvas.drawText(label, w / 2f, h / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return out
    }

    /** The game's library link when there is one (the library, filtered to the game), else just the app. */
    private fun open(link: String?, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .apply { if (link != null) { action = Intent.ACTION_VIEW; data = Uri.parse(link) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private companion object {
        val ROWS = intArrayOf(R.id.cw_row1, R.id.cw_row2, R.id.cw_row3, R.id.cw_row4)
        val COVERS = intArrayOf(R.id.cw_cover1, R.id.cw_cover2, R.id.cw_cover3, R.id.cw_cover4)
        val TITLES = intArrayOf(R.id.cw_game1, R.id.cw_game2, R.id.cw_game3, R.id.cw_game4)
        val SUBS = intArrayOf(R.id.cw_sub1, R.id.cw_sub2, R.id.cw_sub3, R.id.cw_sub4)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ContinueWidgetEntryPoint {
    fun continueWidgetUpdater(): ContinueWidgetUpdater
}

/** The "Continue playing" home-screen widget; its content comes from [ContinueWidgetUpdater]. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    /** A resize changes how many games fit. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refresh(context)
    }

    private fun refresh(context: Context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ContinueWidgetEntryPoint::class.java).continueWidgetUpdater().refresh()
    }
}
