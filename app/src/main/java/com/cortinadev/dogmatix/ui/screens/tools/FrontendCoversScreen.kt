package com.cortinadev.dogmatix.ui.screens.tools

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.CoversRunState
import com.cortinadev.dogmatix.ui.components.ActionPill
import com.cortinadev.dogmatix.ui.components.ActionTone
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.MeterBar
import com.cortinadev.dogmatix.ui.components.Panel
import com.cortinadev.dogmatix.ui.components.Pill
import com.cortinadev.dogmatix.ui.components.PillTone
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.ui.theme.tabular
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.FrontendCovers
import java.text.NumberFormat

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** The launcher's name as it spells it (not translated), or "Another launcher". */
@Composable
internal fun frontendName(target: FrontendCovers.Target): String = when (target) {
    FrontendCovers.Target.IISU -> "iiSU"
    FrontendCovers.Target.DAIJISHO -> "Daijishō"
    FrontendCovers.Target.OTHER -> stringResource(R.string.covers32_other)
}

/**
 * Tools -> Covers for launchers (3.1.0 for iiSU and Daijishō, 3.2.0 for any other launcher): pick
 * the launcher, the folder it reads cover images from (the hint says which), how the files are
 * named and arranged (each layout with an example from a game of the library) and whether covers
 * already there may be replaced; the preview says how many covers would be written and where
 * before anything is written. The run can be stopped; what was written stays.
 */
@Composable
fun FrontendCoversScreen(viewModel: FrontendCoversViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val run = ui.run
    val settings = ui.settings
    val preview = ui.preview
    val firstFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            viewModel.setFolder(it.toString())
        }
    }

    PublishLegend(
        if (focused) listOf(
            LegendEntry("A", stringResource(R.string.pad_change)),
            LegendEntry("B", stringResource(R.string.pad_back))
        ) else null
    )
    val track = Modifier.onFocusChanged { focused = it.isFocused }
    val canWrite = !run.running && preview != null && preview.writable && (preview.plan?.items?.isNotEmpty() == true)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.covers32_title), icon = R.drawable.ic_image, subtitle = stringResource(R.string.covers32_subtitle))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "what") {
                InfoCard(
                    listOf(stringResource(R.string.covers31_what_title), stringResource(R.string.covers32_what_body)),
                    modifier = Modifier.focusRequester(firstFocus).focusableCard(),
                    icon = R.drawable.ic_info
                )
            }

            item(key = "h-frontend") { SectionHeader(stringResource(R.string.covers31_section_frontend), icon = R.drawable.ic_frontends) }
            FrontendCovers.Target.entries.forEach { t ->
                item(key = "t-" + t.key) {
                    ToolRow(
                        title = frontendName(t),
                        lines = listOf(stringResource(folderHint(t))),
                        onClick = { viewModel.selectTarget(t) },
                        modifier = track,
                        icon = R.drawable.ic_frontends,
                        trailing = { RadioButton(selected = ui.target == t, onClick = null, enabled = !run.running) }
                    )
                }
            }

            item(key = "h-folder") { SectionHeader(stringResource(R.string.covers31_section_folder), icon = R.drawable.ic_folder) }
            item(key = "folder") {
                val notWritable = preview != null && preview.folderSet && !preview.writable
                ToolRow(
                    title = if (ui.target == FrontendCovers.Target.OTHER) stringResource(R.string.covers32_folder_title_other)
                        else stringResource(R.string.covers31_folder_title, frontendName(ui.target)),
                    lines = listOfNotNull(
                        settings?.folder?.takeIf { it.isNotBlank() }?.let { FileParsingUtils.toUserReadablePath(it) }
                            ?: stringResource(R.string.covers31_folder_none),
                        if (notWritable) stringResource(R.string.covers31_folder_not_writable) else null,
                        stringResource(folderHint(ui.target))
                    ),
                    onClick = { if (!run.running) folderPicker.launch(null) },
                    modifier = track,
                    badge = if (notWritable) ({ Pill(stringResource(R.string.covers31_needs_folder), tone = PillTone.Warning, icon = R.drawable.ic_warning) }) else null,
                    icon = R.drawable.ic_folder_open,
                    trailing = {
                        ActionPill(
                            stringResource(if (settings?.folder.isNullOrBlank()) R.string.covers31_pick else R.string.covers31_change),
                            { folderPicker.launch(null) },
                            icon = R.drawable.ic_folder_open,
                            enabled = !run.running
                        )
                    }
                )
            }

            item(key = "h-names") {
                SectionHeader(
                    stringResource(R.string.covers31_section_names),
                    subtitle = stringResource(R.string.covers32_names_hint, stringResource(layoutTitle(FrontendCovers.layoutsFor(ui.target).first()))),
                    icon = R.drawable.ic_label
                )
            }
            FrontendCovers.layoutsFor(ui.target).forEach { layout ->
                item(key = "l-" + layout.key) {
                    ToolRow(
                        title = stringResource(layoutTitle(layout)),
                        lines = listOf(stringResource(layoutHint(layout), exampleFor(ui.sample, layout))),
                        onClick = { if (!run.running) viewModel.setLayout(layout) },
                        modifier = track,
                        trailing = { RadioButton(selected = settings?.layout == layout, onClick = null, enabled = !run.running) }
                    )
                }
            }
            item(key = "overwrite") {
                val on = settings?.overwrite == true
                ToolRow(
                    title = stringResource(R.string.covers31_overwrite_title),
                    lines = listOf(stringResource(R.string.covers31_overwrite_hint)),
                    onClick = { if (!run.running) viewModel.setOverwrite(!on) },
                    modifier = track,
                    icon = R.drawable.ic_swap_horiz,
                    trailing = { ThemedSwitch(on) { if (!run.running) viewModel.setOverwrite(it) } }
                )
            }

            item(key = "h-preview") { SectionHeader(stringResource(R.string.covers31_section_preview), icon = R.drawable.ic_visibility) }
            item(key = "preview") { PreviewCard(ui) }
            item(key = "run") {
                if (run.running) RunningCard(run, viewModel::stop)
                else ToolsActions {
                    ActionPill(
                        stringResource(R.string.covers31_write),
                        onClick = viewModel::write,
                        icon = R.drawable.ic_play_arrow,
                        tone = ActionTone.Accent,
                        enabled = canWrite
                    )
                    ActionPill(stringResource(R.string.covers31_refresh), viewModel::refresh, icon = R.drawable.ic_retry, enabled = !ui.previewLoading)
                }
            }
            if (!run.running && run.target == ui.target && (run.finished || run.problem != null || run.done > 0)) {
                item(key = "result") { ResultCard(run) }
            }
        }
    }
}

private fun folderHint(target: FrontendCovers.Target): Int = when (target) {
    FrontendCovers.Target.IISU -> R.string.covers31_hint_iisu
    FrontendCovers.Target.DAIJISHO -> R.string.covers31_hint_daijisho
    FrontendCovers.Target.OTHER -> R.string.covers32_hint_other
}

private fun layoutTitle(layout: FrontendCovers.Layout): Int = when (layout) {
    FrontendCovers.Layout.NAME_WITHOUT_EXTENSION -> R.string.covers31_layout_stem
    FrontendCovers.Layout.NAME_WITH_EXTENSION -> R.string.covers31_layout_full
    FrontendCovers.Layout.FLAT -> R.string.covers32_layout_flat
    FrontendCovers.Layout.NAMED_BOXARTS -> R.string.covers32_layout_boxarts
    FrontendCovers.Layout.PEGASUS -> R.string.covers32_layout_pegasus
    FrontendCovers.Layout.GAMELIST_IMAGES -> R.string.covers32_layout_gamelist
}

/** The layout's one-line explanation; it takes the example path as its argument. */
private fun layoutHint(layout: FrontendCovers.Layout): Int = when (layout) {
    FrontendCovers.Layout.NAME_WITHOUT_EXTENSION -> R.string.covers31_layout_stem_hint
    FrontendCovers.Layout.NAME_WITH_EXTENSION -> R.string.covers31_layout_full_hint
    FrontendCovers.Layout.FLAT -> R.string.covers32_layout_flat_hint
    FrontendCovers.Layout.NAMED_BOXARTS -> R.string.covers32_layout_boxarts_hint
    FrontendCovers.Layout.PEGASUS -> R.string.covers32_layout_pegasus_hint
    FrontendCovers.Layout.GAMELIST_IMAGES -> R.string.covers32_layout_gamelist_hint
}

private val EXAMPLE_GAME = FrontendCovers.Game("", "gba", "Game (Europe).gba", "Game")

/** An example cover path in [layout]: from a game of the library ([sample]), else a made-up one. */
private fun exampleFor(sample: FrontendCovers.Game?, layout: FrontendCovers.Layout): String =
    sample?.let { FrontendCovers.coverPath(it, layout) } ?: FrontendCovers.coverPath(EXAMPLE_GAME, layout).orEmpty()

/** How many covers a run would write, where, and what it leaves alone. */
@Composable
private fun PreviewCard(ui: FrontendCoversUi) {
    val preview = ui.preview
    val lines: List<String> = when {
        ui.previewLoading -> listOf(stringResource(R.string.covers31_preview_loading))
        ui.previewFailed || preview == null -> listOf(stringResource(R.string.covers31_preview_failed))
        !preview.folderSet -> listOf(stringResource(R.string.covers31_preview_no_folder))
        !preview.writable -> listOf(stringResource(R.string.covers31_folder_not_writable))
        preview.games == 0 -> listOf(stringResource(R.string.covers31_preview_no_games))
        else -> buildList {
            val plan = preview.plan
            val items = plan?.items.orEmpty()
            if (items.isEmpty()) add(stringResource(R.string.covers31_preview_nothing))
            else {
                add(pluralStringResource(R.plurals.covers31_preview_count, items.size, count(items.size), preview.folderDisplay))
                val example = "${preview.folderDisplay}/${plan!!.example.orEmpty()}"
                add(
                    if (preview.layout.perSystem) stringResource(R.string.covers31_preview_example, count(plan.systems), example)
                    else stringResource(R.string.covers32_preview_example_flat, example)
                )
                val replacing = items.count { it.replaces }
                if (replacing > 0) add(stringResource(R.string.covers31_preview_replace, count(replacing)))
                add(stringResource(R.string.covers31_preview_sources))
            }
            if ((plan?.kept ?: 0) > 0) add(stringResource(R.string.covers31_preview_kept, count(plan!!.kept)))
            val shared = plan?.shared ?: 0
            val otherSkipped = (plan?.skipped ?: 0) - shared
            if (otherSkipped > 0) add(stringResource(R.string.covers31_preview_skipped, count(otherSkipped)))
            if (shared > 0) add(stringResource(R.string.covers32_preview_shared, count(shared)))
            if (preview.outside > 0) add(stringResource(R.string.covers31_preview_outside, count(preview.outside)))
            if (preview.profileLimited) add(stringResource(R.string.covers31_preview_profile))
        }
    }
    val ready = preview != null && preview.writable && preview.plan?.items?.isNotEmpty() == true
    InfoCard(
        lines,
        modifier = Modifier.focusableCard(),
        accent = ready && !ui.previewLoading,
        icon = R.drawable.ic_image,
        danger = !ui.previewLoading && (ui.previewFailed || (preview != null && preview.folderSet && !preview.writable))
    )
}

/** Progress of a run and a way to stop it (what was written stays). */
@Composable
private fun RunningCard(run: CoversRunState, onStop: () -> Unit) {
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (run.total == 0) stringResource(R.string.covers31_preparing)
                    else stringResource(R.string.covers31_running, count(run.done), count(run.total)),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurface
                )
                MeterBar(if (run.total == 0) 0f else run.done.toFloat() / run.total)
            }
            ActionPill(stringResource(R.string.covers31_stop), onStop, icon = R.drawable.ic_close, tone = ActionTone.Danger)
        }
    }
}

/** The outcome of the last run, or why it could not run. */
@Composable
private fun ResultCard(run: CoversRunState) {
    val problem = run.problem
    if (problem != null) {
        InfoCard(
            listOf(
                stringResource(
                    when (problem) {
                        CoversRunState.Problem.NO_FOLDER -> R.string.covers31_preview_no_folder
                        CoversRunState.Problem.NOT_WRITABLE -> R.string.covers31_folder_not_writable
                        CoversRunState.Problem.NOTHING_ON_DEVICE -> R.string.covers31_preview_no_games
                        CoversRunState.Problem.FAILED -> R.string.covers31_problem_failed
                    }
                )
            ),
            modifier = Modifier.focusableCard(),
            icon = R.drawable.ic_warning,
            danger = problem == CoversRunState.Problem.FAILED
        )
        return
    }
    val lines = buildList {
        add(stringResource(if (run.finished) R.string.covers31_result_title else R.string.covers31_result_stopped))
        add(stringResource(R.string.covers31_result_written, count(run.written)))
        if (run.replaced > 0) add(stringResource(R.string.covers31_result_replaced, count(run.replaced)))
        if (run.kept > 0) add(stringResource(R.string.covers31_result_kept, count(run.kept)))
        if (run.noCover > 0) add(stringResource(R.string.covers31_result_nocover, count(run.noCover)))
        if (run.failed > 0) add(stringResource(R.string.covers31_result_failed, count(run.failed)))
    }
    InfoCard(
        lines,
        modifier = Modifier.focusableCard(),
        accent = run.finished && run.written + run.replaced > 0,
        icon = if (run.finished) R.drawable.ic_check_circle else R.drawable.ic_info,
        danger = run.failed > 0
    )
}
