package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val url by produceState(initialValue = covers.cached(consoleId, fileName), consoleId, fileName) {
        if (value == null) value = covers.coverUrl(consoleId, fileName, title)
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
    Box(modifier = modifier.clip(shape).coverPlaceholder(consoleId), contentAlignment = Alignment.Center) {
        if (showLabel) {
            Text(
                ConsoleFormatter.getConsoleShortName(consoleId),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
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
