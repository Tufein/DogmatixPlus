package com.cortinadev.dogmatix.data.model

/** A download failure's public meaning; translated instructions are supplied by the UI. */
enum class DownloadFailureCategory {
    NETWORK,
    TIMEOUT,
    STORAGE_FULL,
    STORAGE_PERMISSION,
    STORAGE_WRITE,
    HTTP_AUTHENTICATION,
    HTTP_NOT_FOUND,
    HTTP_RATE_LIMITED,
    HTTP_SERVER,
    HTTP_OTHER,
    TORRENT,
    EXTRACTION,
    VERIFICATION,
    UNKNOWN
}

/** Contains no provider text, paths, URLs, account names or credentials. */
data class DownloadFailure(
    val category: DownloadFailureCategory,
    val httpStatusCode: Int? = null
)
