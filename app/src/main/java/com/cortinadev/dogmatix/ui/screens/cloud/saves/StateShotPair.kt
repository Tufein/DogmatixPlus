package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.StateShots
import com.cortinadev.dogmatix.ui.components.coverPlaceholder
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.util.SaveConflict
import com.cortinadev.dogmatix.util.SaveKind

/**
 * The two sides of a save-state conflict as pictures: the screenshot RetroArch keeps next to the
 * device's state and the one RomM keeps with the server's, side by side, so the user picks a state by
 * what it shows. Renders nothing for in-game saves or when neither side has a screenshot.
 *
 * Call in SaveSyncScreen's `ConflictRow` (under its text lines): `StateShotPair(conflict)`.
 */
@Composable
fun StateShotPair(conflict: SaveConflict, modifier: Modifier = Modifier, shotWidth: Dp = 128.dp) {
    if (conflict.local.kind != SaveKind.STATE) return
    val service = rememberCloudSavesService()
    val shots by produceState<StateShots?>(initialValue = null, conflict) {
        value = runCatching { service.stateShots(conflict) }.getOrNull()
    }
    val s = shots?.takeIf { it.any } ?: return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StateShot(
            model = s.deviceUri,
            // The PNG is rewritten in place with each new state: its date keeps the cache honest.
            cacheKey = s.deviceUri?.let { "$it@${s.deviceModified ?: 0L}" },
            label = listOfNotNull(stringResource(R.string.csave_side_device), relativeTime(s.deviceModified)).joinToString(" · "),
            width = shotWidth
        )
        StateShot(
            model = s.serverUrl,
            cacheKey = s.serverUrl,
            label = listOfNotNull(stringResource(R.string.csave_side_server), relativeTime(s.serverModified)).joinToString(" · "),
            width = shotWidth
        )
    }
}

@Composable
private fun StateShot(model: String?, cacheKey: String?, label: String, width: Dp) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(width = width, height = width * 0.75f)
                .clip(shape)
                .coverPlaceholder("")
                .border(1.dp, LocalDogmatixTokens.current.hairline, shape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            if (model != null) {
                val request = remember(model, cacheKey, reduce) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .apply { if (cacheKey != null) memoryCacheKey(cacheKey) }
                        // Device pictures change in place: never keep them on disk.
                        .diskCachePolicy(if (model.startsWith("content:")) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .crossfade(if (reduce) 0 else 180)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.csave_shot_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = width, height = width * 0.75f)
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
