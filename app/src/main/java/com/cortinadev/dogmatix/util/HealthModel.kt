package com.cortinadev.dogmatix.util

/** Where a health check is listed on the "Check everything" screen. */
enum class HealthGroup { LIBRARY, CLOUD, DEVICE }

/** Every check of the health screen, in the order it is listed. */
enum class HealthCheck(val group: HealthGroup) {
    SOURCES(HealthGroup.LIBRARY),
    STORAGE(HealthGroup.LIBRARY),
    BIOS(HealthGroup.LIBRARY),
    COVERS(HealthGroup.LIBRARY),
    SAVE_SYNC(HealthGroup.CLOUD),
    ROMM(HealthGroup.CLOUD),
    WEBDAV(HealthGroup.CLOUD),
    RETRO_ACHIEVEMENTS(HealthGroup.CLOUD),
    FRONTENDS(HealthGroup.DEVICE),
    NOTIFICATIONS(HealthGroup.DEVICE),
    BATTERY(HealthGroup.DEVICE),
    UPDATE(HealthGroup.DEVICE)
}

/** How one check came out. [HINT] and [NOT_SET_UP] are neutral: they never turn the summary red or amber. */
enum class HealthStatus { OK, HINT, LOOK, PROBLEM, NOT_SET_UP }

/**
 * What a row's FIX action does. The first four run inside the health screen; every other value is
 * handed to the host through `onFix` so it can navigate.
 */
enum class HealthFix {
    RETRY_COVERS, REFRESH_ROMM, OPEN_APP_NOTIFICATION_SETTINGS, OPEN_BATTERY_SETTINGS,
    OPEN_SOURCES, OPEN_BIOS, OPEN_SAVE_SYNC, OPEN_ROMM, OPEN_CLOUD_BACKUP, OPEN_STORAGE,
    OPEN_FRONTENDS, OPEN_RETROACHIEVEMENTS, OPEN_UPDATES;

    /** True when the health screen runs this one itself (the others go to the host). */
    val inline: Boolean
        get() = this == RETRY_COVERS || this == REFRESH_ROMM ||
            this == OPEN_APP_NOTIFICATION_SETTINGS || this == OPEN_BATTERY_SETTINGS
}

/** Which sentence a row shows; the screen maps each to a string, filled from [HealthResult.a], [HealthResult.b], [HealthResult.text]. */
enum class HealthDetail {
    // sources: a = sources, b = days since the last scan / a = failed, b = total / a = failed
    SOURCES_NONE, SOURCES_NOT_SCANNED, SOURCES_OK, SOURCES_PARTIAL, SOURCES_FAILED,
    // storage: a = free bytes, b = library bytes / a = bytes short
    STORAGE_NO_FOLDER, STORAGE_OK, STORAGE_FREE_UNKNOWN, STORAGE_LOW, STORAGE_QUEUE_SHORT,
    // bios: a = systems / a = incomplete systems, b = systems
    BIOS_NO_FOLDER, BIOS_NONE_NEEDED, BIOS_OK, BIOS_INCOMPLETE,
    // covers: a = games without a cover
    COVERS_OK, COVERS_MISSES,
    // save sync: a = conflicts / failed files / days
    SAVE_NOT_SET_UP, SAVE_NO_ROMM, SAVE_CONFLICTS, SAVE_ERROR, SAVE_FAILED, SAVE_NEVER, SAVE_STALE, SAVE_OK,
    // RomM: text = server version, or the (redacted) server message
    ROMM_NOT_SET_UP, ROMM_OK, ROMM_AUTH, ROMM_FORBIDDEN, ROMM_TLS, ROMM_UNREACHABLE, ROMM_SERVER,
    // WebDAV: a = days / things needing a look
    DAV_NOT_SET_UP, DAV_NOT_CONNECTED, DAV_ATTENTION, DAV_BACKUP_OVERDUE, DAV_NO_BACKUP, DAV_OK_BACKUP, DAV_OK_NO_BACKUP,
    RA_NOT_SET_UP, RA_INCOMPLETE, RA_OK,
    // frontends: a = frontends ready
    FRONT_NOT_SET_UP, FRONT_OK, FRONT_PARTIAL,
    NOTIF_OK, NOTIF_OFF,
    BATTERY_OK, BATTERY_HINT,
    // update: text = release tag
    UPDATE_OK, UPDATE_AVAILABLE, UPDATE_UNKNOWN,
    /** The check did not answer within its time limit. */
    TIMEOUT,
    /** The check failed unexpectedly; [HealthResult.text] holds the redacted message. */
    ERROR
}

/** One row of the report. */
data class HealthResult(
    val check: HealthCheck,
    val status: HealthStatus,
    val detail: HealthDetail,
    val fix: HealthFix? = null,
    val a: Long = 0,
    val b: Long = 0,
    val text: String = ""
)

/** Counts for the ring at the top of the screen. */
data class HealthSummary(
    /** Checks that came out fine ([HealthStatus.OK] or [HealthStatus.HINT]). */
    val fine: Int,
    /** Checks that apply: finished and not "not set up". */
    val total: Int,
    val problems: Int,
    val looks: Int,
    val notSetUp: Int,
    /** Checks that have answered so far. */
    val done: Int,
    /** Checks expected in all. */
    val expected: Int,
    /** The worst status among the finished checks: PROBLEM, LOOK, or OK. */
    val worst: HealthStatus
) {
    val finished: Boolean get() = done >= expected
    val fraction: Float get() = if (total == 0) 0f else fine.toFloat() / total
}

/** Rolls the finished checks up into one summary and picks where the D-pad starts. Pure JVM for the tests. */
object HealthRollup {

    fun summarize(results: Collection<HealthResult>, expected: Int = HealthCheck.entries.size): HealthSummary {
        val applicable = results.filter { it.status != HealthStatus.NOT_SET_UP }
        val problems = applicable.count { it.status == HealthStatus.PROBLEM }
        val looks = applicable.count { it.status == HealthStatus.LOOK }
        val worst = when {
            problems > 0 -> HealthStatus.PROBLEM
            looks > 0 -> HealthStatus.LOOK
            else -> HealthStatus.OK
        }
        return HealthSummary(
            fine = applicable.count { it.status == HealthStatus.OK || it.status == HealthStatus.HINT },
            total = applicable.size,
            problems = problems,
            looks = looks,
            notSetUp = results.size - applicable.size,
            done = results.size,
            expected = expected,
            worst = worst
        )
    }

    /** The check the D-pad starts on: the first problem, else the first "look at this", else the first row. */
    fun firstFocus(results: Map<HealthCheck, HealthResult>): HealthCheck? =
        HealthCheck.entries.firstOrNull { results[it]?.status == HealthStatus.PROBLEM }
            ?: HealthCheck.entries.firstOrNull { results[it]?.status == HealthStatus.LOOK }
            ?: HealthCheck.entries.firstOrNull { results.containsKey(it) }

    /** The results in listing order. */
    fun ordered(results: Map<HealthCheck, HealthResult>): List<HealthResult> =
        HealthCheck.entries.mapNotNull { results[it] }
}

/** The plain-text report that "Share report" sends. */
object HealthReport {

    data class Row(val group: String, val name: String, val status: String, val detail: String)

    /**
     * Builds the report from already translated parts. Every detail line is passed through
     * [DiagnosticsRedactor], so server text never carries addresses, tokens or credentials.
     */
    fun text(title: String, summary: String, appLine: String, rows: List<Row>): String = buildString {
        appendLine(title)
        appendLine(summary)
        if (appLine.isNotBlank()) appendLine(DiagnosticsRedactor.redact(appLine))
        var group: String? = null
        rows.forEach { row ->
            if (row.group != group) {
                group = row.group
                appendLine()
                appendLine(row.group.uppercase())
            }
            appendLine("[${row.status}] ${row.name}: ${DiagnosticsRedactor.redact(row.detail.replace('\n', ' '))}")
        }
    }.trimEnd()
}
