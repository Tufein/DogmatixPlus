package com.tufein.valkyr.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tufein.valkyr.R
import com.tufein.valkyr.ui.components.ActionPill
import com.tufein.valkyr.ui.components.ActionTone
import com.tufein.valkyr.ui.components.MeterBar
import com.tufein.valkyr.ui.components.NavChevron
import com.tufein.valkyr.ui.navigation.NavRoutes
import com.tufein.valkyr.ui.screens.cloud.saves.ContinuePlayingViewModel
import com.tufein.valkyr.util.SettingKeys
import com.tufein.valkyr.util.SettingsLayout

/** The rows of the Frontends card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.frontendsCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.FRONTEND_CHECK) {
        SettingRow(
            icon = R.drawable.ic_frontends,
            title = stringResource(R.string.nav_frontends),
            hint = stringResource(R.string.tools_frontends_hint),
            onClick = { navController.navigate(NavRoutes.Frontends.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.FRONTEND_SHORTCUTS) {
        SettingRow(
            icon = R.drawable.ic_shortcut,
            title = stringResource(R.string.settings_frontend_shortcuts),
            hint = stringResource(R.string.settings_frontend_shortcuts_hint),
            onClick = { viewModel.onDeployFrontendShortcuts(context) },
        ) {
            PillButton(stringResource(R.string.settings_frontend_shortcuts_action)) {
                viewModel.onDeployFrontendShortcuts(context)
            }
        }
    },
    SettingsRow(key = SettingKeys.ESDE) {
        SettingRow(
            icon = R.drawable.ic_frontends,
            title = stringResource(R.string.settings_esde),
            hint = readableFolder(ui.esdeDirectory) ?: stringResource(R.string.settings_esde_hint),
            onClick = { runEsdeSetup() },
        ) {
            PillButton(stringResource(R.string.settings_esde_action), { runEsdeSetup() })
        }
    },
    SettingsRow(key = SettingKeys.IISU) {
        SettingRow(
            icon = R.drawable.ic_grid,
            title = stringResource(R.string.settings_iisu),
            hint = readableFolder(ui.iisuDirectory) ?: stringResource(R.string.settings_iisu_hint),
            onClick = { runIisuSetup() },
        ) {
            PillButton(stringResource(R.string.settings_iisu_action), { runIisuSetup() })
        }
    },
    SettingsRow(key = SettingKeys.DAIJISHO) {
        SettingRow(
            icon = R.drawable.ic_dashboard,
            title = stringResource(R.string.settings_daijisho),
            hint = stringResource(R.string.settings_daijisho_hint),
            onClick = { viewModel.onPrepareDaijisho(context) },
        ) {
            PillButton(stringResource(R.string.settings_daijisho_action)) { viewModel.onPrepareDaijisho(context) }
        }
    },
    SettingsRow(key = SettingKeys.EMULATORS) { EmulatorChoiceSettingsRow(onWizard = { navController.navigate(it) }) },
    SettingsRow(key = SettingKeys.COCOON) {
        SettingRow(
            icon = R.drawable.ic_help,
            title = stringResource(R.string.settings44_cocoon),
            hint = stringResource(R.string.settings_cocoon_hint),
            onClick = { dialogs.showCocoonHelp = true },
        ) {
            PillButton(stringResource(R.string.settings_daijisho_action)) { dialogs.showCocoonHelp = true }
        }
    }
)

/** The rows of the Cloud card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.cloudCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.RETROACHIEVEMENTS) {
        SettingRow(
            icon = R.drawable.ic_trophy,
            title = stringResource(R.string.nav_ra),
            hint = stringResource(R.string.tools_ra_hint),
            onClick = { navController.navigate(NavRoutes.RetroAchievements.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.CLOUD) {
        SettingRow(
            icon = R.drawable.ic_cloud,
            title = stringResource(R.string.nav_cloud),
            hint = stringResource(R.string.settings_v5_cloud_hint),
            onClick = { navController.navigate(NavRoutes.Cloud.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.SHELF) {
        val shelf: ContinuePlayingViewModel = hiltViewModel()
        val shelfOn by shelf.enabled.collectAsStateWithLifecycle()
        SettingRow(
            icon = R.drawable.ic_play_circle,
            title = stringResource(R.string.csave_shelf_setting_title),
            hint = stringResource(R.string.csave_shelf_setting_hint),
            onClick = { shelf.setEnabled(!shelfOn) },
            onAdjust = { shelf.setEnabled(it > 0) },
        ) { ThemedSwitch(shelfOn) { shelf.setEnabled(it) } }
    },
    SettingsRow(key = SettingKeys.ROMM) {
        SettingRow(
            icon = R.drawable.ic_server,
            title = stringResource(R.string.settings_romm),
            hint = if (!ui.rommEnabled) stringResource(R.string.fix54_romm_off_pill) else ui.rommUrl.ifBlank { stringResource(R.string.settings_romm_hint) },
            onClick = { navController.navigate(NavRoutes.Romm.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.SAVE_SYNC) {
        SettingRow(
            icon = R.drawable.ic_cloud_sync,
            title = stringResource(R.string.settings_save_sync),
            hint = stringResource(R.string.settings_save_sync_hint),
            onClick = { navController.navigate(NavRoutes.SaveSync.route) },
        ) { NavChevron() }
    }
)

/** The rows of the Backup card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.backupCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.NEW_HANDHELD) {
        SettingRow(
            icon = R.drawable.ic_devices,
            title = stringResource(R.string.wizard35_title),
            hint = stringResource(R.string.wizard35_hint),
            onClick = { navController.navigate(NavRoutes.NewHandheld.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.RECOVERY) {
        SettingRow(
            icon = R.drawable.ic_history,
            title = stringResource(R.string.recovery_title),
            hint = stringResource(R.string.recovery_hint),
            onClick = { navController.navigate(NavRoutes.Recovery.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.BACKUP_EXPORT) {
        SettingRow(
            icon = R.drawable.ic_file_export,
            title = stringResource(R.string.settings_backup_export),
            hint = stringResource(R.string.settings_backup_export_hint),
            onClick = { exportBackup() },
        ) {
            ActionPill(stringResource(R.string.settings_backup_export_action), { exportBackup() }, icon = R.drawable.ic_file_export)
        }
    },
    SettingsRow(key = SettingKeys.AUTO_BACKUP) {
        SettingRow(
            icon = R.drawable.ic_update,
            title = stringResource(R.string.settings_auto_backup),
            hint = when {
                appPrefs.autoBackup && appPrefs.autoBackupDir.isBlank() -> stringResource(R.string.settings_auto_backup_pick)
                appPrefs.autoBackupLast > 0 -> stringResource(R.string.settings_auto_backup_last, DateUtils.formatDateTime(context, appPrefs.autoBackupLast, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))
                else -> stringResource(R.string.settings_auto_backup_hint)
            },
            onClick = { extra.setAutoBackup(context, !appPrefs.autoBackup) },
            onAdjust = { extra.setAutoBackup(context, it > 0) },
        ) { ThemedSwitch(appPrefs.autoBackup) { extra.setAutoBackup(context, it) } }
    },
    SettingsRow(key = SettingKeys.AUTO_BACKUP_FOLDER) {
        SettingRow(
            icon = R.drawable.ic_folder,
            title = stringResource(R.string.settings_auto_backup_folder),
            hint = readableFolder(appPrefs.autoBackupDir) ?: stringResource(R.string.settings_not_set),
            onClick = { backupDirLauncher.launch(null) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionPill(stringResource(R.string.settings_change), { backupDirLauncher.launch(null) }, icon = R.drawable.ic_folder_open)
                if (appPrefs.autoBackupDir.isNotBlank()) {
                    ActionPill(stringResource(R.string.auto_backup_now), { extra.backupNow(context) }, icon = R.drawable.ic_backup, tone = ActionTone.Accent)
                }
            }
        }
    },
    SettingsRow(key = SettingKeys.BACKUP_IMPORT) {
        SettingRow(
            icon = R.drawable.ic_restore,
            title = stringResource(R.string.settings_backup_import),
            hint = stringResource(R.string.settings_backup_import_hint),
            onClick = { importBackup() },
        ) {
            ActionPill(stringResource(R.string.settings_backup_import_action), { importBackup() }, icon = R.drawable.ic_restore)
        }
    },
    SettingsRow(key = SettingKeys.CLOUD_BACKUP) {
        SettingRow(
            icon = R.drawable.ic_cloud_upload,
            title = stringResource(R.string.dav_title),
            hint = stringResource(R.string.find8_hint_webdav),
            onClick = { navController.navigate(NavRoutes.CloudBackup.route) },
        ) { NavChevron() }
    }
)

/** The rows of the App card (see [SettingsLayout.groups] for their order). */
internal fun SettingsEnv.appCardRows(): List<SettingsRow> = listOf(
    SettingsRow(key = SettingKeys.PRERELEASES) {
        SettingRow(
            icon = R.drawable.ic_science,
            title = stringResource(R.string.settings_prereleases),
            hint = stringResource(R.string.settings_prereleases_hint),
            onClick = { extra.setPreReleases(context, !appPrefs.preReleases) },
            onAdjust = { extra.setPreReleases(context, it > 0) },
        ) { ThemedSwitch(appPrefs.preReleases) { extra.setPreReleases(context, it) } }
    },
    SettingsRow(key = SettingKeys.UPDATE_CHECK) {
        SettingRow(
            icon = R.drawable.ic_rocket,
            title = stringResource(R.string.settings_update_check),
            hint = updateProgress?.let { stringResource(R.string.update_downloading, (it * 100).toInt()) } ?: stringResource(R.string.settings_update_check_hint),
            onClick = { extra.checkForUpdates(context) },
            below = if (updateProgress != null) {
                { MeterBar(updateProgress ?: 0f, modifier = Modifier.padding(top = 6.dp), height = 6.dp) }
            } else null
        ) { ActionPill(stringResource(R.string.settings43_action_check), { extra.checkForUpdates(context) }, icon = R.drawable.ic_sync) }
    },
    SettingsRow(key = SettingKeys.DIAGNOSTICS) {
        SettingRow(
            icon = R.drawable.ic_bug,
            title = stringResource(R.string.settings_diagnostics),
            hint = stringResource(R.string.settings_diagnostics_hint),
            onClick = { extra.shareDiagnostics(context) },
        ) { ActionPill(stringResource(R.string.settings43_action_share), { extra.shareDiagnostics(context) }, icon = R.drawable.ic_share) }
    },
    SettingsRow(key = SettingKeys.REPORT) {
        SettingRow(
            icon = R.drawable.ic_bug,
            title = stringResource(R.string.report30_title),
            hint = stringResource(R.string.report30_hint),
            onClick = { navController.navigate(NavRoutes.ReportProblem.route) },
        ) { NavChevron() }
    },
    SettingsRow(key = SettingKeys.ABOUT) {
        SettingRow(
            icon = R.drawable.ic_info,
            title = stringResource(R.string.brand40_about_title),
            hint = stringResource(R.string.brand40_settings_hint),
            onClick = { navController.navigate(NavRoutes.Contact.route) },
        ) { NavChevron() }
    }
)
