package com.tufein.valkyr.util

import com.tufein.valkyr.data.model.DownloadStatus
import java.net.URLEncoder

/**
 * 8.0: the rules of the full-screen game page (which tabs it has, what the main button does,
 * which secondary actions show, the order of the versions) and its navigation route. Pure JVM for
 * the tests; the screen only draws what these decide.
 */
object GamePageModel {

    /** Route pattern of the page; [route] fills it in. */
    const val ROUTE = "game/{consoleId}/{fileName}"
    const val ARG_CONSOLE = "consoleId"
    const val ARG_FILE = "fileName"

    /** The route for one game. Both parts are URL-encoded (file names hold spaces, slashes, '#', '?', '%'). */
    fun route(consoleId: String, fileName: String): String = "game/${encode(consoleId)}/${encode(fileName)}"

    /**
     * Path-safe encoding: [URLEncoder] writes a space as '+', which a path segment would keep as a
     * plus, so it becomes %20 (a real '+' is already %2B by then).
     */
    fun encode(part: String): String = URLEncoder.encode(part, "UTF-8").replace("+", "%20")
    // Navigation decodes path arguments itself (Uri.decode), so the page reads them as they come.

    // ---- Tabs ------------------------------------------------------------------------------------

    enum class Tab { ABOUT, VERSIONS, PROGRESS, SIMILAR }

    /**
     * The tabs worth showing: About always; Versions with more than one version; Progress when
     * RetroAchievements or RomM can say something; "More like this" once it has games.
     */
    fun tabs(versionCount: Int, progress: Boolean, similarCount: Int): List<Tab> = buildList {
        add(Tab.ABOUT)
        if (versionCount > 1) add(Tab.VERSIONS)
        if (progress) add(Tab.PROGRESS)
        if (similarCount > 0) add(Tab.SIMILAR)
    }

    /** The tab [delta] steps from [current] (L1 / R1), wrapping around; the first tab when [current] is gone. */
    fun step(tabs: List<Tab>, current: Tab, delta: Int): Tab {
        if (tabs.isEmpty()) return Tab.ABOUT
        val index = tabs.indexOf(current)
        if (index < 0) return tabs.first()
        return tabs[((index + delta) % tabs.size + tabs.size) % tabs.size]
    }

    /** [current] while the page still has it (tabs come and go as facts arrive), else About. */
    fun visible(tabs: List<Tab>, current: Tab): Tab = if (current in tabs) current else tabs.firstOrNull() ?: Tab.ABOUT

    // ---- Actions ---------------------------------------------------------------------------------

    /** What the big button in the sticky action bar does. */
    enum class Primary { DOWNLOAD, DOWNLOADING, DOWNLOAD_AGAIN, PLAY, RESUME, RETRY }

    /** Where the game's download stands in the queue, as far as the primary action cares. */
    enum class Queue { NONE, ACTIVE, PAUSED, FAILED }

    /**
     * Queued, downloading, copying and unpacking are [Queue.ACTIVE]; a parked one is [Queue.PAUSED];
     * only a real failure is [Queue.FAILED] (a download the user stopped is simply downloadable again).
     */
    fun queueOf(status: DownloadStatus?): Queue = when (status) {
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING -> Queue.ACTIVE
        DownloadStatus.PAUSED -> Queue.PAUSED
        DownloadStatus.FAILED -> Queue.FAILED
        DownloadStatus.COMPLETED, DownloadStatus.STOPPED, null -> Queue.NONE
    }

    fun primary(owned: Boolean, downloading: Boolean): Primary =
        primary(owned, if (downloading) Queue.ACTIVE else Queue.NONE)

    /**
     * The one big action: a running download shows as busy; a game on the device is Play (a paused
     * or failed re-download never hides it); otherwise a parked download Resumes, a failed one
     * Retries and anything else Downloads.
     */
    fun primary(owned: Boolean, queue: Queue): Primary = when {
        queue == Queue.ACTIVE -> Primary.DOWNLOADING
        owned -> Primary.PLAY
        queue == Queue.PAUSED -> Primary.RESUME
        queue == Queue.FAILED -> Primary.RETRY
        else -> Primary.DOWNLOAD
    }

    enum class Action { DOWNLOAD_BEST, UPDATE, DLC, DOWNLOAD_WHEN, FAVOURITE, COLLECTIONS, SHARE, REMOVE }

    /**
     * The secondary actions, in the order they are drawn. A better version only when it is another
     * file; "download when" only for a game that is neither on disk nor on its way; remove only for
     * a game on disk (and never while it downloads).
     */
    fun actions(
        owned: Boolean,
        downloading: Boolean,
        betterVersion: Boolean,
        updateAvailable: Boolean,
        missingDlc: Int,
        collections: Boolean = true,
        share: Boolean = true
    ): List<Action> = buildList {
        if (betterVersion) add(Action.DOWNLOAD_BEST)
        if (updateAvailable) add(Action.UPDATE)
        if (missingDlc > 0) add(Action.DLC)
        if (!owned && !downloading) add(Action.DOWNLOAD_WHEN)
        add(Action.FAVOURITE)
        if (collections) add(Action.COLLECTIONS)
        if (share) add(Action.SHARE)
        if (owned && !downloading) add(Action.REMOVE)
    }

    // ---- Versions --------------------------------------------------------------------------------

    /**
     * Versions for the Versions tab: the best one first, then the one this page is about, then the
     * rest in the library's order. [fileName] reads an item's file name.
     */
    fun <T> orderVersions(items: List<T>, fileName: (T) -> String, current: String, best: String?): List<T> {
        fun rank(item: T): Int = when (fileName(item)) {
            best -> 0
            current -> 1
            else -> 2
        }
        return items.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    /** Region and language tags for the header (at most [max]), in the file's own order. */
    fun heroTags(tags: List<String>, max: Int = 4): List<String> = tags
        .filter { val kind = TagClassifier.kindOf(it); kind == TagClassifier.Kind.REGION || kind == TagClassifier.Kind.LANGUAGE }
        .distinct()
        .take(max)
}
