package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.LibretroThumbnails
import com.tufein.valkyr.util.TheGamesDb
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Box art from TheGamesDB (4.4.0): a name search on the game's platform (with the box art of the
 * hits in the same answer, one request per game), and the Images endpoint when the answer did not
 * include it. The key is the user's own or, when they have none, the one the app already holds
 * for the game details ([CoverApis.Keys.tgdbKey]). A name that no hit matches closely is a miss,
 * never "the first result". The free allowance is small (monthly): when the service says it is
 * used up, the source rests until the next month. Written from TheGamesDB's public API
 * documentation; it could not be tried against the real service.
 */
@Singleton
class TheGamesDbService @Inject constructor(
    private val settings: CoverSourceSettings,
    private val stats: CoverSourceStats
) : CoverLookupSource {
    override val source = CoverSources.Source.THEGAMESDB
    private val gate = CoverApiGate(source, concurrency = 2, stats = stats)

    override suspend fun configured(): Boolean = settings.apiKeys.first().configured(source)

    override suspend fun usable(now: Long): Boolean {
        val keys = settings.apiKeys.first()
        return keys.configured(source) && gate.usable(now, keys.fingerprint(source))
    }

    override fun clearPause() = gate.clearPause()

    override suspend fun find(consoleId: String, fileName: String, title: String, regions: List<String>, nonBoxArt: Boolean): Answer {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return Answer.Miss
        val keys = settings.apiKeys.first()
        val key = keys.tgdbKey
        val fp = keys.fingerprint(source)
        if (key.isEmpty() || !gate.usable(System.currentTimeMillis(), fp)) return Answer.Unavailable
        val query = GameTitleCleaner.clean(title.ifBlank { fileName })
        if (query.isBlank()) return Answer.Miss
        val platform = TheGamesDb.PLATFORM_IDS[system.repo]

        val search = call(TheGamesDb.searchUrl(key, query, platform), fp)
        when (search.status) {
            CoverApis.Status.OK -> Unit
            CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> return Answer.Miss
            else -> return Answer.Unavailable
        }
        val page = TheGamesDb.parse(search.json) ?: return Answer.Unavailable
        val game = TheGamesDb.pick(query, page, platform) ?: return Answer.Miss
        var images = page.images[game.id]
        var base = page.baseUrls
        if (!page.boxartIncluded) {
            val reply = call(TheGamesDb.imagesUrl(key, game.id), fp)
            when (reply.status) {
                CoverApis.Status.OK -> Unit
                CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> return Answer.Miss
                else -> return Answer.Unavailable
            }
            val images2 = TheGamesDb.parse(reply.json) ?: return Answer.Unavailable
            images = images2.images[game.id]
            base = images2.baseUrls
        }
        val url = TheGamesDb.frontCover(images.orEmpty(), base) ?: return Answer.Miss
        return Answer.Found(url)
    }

    private suspend fun call(url: String, fingerprint: Int): CoverApiGate.Reply {
        val reply = gate.request("GET", url, fingerprint)
        if (reply.status == CoverApis.Status.OK) noteAllowance(TheGamesDb.parse(reply.json)?.remaining)
        return reply
    }

    /** Remembers the monthly allowance the service reported; none left pauses the source until next month. */
    private fun noteAllowance(left: Int?) {
        if (left == null) return
        stats.allowance(left)
        if (left <= 0) {
            stats.pauseSource(source, CoverApis.nextMonthStart(System.currentTimeMillis()))
            stats.sourceProblem(source, CoverSourceStats.Problem.QUOTA)
        }
    }

    /** Asks the service whether the key works and how many requests are left this month. */
    suspend fun testKey(): CoverApis.KeyTest {
        val keys = settings.apiKeys.first()
        val key = keys.tgdbKey
        if (key.isEmpty()) return CoverApis.KeyTest.Missing
        val fp = keys.fingerprint(source)
        gate.forget()
        val reply = gate.request("GET", TheGamesDb.platformsUrl(key), fp, reacts = { false })
        return when (reply.status) {
            null -> CoverApis.KeyTest.Offline
            CoverApis.Status.OK -> {
                val page = TheGamesDb.parse(reply.json) ?: return CoverApis.KeyTest.Unreadable
                gate.worked()
                stats.pauseSource(source, 0)
                page.remaining?.let { stats.allowance(it) }
                CoverApis.KeyTest.Ok(page.remaining)
            }
            CoverApis.Status.KEY_REJECTED -> CoverApis.KeyTest.Rejected.also { gate.refused(fp) }
            CoverApis.Status.RATE_LIMITED -> CoverApis.KeyTest.RateLimited
            else -> CoverApis.KeyTest.Busy
        }
    }
}
