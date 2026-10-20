package com.tufein.valkyr.util

import java.time.LocalDate

/**
 * Automatic backups: `valkyr-auto-backup-YYYY-MM-DD.json`, the newest [KEEP] kept. Versions before
 * 4.0 wrote `dogmatix-auto-backup-…`: those are still recognised, so they count towards [KEEP] and
 * are rotated away by date like the new ones.
 */
object BackupRotation {
    const val KEEP = 5
    const val PREFIX = "valkyr-auto-backup-"
    const val LEGACY_PREFIX = "dogmatix-auto-backup-"

    fun fileName(date: LocalDate): String = "$PREFIX$date.json"

    /** Whether [name] is an automatic backup, in the current or the pre-4.0 naming. */
    fun isAutoBackup(name: String): Boolean =
        (name.startsWith(PREFIX) || name.startsWith(LEGACY_PREFIX)) && name.endsWith(".json")

    /** Due when there is none yet or the newest is [intervalDays] days old. */
    fun isDue(last: Long, now: Long, intervalDays: Int): Boolean =
        last <= 0 || now - last >= intervalDays * 24L * 3_600_000 - 3_600_000

    /** Of the automatic backups among [names], the ones to delete so the newest [keep] stay (by the date in the name). */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { isAutoBackup(it) }
            .sortedWith(compareByDescending<String> { it.removePrefix(PREFIX).removePrefix(LEGACY_PREFIX) }.thenByDescending { it.startsWith(PREFIX) })
            .drop(keep)
}
