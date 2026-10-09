package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadFailure
import com.cortinadev.dogmatix.data.model.DownloadFailureCategory

/** The only error fields written to history: a known category, optional HTTP code and epoch time. */
object PersistedDownloadFailure {
    fun restore(category: String?, httpStatusCode: Int?): DownloadFailure? {
        if (category == null) return null
        val safeCategory = DownloadFailureCategory.entries.firstOrNull { it.name == category }
            ?: DownloadFailureCategory.UNKNOWN
        return DownloadFailure(safeCategory, httpCode(httpStatusCode))
    }

    fun httpCode(code: Int?): Int? = code?.takeIf { it in 100..599 }

    fun timestamp(epochMillis: Long?): Long? = epochMillis?.takeIf { it > 0L }
}
