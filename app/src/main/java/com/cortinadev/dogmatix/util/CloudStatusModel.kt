package com.cortinadev.dogmatix.util

/**
 * One cloud feature's share of the top-bar cloud icon (RomM, save sync, and later WebDAV backup and
 * device sync, which report theirs through `CloudStatusService.report`).
 */
data class CloudStatusPart(
    /** Set up by the user: an unset feature never shows the icon. */
    val configured: Boolean,
    val syncing: Boolean = false,
    /** Conflicts, failures and errors waiting for the user. */
    val attention: Int = 0,
    /** 0..1 while [syncing] when known; null otherwise. */
    val progress: Float? = null
)

enum class CloudActivity { HIDDEN, IDLE, SYNCING, ATTENTION }

/** What the top-bar cloud icon shows. */
data class CloudStatus(
    val activity: CloudActivity,
    /** Total of what needs the user; shown as a count. */
    val attention: Int = 0,
    /** 0..1 of the running sync when known. */
    val progress: Float? = null
) {
    val visible: Boolean get() = activity != CloudActivity.HIDDEN

    companion object {
        val Hidden = CloudStatus(CloudActivity.HIDDEN)
    }
}

/** Merges the features' parts into one icon state. Pure JVM for the tests. */
object CloudStatusModel {

    /** Hidden when nothing is set up; syncing beats attention, which beats idle. The count is kept either way. */
    fun merge(parts: Collection<CloudStatusPart>): CloudStatus {
        val live = parts.filter { it.configured }
        if (live.isEmpty()) return CloudStatus.Hidden
        val attention = live.sumOf { it.attention.coerceAtLeast(0) }
        val syncing = live.filter { it.syncing }
        val activity = when {
            syncing.isNotEmpty() -> CloudActivity.SYNCING
            attention > 0 -> CloudActivity.ATTENTION
            else -> CloudActivity.IDLE
        }
        // One sync with a known progress: show it; several at once: just "busy".
        val progress = syncing.singleOrNull()?.progress?.coerceIn(0f, 1f)
        return CloudStatus(activity, attention, if (activity == CloudActivity.SYNCING) progress else null)
    }

    /**
     * The save sync's part: conflicts waiting for a choice, files that failed, a sync that stopped
     * as a whole and deletions held back for confirmation each need the user.
     */
    fun saveSyncPart(
        configured: Boolean,
        running: Boolean,
        conflicts: Int,
        failed: Int,
        hasError: Boolean,
        deletionsHeld: Int,
        progress: String? = null
    ): CloudStatusPart = CloudStatusPart(
        configured = configured,
        syncing = configured && running,
        attention = if (!configured) 0 else conflicts.coerceAtLeast(0) + failed.coerceAtLeast(0) +
            (if (hasError) 1 else 0) + (if (deletionsHeld > 0) 1 else 0),
        progress = parseProgress(progress)
    )

    /** "3 / 12" (the save sync's progress text) → 0.25; null when it is not that. */
    fun parseProgress(text: String?): Float? {
        val m = Regex("""^\s*(\d+)\s*/\s*(\d+)\s*$""").find(text ?: return null) ?: return null
        val done = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (done.toFloat() / total).coerceIn(0f, 1f)
    }
}
