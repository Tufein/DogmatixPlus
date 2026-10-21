package com.tufein.valkyr.ui.screens.next

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.util.LibraryRestrictions
import com.tufein.valkyr.data.service.ProfileService
import com.tufein.valkyr.data.service.UpNextService
import com.tufein.valkyr.ui.components.GameCover
import com.tufein.valkyr.ui.components.SectionTitle
import com.tufein.valkyr.ui.components.focusRing
import com.tufein.valkyr.ui.components.focusScale
import com.tufein.valkyr.ui.components.rememberFocusSource
import com.tufein.valkyr.ui.theme.LocalDogmatixTokens
import com.tufein.valkyr.ui.theme.accentInk
import com.tufein.valkyr.ui.theme.consoleColor
import com.tufein.valkyr.util.UpNext
import com.tufein.valkyr.util.UpNextEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Backs [UpNextShelf]: the first games still to play on the active profile's list, minus the consoles it hides. */
@HiltViewModel
class UpNextShelfViewModel @Inject constructor(service: UpNextService, profiles: ProfileService, private val fileDao: DownloadableFileDao) : ViewModel() {
    val items: StateFlow<List<UpNextEntry>> = combine(service.entries, profiles.restrictions) { list, restrictions ->
        UpNext.open(list.orEmpty().filter { it.consoleId !in restrictions.hiddenConsoles }, SHELF_SIZE) to restrictions
    }.map { (open, restrictions) -> withoutHiddenTags(open, restrictions) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A game with a tag the profile hides stays on the list but is not shown here (the Up next screen does the same). */
    private suspend fun withoutHiddenTags(open: List<UpNextEntry>, restrictions: LibraryRestrictions): List<UpNextEntry> {
        if (restrictions.hiddenTags.isEmpty() || open.isEmpty()) return open
        return try {
            val rows = open.map { it.fileName }.distinct().flatMap { fileDao.filesByFileNames(listOf(it)) }
            val tags = rows.map { it.id }.chunked(400).flatMap { fileDao.tagsOfFiles(it) }.groupBy({ it.fileId }, { it.tag })
            val hidden = rows.filter { r -> tags[r.id].orEmpty().any { t -> restrictions.hiddenTags.any { it.equals(t, ignoreCase = true) } } }
                .mapTo(HashSet()) { UpNext.key(it.consoleId, it.fileName) }
            open.filter { it.key !in hidden }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
        catch (_: Exception) { open }
    }

    private companion object { const val SHELF_SIZE = 12 }
}

/**
 * "Up next" on Home (3.0.0): the first games still to play, as cover cards like Continue playing,
 * ending in a "See all" card. Renders nothing while the list is empty. Place it next to
 * ContinuePlayingShelf (only while the search is empty and no filter is active):
 * `UpNextShelf(onOpenGame = { c, f -> onOpenGame?.invoke(c, f) }, onOpenAll = { navController.navigate(<up next route>) })`.
 *
 * @param onOpenGame opens the game page (console id, file name).
 * @param onOpenAll opens the Up next screen.
 */
@Composable
fun UpNextShelf(
    onOpenGame: (String, String) -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpNextShelfViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.next30_title), icon = R.drawable.ic_playlist_play, modifier = Modifier.padding(horizontal = 6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // Room around the cards for the focus halo and the 1.03 scale.
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items, key = { it.key }, contentType = { "card" }) { entry -> ShelfCard(entry) { onOpenGame(entry.consoleId, entry.fileName) } }
            item(key = "all", contentType = "all") { SeeAllCard(onOpenAll) }
        }
    }
}

@Composable
private fun ShelfCard(entry: UpNextEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
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
            consoleId = entry.consoleId,
            fileName = entry.fileName,
            title = entry.title,
            modifier = Modifier.size(width = 52.dp, height = 68.dp),
            shape = RoundedCornerShape(8.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(consoleColor(entry.consoleId)))
                Text(stateText(entry.state), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val source = rememberFocusSource()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(120.dp)
            .heightIn(min = 84.dp)
            .focusScale(source, 1.03f)
            .focusRing(source, cornerRadius = 12.dp, fill = false)
            .clip(shape)
            .background(scheme.surfaceContainer)
            .border(1.dp, tokens.hairline, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = accentInk(), modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.next30_see_all), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 2)
    }
}
