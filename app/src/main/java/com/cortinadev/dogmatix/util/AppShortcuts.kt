package com.cortinadev.dogmatix.util

import java.net.URLEncoder

/**
 * One app shortcut (the kind launchers show on a long press, and frontends such as Cocoon import
 * as a game tile): a console's library, a saved view, or the Downloads section.
 */
data class AppShortcut(
    val id: String,
    val label: String,
    /** `dogmatix://library?…` to open, or null for a section ([route]). */
    val deepLink: String? = null,
    val route: String? = null,
    /** The console it belongs to, so a frontend can file it under that platform. */
    val consoleId: String? = null
)

/** Builds the shortcuts Dogmatix+ offers. Pure JVM so it can be unit-tested. */
object AppShortcuts {
    const val DOWNLOADS_ID = "section:downloads"
    /** Android keeps at most this many dynamic shortcuts per activity on most devices. */
    const val MAX_DYNAMIC = 15

    fun downloads(label: String, route: String) = AppShortcut(DOWNLOADS_ID, label, route = route)

    fun console(consoleId: String, name: String) = AppShortcut(
        id = "console:$consoleId", label = name,
        deepLink = "dogmatix://library?console=" + URLEncoder.encode(consoleId, "UTF-8").replace("+", "%20"),
        consoleId = consoleId
    )

    fun view(view: LibraryView) = AppShortcut(
        id = "view:${view.id}", label = view.name, deepLink = view.deepLink(),
        consoleId = view.consoles.singleOrNull()
    )

    /** Downloads first, then saved views, then consoles A–Z, cut to [max]. */
    fun plan(downloads: AppShortcut, consoles: List<Pair<String, String>>, views: List<LibraryView>, max: Int = MAX_DYNAMIC): List<AppShortcut> =
        (listOf(downloads) + views.map(::view) + consoles.sortedBy { it.second.lowercase() }.map { (id, name) -> console(id, name) })
            .distinctBy { it.id }
            .take(max.coerceAtLeast(0))
}
