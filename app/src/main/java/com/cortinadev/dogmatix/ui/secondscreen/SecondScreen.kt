package com.cortinadev.dogmatix.ui.secondscreen

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.ui.components.stripExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the second screen shows: the game under the cursor in the library (set by Home). */
object SecondScreenState {
    private val _focused = MutableStateFlow<DownloadableFileWithTags?>(null)
    val focused: StateFlow<DownloadableFileWithTags?> = _focused.asStateFlow()
    fun focus(item: DownloadableFileWithTags?) { _focused.value = item }
}

/**
 * A second display (a dual-screen handheld such as the AYN Thor, or a TV over USB-C) shows the
 * game under the cursor — cover, title and description — above the downloads in progress, while
 * the app stays on the main screen. Uses Android's presentation API, so it works on any device
 * that reports a presentation display.
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
            // The display context does not inherit the in-app language; take the activity's.
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocales(activity.resources.configuration.locales) }
            )
            setContentView(ComposeView(localized).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(saved)
                setContent { MaterialTheme(colorScheme = darkColorScheme()) { Content(downloads, lookup) } }
            })
        }
    }
}

private val Accent = Color(0xFFFF8A1F)

@Composable
private fun Content(downloads: StateFlow<List<DownloadItemModel>>, lookup: suspend (DownloadableFileWithTags) -> GameDetails?) {
    val focused by SecondScreenState.focused.collectAsState()
    val list by downloads.collectAsState()
    var details by remember { mutableStateOf<GameDetails?>(null) }
    LaunchedEffect(focused) {
        details = null
        val item = focused ?: return@LaunchedEffect
        delay(400)   // the cursor moving through a list should not fire a lookup per row
        details = runCatching { lookup(item) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF111214)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val item = focused
        if (item == null) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Accent)
            Text(stringResource(R.string.second_screen_idle), style = MaterialTheme.typography.bodyLarge, color = Color(0xB3FFFFFF))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(260.dp).height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF26282C))) {
                    details?.imageUrl?.takeIf { it.isNotBlank() }?.let {
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(item.file.name),
                        style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.tags.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = Accent, maxLines = 1)
                    Text(details?.description.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Color(0xD9FFFFFF), maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val active = list.filter { !it.isFinished && it.status != DownloadStatus.PAUSED }
        if (active.isNotEmpty()) {
            Text(stringResource(R.string.second_screen_downloads, active.size), style = MaterialTheme.typography.titleMedium, color = Color.White)
            active.take(4).forEach { d ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stripExtension(d.name), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.width(220.dp).height(6.dp), color = Accent)
                    Text("${(d.progress * 100).toInt()}%", color = Color(0xB3FFFFFF))
                }
            }
        }
    }
}
