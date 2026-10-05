package com.cortinadev.dogmatix.ui.screens.cloud.sections

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RetroAchievementsService
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.util.RaErrorKind
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.NumberFormat

/*
 * Shared pieces of the RetroAchievements sections (AchievementsSection, RaUserSummaryCard): access
 * to the service from a composable, badge and avatar images, number / time / error formatting.
 */

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RaSectionEntryPoint {
    fun retroAchievements(): RetroAchievementsService
}

/** The app's [RetroAchievementsService], for the RA sections that live outside a ViewModel. */
@Composable
internal fun rememberRaService(): RetroAchievementsService {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, RaSectionEntryPoint::class.java).retroAchievements() }
}

/** Saturation 0: how a badge that is not earned yet is drawn. */
private val Greyscale: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * An achievement badge: in colour when earned, grey and dimmed when not (one image per badge, so
 * it is cached once and simply turns colourful when earned). A hardcore unlock gets an accent ring.
 */
@Composable
internal fun RaBadge(
    url: String,
    earned: Boolean,
    hardcore: Boolean,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(size * 0.2f)
    val request = remember(url, reduce) {
        ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 180).build()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(scheme.surfaceContainerHighest)
            .then(if (hardcore && earned) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            colorFilter = if (earned) null else Greyscale,
            alpha = if (earned) 1f else 0.55f,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** The user's RA picture in a circle, over a person icon while it loads (or when there is none). */
@Composable
internal fun RaAvatar(url: String?, size: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_account),
            contentDescription = null,
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.55f)
        )
        if (!url.isNullOrBlank()) {
            val request = remember(url, reduce) {
                ImageRequest.Builder(context).data(url).crossfade(if (reduce) 0 else 180).build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** A count with the locale's grouping ("10,483", "10.483"). */
internal fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** A 0..1 share as the locale writes a percentage ("52%", "52 %"), with one decimal under 10 %. */
internal fun formatPercent(fraction: Float): String {
    val f = NumberFormat.getPercentInstance()
    f.maximumFractionDigits = if (fraction in 0.0001f..0.0999f) 1 else 0
    return f.format(fraction.toDouble())
}

/** "3 minutes ago", "Mar 17, 2023" — or "just now" under a minute. */
@Composable
internal fun raRelativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    return if (now - millis in 0 until DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.ra5_just_now)
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** The sentence for an RA failure. */
@Composable
internal fun raErrorText(kind: RaErrorKind): String = stringResource(
    when (kind) {
        RaErrorKind.NO_ACCOUNT -> R.string.ra5_error_no_account
        RaErrorKind.BAD_KEY -> R.string.ra5_error_bad_key
        RaErrorKind.NOT_FOUND -> R.string.ra5_error_not_found
        RaErrorKind.RATE_LIMITED -> R.string.ra5_error_rate_limited
        RaErrorKind.OFFLINE -> R.string.ra5_error_offline
        RaErrorKind.SERVER -> R.string.ra5_error_server
        RaErrorKind.BAD_RESPONSE -> R.string.ra5_error_bad_response
    }
)
