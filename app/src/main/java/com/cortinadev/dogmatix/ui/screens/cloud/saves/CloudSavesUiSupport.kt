package com.cortinadev.dogmatix.ui.screens.cloud.saves

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.CloudSavesEntryPoint
import com.cortinadev.dogmatix.data.service.CloudSavesService
import com.cortinadev.dogmatix.data.service.CloudStatusService
import com.cortinadev.dogmatix.util.SaveKind
import dagger.hilt.android.EntryPointAccessors

/*
 * Shared bits of the 5.0 cloud-saves pieces (section, shelf, top-bar icon, conflict screenshots).
 */

/** The app's [CloudSavesService], for composables outside a ViewModel. */
@Composable
internal fun rememberCloudSavesService(): CloudSavesService {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, CloudSavesEntryPoint::class.java).cloudSaves() }
}

/** The app's [CloudStatusService], for the top-bar icon. */
@Composable
internal fun rememberCloudStatusService(): CloudStatusService {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, CloudSavesEntryPoint::class.java).cloudStatus() }
}

/** "2 hr. ago" (abbreviated, localised); "Just now" under a minute; null without a time. */
@Composable
internal fun relativeTime(millis: Long?): String? {
    if (millis == null || millis <= 0L) return null
    val now = System.currentTimeMillis()
    if (kotlin.math.abs(now - millis) < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.csave_just_now)
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

@Composable
internal fun kindLabel(kind: SaveKind): String =
    stringResource(if (kind == SaveKind.STATE) R.string.save_sync_kind_state else R.string.save_sync_kind_save)

/** Icon of a save (memory card) or a state (bookmark: a moment kept). */
internal fun kindIcon(kind: SaveKind): Int = if (kind == SaveKind.STATE) R.drawable.ic_bookmark else R.drawable.ic_save
