package com.cortinadev.dogmatix.ui.screens.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.GamepadLegend
import com.cortinadev.dogmatix.ui.components.IconTile
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.PrimaryButton
import com.cortinadev.dogmatix.ui.theme.LocalReduceMotion
import com.cortinadev.dogmatix.ui.theme.Motion
import com.cortinadev.dogmatix.ui.theme.motionSpec
import com.cortinadev.dogmatix.util.FileParsingUtils

/**
 * First-run flow: what the app does → ROM root folder → import a sources JSON → (when ES-DE
 * is installed) connect ES-DE. Every step can be skipped; finishing sets `onboarding_done`
 * and the shell takes over.
 * Gamepad: focus starts on the primary action of each step, B goes one step back.
 *
 * [onImportSources] receives the picked document URI; the caller runs the import + rescan.
 */
@Composable
fun OnboardingScreen(
    onImportSources: (String) -> Unit,
    isRescanning: Boolean,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val downloadDirectory by viewModel.downloadDirectory.collectAsState()
    val gamepadConnected by Gamepad.connected.collectAsState()
    val esdeBusy by viewModel.esdeBusy.collectAsState()
    val totalSteps = if (viewModel.esdeInstalled) 4 else 3
    var step by rememberSaveable { mutableIntStateOf(0) }
    var importStarted by rememberSaveable { mutableStateOf(false) }
    // One requester per step: while the steps cross-fade, the old and the new one are both on screen.
    val stepFocus = remember { List(4) { FocusRequester() } }
    val rootFocus = remember { FocusRequester() }
    val reduce = LocalReduceMotion.current
    // After the sources step: the ES-DE step when it exists, otherwise the tour is over.
    fun leaveSourcesStep() {
        if (viewModel.esdeInstalled) step = 3 else viewModel.finish()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.updateDownloadDirectory(it.toString())
        }
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importStarted = true; onImportSources(it.toString()) }
    }
    val esdePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.configureEsde(context, it.toString())
        }
    }

    // Once the import kicked off and its rescan is running (or already over), move on: the
    // consoles are already in Room, so the ES-DE step can use them while the rescan continues.
    LaunchedEffect(importStarted, isRescanning) {
        if (importStarted && isRescanning && step == 2) leaveSourcesStep()
    }
    // Focus the step's main action once the new content is laid out; if that fails, keep focus
    // on the root so B / Back are still caught here instead of leaving the app.
    LaunchedEffect(step) {
        repeat(2) { withFrameNanos { } }
        if (runCatching { stepFocus[step.coerceIn(0, 3)].requestFocus() }.isFailure) runCatching { rootFocus.requestFocus() }
    }
    BackHandler(enabled = step > 0) { step-- }

    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.ButtonB || event.key == Key.Back) && step > 0) {
                    step--; true
                } else false
            }
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StepDots(step, totalSteps)
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        if (reduce) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            // Forward steps slide in from the right, going back from the left.
                            val direction = if (targetState >= initialState) 1 else -1
                            (fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.FAST)) +
                                slideInHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { (it / 10) * direction }) togetherWith
                                (fadeOut(tween(Motion.FAST)) +
                                    slideOutHorizontally(tween(Motion.FAST, easing = FastOutSlowInEasing)) { -(it / 10) * direction })
                        }
                    },
                    label = "onboarding-step"
                ) { shown ->
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (shown) {
                            0 -> {
                                Image(
                                    painterResource(R.drawable.milou),
                                    contentDescription = null,
                                    modifier = Modifier.widthIn(max = 160.dp).heightIn(max = 120.dp)
                                )
                                Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                                Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Bullet(R.drawable.ic_library, stringResource(R.string.onboarding_welcome_point_1))
                                    Bullet(R.drawable.ic_filter, stringResource(R.string.onboarding_welcome_point_2))
                                    Bullet(R.drawable.ic_folder, stringResource(R.string.onboarding_welcome_point_3))
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OnboardingButton(stringResource(R.string.onboarding_start), primary = true, focus = stepFocus[0], icon = R.drawable.ic_arrow_forward) { step = 1 }
                                }
                            }
                            1 -> {
                                val chosen = downloadDirectory.isNotBlank()
                                IconTile(R.drawable.ic_folder_open, size = 64.dp)
                                Text(stringResource(R.string.onboarding_folder_title), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                                Text(stringResource(R.string.onboarding_folder_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                if (chosen) {
                                    Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(20.dp))
                                            Text(
                                                stringResource(R.string.onboarding_folder_current, FileParsingUtils.toUserReadablePath(downloadDirectory)),
                                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (chosen) {
                                        OnboardingButton(stringResource(R.string.onboarding_continue), primary = true, focus = stepFocus[1], icon = R.drawable.ic_arrow_forward) { step = 2 }
                                        OnboardingButton(stringResource(R.string.onboarding_folder_change), icon = R.drawable.ic_folder_open) { folderPicker.launch(null) }
                                    } else {
                                        OnboardingButton(stringResource(R.string.onboarding_folder_choose), primary = true, focus = stepFocus[1], icon = R.drawable.ic_folder_open) { folderPicker.launch(null) }
                                        OnboardingButton(stringResource(R.string.onboarding_skip)) { step = 2 }
                                    }
                                }
                            }
                            2 -> {
                                IconTile(R.drawable.ic_import, size = 64.dp)
                                Text(stringResource(R.string.onboarding_sources_title), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                                Text(stringResource(R.string.onboarding_sources_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                if (importStarted) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.sources_rescanning), style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OnboardingButton(stringResource(R.string.onboarding_sources_import), primary = true, focus = stepFocus[2], icon = R.drawable.ic_import) {
                                            jsonPicker.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                                        }
                                        OnboardingButton(
                                            stringResource(if (viewModel.esdeInstalled) R.string.onboarding_continue else R.string.onboarding_finish)
                                        ) { leaveSourcesStep() }
                                    }
                                }
                            }
                            else -> {
                                IconTile(R.drawable.ic_frontends, size = 64.dp)
                                Text(stringResource(R.string.onboarding_esde_title), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
                                Text(stringResource(R.string.onboarding_esde_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                if (esdeBusy) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.onboarding_esde_configuring), style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OnboardingButton(stringResource(R.string.onboarding_esde_choose), primary = true, focus = stepFocus[3], icon = R.drawable.ic_folder_open) {
                                            esdePicker.launch(null)
                                        }
                                        OnboardingButton(stringResource(R.string.onboarding_finish)) { viewModel.finish() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (gamepadConnected) {
            GamepadLegend(
                entries = buildList {
                    add(LegendEntry("A", stringResource(R.string.onboarding_select)))
                    if (step > 0) add(LegendEntry("B", stringResource(R.string.onboarding_back)))
                }
            )
        }
    }
}

/** The step counter: the current step's dot stretches into a bar, the finished ones stay softly lit. */
@Composable
private fun StepDots(current: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, motionSpec(Motion.MEDIUM), label = "dot-width")
            val color by animateColorAsState(
                when {
                    i == current -> scheme.primary
                    i < current -> scheme.primary.copy(alpha = 0.45f)
                    else -> scheme.surfaceContainerHighest
                },
                motionSpec(Motion.MEDIUM),
                label = "dot-color"
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.onboarding_step, current + 1, total),
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
        )
    }
}

/** One point of the welcome list: a small icon tile and the line. */
@Composable
private fun Bullet(icon: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, size = 32.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** The step's main action is the accent button, anything else a quiet pill (both 44 dp high, with the focus ring). */
@Composable
private fun OnboardingButton(
    label: String,
    primary: Boolean = false,
    focus: FocusRequester? = null,
    icon: Int? = null,
    onClick: () -> Unit
) {
    val modifier = focus?.let { Modifier.focusRequester(it) } ?: Modifier
    if (primary) {
        PrimaryButton(label, onClick, modifier = modifier, icon = icon)
    } else {
        ActionPill(label, onClick, modifier = modifier.defaultMinSize(minHeight = 44.dp), icon = icon)
    }
}
