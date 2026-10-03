package com.cortinadev.dogmatix.util

/** One game of the collection as it appears in an export. */
data class ExportGame(val console: String, val title: String, val files: Int, val sizeBytes: Long, val folder: String)

/** Column titles and wording; the caller passes the app's current language. */
data class ExportLabels(
    val title: String, val console: String, val game: String, val files: String, val size: String, val folder: String,
    val total: String, val generated: String
)

/** Turns the collection into a CSV file or a self-contained web page (no scripts, no network). */
object CollectionExport {

    fun csv(games: List<ExportGame>, labels: ExportLabels): String = buildString {
        append('﻿')   // so spreadsheets read UTF-8 names correctly
        appendLine(listOf(labels.console, labels.game, labels.files, labels.size, labels.folder).joinToString(",") { cell(it) })
        sorted(games).forEach { g ->
            appendLine(listOf(g.console, g.title, g.files.toString(), g.sizeBytes.toString(), g.folder).joinToString(",") { cell(it) })
        }
    }

    fun html(games: List<ExportGame>, labels: ExportLabels, generatedOn: String): String = buildString {
        val sorted = sorted(games)
        appendLine("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<title>${escape(labels.title)}</title>")
        appendLine("<style>body{font-family:system-ui,sans-serif;margin:1.5rem;color:#1b1b1f}h1{margin:0 0 .25rem}h2{margin:1.5rem 0 .25rem;font-size:1.1rem}" +
            "p.meta{color:#5b5b66;margin:0}table{border-collapse:collapse;width:100%}td,th{padding:.25rem .5rem;text-align:left;border-bottom:1px solid #e3e3e8}" +
            "td.n{text-align:right;white-space:nowrap}@media(prefers-color-scheme:dark){body{background:#121214;color:#e6e6ea}td,th{border-color:#2c2c31}p.meta{color:#a0a0aa}}</style></head><body>")
        appendLine("<h1>${escape(labels.title)}</h1>")
        appendLine("<p class=\"meta\">${escape(labels.total)}: ${sorted.size} · ${escape(humanSize(sorted.sumOf { it.sizeBytes }))} · ${escape(labels.generated)} ${escape(generatedOn)}</p>")
        sorted.groupBy { it.console }.forEach { (console, list) ->
            appendLine("<h2>${escape(console)} <small>(${list.size} · ${escape(humanSize(list.sumOf { it.sizeBytes }))})</small></h2>")
            appendLine("<table><tr><th>${escape(labels.game)}</th><th class=\"n\">${escape(labels.files)}</th><th class=\"n\">${escape(labels.size)}</th></tr>")
            list.forEach { g -> appendLine("<tr><td>${escape(g.title)}</td><td class=\"n\">${g.files}</td><td class=\"n\">${escape(humanSize(g.sizeBytes))}</td></tr>") }
            appendLine("</table>")
        }
        appendLine("</body></html>")
    }

    private fun sorted(games: List<ExportGame>) = games.sortedWith(compareBy({ it.console.lowercase() }, { it.title.lowercase() }))

    /** RFC 4180 cell, and a leading `=`/`+`/`-`/`@` is defused so a spreadsheet never runs a game name as a formula. */
    fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }
}
