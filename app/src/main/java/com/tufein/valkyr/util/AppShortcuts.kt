package com.tufein.valkyr.util

import java.net.URLEncoder

/**
 * One app shortcut (the kind launchers show on a long press, and frontends such as Cocoon import
 * as a game tile): a console's library, a saved view, the Downloads section, or (3.5) a game that
 * starts straight in its emulator.
 */
data class AppShortcut(
    val id: String,
    val label: String,
    /** `valkyr://library?…` to open, or null for a section ([route]). */
    val deepLink: String? = null,
    val route: String? = null,
    /** The console it belongs to, so a frontend can file it under that platform. */
    val consoleId: String? = null,
    /** 7.0: a quick action (Surprise me, Search) instead of a link or a section; see [QuickActions]. */
    val quick: QuickAction? = null,
    /** 3.5: the library key of a game to start ([GameShortcuts.Key.value]) instead of anything above. */
    val game: String? = null
)

/** Builds the shortcuts Dogmatix+ offers. Pure JVM so it can be unit-tested. */
object AppShortcuts {
    const val DOWNLOADS_ID = "section:downloads"
    /** 7.0 quick access. Both ids start with `section:` so the shortcut picker files them under Sections. */
    const val SURPRISE_ID = "section:surprise"
    const val SEARCH_ID = "section:search"
    /** Android keeps at most this many dynamic shortcuts per activity on most devices. */
    const val MAX_DYNAMIC = 15

    fun downloads(label: String, route: String) = AppShortcut(DOWNLOADS_ID, label, route = route)

    fun surprise(label: String) = AppShortcut(SURPRISE_ID, label, quick = QuickAction.SURPRISE)

    fun search(label: String) = AppShortcut(SEARCH_ID, label, quick = QuickAction.SEARCH)

    fun console(consoleId: String, name: String) = AppShortcut(
        id = "console:$consoleId", label = name,
        deepLink = "${DeepLinkParser.SCHEME}://${DeepLinkParser.HOST_LIBRARY}?console=" + URLEncoder.encode(consoleId, "UTF-8").replace("+", "%20"),
        consoleId = consoleId
    )

    /** 3.5: a game to start, labelled with its clean title; null when the key cannot be carried (see [GameShortcuts.key]). */
    fun game(consoleId: String, fileName: String, label: String): AppShortcut? =
        GameShortcuts.key(consoleId, fileName)?.let { AppShortcut(it.shortcutId, label, consoleId = consoleId, game = it.value) }

    fun view(view: LibraryView) = AppShortcut(
        id = "view:${view.id}", label = view.name, deepLink = view.deepLink(),
        consoleId = view.consoles.singleOrNull()
    )

    /**
     * (3.5) The recently played [games] first, newest first and at most [GameShortcuts.RECENT_COUNT]
     * (none when [showGames] is off): a launcher shows only the first few shortcuts of the icon
     * menu, and the games are what that menu is for. Then the quick actions (Surprise me,
     * Downloads, Search), saved views, then consoles A–Z, cut to [max]. Without [surprise] /
     * [search] it is Downloads first among them, as before.
     */
    fun plan(
        downloads: AppShortcut,
        consoles: List<Pair<String, String>>,
        views: List<LibraryView>,
        max: Int = MAX_DYNAMIC,
        surprise: AppShortcut? = null,
        search: AppShortcut? = null,
        games: List<AppShortcut> = emptyList(),
        showGames: Boolean = true
    ): List<AppShortcut> =
        (GameShortcuts.menuGames(games, showGames, { it.id }) + listOfNotNull(surprise, downloads, search) + views.map(::view) +
            consoles.sortedBy { it.second.lowercase() }.map { (id, name) -> console(id, name) })
            .distinctBy { it.id }
            .take(max.coerceAtLeast(0))
}
