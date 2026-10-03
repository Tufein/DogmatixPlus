package com.cortinadev.dogmatix

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.util.DeepLinkParser
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.StorageStatusViewModel
import com.cortinadev.dogmatix.ui.components.FreeSpaceText
import com.cortinadev.dogmatix.ui.components.BottomTabs
import com.cortinadev.dogmatix.ui.components.GamepadLegend
import com.cortinadev.dogmatix.ui.components.NoGamepadHint
import com.cortinadev.dogmatix.ui.components.PortraitHeader
import com.cortinadev.dogmatix.ui.components.TopTabs
import com.cortinadev.dogmatix.ui.components.legendFor
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.contact.ContactScreen
import com.cortinadev.dogmatix.ui.screens.download.DownloadScreen
import com.cortinadev.dogmatix.ui.screens.download.DownloadViewModel
import com.cortinadev.dogmatix.ui.screens.home.HomeScreen
import com.cortinadev.dogmatix.ui.screens.onboarding.OnboardingScreen
import com.cortinadev.dogmatix.ui.screens.settings.SettingsScreen
import com.cortinadev.dogmatix.ui.screens.settings.SettingsViewModel
import com.cortinadev.dogmatix.ui.screens.settings.romm.RommScreen
import com.cortinadev.dogmatix.ui.screens.sources.SourcesScreen
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel
import com.cortinadev.dogmatix.ui.screens.tools.DuplicatesScreen
import com.cortinadev.dogmatix.ui.screens.tools.LibraryOverviewScreen
import com.cortinadev.dogmatix.ui.screens.tools.SetsScreen
import com.cortinadev.dogmatix.ui.screens.tools.StorageScreen
import com.cortinadev.dogmatix.ui.screens.tools.ToolsHubScreen
import com.cortinadev.dogmatix.ui.screens.tools.WishlistScreen
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.data.service.SaveSyncService
import com.cortinadev.dogmatix.ui.screens.settings.savesync.SaveSyncScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var pendingFilters: PendingLibraryFilters
    @Inject lateinit var saveSyncService: SaveSyncService
    @Inject lateinit var appSettings: com.cortinadev.dogmatix.data.local.AppSettings
    @Inject lateinit var downloadService: com.cortinadev.dogmatix.data.service.DownloadService
    @Inject lateinit var metadataService: com.cortinadev.dogmatix.data.service.GameMetadataService
    private var secondScreen: com.cortinadev.dogmatix.ui.secondscreen.SecondScreenPresenter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Gamepad.startWatching(this)
        handleDeepLink(intent)
        // A second display (dual-screen handheld, TV) shows the game under the cursor and the downloads.
        lifecycleScope.launch {
            appSettings.secondScreen.collect { on ->
                secondScreen?.stop()
                secondScreen = if (!on) null else com.cortinadev.dogmatix.ui.secondscreen.SecondScreenPresenter(
                    this@MainActivity, downloadService.downloads
                ) { item -> metadataService.lookup(item.file.name, item.file.consoleId) }.also { it.start() }
            }
        }
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.uiState.collectAsState()
            val onboardingDone by settingsViewModel.onboardingDone.collectAsState()
            // Key handling runs outside the composition, so the pad settings are mirrored there.
            LaunchedEffect(settings.gamepadLayout, settings.swapFaceButtons) {
                Gamepad.layout.value = settings.gamepadLayout
                Gamepad.swapFaceButtons.value = settings.swapFaceButtons
            }
            val boldFocus by appSettings.boldFocus.collectAsState(initial = false)
            DogmatixTheme(themeMode = settings.themeMode, accent = settings.accent) {
                CompositionLocalProvider(com.cortinadev.dogmatix.ui.components.LocalBoldFocus provides boldFocus) {
                    when (onboardingDone) {
                        null -> Unit                      // DataStore not read yet: avoid flashing the wrong screen
                        false -> OnboardingHost()
                        true -> DogmatixApp(pendingFilters)
                    }
                }
            }
        }
    }

    /** Opened, or back from a game: bring the saves up to date with RomM (when switched on). */
    override fun onStart() {
        super.onStart()
        saveSyncService.autoSync()
    }

    /** singleTask: a deep link while the app is running arrives here instead of a new instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent?.getStringExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)?.let {
            pendingFilters.openSection(it)
            intent.removeExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)
        }
        // Shared text (a link from a browser or chat) or a magnet link opened in the app.
        if (intent?.action == Intent.ACTION_SEND) {
            com.cortinadev.dogmatix.util.SharedLinks.parse(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(pendingFilters::share)
                ?: ToastUtil.showError(this, getString(R.string.share_no_link))
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            com.cortinadev.dogmatix.util.SharedLinks.parse(intent.dataString)?.let(pendingFilters::share)
            return
        }
        if (DeepLinkParser.SCHEME.equals(data.scheme, ignoreCase = true)) {
            DeepLinkParser.parse(intent.dataString)?.let(pendingFilters::submit)
            return
        }
        // A .dgmtx shortcut opened from a frontend (ES-DE…) or a file manager: the deep link is
        // inside the file. content:// URIs arrive with a read grant; file:// may not be readable.
        lifecycleScope.launch {
            val request = withContext(Dispatchers.IO) {
                runCatching { readShortcutFile(data) }.getOrNull()
                    ?.let { DeepLinkParser.parse(DgmtxFile.extractLink(it)) }
            }
            if (request != null) {
                pendingFilters.submit(request)
            } else {
                ToastUtil.showError(this@MainActivity, getString(R.string.dgmtx_invalid))
            }
        }
    }

    /** The file's text, or null when it is unreadable or larger than [DgmtxFile.MAX_BYTES]. */
    private fun readShortcutFile(uri: Uri): String? =
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(DgmtxFile.MAX_BYTES + 1)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read > DgmtxFile.MAX_BYTES) null else String(buffer, 0, read, Charsets.UTF_8)
        }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The face-button swap is applied before anything else sees the key; dialogs and the
        // filter sheet are their own window and do it themselves (Modifier.swapFaceButtons).
        val swapped = Gamepad.remap(event)
        return Gamepad.interceptKey(swapped) || super.dispatchKeyEvent(swapped)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        Gamepad.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onDestroy() {
        secondScreen?.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full-screen: status and navigation bars stay hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Insets the app content keeps clear of: the system bars (zero while immersive) plus the display
 * cutout, so on notched devices nothing sits under the notch in either orientation while the
 * background still paints edge to edge.
 */
@Composable
private fun contentInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** First-run tour on the same gradient ground as the shell; import reuses the Sources ViewModel. */
@Composable
private fun OnboardingHost() {
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val isRescanning by sourcesViewModel.isRescanning.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(tokens.gradientTop, scheme.background, scheme.background)))
                .windowInsetsPadding(contentInsets())
        ) {
            OnboardingScreen(
                onImportSources = sourcesViewModel::importSources,
                isRescanning = isRescanning
            )
        }
    }
}

@Composable
private fun DogmatixApp(pendingFilters: PendingLibraryFilters) {
    val context = LocalContext.current
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val rescanErrorMessage by sourcesViewModel.rescanErrorMessage.collectAsState()
    val downloads by downloadViewModel.downloads.collectAsState()
    val gamepadConnected by Gamepad.connected.collectAsState()
    val legendOverride by Gamepad.legendOverride.collectAsState()
    val storageViewModel: StorageStatusViewModel = hiltViewModel()
    val freeBytes by storageViewModel.freeBytes.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        sourcesViewModel.initializeSources()
    }

    // A link shared to the app: download it or add it as a source.
    val shared by pendingFilters.shared.collectAsState()
    shared?.let { link -> com.cortinadev.dogmatix.ui.screens.share.ShareTargetDialog(link, onDismiss = pendingFilters::dismissShare) }

    val scanReport by sourcesViewModel.scanReport.collectAsState()
    scanReport?.let { failed ->
        com.cortinadev.dogmatix.ui.screens.sources.components.ScanReportDialog(
            failures = failed,
            onRetry = sourcesViewModel::retryFailedSources,
            onDismiss = sourcesViewModel::dismissScanReport
        )
    }

    if (rescanErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { sourcesViewModel.clearRescanError() },
            title = { Text(stringResource(R.string.scrape_error_title)) },
            text = { Text(rescanErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { sourcesViewModel.clearRescanError() }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoutes.Home.route

    Gamepad.currentRoute = currentRoute
    // A deep link lands on the Library tab; HomeViewModel picks the filters up from the holder.
    val pendingVersion by pendingFilters.version.collectAsState()
    LaunchedEffect(pendingVersion) {
        if (pendingVersion > 0 && navController.currentBackStackEntry?.destination?.route != NavRoutes.Home.route) {
            navController.switchTo(NavRoutes.Home)
        }
    }
    // The widget's download line: open that section.
    val openRoute by pendingFilters.openRoute.collectAsState()
    LaunchedEffect(openRoute) {
        val route = pendingFilters.consumeSection() ?: return@LaunchedEffect
        NavRoutes.allRoutes.firstOrNull { it.route == route }?.let { navController.switchTo(it) }
    }
    // After a gamepad section switch the focus ring goes away: focus is parked on an invisible
    // sink (clearing it would make Compose re-focus the first tab). The next D-pad press puts
    // it back on the active tab (see Gamepad.interceptKey).
    val focusSink = remember { FocusRequester() }
    LaunchedEffect(currentRoute) {
        if (Gamepad.pointerHidden.value) {
            withFrameNanos { }
            runCatching { focusSink.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            val tabs = NavRoutes.tabs
            val route = navController.currentBackStackEntry?.destination?.route
            val index = tabs.indexOfFirst { it.route == route }.coerceAtLeast(0)
            when (button) {
                GamepadButton.PREV_TAB, GamepadButton.NEXT_TAB -> {
                    val delta = if (button == GamepadButton.PREV_TAB) -1 else 1
                    Gamepad.pointerHidden.value = true
                    runCatching { focusSink.requestFocus() }
                    navController.switchTo(tabs[((index + delta) % tabs.size + tabs.size) % tabs.size])
                }
                GamepadButton.FOCUS_TAB -> runCatching { Gamepad.sectionFocus.requestFocus() }
                else -> Unit
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalDogmatixTokens.current
    val gradientTop = tokens.gradientTop
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !tokens.isDark
        }
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(gradientTop, scheme.background, scheme.background)))
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusSink)
                .focusable()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets())
        ) {
            if (isLandscape) {
                TopTabs(currentRoute = currentRoute, onSelect = navController::switchTo)
            } else {
                PortraitHeader(trailing = { FreeSpaceText(freeBytes) })
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(NavRoutes.Home.route) { HomeScreen(navController) }
                    composable(NavRoutes.Downloads.route) { DownloadScreen(navController) }
                    composable(NavRoutes.Sources.route) { SourcesScreen() }
                    composable(NavRoutes.Settings.route) { SettingsScreen(navController) }
                    composable(NavRoutes.Contact.route) { ContactScreen(navController) }
                    composable(NavRoutes.Romm.route) { RommScreen() }
                    composable(NavRoutes.SaveSync.route) { SaveSyncScreen() }
                    composable(NavRoutes.Overview.route) { LibraryOverviewScreen() }
                    composable(NavRoutes.Duplicates.route) { DuplicatesScreen() }
                    composable(NavRoutes.Tools.route) { ToolsHubScreen(navController) }
                    composable(NavRoutes.Sets.route) { SetsScreen() }
                    composable(NavRoutes.Storage.route) { StorageScreen() }
                    composable(NavRoutes.Wishlist.route) { WishlistScreen(navController) }
                    composable(NavRoutes.Files.route) { com.cortinadev.dogmatix.ui.screens.tools.FileExplorerScreen() }
                    composable(NavRoutes.Collections.route) { com.cortinadev.dogmatix.ui.screens.tools.CollectionsScreen(navController) }
                    composable(NavRoutes.Switch.route) { com.cortinadev.dogmatix.ui.screens.tools.SwitchScreen() }
                    composable(NavRoutes.Dat.route) { com.cortinadev.dogmatix.ui.screens.tools.DatScreen() }
                    composable(NavRoutes.Bios.route) { com.cortinadev.dogmatix.ui.screens.tools.BiosScreen() }
                    composable(NavRoutes.Stats.route) { com.cortinadev.dogmatix.ui.screens.tools.StatsScreen() }
                }
            }

            if (gamepadConnected) {
                GamepadLegend(entries = legendOverride?.entries ?: legendFor(currentRoute), trailing = if (isLandscape) ({ FreeSpaceText(freeBytes) }) else null)
            } else if (isLandscape) {
                NoGamepadHint(trailing = { FreeSpaceText(freeBytes) })
            }

            if (!isLandscape) {
                BottomTabs(
                    currentRoute = currentRoute,
                    activeDownloads = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED },
                    onSelect = navController::switchTo
                )
            }
        }
    }
    }
}

private fun NavController.switchTo(route: NavRoutes) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
