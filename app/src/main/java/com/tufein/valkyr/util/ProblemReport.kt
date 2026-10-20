package com.tufein.valkyr.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What kind of feedback a problem report is. The label goes into the (English) report. */
enum class ReportCategory(val label: String) {
    BUG("Bug"),
    DATA_LOSS_RISK("Data loss risk"),
    COMPATIBILITY("Compatibility"),
    IDEA("Idea")
}

/** The parts of a problem report the player can switch off; [ProblemReport.build] writes only these. */
enum class ReportSection { APP, DEVICE, LANGUAGE, STORAGE, SETTINGS, INTEGRATIONS, COUNTS, HEALTH, HISTORY, CRASH }

/** Which key signed the installed app, compared with the published signers (the digest itself is never written). */
enum class ReportSigning(val label: String) {
    PRODUCTION("production app (published release signer)"),
    TEST("test app (published test signer)"),
    OTHER("other signer (local or third-party build)"),
    UNKNOWN("unknown")
}

/** What the player typed. Blank fields are written as an empty template. */
data class ReportText(
    val whatHappened: String = "",
    val expected: String = "",
    val steps: String = "",
    val storage: String = ""
)

/** One storage volume: its kind and space only, never its path, label or id. */
data class ReportVolume(val primary: Boolean, val removable: Boolean, val state: String, val freeBytes: Long?, val totalBytes: Long?)

/** One display: pixel size and whether Android offers it for a second-screen presentation. */
data class ReportDisplay(val widthPx: Int, val heightPx: Int, val presentation: Boolean)

/** An installed emulator or frontend the app knows, with its own version name. */
data class ReportApp(val label: String, val packageName: String, val version: String)

/** One history line: a time, a fixed kind and a fixed detail code (phase, topic or reason), never a title or path. */
data class ReportHistoryLine(val at: Long, val kind: String, val detail: String)

/**
 * Everything the app gathered for a report ([com.tufein.valkyr.data.service.ProblemReportService]).
 * Only fixed codes, counts, versions and yes/no flags; every line still goes through
 * [ProblemReport.clean] when the text is written.
 */
data class ReportFacts(
    val generatedAt: Long = 0L,
    // App
    val appVersion: String = "",
    val buildCode: Long = 0L,
    val debugBuild: Boolean = false,
    val packageName: String = "",
    val signing: ReportSigning = ReportSigning.UNKNOWN,
    // Device
    val manufacturer: String = "",
    val model: String = "",
    val androidRelease: String = "",
    val sdk: Int = 0,
    val firmware: String = "",
    val abi: String = "",
    val windowDp: String = "",
    val displays: List<ReportDisplay> = emptyList(),
    val controller: Boolean = false,
    // Language
    val appLanguage: String = "",
    val systemLocale: String = "",
    val fontScale: Float = 1f,
    val textSizePercent: Int = 100,
    // Storage
    val volumes: List<ReportVolume> = emptyList(),
    // Settings: only the keys in [ProblemReport.SETTINGS_WHITELIST] are written.
    val settings: Map<String, String> = emptyMap(),
    // Integrations
    val rommConfigured: Boolean = false,
    val rommVersion: String? = null,
    val webDav: Boolean = false,
    val retroAchievements: Boolean = false,
    val saveSync: Boolean = false,
    val debrid: String = "",
    val apps: List<ReportApp> = emptyList(),
    // Counts
    val consoles: Int = 0,
    val sourcesEnabled: Int = 0,
    val sourcesTotal: Int = 0,
    val libraryGames: Int = 0,
    val profiles: Int = 0,
    val downloadsByStatus: Map<String, Int> = emptyMap(),
    val failedByCategory: Map<String, Int> = emptyMap(),
    // Health: the checks that ran here, and the ones left out (they would need the network)
    val health: List<HealthResult> = emptyList(),
    val healthSkipped: List<HealthCheck> = emptyList(),
    // History, newest first
    val operations: List<ReportHistoryLine> = emptyList(),
    val actions: List<ReportHistoryLine> = emptyList(),
    // Last crash: the raw CrashLog text (only class names and app frames are kept) and when it was written
    val crash: String? = null,
    val crashAt: Long = 0L
)

/**
 * Builds the text of "Report a problem": the headings of the launch bug template, the player's own
 * words, then the sections they kept. Pure JVM for the tests. The report is in English on purpose
 * (it is read by the developer); the screen around it is translated.
 *
 * Privacy: the whole text goes through [clean], which removes what [DiagnosticsRedactor] removes
 * (tokens, passwords, server addresses, e-mail addresses, IP addresses) and also file and folder
 * paths, storage document ids, other URIs, host:port pairs and long random strings.
 */
object ProblemReport {
    /** Classes of this app: only their frames of a crash are kept. */
    const val APP_PACKAGE = "com.tufein.valkyr"
    const val HISTORY_LINES = 15
    const val MAX_FRAMES = 6
    private const val MAX_CAUSES = 3
    private const val MAX_APPS = 16

    /** SHA-256 of the published signing certificates. */
    const val PRODUCTION_SIGNER = "e0e4eaeaf92e4d7a68ef620b021598f0bcbb9f9399e2bdcf18c8297077bedc46"
    const val TEST_SIGNER = "6e01f3ae0a73660bca0910f23af9e85780233f092af3ee1464c954db61fa7a70"

    /** The only settings a report may name: switches and numbers, nothing typed by the player. */
    val SETTINGS_WHITELIST: List<String> = listOf(
        "theme", "gamepad_layout", "swap_face_buttons", "text_size", "bold_focus", "second_screen",
        "separate_by_console", "auto_unzip", "concurrent_downloads", "speed_limit", "per_server_limit",
        "wifi_only", "charging_only", "night_only", "resume_downloads", "requeue_after_restart",
        "auto_retry", "queue_held", "auto_scan", "auto_m3u", "esde_covers", "pegasus_covers",
        "update_channel", "min_free_gb", "auto_backup"
    )
    private val safeValue = Regex("""^[A-Za-z0-9_.%+-]{1,24}$""")

    fun build(
        facts: ReportFacts,
        text: ReportText,
        category: ReportCategory,
        sections: Set<ReportSection>,
        secrets: Collection<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
        fun at(ms: Long) = time.format(Instant.ofEpochMilli(ms))
        fun on(s: ReportSection) = s in sections
        val out = buildString {
            appendLine("## Valkyr problem report")
            appendLine()
            appendLine("**Category:** ${category.label}")
            if (facts.generatedAt > 0) appendLine("Prepared on the device on ${at(facts.generatedAt)}. The app did not send it; the reporter reviewed it before sharing.")
            heading("What happened")
            appendLine(text.whatHappened.trim().ifEmpty { NOT_FILLED })
            heading("Expected result")
            appendLine(text.expected.trim().ifEmpty { NOT_FILLED })
            heading("Steps to reproduce")
            appendLine(text.steps.trim().ifEmpty { "1. \n2. \n3. " })
            if (text.storage.isNotBlank() || category == ReportCategory.DATA_LOSS_RISK) {
                heading("Storage or save changes")
                appendLine(text.storage.trim().ifEmpty { NOT_FILLED })
            }

            heading("App version and build")
            if (on(ReportSection.APP)) {
                appendLine("- Valkyr ${facts.appVersion} (build ${facts.buildCode}), ${if (facts.debugBuild) "debug" else "release"}")
                appendLine("- Package: ${facts.packageName}")
                appendLine("- Signing: ${facts.signing.label}")
            } else appendLine(NOT_INCLUDED)

            heading("Device, Android version and firmware")
            if (on(ReportSection.DEVICE)) {
                appendLine("- Device: ${facts.manufacturer} ${facts.model}".trimEnd())
                appendLine("- Android ${facts.androidRelease} (API ${facts.sdk}), firmware ${facts.firmware.ifBlank { "unknown" }}, ABI ${facts.abi.ifBlank { "unknown" }}")
                val sizes = facts.displays.joinToString(", ") { "${it.widthPx}x${it.heightPx}" }
                appendLine("- Window ${facts.windowDp}; displays: ${facts.displays.size}${if (sizes.isNotEmpty()) " ($sizes)" else ""}")
                appendLine("- Second display: ${yes(facts.displays.size > 1 || facts.displays.any { it.presentation })}")
                appendLine("- Controller connected: ${yes(facts.controller)}")
            }
            if (on(ReportSection.LANGUAGE)) {
                appendLine("- Language: app ${facts.appLanguage.ifBlank { "system default" }}, system ${facts.systemLocale}, font scale ${"%.2f".format(Locale.ROOT, facts.fontScale)}, app text size ${facts.textSizePercent}%")
            }
            if (!on(ReportSection.DEVICE) && !on(ReportSection.LANGUAGE)) appendLine(NOT_INCLUDED)

            heading("Emulator or server versions")
            if (on(ReportSection.INTEGRATIONS)) {
                appendLine("- RomM: ${if (!facts.rommConfigured) "not set up" else "set up, server version ${facts.rommVersion?.takeIf { safeValue.matches(it) } ?: "not known yet"}"}")
                appendLine("- WebDAV cloud: ${yes(facts.webDav)}")
                appendLine("- RetroAchievements: ${yes(facts.retroAchievements)}")
                appendLine("- Save sync: ${yes(facts.saveSync)}")
                appendLine("- Debrid service: ${facts.debrid.takeIf { safeValue.matches(it) } ?: "unknown"}")
                if (facts.apps.isEmpty()) appendLine("- Known emulators and frontends installed: none found")
                else {
                    appendLine("- Known emulators and frontends installed:")
                    facts.apps.take(MAX_APPS).forEach { appendLine("  - ${it.label} (${it.packageName}) ${it.version}") }
                    if (facts.apps.size > MAX_APPS) appendLine("  - and ${facts.apps.size - MAX_APPS} more")
                }
            } else appendLine(NOT_INCLUDED)

            heading("Sanitized diagnostics")
            val diagnostics = listOf(ReportSection.STORAGE, ReportSection.SETTINGS, ReportSection.COUNTS, ReportSection.HEALTH, ReportSection.HISTORY, ReportSection.CRASH)
            if (diagnostics.none(::on)) appendLine(NOT_INCLUDED)
            if (on(ReportSection.STORAGE)) {
                appendLine("Storage:")
                if (facts.volumes.isEmpty()) appendLine("- no volume could be read")
                facts.volumes.forEach { v ->
                    val kind = when { v.primary -> "internal"; v.removable -> "removable"; else -> "other" }
                    val state = v.state.takeIf { safeValue.matches(it) } ?: "unknown"
                    val space = if (v.freeBytes != null && v.totalBytes != null) "${size(v.freeBytes)} free of ${size(v.totalBytes)}" else "space unknown"
                    appendLine("- $kind ($state): $space")
                }
            }
            if (on(ReportSection.SETTINGS)) {
                appendLine("Settings:")
                val kept = SETTINGS_WHITELIST.mapNotNull { key -> facts.settings[key]?.let { key to (it.takeIf { v -> safeValue.matches(v) } ?: "<omitted>") } }
                appendLine(if (kept.isEmpty()) "- none read" else "- " + kept.joinToString(", ") { "${it.first}=${it.second}" })
            }
            if (on(ReportSection.COUNTS)) {
                appendLine("Counts:")
                appendLine("- consoles: ${facts.consoles}; sources: ${facts.sourcesEnabled} enabled of ${facts.sourcesTotal}; profiles: ${facts.profiles}")
                appendLine("- library size: ${libraryBand(facts.libraryGames)} games")
                appendLine("- downloads: ${counts(facts.downloadsByStatus)}")
                appendLine("- failed downloads by reason: ${counts(facts.failedByCategory)}")
            }
            if (on(ReportSection.HEALTH)) {
                appendLine("Health checks:")
                if (facts.health.isEmpty()) appendLine("- no result")
                HealthCheck.entries.forEach { check -> facts.health.firstOrNull { it.check == check }?.let { appendLine("- ${healthLine(it)}") } }
                if (facts.healthSkipped.isNotEmpty()) appendLine("- not run here (they need the network): ${facts.healthSkipped.joinToString(", ") { it.name.lowercase() }}")
            }
            if (on(ReportSection.HISTORY)) {
                appendLine("Recent actions (newest first):")
                if (facts.actions.isEmpty()) appendLine("- none")
                facts.actions.take(HISTORY_LINES).forEach { appendLine("- ${at(it.at)} | ${code(it.kind)} | ${code(it.detail)}") }
                appendLine("Recent file operations (newest first):")
                if (facts.operations.isEmpty()) appendLine("- none")
                facts.operations.take(HISTORY_LINES).forEach { appendLine("- ${at(it.at)} | ${code(it.kind)} | ${code(it.detail)}") }
            }
            if (on(ReportSection.CRASH)) {
                appendLine("Last crash:")
                val summary = crashSummary(facts.crash)
                if (summary.isEmpty()) appendLine("- none recorded")
                else {
                    if (facts.crashAt > 0) appendLine("- recorded ${at(facts.crashAt)}")
                    summary.forEach { appendLine("    $it") }
                }
            }
        }
        return clean(out.trimEnd() + "\n", secrets)
    }

    /**
     * Removes private details from any report text, also text the player typed or edited:
     * everything [DiagnosticsRedactor] removes, plus paths, document ids, other URIs, host:port
     * pairs and long random strings (keys). Errs on the side of removing too much.
     */
    fun clean(text: String, secrets: Collection<String> = emptyList()): String {
        var out = DiagnosticsRedactor.redact(text, secrets)
        out = otherUri.replace(out, "<uri>")
        out = windowsPath.replace(out, "<path>")
        out = uncPath.replace(out, "<path>")
        out = documentId.replace(out, "<path>")
        out = unixPath.replace(out, "<path>")
        out = hostPort.replace(out, "<host>")
        out = longKey.replace(out, "<redacted>")
        return out
    }

    private val otherUri = Regex("""(?i)\b(?:file|smb|nfs|sftp|ftps|davs?|webdavs?):/{0,3}[^\s"'<>]+""")
    private val windowsPath = Regex("""\b[A-Za-z]:\\[^\s"'<>|]*""")
    private val uncPath = Regex("""\\\\[^\s"'<>|]+""")
    /** Storage Access Framework ids: `primary:ROMs/SNES`, `1A2B-3C4D:Games`, also URL-encoded. */
    private val documentId = Regex("""(?i)\b(?:primary|home|[0-9A-F]{4}-[0-9A-F]{4})(?::|%3A)[^\s"'<>|,;]+""")
    /** `/storage/emulated/0/…`, `/sdcard`: a slash that does not follow a letter, digit, `:` or another slash. */
    private val unixPath = Regex("""(?<![A-Za-z0-9:/._<>-])/[^\s/"'<>|()]+(?:/[^\s/"'<>|()]*)*""")
    /** `nas.local:5000`, `romm:8080`; not `14:03` (no letter) and not a stack frame's `Foo.kt:12`. */
    private val hostPort = Regex("""\b(?=[A-Za-z0-9.-]*[A-Za-z])[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*(?<!\.kt)(?<!\.java)(?<!Source)(?<!SourceFile):\d{2,5}\b""")
    /** A run of 24+ letters/digits with both in it: an API key, token or hash. */
    private val longKey = Regex("""(?<![A-Za-z0-9_./-])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}(?![A-Za-z0-9_./-])""")

    /**
     * The last crash as exception class names and this app's own frames only: messages (which can
     * name files or servers) and platform frames are left out; frames are code positions, never data.
     */
    fun crashSummary(raw: String?, maxFrames: Int = MAX_FRAMES): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        var frames = 0
        var causes = 0
        var other = 0
        fun flushOther() { if (other > 0) { out += "  (+$other other frames)"; other = 0 } }
        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() || t.contains(" on thread ") && out.isEmpty() -> Unit
                t.startsWith("at ") -> {
                    if (causes > MAX_CAUSES) continue
                    val frame = t.removePrefix("at ").trim()
                    if (appFrame(frame) && frames < maxFrames && frameShape.matches(frame)) {
                        flushOther(); out += "  at $frame"; frames++
                    } else other++
                }
                t.startsWith("Caused by:") -> {
                    causes++
                    if (causes > MAX_CAUSES) continue
                    flushOther(); frames = 0
                    out += "Caused by: " + exceptionClass(t.removePrefix("Caused by:"))
                }
                t.startsWith("...") || t.startsWith("Suppressed:") -> Unit
                out.isEmpty() -> out += exceptionClass(t)
            }
        }
        flushOther()
        return out
    }

    /**
     * A frame of this app. A release build is shrunk, so its own classes have short made-up names
     * (the developer maps them back): any frame outside the platform and its standard libraries counts.
     */
    private fun appFrame(frame: String): Boolean =
        frame.startsWith("$APP_PACKAGE.") || PLATFORM_PREFIXES.none { frame.startsWith(it) }

    private val PLATFORM_PREFIXES = listOf(
        "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.", "android.", "androidx.", "com.android.",
        "kotlin.", "kotlinx.", "com.google.", "okhttp3.", "okio.", "org."
    )
    private val className = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$""")
    private val frameShape = Regex("""^[A-Za-z0-9_$.<>-]+\([A-Za-z0-9_$. -]*(?::\d+)?\)$""")

    private fun exceptionClass(text: String): String =
        text.trim().substringBefore(':').trim().takeIf { className.matches(it) } ?: "<unknown exception>"

    /** A rough size of the library, so the exact count does not identify a collection. */
    fun libraryBand(games: Int): String = when {
        games <= 0 -> "none"
        games < 100 -> "1-99"
        games < 1_000 -> "100-999"
        games < 10_000 -> "1,000-9,999"
        games < 50_000 -> "10,000-49,999"
        else -> "50,000 or more"
    }

    /** Which signer [digests] (SHA-256 of the installed certificates) are. */
    fun signingOf(digests: Collection<String>): ReportSigning {
        val set = digests.map { it.lowercase() }.toSet()
        return when {
            set.isEmpty() -> ReportSigning.UNKNOWN
            set == setOf(PRODUCTION_SIGNER) -> ReportSigning.PRODUCTION
            set == setOf(TEST_SIGNER) -> ReportSigning.TEST
            else -> ReportSigning.OTHER
        }
    }

    /** One health result as a short English line: check, status, the detail code and its numbers. */
    fun healthLine(r: HealthResult): String {
        val bytes = r.check == HealthCheck.STORAGE
        val numbers = listOf(r.a, r.b).takeIf { r.a != 0L || r.b != 0L }?.joinToString(", ") { if (bytes) size(it) else it.toString() }
        return "${r.check.name.lowercase()}: ${r.status.name} (${r.detail.name}${numbers?.let { "; $it" } ?: ""})"
    }

    fun size(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 1) "%.1f GB".format(Locale.ROOT, gb) else "%.0f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
    }

    private fun counts(map: Map<String, Int>): String =
        map.entries.filter { it.value > 0 }.sortedBy { it.key }.joinToString(", ") { "${code(it.key)}=${it.value}" }.ifEmpty { "none" }

    /** Kinds, phases and reasons are fixed codes; anything that does not look like one is left out. */
    private fun code(text: String): String = if (text.isEmpty()) "-" else text.takeIf { codeShape.matches(it) } ?: "<other>"
    private val codeShape = Regex("""^[A-Za-z0-9_:|.=-]{0,64}$""")

    private fun yes(on: Boolean) = if (on) "yes" else "no"
    private fun StringBuilder.heading(title: String) { appendLine(); appendLine("### $title") }

    private const val NOT_FILLED = "_Not filled in._"
    private const val NOT_INCLUDED = "_Not included by the reporter._"
}
