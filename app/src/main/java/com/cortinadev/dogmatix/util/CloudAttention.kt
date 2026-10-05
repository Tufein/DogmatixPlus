package com.cortinadev.dogmatix.util

/**
 * What the cloud status (the hub card, the top-bar icon) counts as "needs a look" for the WebDAV
 * cloud. Pure JVM for the tests.
 */
object CloudAttention {

    /** An automatic backup that is this many days overdue is worth a hint (charging/Wi-Fi never came). */
    const val STALE_DAYS = 3

    /**
     * The number of things that need the user: a failed connection test, a backup that failed after
     * the last good one, a failed sync and sync removals held back for confirmation. The error
     * arguments are the stored codes ([CloudErrors]); empty means none. Nothing counts while no
     * server is set up.
     */
    fun count(configured: Boolean, testError: String, backupError: String, syncError: String, syncHeldBack: Int): Int {
        if (!configured) return 0
        return listOf(testError.isNotEmpty(), backupError.isNotEmpty(), syncError.isNotEmpty(), syncHeldBack > 0).count { it }
    }

    /** Automatic backup is on, has run before, and the last one is more than [STALE_DAYS] days old. */
    fun backupStale(autoBackup: Boolean, lastBackupAt: Long, now: Long): Boolean =
        autoBackup && lastBackupAt > 0 && now - lastBackupAt > STALE_DAYS * 86_400_000L
}
