package com.cortinadev.dogmatix.ui.secondscreen

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.LookSettings
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.ui.components.CoverImage
import com.cortinadev.dogmatix.ui.components.EmptyState
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.components.ProgressRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberCoverRepository
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import com.cortinadev.dogmatix.ui.theme.consoleColor
import com.cortinadev.dogmatix.ui.theme.dogmatixBackground
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.QueueEta
import com.cortinadev.dogmatix.util.QueueGlance
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the second screen shows: the game under the cursor in the library (set by Home) and the
 * section the main screen is on (set by the shell). The game view is for the library; on any
 * other section the second screen shows the downloads dashboard.
 */
object SecondScreenState {
    private val _focused = MutableStateFlow<DownloadableFileWithTags?>(null)
    val focused: StateFlow<DownloadableFileWithTags?> = _focused.asStateFlow()
    fun focus(item: DownloadableFileWithTags?) { _focused.value = item }

    private val _route = MutableStateFlow(NavRoutes.Home.route)
    /** The route of the section the main screen is on; [NavRoutes.Home] until the shell says otherwise. */
    val route: StateFlow<String> = _route.asStateFlow()
    fun setRoute(route: String) { _route.value = route }
}

/** The settings the second screen follows, so it wears the same theme and accent as the app. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SecondScreenEntryPoint {
    fun settings(): SettingsDataStore
    fun look(): LookSettings
    fun files(): DownloadableFileDao
}

/**
 * A second display (a dual-screen handheld such as the AYN Thor, or a TV over USB-C) shows the
 * game under the cursor — cover, title and description — or, away from the library, the downloads
 * in progress, while the app stays on the main screen. Uses Android's presentation API, so it
 * works on any device that reports a presentation display.
 */
class SecondScreenPresenter(
    private val activity: Activity,
    private val downloads: StateFlow<List<DownloadItemModel>>,
    private val lookup: suspend (DownloadableFileWithTags) -> GameDetails?
) : DisplayManager.DisplayListener {
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private var presentation: Presentation? = null

    fun start() {
        displayManager.registerDisplayListener(this, null)
        update()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        presentation = null
    }

    private fun update() {
        val display = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (display == null) { presentation?.dismiss(); presentation = null; return }
        if (presentation?.display?.displayId == display.displayId) return
        presentation?.dismiss()
        presentation = Screen(activity, display).also { runCatching { it.show() } }
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) = Unit

    private inner class Screen(context: Context, display: Display) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val owner = activity as LifecycleOwner
            val saved = activity as SavedStateRegistryOwner
            window?.decorView?.let { it.setViewTreeLifecycleOwner(owner); it.setViewTreeSavedStateRegistryOwner(saved) }
            // The display context inherits neither the in-app language nor the day/night mode of the
            // activity; take both from it.
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(activity.resources.configuration.locales)
                    uiMode = activity.resources.configuration.uiMode
                }
            )
            setContentView(ComposeView(localized).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(saved)
                setContent { SecondScreenRoot(downloads, lookup) }
            })
        }
    }
}

/** The cover shown, with the console it belongs to (for the placeholder when there is no picture). */
private data class Art(val url: String?, val consoleId: String)

/** Wraps everything in the app's own theme, accent, glow and motion setting. */
@Composable
private fun SecondScreenRoot(
    downloads: StateFlow<List<DownloadItemModel>>,
    lookup: suspend (DownloadableFileWithTags) -> GameDetails?
) {
    val context = LocalContext.current
    val env = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, SecondScreenEntryPoint::class.java) }
    val themeName by remember(env) { env.settings().themeMode }.collectAsState(initial = null)
    val accentHex by remember(env) { env.settings().accentColor }.collectAsState(initial = null)
    val glow by remember(env) { env.look().glow }.collectAsState(initial = true)
    val animations by remember(env) { env.look().animations }.collectAsState(initial = true)
    val systemAnimationsOff = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val name = themeName
    val hex = accentHex
    if (name == null || hex == null) {
        // The settings are read in a few milliseconds; no point drawing the default look first.
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    DogmatixTheme(themeMode = ThemeMode.fromName(name), accent = AccentPresets.fromHex(hex), glow = glow) {
        CompositionLocalProvider(
            LocalReduceMotion provides (!animations || systemAnimationsOff),
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            SecondScreenContent(downloads, lookup, env.files())
        }
    }
}

@Composable
private fun SecondScreenContent(
    downloads: StateFlow<List<DownloadItemModel>>,
    lookup: suspend (DownloadableFileWithTags) -> GameDetails?,
    files: DownloadableFileDao
) {
    val focused by SecondScreenState.focused.collectAsState()
    val route by SecondScreenState.route.collectAsState()
    val list by downloads.collectAsState()
    val glance = remember(list) { QueueGlance.of(list) }
    val covers = rememberCoverRepository()
    val reduce = LocalReduceMotion.current
    val spec: FiniteAnimationSpec<Float> = if (reduce) snap() else tween(Motion.SLOW, easing = FastOutSlowInEasing)

    // The game view belongs to the library; on any other section the dashboard takes over.
    val item = if (route == NavRoutes.Home.route) focused else null
    var details by remember { mutableStateOf<GameDetails?>(null) }
    // The picture stays until the next game's own picture has been found, so moving through the
    // list does not blank the screen on every row.
    var art by remember { mutableStateOf(Art(null, "")) }
    LaunchedEffect(item) {
        details = null
        val game = item ?: return@LaunchedEffect
        val consoleId = game.file.consoleId
        covers.cached(consoleId, game.file.fileName)?.let { art = Art(it, consoleId) }
        delay(400)   // the cursor moving through a list should not fire a lookup per row
        coroutineScope {
            val info = async { runCatching { lookup(game) }.getOrNull() }
            val cover = async { runCatching { covers.coverUrl(consoleId, game.file.fileName, game.file.name) }.getOrNull() }
            val url = cover.await()
            if (url != null) art = Art(url, consoleId)
            val found = info.await()
            details = found
            if (url == null) art = Art(found?.imageUrl?.takeIf { it.isNotBlank() }, consoleId)
        }
    }

    Box(Modifier.fillMaxSize().dogmatixBackground()) {
        if (item != null) {
            Backdrop(art.url, spec)
            GameView(item, details, art, glance, spec)
        } else {
            DownloadsDashboard(list, glance, files)
        }
    }
}

/**
 * The cover again behind everything, decoded tiny and stretched (a cheap blur that works on every
 * Android version) under a veil in the theme's background colour.
 */
@Composable
private fun Backdrop(url: String?, spec: FiniteAnimationSpec<Float>) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    Crossfade(targetState = url, animationSpec = spec, modifier = Modifier.fillMaxSize(), label = "backdrop") { shown ->
        if (shown != null) {
            val request = remember(shown) { ImageRequest.Builder(context).data(shown).size(48).build() }
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(background.copy(alpha = 0.62f), background.copy(alpha = 0.92f)))
                    )
                )
            }
        }
    }
}

/** Cover on the left (above in portrait), the game's name, tags and description beside it. */
@Composable
private fun GameView(
    item: DownloadableFileWithTags,
    details: GameDetails?,
    art: Art,
    glance: QueueGlance.Glance,
    spec: FiniteAnimationSpec<Float>
) {
    val stripHeight = if (glance.active > 0) 72.dp else 0.dp
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val areaWidth = maxWidth
        val wide = areaWidth >= maxHeight
        val gameHeight = maxHeight - stripHeight
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    val coverWidth = (areaWidth * 0.38f).coerceAtMost(gameHeight * COVER_RATIO)
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        CoverCard(art, spec, Modifier.width(coverWidth).height(coverWidth / COVER_RATIO))
                        GameText(item, details, descriptionLines = ((gameHeight - 150.dp) / 22.dp).toInt().coerceIn(2, 12), modifier = Modifier.weight(1f))
                    }
                } else {
                    val coverWidth = (areaWidth * 0.62f).coerceAtMost(gameHeight * 0.4f * COVER_RATIO)
                    val coverHeight = coverWidth / COVER_RATIO
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CoverCard(art, spec, Modifier.width(coverWidth).height(coverHeight))
                        GameText(item, details, descriptionLines = ((gameHeight - coverHeight - 170.dp) / 22.dp).toInt().coerceIn(2, 12), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (glance.active > 0) {
                Spacer(Modifier.height(8.dp))
                QueueStrip(glance, Modifier.fillMaxWidth())
            }
        }
    }
}

private const val COVER_RATIO = 0.8f

/** The cover on its console-coloured tile; changing games crossfades the picture. */
@Composable
private fun CoverCard(art: Art, spec: FiniteAnimationSpec<Float>, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val hairline = LocalDogmatixTokens.current.hairline
    Box(modifier.border(1.dp, hairline, shape)) {
        Crossfade(targetState = art, animationSpec = spec, modifier = Modifier.fillMaxSize(), label = "cover") { shown ->
            CoverImage(
                url = shown.url,
                consoleId = shown.consoleId,
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                contentScale = ContentScale.Fit
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameText(
    item: DownloadableFileWithTags,
    details: GameDetails?,
    descriptionLines: Int,
    modifier: Modifier
) {
    val file = item.file
    val scheme = MaterialTheme.colorScheme
    val year = details?.released?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) }?.take(4)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = ConsoleFormatter.getConsoleDisplayName(file.consoleId),
                tone = PillTone.Tint(consoleColor(file.consoleId)),
                icon = R.drawable.ic_controller
            )
            year?.let { Pill(text = it, tone = PillTone.Info) }
        }
        Text(
            details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(file.name),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        details?.developer?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val genres = details?.genres.orEmpty().filter { it.isNotBlank() }.take(3)
        val tags = item.tags.filter { it.isNotBlank() }.take(8)
        if (genres.isNotEmpty() || tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                genres.forEach { Pill(text = it, tone = PillTone.Accent) }
                tags.forEach { Pill(text = it, tone = PillTone.Neutral) }
            }
        }
        val description = details?.description.orEmpty()
        if (description.isNotBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                maxLines = descriptionLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The queue as one slim line under the game view: ring, count, percentage, speed, time left. */
@Composable
private fun QueueStrip(glance: QueueGlance.Glance, modifier: Modifier) {
    Panel(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressRing(glance.fraction, size = 40.dp, stroke = 5.dp)
            val parts = listOfNotNull(
                stringResource(R.string.second_screen_downloads, glance.active),
                stringResource(R.string.status_downloading, glance.percent),
                speedLabel(glance),
                etaLabel(glance)
            )
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** "3.2 MB/s" while something transfers, otherwise null. */
@Composable
private fun speedLabel(glance: QueueGlance.Glance): String? =
    if (glance.bytesPerSecond > 0) stringResource(R.string.second_screen_speed, formatBytes(glance.bytesPerSecond)) else null

/** "about 4 min" / "about 1 h 20 min" while the queue has a time left, otherwise null. */
@Composable
private fun etaLabel(glance: QueueGlance.Glance): String? {
    val seconds = glance.etaSeconds
    if (seconds == null) return null
    val (hours, minutes) = QueueEta.hoursMinutes(seconds)
    return if (hours > 0) stringResource(R.string.downloads_eta_hours, hours, minutes)
    else stringResource(R.string.downloads_eta_minutes, minutes)
}

/** The app name with the accent on its "+". */
@Composable
private fun brandText(accent: Color): AnnotatedString {
    val name = stringResource(R.string.app_name)
    return buildAnnotatedString {
        if (name.endsWith("+")) {
            append(name.dropLast(1))
            withStyle(SpanStyle(color = accent)) { append("+") }
        } else append(name)
    }
}

/**
 * Away from the library: the queue as a ring with its speed and time left, and the running
 * downloads (as many as fit, at most four) with covers and progress bars. When nothing downloads,
 * the mascot and a line of help.
 */
@Composable
private fun DownloadsDashboard(list: List<DownloadItemModel>, glance: QueueGlance.Glance, files: DownloadableFileDao) {
    val scheme = MaterialTheme.colorScheme
    val active = remember(list) { list.filter { QueueGlance.isRunning(it) } }
    if (active.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.app_name),
                message = stringResource(R.string.second_screen_idle),
                illustration = R.drawable.milou
            )
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // A short display (the second screen of a dual-screen handheld) gets a tighter layout.
        val compact = maxHeight < 440.dp
        val pad = if (compact) 16.dp else 20.dp
        val gap = if (compact) 12.dp else 14.dp
        val ring = if (compact) 72.dp else 96.dp
        val thumb = if (compact) 36.dp else 44.dp
        val rowGap = if (compact) 10.dp else 12.dp
        // What the header, the ring panel and the list panel's padding take; the rest holds rows.
        val fixed = pad * 2 + 28.dp + gap * 2 + ring + (if (compact) 24.dp else 32.dp) + 24.dp + 28.dp
        val rows = ((maxHeight - fixed + rowGap) / (thumb + rowGap)).toInt().coerceIn(1, 4)
        val shown = active.take(rows)
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().padding(pad),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(brandText(scheme.primary), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, maxLines = 1)
                Pill(text = stringResource(R.string.second_screen_downloads, glance.active), tone = PillTone.Accent, icon = R.drawable.ic_download)
            }
            Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(if (compact) 12.dp else 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ProgressRing(glance.fraction, size = ring, stroke = if (compact) 8.dp else 10.dp) {
                        Text(
                            stringResource(R.string.status_downloading, glance.percent),
                            style = if (compact) MaterialTheme.typography.titleMedium.tabular() else MaterialTheme.typography.titleLarge.tabular(),
                            color = scheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        speedLabel(glance)?.let { StatLine(R.drawable.ic_speed, it) }
                        etaLabel(glance)?.let { StatLine(R.drawable.ic_timer, it) }
                        if (glance.remainingBytes > 0) StatLine(R.drawable.ic_storage, stringResource(R.string.downloads_left, formatBytes(glance.remainingBytes)))
                    }
                }
            }
            Panel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(rowGap)) {
                shown.forEach { d -> key(d.fileName) { DashboardRow(d, files, thumb) } }
                val more = active.size - shown.size
                if (more > 0) {
                    Text(
                        stringResource(R.string.second_screen_more, more),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLine(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleMedium.tabular(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One running download: cover, name, status and a bar in its console's colour. */
@Composable
private fun DashboardRow(d: DownloadItemModel, files: DownloadableFileDao, thumbSize: Dp) {
    val scheme = MaterialTheme.colorScheme
    // A download only knows its file name; the console comes from the library index.
    var consoleId by remember(d.fileName) { mutableStateOf<String?>(null) }
    LaunchedEffect(d.fileName) { consoleId = runCatching { files.getFileByFileName(d.fileName)?.consoleId }.getOrNull() }
    val console = consoleId
    val thumb = Modifier.size(thumbSize)
    val shape = RoundedCornerShape(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (console != null) GameCover(console, d.fileName, d.name, thumb, shape, showLabel = false)
        else CoverImage(null, "", thumb, shape, showLabel = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stripExtension(d.name),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(rowStatus(d), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.onSurfaceVariant, maxLines = 1)
            }
            MeterBar(d.progress, height = 6.dp, color = if (console != null) consoleColor(console) else scheme.primary)
        }
    }
}

@Composable
private fun rowStatus(d: DownloadItemModel): String = when (d.status) {
    DownloadStatus.COPYING -> stringResource(R.string.status_copying)
    DownloadStatus.UNZIPPING -> stringResource(R.string.status_extracting)
    else -> stringResource(R.string.status_downloading, (d.progress.coerceIn(0f, 1f) * 100).toInt())
}
