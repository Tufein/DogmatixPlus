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
import androidx.compose.ui.unit.Density
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.cortinadev.dogmatix.ui.screens.game.GamePage
import com.cortinadev.dogmatix.util.GamePageModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.GameMetadataService
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.components.LocalBoldFocus
import com.cortinadev.dogmatix.ui.components.WhatsNewDialog
import com.cortinadev.dogmatix.ui.screens.share.ShareTargetDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.ScanReportDialog
import com.cortinadev.dogmatix.ui.screens.tools.BiosScreen
import com.cortinadev.dogmatix.ui.screens.tools.CollectionsScreen
import com.cortinadev.dogmatix.ui.screens.tools.CollectionGoalsScreen
import com.cortinadev.dogmatix.ui.screens.tools.HealthScreen
import com.cortinadev.dogmatix.ui.screens.tools.RecapScreen
import com.cortinadev.dogmatix.ui.screens.tools.FreeSpaceScreen
import com.cortinadev.dogmatix.ui.screens.tools.BestGamesScreen
import com.cortinadev.dogmatix.ui.screens.tools.FrontendMetadataScreen
import com.cortinadev.dogmatix.ui.screens.tools.BetterVersionsScreen
import com.cortinadev.dogmatix.util.QuickActions
import com.cortinadev.dogmatix.ui.screens.tools.HistoryScreen
import com.cortinadev.dogmatix.util.HealthFix
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.ui.screens.tools.DatScreen
import com.cortinadev.dogmatix.ui.screens.tools.ImportListScreen
import com.cortinadev.dogmatix.ui.screens.tools.FileExplorerScreen
import com.cortinadev.dogmatix.ui.screens.tools.FrontendCheckScreen
import com.cortinadev.dogmatix.ui.screens.tools.ProfilesScreen
import com.cortinadev.dogmatix.ui.screens.tools.RetroAchievementsScreen
import com.cortinadev.dogmatix.ui.screens.tools.StatsScreen
import com.cortinadev.dogmatix.ui.screens.tools.SwitchScreen
import com.cortinadev.dogmatix.ui.secondscreen.SecondScreenPresenter
import com.cortinadev.dogmatix.ui.secondscreen.SecondScreenState
import com.cortinadev.dogmatix.util.DeepLinkParser
import com.cortinadev.dogmatix.util.DgmtxFile
import com.cortinadev.dogmatix.util.SharedLinks
import com.cortinadev.dogmatix.util.TextSize
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.WhatsNew
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
import com.cortinadev.dogmatix.ui.navigation.switchToTab
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
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import com.cortinadev.dogmatix.data.local.LookSettings
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.dogmatixBackground
import com.cortinadev.dogmatix.data.local.TvModeSettings
import com.cortinadev.dogmatix.util.TvModeSetting
import com.cortinadev.dogmatix.util.QuickAction
import com.cortinadev.dogmatix.ui.components.ProvideTvMode
import com.cortinadev.dogmatix.ui.components.LocalTvMode
import com.cortinadev.dogmatix.ui.components.tvSafeArea
import com.cortinadev.dogmatix.ui.components.tabRouteFor
import com.cortinadev.dogmatix.ui.components.QuickMenuOverlay
import com.cortinadev.dogmatix.ui.screens.search.SearchAllScreen
import com.cortinadev.dogmatix.ui.screens.cloud.CloudBackupScreen
import com.cortinadev.dogmatix.ui.screens.cloud.CloudScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var smartCollections: com.cortinadev.dogmatix.data.service.SmartCollectionsService
    @Inject lateinit var pendingFilters: PendingLibraryFilters
    @Inject lateinit var saveSyncService: SaveSyncService
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var downloadService: DownloadService
    @Inject lateinit var metadataService: GameMetadataService
    @Inject lateinit var lookSettings: LookSettings
    @Inject lateinit var tvModeSettings: TvModeSettings
    private var secondScreen: SecondScreenPresenter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Gamepad.startWatching(this)
        handleDeepLink(intent)
        // Downloads that were cut off when the app closed join the queue again (once per run).
        lifecycleScope.launch {
            val n = downloadService.requeueInterrupted()
            if (n > 0) ToastUtil.showInfo(this@MainActivity, resources.getQuantityString(R.plurals.downloads_requeued, n, n))
        }
        // A second display (dual-screen handheld, TV) shows the game under the cursor and the downloads.
        lifecycleScope.launch {
            appSettings.secondScreen.collect { on ->
                secondScreen?.stop()
                secondScreen = if (!on) null else SecondScreenPresenter(
                    this@MainActivity, downloadService.downloads
                ) { item -> metadataService.lookup(item.file.name, item.file.consoleId, item.file.fileName) }.also { it.start() }
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
            val textSize by appSettings.textSizePercent.collectAsState(initial = TextSize.DEFAULT)
            val animations by lookSettings.animations.collectAsState(initial = true)
            val glow by lookSettings.glow.collectAsState(initial = true)
            val tvSetting by tvModeSettings.mode.collectAsState(initial = TvModeSetting.AUTO)
            val systemAnimationsOff = remember {
                runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
            }
            val density = LocalDensity.current
            DogmatixTheme(themeMode = settings.themeMode, accent = settings.accent, glow = glow) {
                CompositionLocalProvider(
                    LocalBoldFocus provides boldFocus,
                    LocalReduceMotion provides (!animations || systemAnimationsOff),
                    LocalDensity provides Density(density.density, density.fontScale * TextSize.factor(textSize))
                ) {
                    ProvideTvMode(tvSetting) {
                        when (onboardingDone) {
                            null -> Unit                      // DataStore not read yet: avoid flashing the wrong screen
                            false -> OnboardingHost()
                            true -> DogmatixApp(pendingFilters)
                        }
                        WhatsNewAfterUpdate(onboardingDone)
                    }
                }
            }
        }
    }

    /** The highlights of this version, once after an update; a fresh install only records the version. */
    @Composable
    private fun WhatsNewAfterUpdate(onboardingDone: Boolean?) {
        val lastSeen by appSettings.lastSeenVersion.collectAsState(initial = -1)
        if (onboardingDone == null || lastSeen < 0) return
        val current = BuildConfig.VERSION_CODE
        if (onboardingDone == false) {
            LaunchedEffect(lastSeen) { if (lastSeen != current) appSettings.setLastSeenVersion(current) }
            return
        }
        if (WhatsNew.shouldShow(lastSeen, current, onboarded = true)) {
            WhatsNewDialog(BuildConfig.VERSION_NAME) {
                lifecycleScope.launch { appSettings.setLastSeenVersion(current) }
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
        QuickActions.parse(intent?.action, intent?.getStringExtra(QuickActions.EXTRA_QUICK_ACTION))?.let { quick ->
            pendingFilters.submitQuick(quick)
            intent?.removeExtra(QuickActions.EXTRA_QUICK_ACTION)
            return
        }
        intent?.getStringExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)?.let {
            pendingFilters.openSection(it)
            intent.removeExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE)
        }
        // Shared text (a link from a browser or chat) or a magnet link opened in the app.
        if (intent?.action == Intent.ACTION_SEND) {
            SharedLinks.parse(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(pendingFilters::share)
                ?: ToastUtil.showError(this, getString(R.string.share_no_link))
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            SharedLinks.parse(intent.dataString)?.let(pendingFilters::share)
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
        if (hasFocus) hideSystemBars() else Gamepad.cancelSelectHold()
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
                .dogmatixBackground()
                .windowInsetsPadding(contentInsets())
        ) {
            OnboardingScreen(
                onImportSources = sourcesViewModel::importSources,
                isRescanning = isRescanning
            )
        }
    }
}

/** The screens built from the settings kit: they get the plain AMOLED ground (see SettingsSurface). */
private val SettingsKitRoutes = setOf(NavRoutes.Settings.route, NavRoutes.Romm.route, NavRoutes.SaveSync.route, NavRoutes.VersionPreference.route)

@Composable
private fun DogmatixApp(pendingFilters: PendingLibraryFilters) {
    val context = LocalContext.current
    val sourcesViewModel: SourcesViewModel = hiltViewModel()
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val rescanErrorMessage by sourcesViewModel.rescanErrorMessage.collectAsState()
    // Only the count: the whole list changes with every progress tick and would redraw the app shell each time.
    val activeDownloadCount by downloadViewModel.activeCount.collectAsState()
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
    shared?.let { link -> ShareTargetDialog(link, onDismiss = pendingFilters::dismissShare) }

    val scanReport by sourcesViewModel.scanReport.collectAsState()
    scanReport?.let { failed ->
        ScanReportDialog(
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
    SecondScreenState.setRoute(tabRouteFor(currentRoute))   // the second screen shows its downloads dashboard away from the library (a game page counts as library)
    val tvActive = LocalTvMode.current
    SideEffect { Gamepad.tvModeActive = tvActive }
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
            val index = tabs.indexOfFirst { it.route == tabRouteFor(route.orEmpty()) }.coerceAtLeast(0)
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
            // 8.1: Settings and its sub-screens sit on a plain (AMOLED black in dark) ground.
            .dogmatixBackground(amoled = currentRoute in SettingsKitRoutes)
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
                .tvSafeArea()
        ) {
            if (isLandscape) {
                TopTabs(
                    currentRoute = currentRoute,
                    onSelect = navController::switchTo,
                    activeDownloads = activeDownloadCount,
                    onOpenCloud = { navController.openCloud() }
                )
            } else {
                PortraitHeader(trailing = { FreeSpaceText(freeBytes) }, onOpenCloud = { navController.openCloud() })
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val reduceMotion = LocalReduceMotion.current
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.Home.route,
                    modifier = Modifier.fillMaxSize(),
                    // 5.0: a quick fade with a short slide in the direction of travel (tabs left/right,
                    // screens opened from Settings from the right). Short, because a fade costs frames
                    // on low-end handhelds; nothing at all with animations off.
                    enterTransition = {
                        if (reduceMotion) EnterTransition.None
                        else fadeIn(tween(Motion.MEDIUM)) + slideInHorizontally(tween(Motion.SLOW)) { full ->
                            travelDirection(initialState.destination.route, targetState.destination.route) * full / 24
                        }
                    },
                    exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(Motion.FAST)) },
                    popEnterTransition = {
                        if (reduceMotion) EnterTransition.None
                        else fadeIn(tween(Motion.MEDIUM)) + slideInHorizontally(tween(Motion.SLOW)) { full ->
                            -travelDirection(targetState.destination.route, initialState.destination.route) * full / 24
                        }
                    },
                    popExitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(Motion.FAST)) }
                ) {
                    composable(NavRoutes.Home.route) {
                        HomeScreen(navController, onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) })
                    }
                    composable(
                        NavRoutes.Game.route,
                        arguments = listOf(
                            navArgument(GamePageModel.ARG_CONSOLE) { type = NavType.StringType },
                            navArgument(GamePageModel.ARG_FILE) { type = NavType.StringType }
                        )
                    ) { entry ->
                        // Navigation already URL-decodes path arguments; do not decode them again.
                        GamePage(
                            consoleId = entry.arguments?.getString(GamePageModel.ARG_CONSOLE).orEmpty(),
                            fileName = entry.arguments?.getString(GamePageModel.ARG_FILE).orEmpty(),
                            onBack = { navController.popBackStack() },
                            onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) },
                            onOpenVersionPreference = { navController.navigate(NavRoutes.VersionPreference.route) },
                            onReadiness = { c, f -> navController.navigate(com.cortinadev.dogmatix.ui.screens.tools.ReadinessRoute.of(c, f)) }
                        )
                    }
                    composable(com.cortinadev.dogmatix.ui.screens.tools.ReadinessRoute.route,
                        arguments = listOf(navArgument("consoleId") { type = NavType.StringType }, navArgument("fileName") { type = NavType.StringType })) { entry ->
                        com.cortinadev.dogmatix.ui.screens.tools.GameReadinessScreen(
                            entry.arguments?.getString("consoleId").orEmpty(), entry.arguments?.getString("fileName").orEmpty(),
                            onNavigate = { navController.navigate(it) })
                    }
                    composable(NavRoutes.VersionPreference.route) { com.cortinadev.dogmatix.ui.screens.settings.VersionPreferenceScreen() }
                    composable(NavRoutes.Downloads.route) { DownloadScreen(navController) }
                    composable(NavRoutes.Sources.route) { SourcesScreen() }
                    composable(NavRoutes.Settings.route) { SettingsScreen(navController) }
                    composable(NavRoutes.Contact.route) { ContactScreen(navController) }
                    composable(NavRoutes.Romm.route) { RommScreen() }
                    composable(NavRoutes.SaveSync.route) { SaveSyncScreen() }
                    composable(NavRoutes.Overview.route) { LibraryOverviewScreen() }
                    composable(NavRoutes.Duplicates.route) { DuplicatesScreen() }
                    composable(NavRoutes.Recovery.route) { com.cortinadev.dogmatix.ui.screens.tools.RecoveryScreen(onNavigate = { navController.navigate(it) }) }
                    composable(NavRoutes.ActionHistory.route) {
                        com.cortinadev.dogmatix.ui.screens.tools.ActionHistoryScreen(
                            onOpenGame = { c, f -> navController.navigate(GamePageModel.route(c, f)) },
                            onNavigate = { navController.navigate(it) })
                    }
                    composable(NavRoutes.Tools.route) { ToolsHubScreen(navController) }
                    composable(NavRoutes.Sets.route) { SetsScreen() }
                    composable(NavRoutes.Storage.route) { StorageScreen() }
                    composable(NavRoutes.Wishlist.route) { WishlistScreen(navController) }
                    composable(NavRoutes.Frontends.route) { FrontendCheckScreen(navController) }
                    composable(NavRoutes.Files.route) { FileExplorerScreen() }
                    composable(NavRoutes.Collections.route) { CollectionsScreen(navController) }
                    composable(NavRoutes.Switch.route) { SwitchScreen() }
                    composable(NavRoutes.Dat.route) { DatScreen(navController) }
                    composable(NavRoutes.ImportList.route) { ImportListScreen() }
                    composable(NavRoutes.Bios.route) { BiosScreen() }
                    composable(NavRoutes.Stats.route) { StatsScreen() }
                    composable(NavRoutes.Profiles.route) { ProfilesScreen() }
                    composable(NavRoutes.RetroAchievements.route) { RetroAchievementsScreen() }
                    composable(NavRoutes.Cloud.route) { CloudScreen(navController) }
                    composable(NavRoutes.CloudBackup.route) { CloudBackupScreen() }
                    composable(NavRoutes.CollectionGoals.route) { CollectionGoalsScreen(onOpenImportList = { navController.navigate(NavRoutes.ImportList.route) }) }
                    composable(NavRoutes.History.route) {
                        HistoryScreen(onOpenGame = { consoleId, fileName ->
                            pendingFilters.submit(LibraryFilterRequest(consoles = setOf(consoleId), query = fileName.substringBeforeLast('.')))
                            navController.switchTo(NavRoutes.Home)
                        })
                    }
                    composable(NavRoutes.Health.route) { HealthScreen(onFix = { fix -> navController.healthFix(fix) }, onRecovery = { navController.navigate(NavRoutes.Recovery.route) }) }
                    composable(NavRoutes.FreeSpace.route) { FreeSpaceScreen(onOpenWishlist = { navController.navigate(NavRoutes.Wishlist.route) }) }
                    composable(NavRoutes.Recap.route) { RecapScreen() }
                    composable(NavRoutes.BestGames.route) {
                        BestGamesScreen(
                            onOpenRetroAchievements = { navController.navigate(NavRoutes.RetroAchievements.route) },
                            onOpenLibrary = { navController.switchTo(NavRoutes.Home) }
                        )
                    }
                    composable(NavRoutes.FrontendMetadata.route) {
                        FrontendMetadataScreen(onOpenSettings = { navController.navigate(NavRoutes.Settings.route) })
                    }
                    composable(NavRoutes.BetterVersions.route) { BetterVersionsScreen(onRecovery = { navController.navigate(NavRoutes.Recovery.route) }) }
                    composable(NavRoutes.SearchAll.route) { SearchAllScreen(navController) }
                }
            }

            // On a TV the remote is the controller: show the legend there too.
            if (gamepadConnected || LocalTvMode.current) {
                GamepadLegend(entries = legendOverride?.entries ?: legendFor(currentRoute), trailing = if (isLandscape) ({ FreeSpaceText(freeBytes) }) else null)
            } else if (isLandscape) {
                NoGamepadHint(trailing = { FreeSpaceText(freeBytes) })
            }

            if (!isLandscape) {
                BottomTabs(
                    currentRoute = currentRoute,
                    activeDownloads = activeDownloadCount,
                    onSelect = navController::switchTo
                )
            }
        }
        // 8.0: hold SELECT for the quick menu, over every screen.
        val queueHeld by downloadViewModel.held.collectAsState()
        QuickMenuOverlay(
            queueHeld = queueHeld,
            onSearch = { pendingFilters.submitQuick(QuickAction.SEARCH) },
            onSearchAll = { navController.navigate(NavRoutes.SearchAll.route) { launchSingleTop = true } },
            onSurprise = { pendingFilters.submitQuick(QuickAction.SURPRISE) },
            onDownloads = { navController.switchTo(NavRoutes.Downloads) },
            onSetQueueHeld = downloadViewModel::setHeld,
            onTools = { navController.navigate(NavRoutes.Tools.route) { launchSingleTop = true } },
            onSettings = { navController.switchTo(NavRoutes.Settings) }
        )
    }
    }
}

/**
 * +1 when going "right" (a later tab, or deeper from Settings), -1 when going back "left": the
 * side the new screen slides in from.
 */
private fun travelDirection(from: String?, to: String?): Int {
    val tabs = NavRoutes.tabs.map { it.route }
    val a = tabs.indexOf(from)
    val b = tabs.indexOf(to)
    return when {
        a >= 0 && b >= 0 -> if (b >= a) 1 else -1
        b < 0 -> 1      // into a screen opened from Settings
        else -> -1      // back out to a tab
    }
}

/** The Cloud hub on top of whatever is open, so back returns to it. */
private fun NavController.openCloud() {
    if (currentDestination?.route != NavRoutes.Cloud.route) navigate(NavRoutes.Cloud.route) { launchSingleTop = true }
}

/** Where a health row's fix goes; the inline fixes never reach here. */
private fun NavController.healthFix(fix: HealthFix) {
    val route = when (fix) {
        HealthFix.OPEN_SOURCES -> NavRoutes.Sources
        HealthFix.OPEN_BIOS -> NavRoutes.Bios
        HealthFix.OPEN_SAVE_SYNC -> NavRoutes.SaveSync
        HealthFix.OPEN_ROMM -> NavRoutes.Romm
        HealthFix.OPEN_CLOUD_BACKUP -> NavRoutes.CloudBackup
        HealthFix.OPEN_STORAGE -> NavRoutes.Storage
        HealthFix.OPEN_FRONTENDS -> NavRoutes.Frontends
        HealthFix.OPEN_RETROACHIEVEMENTS -> NavRoutes.RetroAchievements
        HealthFix.OPEN_UPDATES -> NavRoutes.Settings
        else -> return
    }
    navigate(route.route) { launchSingleTop = true }
}

private fun NavController.switchTo(route: NavRoutes) = switchToTab(route.route)
