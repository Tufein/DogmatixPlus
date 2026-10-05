package com.cortinadev.dogmatix.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.tabular

/*
 * Small Canvas charts for the 5.0 screens. Values animate from their previous value in the draw
 * phase (no recomposition per frame) and snap when motion is reduced.
 */

/** An animated 0..1 value that follows [target], read only while drawing. */
@Composable
private fun animatedFraction(target: Float, durationMillis: Int = Motion.SLOW * 2): () -> Float {
    val reduce = LocalReduceMotion.current
    val value = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) value.snapTo(target) else value.animateTo(target, Motion.spec(false, durationMillis))
    }
    return { value.value }
}

/** A ring that fills clockwise from the top; [center] goes in the middle (a percentage, an icon). */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    stroke: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    center: (@Composable () -> Unit)? = null
) {
    val shown = animatedFraction(fraction.coerceIn(0f, 1f))
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            val sweep = 360f * shown()
            if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        center?.invoke()
    }
}

/** A thin rounded bar filled to [fraction]. */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val shown = animatedFraction(fraction.coerceIn(0f, 1f))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val w = size.width * shown()
                if (w > 0f) drawRoundRect(color, size = Size(maxOf(w, size.height), size.height), cornerRadius = r)
            }
    )
}

/** One part of a [SegmentedBar]. */
@Immutable
data class BarSegment(val fraction: Float, val color: Color)

/** Several parts side by side in one rounded track (disk use: games, other, free, queue). */
@Composable
fun SegmentedBar(
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val grow = animatedFraction(1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val gap = 2.dp.toPx()
                var x = 0f
                val total = size.width * grow()
                segments.forEach { s ->
                    val w = (size.width * s.fraction.coerceIn(0f, 1f)).coerceAtMost(total - x)
                    if (w > gap) {
                        drawRoundRect(s.color, topLeft = Offset(x, 0f), size = Size(w - gap, size.height), cornerRadius = r)
                    }
                    x += maxOf(w, 0f)
                }
            }
    )
}

/**
 * Columns with rounded tops and their labels underneath: months, weeks. [highlight] draws one
 * column (usually the latest) in the full colour, the others softer.
 */
@Composable
fun ColumnChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 96.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    highlight: Int = values.lastIndex,
    valueLabels: List<String>? = null
) {
    val grow = animatedFraction(1f)
    val max = (values.maxOrNull() ?: 0f).takeIf { it > 0f } ?: 1f
    val soft = color.copy(alpha = 0.45f)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier = modifier) {
        if (valueLabels != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                valueLabels.forEachIndexed { i, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = if (i == highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight).padding(vertical = 4.dp)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(barW / 3)
            values.forEachIndexed { i, v ->
                val left = slot * i + (slot - barW) / 2
                drawRoundRect(track, topLeft = Offset(left, 0f), size = Size(barW, size.height), cornerRadius = r)
                val h = size.height * (v / max).coerceIn(0f, 1f) * grow()
                if (h > 0f) drawRoundRect(
                    if (i == highlight) color else soft,
                    topLeft = Offset(left, size.height - maxOf(h, barW / 2)),
                    size = Size(barW, maxOf(h, barW / 2)),
                    cornerRadius = r
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** A small line chart without axes, for a trend next to a number. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp
) {
    val grow = animatedFraction(1f)
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().takeIf { it > 0f } ?: 1f
        val min = minOf(values.min(), 0f)
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        val line = Path()
        val area = Path()
        values.forEachIndexed { i, v ->
            val x = step * i
            val y = size.height - size.height * ((v - min) / span) * grow()
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, size.height); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, color.copy(alpha = 0.14f))
        drawPath(line, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}

/** A label, a bar and a value on one line (top consoles, monthly totals). */
@Composable
fun LabeledBar(
    label: String,
    fraction: Float,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    labelWidth: Dp = 110.dp,
    valueWidth: Dp = 96.dp
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(labelWidth))
        MeterBar(fraction, modifier = Modifier.weight(1f), color = color)
        Text(value, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(valueWidth))
    }
}
