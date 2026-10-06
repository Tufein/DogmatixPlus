package com.cortinadev.dogmatix.data.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.ConsoleFamily
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.GameShareText
import com.cortinadev.dogmatix.util.Recap
import com.cortinadev.dogmatix.util.RecapPeriod
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.YearRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Sharing "Your year in games" (7.0): the recap as a picture card plus a one-line text. Drawn the
 * way [GameShare] draws a game card (android.graphics, no view, saved under cache `exports/` and
 * handed to the share sheet), in the same 4:5 size, tinted with the colour of the top console.
 */
object RecapShare {

    private val INK = Color.WHITE
    private val GROUND = 0xFF1E1B24.toInt()
    private val ACCENT = 0xFFFF8A1F.toInt()
    private const val MARGIN = 80f

    /**
     * Draws the card of [recap] and opens the share sheet. Never throws: on failure nothing is
     * shared and a toast says so. Call from a coroutine (draws off the main thread, opens the
     * sheet on it).
     */
    suspend fun share(context: Context, recap: YearRecap) {
        val app = context.applicationContext
        val result = runCatching {
            val locale = Locale.getDefault()
            val file = withContext(Dispatchers.IO) {
                val bitmap = drawCard(app, recap, locale)
                val out = File(File(app.cacheDir, "exports").apply { mkdirs() }, Recap.fileName(recap.period))
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                out
            }
            val title = app.getString(R.string.quick7_title)
            val text = shareText(app, recap)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                app.startActivity(Intent.createChooser(send, app.getString(R.string.quick7_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (result.isFailure) withContext(Dispatchers.Main) {
            runCatching { ToastUtil.showError(app, app.getString(R.string.quick7_share_failed)) }
        }
    }

    /** "My 2026 in games: 87 downloaded, 41 played" (the played part is left out when ES-DE is not set up). */
    private fun shareText(app: Context, recap: YearRecap): String {
        val head = when (val p = recap.period) {
            is RecapPeriod.Year -> app.getString(R.string.quick7_share_year, p.year)
            RecapPeriod.Last12Months -> app.getString(R.string.quick7_share_last12)
        }
        val parts = listOfNotNull(
            app.resources.getQuantityString(R.plurals.quick7_share_downloaded, recap.downloaded, recap.downloaded),
            recap.played?.let { app.resources.getQuantityString(R.plurals.quick7_share_played, it, it) }
        )
        return "$head ${parts.joinToString(", ")}"
    }

    private fun drawCard(app: Context, recap: YearRecap, locale: Locale): Bitmap {
        val w = GameShareText.CARD_WIDTH
        val h = GameShareText.CARD_HEIGHT
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = recap.topConsoles.firstOrNull()?.let { ConsoleFamily.of(it.consoleId).argb.toInt() } ?: ACCENT
        val numbers = NumberFormat.getIntegerInstance(locale)

        // Background: the top console's colour as a diagonal gradient, as behind a cover.
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            ColorUtils.blendARGB(GROUND, base, 0.5f), ColorUtils.blendARGB(GROUND, base, 0.14f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        // Heading: what it is, and which stretch of time.
        val kicker = text(40f, bold, Color.argb(205, 255, 255, 255)).apply { letterSpacing = 0.12f }
        canvas.drawText(app.getString(R.string.quick7_title).uppercase(locale), w / 2f, 130f, kicker)
        val periodText = when (val p = recap.period) {
            is RecapPeriod.Year -> p.year.toString()
            RecapPeriod.Last12Months -> app.getString(R.string.quick7_period_last12)
        }
        val periodPaint = text(170f, bold, INK)
        periodPaint.textSize = GameShareText.fitSize(170f, periodPaint.measureText(periodText), w - 2 * MARGIN, 80f)
        canvas.drawText(periodText, w / 2f, 290f, periodPaint)

        // Four numbers in a 2 x 2 grid.
        val gap = 30f
        val cellW = (w - 2 * MARGIN - gap) / 2f
        val cellH = 200f
        val streak = recap.longestStreak?.days
        val cells = listOf(
            numbers.format(recap.downloaded) to app.getString(R.string.quick7_stat_downloaded),
            (recap.played?.let { numbers.format(it) } ?: "—") to app.getString(R.string.quick7_stat_played),
            (if (recap.bytes > 0) formatBytes(recap.bytes) else "—") to app.getString(R.string.quick7_stat_size),
            (streak?.let { app.resources.getQuantityString(R.plurals.quick7_days, it, it) } ?: "—") to app.getString(R.string.quick7_stat_streak)
        )
        val gridTop = 370f
        cells.forEachIndexed { i, (value, label) ->
            val left = MARGIN + (i % 2) * (cellW + gap)
            val top = gridTop + (i / 2) * (cellH + gap)
            paint.color = Color.argb(46, 255, 255, 255)
            canvas.drawRoundRect(RectF(left, top, left + cellW, top + cellH), 36f, 36f, paint)
            val valuePaint = text(78f, bold, INK).apply { textAlign = Paint.Align.LEFT }
            valuePaint.textSize = GameShareText.fitSize(78f, valuePaint.measureText(value), cellW - 64f, 40f)
            canvas.drawText(value, left + 32f, top + 100f, valuePaint)
            val labelPaint = text(34f, Typeface.DEFAULT, Color.argb(215, 255, 255, 255)).apply { textAlign = Paint.Align.LEFT }
            labelPaint.textSize = GameShareText.fitSize(34f, labelPaint.measureText(label), cellW - 64f, 22f)
            canvas.drawText(label, left + 32f, top + 154f, labelPaint)
        }

        // Top consoles with a bar each.
        var y = gridTop + 2 * cellH + gap + 80f
        val consoles = recap.topConsoles.take(3)
        if (consoles.isNotEmpty()) {
            val head = text(32f, bold, Color.argb(205, 255, 255, 255)).apply { textAlign = Paint.Align.LEFT; letterSpacing = 0.1f }
            canvas.drawText(app.getString(R.string.quick7_section_consoles).uppercase(locale), MARGIN, y, head)
            y += 25f
            val max = consoles.first().total.coerceAtLeast(1)
            val barW = w - 2 * MARGIN
            consoles.forEach { c ->
                val name = text(40f, bold, INK).apply { textAlign = Paint.Align.LEFT }
                val count = text(34f, Typeface.DEFAULT, Color.argb(215, 255, 255, 255)).apply { textAlign = Paint.Align.RIGHT }
                val countText = app.getString(R.string.quick7_console_line, c.downloads, c.played)
                canvas.drawText(countText, w - MARGIN, y + 42f, count)
                val label = ConsoleFormatter.getConsoleDisplayName(c.consoleId)
                name.textSize = GameShareText.fitSize(40f, name.measureText(label), barW - count.measureText(countText) - 30f, 24f)
                canvas.drawText(label, MARGIN, y + 42f, name)
                paint.color = Color.argb(60, 255, 255, 255)
                canvas.drawRoundRect(RectF(MARGIN, y + 56f, MARGIN + barW, y + 68f), 6f, 6f, paint)
                paint.color = ColorUtils.blendARGB(ConsoleFamily.of(c.consoleId).argb.toInt(), INK, 0.35f)
                canvas.drawRoundRect(RectF(MARGIN, y + 56f, MARGIN + barW * (c.total.toFloat() / max), y + 68f), 6f, 6f, paint)
                y += 84f
            }
        }

        // One line each for the most played game and the busiest month, when there are any.
        val line = text(36f, Typeface.DEFAULT, Color.argb(225, 255, 255, 255))
        val lines = listOfNotNull(
            recap.mostPlayed?.let { app.getString(R.string.quick7_card_most_played, it.title) },
            recap.busiestMonth?.let { app.getString(R.string.quick7_card_busiest, Recap.monthName(it.month, recap.period == RecapPeriod.Last12Months, locale)) }
        )
        y += 38f
        lines.forEach { l ->
            line.textSize = GameShareText.fitSize(36f, line.apply { textSize = 36f }.measureText(l), w - 2 * MARGIN, 22f)
            canvas.drawText(l, w / 2f, y, line)
            y += 50f
        }

        // Footer.
        val footer = text(36f, bold, Color.argb(200, 255, 255, 255))
        canvas.drawText(app.getString(R.string.quick7_card_footer), w / 2f, h - 44f, footer)
        return bitmap
    }

    private fun text(size: Float, face: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = face
        this.color = color
        textAlign = Paint.Align.CENTER
    }
}
