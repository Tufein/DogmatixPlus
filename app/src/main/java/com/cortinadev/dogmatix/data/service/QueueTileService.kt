package com.cortinadev.dogmatix.data.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.QueueTile
import com.cortinadev.dogmatix.util.QueueTileMode
import com.cortinadev.dogmatix.util.QueueTileTap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The *Downloads* tile of the Quick Settings panel (7.0): active, paused or idle, with the number
 * of downloads in the queue, and a tap that pauses or resumes the queue through the same hold as
 * *Downloads → Hold the queue* ([DownloadGate.setHeld]: running downloads finish, nothing new
 * starts; the hold survives a restart). With nothing queued a tap opens the Downloads section.
 *
 * The tile follows the queue only while the panel is open ([onStartListening] to
 * [onStopListening]), so it costs nothing in the background.
 */
@AndroidEntryPoint
class QueueTileService : TileService() {

    @Inject lateinit var downloadService: DownloadService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = scope.launch {
            combine(downloadService.downloads, downloadService.gate.held) { list, held -> QueueTile.state(list, held) }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val gate = downloadService.gate
        when (QueueTile.tap(QueueTile.state(downloadService.downloads.value, gate.held.value))) {
            QueueTileTap.HOLD -> gate.setHeld(true)
            QueueTileTap.RESUME -> gate.setHeld(false)
            QueueTileTap.OPEN_DOWNLOADS -> openDownloads()
        }
    }

    private fun render(state: QueueTile.State) {
        val tile = qsTile ?: return
        tile.state = if (state.mode == QueueTileMode.ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick7_tile_label)
        tile.subtitle = when (state.mode) {
            QueueTileMode.ACTIVE -> resources.getQuantityString(R.plurals.quick7_tile_active, state.queued, state.queued)
            QueueTileMode.PAUSED ->
                if (state.queued > 0) resources.getQuantityString(R.plurals.quick7_tile_paused, state.queued, state.queued)
                else getString(R.string.quick7_tile_paused_empty)
            QueueTileMode.IDLE -> getString(R.string.quick7_tile_idle)
        }
        tile.icon = Icon.createWithResource(this, if (state.mode == QueueTileMode.PAUSED) R.drawable.ic_pause else R.drawable.ic_download)
        tile.updateTile()
    }

    private fun openDownloads() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, NavRoutes.Downloads.route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
