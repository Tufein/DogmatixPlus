package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.HealthCheck.*
import com.cortinadev.dogmatix.util.HealthDetail.*
import com.cortinadev.dogmatix.util.HealthStatus.*

/**
 * Turns what the app already knows (scan results, free space, server answers, settings) into one
 * [HealthResult] per check. No I/O here: the service gathers the inputs, so every rule is
 * testable. A feature that is not set up is [HealthStatus.NOT_SET_UP], never a problem.
 */
object HealthRules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val GB = 1024L * 1024 * 1024

    /** Free space under this is a problem, under [LOW_FREE_WARN] a hint to look. */
    const val LOW_FREE_PROBLEM = 1 * GB
    const val LOW_FREE_WARN = 3 * GB
    /** A sources scan or a save sync older than this is worth a look. */
    const val SCAN_STALE_DAYS = 30
    const val SAVE_STALE_DAYS = 14

    /** Whole days from [at] to [now]; 0 for today or a time in the future. */
    fun daysSince(at: Long, now: Long): Int = if (at <= 0L) 0 else ((now - at).coerceAtLeast(0) / DAY_MS).toInt()

    /**
     * @param perConsole for each console, the last-scan outcome of each enabled source (true = worked,
     *   false = failed, null = never scanned), as for [SourceHealth.of].
     * @param lastScanAt epoch ms of the newest scan result, 0 = none.
     */
    fun sources(perConsole: List<List<Boolean?>>, lastScanAt: Long, now: Long): HealthResult {
        val all = perConsole.flatten()
        if (all.isEmpty()) return HealthResult(SOURCES, NOT_SET_UP, SOURCES_NONE, HealthFix.OPEN_SOURCES)
        val scanned = all.count { it != null }
        val failed = all.count { it == false }
        val days = daysSince(lastScanAt, now)
        return when {
            scanned == 0 -> HealthResult(SOURCES, LOOK, SOURCES_NOT_SCANNED, HealthFix.OPEN_SOURCES, a = all.size.toLong())
            failed == scanned -> HealthResult(SOURCES, PROBLEM, SOURCES_FAILED, HealthFix.OPEN_SOURCES, a = failed.toLong(), b = all.size.toLong())
            failed > 0 -> HealthResult(SOURCES, LOOK, SOURCES_PARTIAL, HealthFix.OPEN_SOURCES, a = failed.toLong(), b = all.size.toLong())
            days >= SCAN_STALE_DAYS -> HealthResult(SOURCES, LOOK, SOURCES_OK, HealthFix.OPEN_SOURCES, a = all.size.toLong(), b = days.toLong())
            else -> HealthResult(SOURCES, OK, SOURCES_OK, null, a = all.size.toLong(), b = days.toLong())
        }
    }

    /**
     * @param folderSet the download folder is picked and readable.
     * @param freeBytes free space of its volume; null = unknown.
     * @param queueShortfall bytes the queue still needs beyond the free space (0 = fits; null = unknown).
     */
    fun storage(folderSet: Boolean, freeBytes: Long?, libraryBytes: Long, queueShortfall: Long?): HealthResult {
        if (!folderSet) return HealthResult(STORAGE, NOT_SET_UP, STORAGE_NO_FOLDER, HealthFix.OPEN_STORAGE)
        if ((queueShortfall ?: 0L) > 0L) return HealthResult(STORAGE, PROBLEM, STORAGE_QUEUE_SHORT, HealthFix.OPEN_STORAGE, a = queueShortfall ?: 0L, b = libraryBytes)
        if (freeBytes == null) return HealthResult(STORAGE, OK, STORAGE_FREE_UNKNOWN, null, b = libraryBytes)
        return when {
            freeBytes < LOW_FREE_PROBLEM -> HealthResult(STORAGE, PROBLEM, STORAGE_LOW, HealthFix.OPEN_STORAGE, a = freeBytes, b = libraryBytes)
            freeBytes < LOW_FREE_WARN -> HealthResult(STORAGE, LOOK, STORAGE_LOW, HealthFix.OPEN_STORAGE, a = freeBytes, b = libraryBytes)
            else -> HealthResult(STORAGE, OK, STORAGE_OK, null, a = freeBytes, b = libraryBytes)
        }
    }

    /** @param systems BIOS systems the consoles in use need; [incomplete] of them lack a required file. */
    fun bios(folderSet: Boolean, systems: Int, incomplete: Int): HealthResult = when {
        !folderSet -> HealthResult(BIOS, NOT_SET_UP, BIOS_NO_FOLDER, HealthFix.OPEN_BIOS)
        systems == 0 -> HealthResult(BIOS, OK, BIOS_NONE_NEEDED)
        incomplete > 0 -> HealthResult(BIOS, LOOK, BIOS_INCOMPLETE, HealthFix.OPEN_BIOS, a = incomplete.toLong(), b = systems.toLong())
        else -> HealthResult(BIOS, OK, BIOS_OK, null, a = systems.toLong())
    }

    fun covers(misses: Int): HealthResult =
        if (misses > 0) HealthResult(COVERS, LOOK, COVERS_MISSES, HealthFix.RETRY_COVERS, a = misses.toLong())
        else HealthResult(COVERS, OK, COVERS_OK)

    /**
     * @param foldersSet a saves or states folder (or an emulator's own folder) is picked.
     * @param rommConfigured RomM address and token are set (needed to sync).
     * @param lastSyncAt epoch ms of the last finished sync, 0 = never.
     */
    fun saveSync(
        foldersSet: Boolean, rommConfigured: Boolean, running: Boolean, error: String?,
        conflicts: Int, failed: Int, lastSyncAt: Long, now: Long
    ): HealthResult {
        if (!foldersSet) return HealthResult(SAVE_SYNC, NOT_SET_UP, SAVE_NOT_SET_UP, HealthFix.OPEN_SAVE_SYNC)
        if (!rommConfigured) return HealthResult(SAVE_SYNC, LOOK, SAVE_NO_ROMM, HealthFix.OPEN_ROMM)
        val days = daysSince(lastSyncAt, now)
        return when {
            !error.isNullOrBlank() -> HealthResult(SAVE_SYNC, PROBLEM, SAVE_ERROR, HealthFix.OPEN_SAVE_SYNC, text = DiagnosticsRedactor.redact(error))
            conflicts > 0 -> HealthResult(SAVE_SYNC, LOOK, SAVE_CONFLICTS, HealthFix.OPEN_SAVE_SYNC, a = conflicts.toLong())
            failed > 0 -> HealthResult(SAVE_SYNC, LOOK, SAVE_FAILED, HealthFix.OPEN_SAVE_SYNC, a = failed.toLong())
            lastSyncAt <= 0L -> HealthResult(SAVE_SYNC, LOOK, SAVE_NEVER, HealthFix.OPEN_SAVE_SYNC)
            days >= SAVE_STALE_DAYS && !running -> HealthResult(SAVE_SYNC, LOOK, SAVE_STALE, HealthFix.OPEN_SAVE_SYNC, a = days.toLong())
            else -> HealthResult(SAVE_SYNC, OK, SAVE_OK, null, a = days.toLong())
        }
    }

    /** @param serverText what the server or the network said; it is redacted here. */
    fun romm(configured: Boolean, reachable: Boolean, errorKind: String?, version: String?, serverText: String?): HealthResult {
        if (!configured) return HealthResult(ROMM, NOT_SET_UP, ROMM_NOT_SET_UP, HealthFix.OPEN_ROMM)
        val text = DiagnosticsRedactor.redact(serverText.orEmpty()).take(160)
        return when (errorKind) {
            null -> if (reachable) HealthResult(ROMM, OK, ROMM_OK, null, text = version.orEmpty())
            else HealthResult(ROMM, PROBLEM, ROMM_UNREACHABLE, HealthFix.REFRESH_ROMM, text = text)
            "AUTH" -> HealthResult(ROMM, PROBLEM, ROMM_AUTH, HealthFix.OPEN_ROMM)
            "FORBIDDEN" -> HealthResult(ROMM, PROBLEM, ROMM_FORBIDDEN, HealthFix.OPEN_ROMM)
            "TLS" -> HealthResult(ROMM, PROBLEM, ROMM_TLS, HealthFix.OPEN_ROMM)
            "UNREACHABLE" -> HealthResult(ROMM, PROBLEM, ROMM_UNREACHABLE, HealthFix.REFRESH_ROMM, text = text)
            "NOT_SET_UP" -> HealthResult(ROMM, NOT_SET_UP, ROMM_NOT_SET_UP, HealthFix.OPEN_ROMM)
            else -> HealthResult(ROMM, PROBLEM, ROMM_SERVER, HealthFix.REFRESH_ROMM, text = text)
        }
    }

    fun webdav(
        configured: Boolean, connected: Boolean?, autoBackup: Boolean, lastBackupAt: Long,
        backupStale: Boolean, attention: Int, now: Long
    ): HealthResult {
        if (!configured) return HealthResult(WEBDAV, NOT_SET_UP, DAV_NOT_SET_UP, HealthFix.OPEN_CLOUD_BACKUP)
        val days = daysSince(lastBackupAt, now).toLong()
        return when {
            connected == false -> HealthResult(WEBDAV, PROBLEM, DAV_NOT_CONNECTED, HealthFix.OPEN_CLOUD_BACKUP)
            attention > 0 -> HealthResult(WEBDAV, LOOK, DAV_ATTENTION, HealthFix.OPEN_CLOUD_BACKUP, a = attention.toLong())
            backupStale && lastBackupAt > 0L -> HealthResult(WEBDAV, LOOK, DAV_BACKUP_OVERDUE, HealthFix.OPEN_CLOUD_BACKUP, a = days)
            lastBackupAt <= 0L && autoBackup -> HealthResult(WEBDAV, LOOK, DAV_NO_BACKUP, HealthFix.OPEN_CLOUD_BACKUP)
            lastBackupAt <= 0L -> HealthResult(WEBDAV, OK, DAV_OK_NO_BACKUP)
            else -> HealthResult(WEBDAV, OK, DAV_OK_BACKUP, null, a = days)
        }
    }

    fun retroAchievements(user: String, key: String): HealthResult = when {
        user.isBlank() && key.isBlank() -> HealthResult(RETRO_ACHIEVEMENTS, NOT_SET_UP, RA_NOT_SET_UP, HealthFix.OPEN_RETROACHIEVEMENTS)
        user.isBlank() || key.isBlank() -> HealthResult(RETRO_ACHIEVEMENTS, LOOK, RA_INCOMPLETE, HealthFix.OPEN_RETROACHIEVEMENTS)
        else -> HealthResult(RETRO_ACHIEVEMENTS, OK, RA_OK)
    }

    /**
     * Reads the Tools → Frontend check findings. Only a half-finished ES-DE (folder set, covers
     * off) is worth a look: a frontend the user does not use is simply not set up.
     */
    fun frontends(findings: List<FrontendCheck.Finding>): HealthResult {
        val ready = findings.count { it.status == FrontendCheck.Status.READY }
        val partial = findings.any { it.detail == FrontendCheck.Detail.ESDE_NO_COVERS }
        return when {
            partial -> HealthResult(FRONTENDS, LOOK, FRONT_PARTIAL, HealthFix.OPEN_FRONTENDS, a = ready.toLong())
            ready > 0 -> HealthResult(FRONTENDS, OK, FRONT_OK, null, a = ready.toLong())
            else -> HealthResult(FRONTENDS, NOT_SET_UP, FRONT_NOT_SET_UP, HealthFix.OPEN_FRONTENDS)
        }
    }

    fun notifications(enabled: Boolean): HealthResult =
        if (enabled) HealthResult(NOTIFICATIONS, OK, NOTIF_OK)
        else HealthResult(NOTIFICATIONS, LOOK, NOTIF_OFF, HealthFix.OPEN_APP_NOTIFICATION_SETTINGS)

    /** Only ever a hint: battery optimisation can delay background jobs, but nothing breaks. */
    fun battery(ignoringOptimisations: Boolean): HealthResult =
        if (ignoringOptimisations) HealthResult(BATTERY, OK, BATTERY_OK)
        else HealthResult(BATTERY, HINT, BATTERY_HINT, HealthFix.OPEN_BATTERY_SETTINGS)

    /** @param latestTag the newest release tag; null = the check could not tell. */
    fun update(updateAvailable: Boolean?, latestTag: String?): HealthResult = when (updateAvailable) {
        null -> HealthResult(UPDATE, HINT, UPDATE_UNKNOWN)
        true -> HealthResult(UPDATE, LOOK, UPDATE_AVAILABLE, HealthFix.OPEN_UPDATES, text = latestTag.orEmpty())
        false -> HealthResult(UPDATE, OK, UPDATE_OK, null, text = latestTag.orEmpty())
    }

    /** A check that ran past its time limit: a look, never a verdict. */
    fun timedOut(check: HealthCheck): HealthResult = HealthResult(check, LOOK, TIMEOUT)

    /** A check that threw; the message is redacted. */
    fun failed(check: HealthCheck, message: String?): HealthResult =
        HealthResult(check, LOOK, ERROR, text = DiagnosticsRedactor.redact(message.orEmpty()).take(160))
}
