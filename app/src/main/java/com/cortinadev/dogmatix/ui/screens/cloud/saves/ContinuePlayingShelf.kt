package com.cortinadev.dogmatix.ui.screens.cloud.saves

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.service.ContinueItem
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.SectionTitle
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.focusScale
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.consoleColor

/**
 * "Continue playing" (5.0): the games last saved on any device (RomM), or last played in ES-DE,
 * as a row of cover cards. Renders nothing when switched off or empty.
 *
 * Call from HomeScreen above the result list, only while the search is empty and no filter is
 * active: `ContinuePlayingShelf(onOpenGame = viewModel::openDetails)` (HomeViewModel.openDetails
 * takes the same [DownloadableFileWithTags]).
 */
@Composable
fun ContinuePlayingShelf(
    onOpenGame: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContinuePlayingViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsState()
    LaunchedEffect(enabled) { if (enabled) viewModel.onShown() }
    val shelf by viewModel.items.collectAsState()
    if (!enabled || shelf.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(
            stringResource(R.string.csave_continue_title),
            icon = R.drawable.ic_play_circle,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // Room around the cards for the focus halo and the 1.03 scale.
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(shelf, key = { "${it.row.file.consoleId}|${it.row.file.fileName}" }) { item ->
                ContinueCard(item, onClick = { onOpenGame(item.row) })
            }
        }
    }
}

/** One game: cover, title and "2 hr. ago · Thor". A opens the game's details. */
@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val file = item.row.file
    val title = remember(file.name) { stripExtension(file.name) }
    val shape = RoundedCornerShape(12.dp)
    val line = listOfNotNull(relativeTime(item.at), item.via?.takeIf { it.isNotBlank() }).joinToString(" · ")
    Row(
        modifier = Modifier
            .width(236.dp)
            .focusScale(source, 1.03f)
            .focusRing(source, cornerRadius = 12.dp, fill = false)
            .clip(shape)
            .background(scheme.surfaceContainer)
            .border(1.dp, tokens.hairline, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GameCover(
            consoleId = file.consoleId,
            fileName = file.fileName,
            title = title,
            modifier = Modifier.size(width = 52.dp, height = 68.dp),
            shape = RoundedCornerShape(8.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The console's colour, as on the cover placeholder.
                Box(Modifier.size(6.dp).clip(CircleShape).background(consoleColor(file.consoleId)))
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
