package com.cortinadev.dogmatix.util

import java.time.LocalDate

/** Automatic backups: `dogmatix-auto-backup-YYYY-MM-DD.json`, the newest [KEEP] kept. */
object BackupRotation {
    const val KEEP = 5
    const val PREFIX = "dogmatix-auto-backup-"

    fun fileName(date: LocalDate): String = "$PREFIX$date.json"

    /** Due when there is none yet or the newest is [intervalDays] days old. */
    fun isDue(last: Long, now: Long, intervalDays: Int): Boolean =
        last <= 0 || now - last >= intervalDays * 24L * 3_600_000 - 3_600_000

    /** Of the automatic backups among [names], the ones to delete so the newest [keep] stay. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sortedDescending().drop(keep)
}
