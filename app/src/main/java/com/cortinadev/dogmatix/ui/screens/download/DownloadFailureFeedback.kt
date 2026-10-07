package com.cortinadev.dogmatix.ui.screens.download

import androidx.annotation.StringRes
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory

/** A safe, translated explanation and the most useful next step; never raw URLs or exception text. */
internal data class DownloadFailureFeedback(
    @param:StringRes val message: Int,
    val action: DownloadRecoveryAction = DownloadRecoveryAction.RETRY
)

internal enum class DownloadRecoveryAction(@param:StringRes val label: Int, val icon: Int) {
    RETRY(R.string.download_retry, R.drawable.ic_retry),
    SETTINGS(R.string.download_fix_folder, R.drawable.ic_settings),
    STORAGE(R.string.download_free_storage, R.drawable.ic_storage),
    SOURCES(R.string.download_check_sources, R.drawable.ic_globe)
}

internal fun DownloadFailure?.feedback(): DownloadFailureFeedback = when (this?.category) {
    DownloadFailureCategory.NETWORK -> DownloadFailureFeedback(R.string.download_error_network)
    DownloadFailureCategory.TIMEOUT -> DownloadFailureFeedback(R.string.download_error_timeout)
    DownloadFailureCategory.STORAGE_FULL -> DownloadFailureFeedback(R.string.download_error_storage_full, DownloadRecoveryAction.STORAGE)
    DownloadFailureCategory.STORAGE_PERMISSION -> DownloadFailureFeedback(R.string.download_error_storage_permission, DownloadRecoveryAction.SETTINGS)
    DownloadFailureCategory.STORAGE_WRITE -> DownloadFailureFeedback(R.string.download_error_storage_write, DownloadRecoveryAction.SETTINGS)
    DownloadFailureCategory.HTTP_AUTHENTICATION -> DownloadFailureFeedback(R.string.download_error_authentication, DownloadRecoveryAction.SOURCES)
    DownloadFailureCategory.HTTP_NOT_FOUND -> DownloadFailureFeedback(R.string.download_error_not_found, DownloadRecoveryAction.SOURCES)
    DownloadFailureCategory.HTTP_RATE_LIMITED -> DownloadFailureFeedback(R.string.download_error_rate_limit)
    DownloadFailureCategory.HTTP_SERVER -> DownloadFailureFeedback(R.string.download_error_server)
    DownloadFailureCategory.HTTP_OTHER -> DownloadFailureFeedback(R.string.download_error_http, DownloadRecoveryAction.SOURCES)
    DownloadFailureCategory.TORRENT -> DownloadFailureFeedback(R.string.download_error_torrent)
    DownloadFailureCategory.EXTRACTION -> DownloadFailureFeedback(R.string.download_error_extraction)
    DownloadFailureCategory.VERIFICATION -> DownloadFailureFeedback(R.string.download_error_verification)
    DownloadFailureCategory.UNKNOWN, null -> DownloadFailureFeedback(R.string.download_error_unknown)
}
