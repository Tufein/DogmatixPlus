package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.SteamGridDb
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Artwork from SteamGridDB (4.4.0): a name search, then the portrait static grids of the game that
 * matches closely, best-voted first. SteamGridDB art is community artwork and not always the
 * official box. The API key is sent as a bearer header to www.steamgriddb.com only. Written from
 * SteamGridDB's public API documentation; it could not be tried against the real service.
 */
@Singleton
class SteamGridDbService @Inject constructor(
    private val settings: CoverSourceSettings,
    stats: CoverSourceStats
) : CoverLookupSource {
    override val source = CoverSources.Source.STEAMGRIDDB
    private val gate = CoverApiGate(source, concurrency = 2, stats = stats)

    override suspend fun configured(): Boolean = settings.apiKeys.first().configured(source)

    override suspend fun usable(now: Long): Boolean {
        val keys = settings.apiKeys.first()
        return keys.configured(source) && gate.usable(now, keys.fingerprint(source))
    }

    override fun clearPause() = gate.clearPause()

    override suspend fun find(consoleId: String, fileName: String, title: String, regions: List<String>, nonBoxArt: Boolean): Answer {
        val keys = settings.apiKeys.first()
        val key = keys.sgdbKey
        val fp = keys.fingerprint(source)
        if (key.isEmpty() || !gate.usable(System.currentTimeMillis(), fp)) return Answer.Unavailable
        val query = GameTitleCleaner.clean(title.ifBlank { fileName })
        if (query.isBlank()) return Answer.Miss
        val headers = mapOf("Authorization" to SteamGridDb.authorization(key))

        val search = gate.request("GET", SteamGridDb.searchUrl(query), fp, headers)
        when (search.status) {
            CoverApis.Status.OK -> Unit
            CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> return Answer.Miss
            else -> return Answer.Unavailable
        }
        val game = SteamGridDb.pickGame(query, SteamGridDb.parseSearch(search.json)) ?: return Answer.Miss

        val grids = gate.request("GET", SteamGridDb.gridsUrl(game.id), fp, headers)
        return when (grids.status) {
            CoverApis.Status.OK -> SteamGridDb.pickGrid(SteamGridDb.parseGrids(grids.json))?.let { Answer.Found(it) } ?: Answer.Miss
            CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> Answer.Miss
            else -> Answer.Unavailable
        }
    }

    /** Asks the service whether the key works. */
    suspend fun testKey(): CoverApis.KeyTest {
        val keys = settings.apiKeys.first()
        val key = keys.sgdbKey
        if (key.isEmpty()) return CoverApis.KeyTest.Missing
        val fp = keys.fingerprint(source)
        gate.forget()
        val reply = gate.request("GET", SteamGridDb.searchUrl("zelda"), fp, mapOf("Authorization" to SteamGridDb.authorization(key)), reacts = { false })
        return when (reply.status) {
            null -> CoverApis.KeyTest.Offline
            CoverApis.Status.OK, CoverApis.Status.NOT_FOUND -> CoverApis.KeyTest.Ok().also { gate.worked() }
            CoverApis.Status.KEY_REJECTED -> CoverApis.KeyTest.Rejected.also { gate.refused(fp) }
            CoverApis.Status.RATE_LIMITED -> CoverApis.KeyTest.RateLimited
            else -> CoverApis.KeyTest.Busy
        }
    }
}
