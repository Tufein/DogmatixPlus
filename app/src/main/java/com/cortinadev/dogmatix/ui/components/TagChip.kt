package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.StatusInfo
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.util.TagClassifier

/** Fill and text colour of one [TagChip]. */
@Immutable
data class TagColors(val background: Color, val content: Color)

/**
 * A small label: console, region, language, revision or extension. [colors] overrides the plain
 * look (the console chip in its console's colour, tags in the subtle colour of their kind).
 */
@Composable
fun TagChip(text: String, emphasized: Boolean = false, modifier: Modifier = Modifier, colors: TagColors? = null) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (emphasized) FontWeight.Bold else null,
        color = colors?.content ?: if (emphasized) scheme.onSurface else scheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                colors?.background ?: if (emphasized) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/**
 * Console chip first (emphasized; in the console's colour when [consoleId] is given), then the
 * file's tags, each kind in its own subtle colour, and the extension last in neutral grey. With
 * `maxLines = 1` the chips that do not fit are summed up in a final "+N" chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(
    console: String,
    tags: List<String>,
    extension: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    /** The console's id (5.0): its chip then takes the console family's colour. */
    consoleId: String? = null
) {
    val palette = tagPalette()
    val consoleTint = consoleId?.let { id ->
        val (bg, fg) = pillColors(PillTone.Tint(consoleColor(id)))
        TagColors(bg, fg)
    }
    val ext = extension.trimStart('.').uppercase()
    val chips: @Composable () -> Unit = {
        TagChip(console, emphasized = true, colors = consoleTint)
        tags.forEach { TagChip(it, colors = palette.of(it)) }
        if (ext.isNotBlank()) TagChip(ext)
    }
    if (maxLines == 1) {
        SingleLineTags(modifier, chips)
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = maxLines
        ) {
            chips()
        }
    }
}

/** The subtle colour of each kind of tag, made once per theme. */
@Immutable
private class TagPalette(
    val region: TagColors,
    val language: TagColors,
    val revision: TagColors,
    val release: TagColors,
    val plain: TagColors
) {
    fun of(tag: String): TagColors = when (TagClassifier.kindOf(tag)) {
        TagClassifier.Kind.REGION -> region
        TagClassifier.Kind.LANGUAGE -> language
        TagClassifier.Kind.REVISION -> revision
        TagClassifier.Kind.RELEASE -> release
        TagClassifier.Kind.VIDEO, TagClassifier.Kind.OTHER -> plain
    }
}

@Composable
private fun tagPalette(): TagPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDogmatixTokens.current.isDark
    val ground = scheme.surfaceContainerHigh
    val muted = scheme.onSurfaceVariant
    val plainText = scheme.secondary
    return remember(ground, muted, plainText, dark) {
        fun subtle(c: Color) = TagColors(
            background = lerp(ground, c, if (dark) 0.14f else 0.12f),
            content = lerp(muted, if (dark) lerp(c, Color.White, 0.30f) else lerp(c, Color.Black, 0.50f), 0.75f)
        )
        TagPalette(
            region = subtle(StatusInfo),
            language = subtle(Color(0xFF2ED6B8)),
            revision = subtle(Color(0xFFA57BFF)),
            release = subtle(Color(0xFFFFB300)),
            plain = TagColors(ground, plainText)
        )
    }
}

/**
 * One line of chips: as many as fit, then a "+N" chip for the rest. The "+N" chip is drawn rather
 * than composed (its number is only known while measuring), so a list row costs no extra
 * composition; the count is handed from the measure pass to the draw pass through state.
 */
@Composable
private fun SingleLineTags(modifier: Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurface)
    val moreFill = scheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val hidden = remember { mutableIntStateOf(0) }
    val moreX = remember { mutableIntStateOf(0) }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            drawContent()
            val n = hidden.intValue
            if (n > 0) {
                val text = measurer.measure("+$n", style, maxLines = 1)
                val padH = 6.dp.toPx()
                val padV = 3.dp.toPx()
                val w = text.size.width + padH * 2
                val h = text.size.height + padV * 2
                val top = ((size.height - h) / 2f).coerceAtLeast(0f)
                val x = moreX.intValue.toFloat()
                drawRoundRect(moreFill, topLeft = Offset(x, top), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()))
                drawText(text, topLeft = Offset(x + padH, top + padV))
            }
        }
    ) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val bounded = constraints.hasBoundedWidth
        val maxW = constraints.maxWidth
        val count = measurables.size
        // Room kept for the "+N" chip, sized for the largest N it could show.
        val moreW = if (bounded && count > 1) {
            measurer.measure("+${count - 1}", style, maxLines = 1).size.width + 12.dp.roundToPx()
        } else 0
        val loose = Constraints(maxWidth = if (bounded) maxW else Constraints.Infinity)
        val placeables = measurables.mapIndexed { i, m ->
            // The first (console) chip must leave room for "+N" so it is never pushed out.
            if (i == 0 && bounded && count > 1) m.measure(Constraints(maxWidth = (maxW - moreW - gap).coerceAtLeast(0)))
            else m.measure(loose)
        }
        val total = placeables.sumOf { it.width } + gap * (count - 1).coerceAtLeast(0)
        var shown = count
        var moreAt = 0
        if (bounded && total > maxW && count > 1) {
            var x = placeables[0].width
            shown = 1
            while (shown < count && x + gap + placeables[shown].width + gap + moreW <= maxW) {
                x += gap + placeables[shown].width
                shown++
            }
            moreAt = x + gap
        }
        hidden.intValue = count - shown
        moreX.intValue = moreAt
        val used = if (shown < count) moreAt + moreW else total
        val width = used.coerceIn(constraints.minWidth, if (bounded) maxW else Int.MAX_VALUE)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                if (i < shown) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
        }
    }
}
