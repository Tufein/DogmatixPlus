package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cortinadev.dogmatix.data.service.CoverRepository
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoverEntryPoint {
    fun covers(): CoverRepository
}

/** The app's [CoverRepository], for composables outside a ViewModel (rows, tiles). */
@Composable
fun rememberCoverRepository(): CoverRepository {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, CoverEntryPoint::class.java).covers() }
}

/**
 * A game's cover, looked up by console and file name (RomM, libretro box art, cached metadata).
 * Until it arrives — or when there is none — a placeholder in the console's colour with its short
 * name. [showLabel] off hides that name (very small thumbnails).
 */
@Composable
fun GameCover(
    consoleId: String,
    fileName: String,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    showLabel: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop
) {
    val covers = rememberCoverRepository()
    // Remembered per game: a recycled list slot must not keep the previous game's cover.
    var url by remember(consoleId, fileName) { mutableStateOf(covers.cached(consoleId, fileName)) }
    LaunchedEffect(consoleId, fileName) {
        if (url == null) url = runCatching { covers.coverUrl(consoleId, fileName, title) }.getOrNull()
    }
    CoverImage(url, consoleId, modifier, shape, showLabel, contentScale)
}

/** A cover from a known URL (or null) on the console-coloured placeholder. */
@Composable
fun CoverImage(
    url: String?,
    consoleId: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    showLabel: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val dark = LocalDogmatixTokens.current.isDark
    val labelColor = if (dark) Color.White.copy(alpha = 0.85f) else lerp(consoleColor(consoleId), Color.Black, 0.5f)
    Box(modifier = modifier.clip(shape).coverPlaceholder(consoleId), contentAlignment = Alignment.Center) {
        if (showLabel) {
            val label = ConsoleFormatter.getConsoleShortName(consoleId)
            // Sized to the cover: big on the details hero, small on a list thumbnail, never broken mid-word.
            BasicText(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = labelColor,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                ),
                maxLines = if (' ' in label) 2 else 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 22.sp, stepSize = 3.sp),
                modifier = Modifier.padding(4.dp)
            )
        }
        if (!url.isNullOrBlank()) {
            val request = remember(url, reduce) {
                ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 180).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The console-coloured diagonal gradient behind a cover. */
@Composable
fun Modifier.coverPlaceholder(consoleId: String): Modifier {
    val base = consoleColor(consoleId)
    val dark = LocalDogmatixTokens.current.isDark
    val ground = MaterialTheme.colorScheme.surfaceContainerHigh
    val from = lerp(ground, base, if (dark) 0.55f else 0.65f)
    val to = lerp(ground, base, if (dark) 0.18f else 0.30f)
    return drawWithCache {
        val brush = Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(size.width, size.height))
        onDrawBehind { drawRect(brush) }
    }
}
