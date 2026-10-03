package com.cortinadev.dogmatix.ui.screens.share

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.SourceScanService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.SharedLink
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShareTargetViewModel @Inject constructor(
    consoleRepository: ConsoleRepository,
    private val sources: SourcesRepository,
    private val scanService: SourceScanService,
    private val downloadService: DownloadService
) : ViewModel() {
    val consoles: StateFlow<List<ConsoleEntity>> = consoleRepository.getAllConsoles()
        .map { list -> list.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Downloads a shared file straight into [consoleId]'s folder. */
    fun download(context: Context, link: SharedLink, consoleId: String) {
        val fileName = link.fileName ?: return
        val (name, _) = FileParsingUtils.extractNameAndTags(fileName.substringBeforeLast('.'))
        downloadService.startDownload(
            DownloadableFileEntity(
                name = name.ifBlank { fileName }, fileName = fileName, consoleId = consoleId, downloadUrl = link.url,
                fileExtension = fileName.substringAfterLast('.', "")
            )
        )
        ToastUtil.showInfo(context.applicationContext, context.getString(R.string.download_started, fileName))
    }

    /** Adds a shared link (magnet, torrent, web folder; or a file's folder) as a source of [consoleId] and scans that console. */
    fun addSource(context: Context, url: String, consoleId: String) {
        val app = context.applicationContext
        viewModelScope.launch {
            if (sources.addUrl(consoleId, url, ContentType.GAME)) {
                ToastUtil.showSuccess(app, app.getString(R.string.share_source_added))
                scanService.scanConsole(consoleId)
            } else ToastUtil.showError(app, app.getString(R.string.sources_url_not_added))
        }
    }
}

/**
 * What to do with a link shared to the app: pick the console it belongs to, then download the file
 * into that console's folder, or add the link (or the folder the file is in) as a source.
 */
@Composable
fun ShareTargetDialog(link: SharedLink, onDismiss: () -> Unit, viewModel: ShareTargetViewModel = hiltViewModel()) {
    val consoles by viewModel.consoles.collectAsState()
    val context = LocalContext.current
    var consoleId by remember { mutableStateOf<String?>(null) }
    val cancelFocus = rememberInitialFocus()
    val folderUrl = if (link.kind == SharedLink.Kind.FILE) link.url.substringBeforeLast('/') + "/" else null
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(link.fileName ?: link.url, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(when (link.kind) {
                        SharedLink.Kind.FILE -> R.string.share_kind_file
                        SharedLink.Kind.MAGNET, SharedLink.Kind.TORRENT -> R.string.share_kind_torrent
                        SharedLink.Kind.DIRECTORY -> R.string.share_kind_folder
                    }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (consoles.isEmpty()) {
                    Text(stringResource(R.string.share_no_consoles), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.share_pick_console), style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        consoles.forEach { c ->
                            DialogButton(
                                text = (if (consoleId == c.id) "● " else "") + ConsoleFormatter.getConsoleDisplayName(c.id),
                                onClick = { consoleId = c.id }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val id = consoleId
            if (link.kind == SharedLink.Kind.FILE) {
                DialogButton(stringResource(R.string.share_download), onClick = { id?.let { viewModel.download(context, link, it); onDismiss() } }, enabled = id != null)
                DialogButton(stringResource(R.string.share_add_folder), onClick = { id?.let { viewModel.addSource(context, folderUrl!!, it); onDismiss() } }, enabled = id != null)
            } else {
                DialogButton(stringResource(R.string.share_add_source), onClick = { id?.let { viewModel.addSource(context, link.url, it); onDismiss() } }, enabled = id != null)
            }
        },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}
