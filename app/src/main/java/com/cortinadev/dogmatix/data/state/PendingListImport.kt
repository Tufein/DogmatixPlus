package com.cortinadev.dogmatix.data.state

import javax.inject.Inject
import javax.inject.Singleton

/** A list handed to *Import a list* by another screen (the missing games of a DAT check). */
data class ListImportRequest(val titles: List<String>, val consoleId: String?)

@Singleton
class PendingListImport @Inject constructor() {
    @Volatile private var request: ListImportRequest? = null

    fun submit(request: ListImportRequest) { this.request = request }

    /** The waiting list, once. */
    fun consume(): ListImportRequest? = request.also { request = null }
}
