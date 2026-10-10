package com.cortinadev.dogmatix.util

/** A missing folder is never a successful empty scan. RETURNED still needs an explicit resume. */
enum class StorageAccessStatus { AVAILABLE, RETURNED, MISSING, ACCESS_LOST, READ_ONLY, UNAVAILABLE }

object StorageAvailability {
    fun readable(status: StorageAccessStatus) = status in setOf(StorageAccessStatus.AVAILABLE, StorageAccessStatus.RETURNED, StorageAccessStatus.READ_ONLY)
    fun writable(status: StorageAccessStatus) = status == StorageAccessStatus.AVAILABLE || status == StorageAccessStatus.RETURNED
    fun transition(previous: StorageAccessStatus?, observed: StorageAccessStatus): StorageAccessStatus =
        if (observed == StorageAccessStatus.AVAILABLE && previous != null && !writable(previous)) StorageAccessStatus.RETURNED else observed

    /** Folder boundaries matter: a grant to /Games does not identify /Games-old. */
    fun contains(root: String, child: String) = root == child || child.startsWith(root.trimEnd('/') + "/")
}
