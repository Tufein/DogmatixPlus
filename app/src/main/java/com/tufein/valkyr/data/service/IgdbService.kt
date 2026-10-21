package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.CoverSources.Answer
import com.tufein.valkyr.util.GameTitleCleaner
import com.tufein.valkyr.util.Igdb
import com.tufein.valkyr.util.LibretroThumbnails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers from IGDB (4.4.0). IGDB sits behind Twitch: the user's client id and secret are traded for
 * an app access token (client credentials) that is kept in memory until a minute before it
 * expires, and every search carries the client id and that token. The best hit on the game's
 * console whose title matches closely gives the cover (`t_cover_big`); no hit that matches closely
 * is a miss. The secret goes only to id.twitch.tv, in a form body; the token only to api.igdb.com.
 * Written from IGDB's and Twitch's public API documentation; it could not be tried against the
 * real services.
 */
@Singleton
class IgdbService @Inject constructor(
    private val settings: CoverSourceSettings,
    stats: CoverSourceStats
) : CoverLookupSource {
    override val source = CoverSources.Source.IGDB
    private val gate = CoverApiGate(source, concurrency = 3, stats = stats)

    private val tokenLock = Mutex()
    @Volatile private var token: Igdb.Token? = null
    /** The keys the cached token was issued for: other keys never use it. */
    @Volatile private var tokenFor = 0

    override suspend fun configured(): Boolean = settings.apiKeys.first().configured(source)

    override suspend fun usable(now: Long): Boolean {
        val keys = settings.apiKeys.first()
        return keys.configured(source) && gate.usable(now, keys.fingerprint(source))
    }

    override fun clearPause() = gate.clearPause()

    private sealed interface Bearer {
        data class Ok(val token: String) : Bearer
        data class Failed(val test: CoverApis.KeyTest) : Bearer
    }

    /** The cached token, or a new one from Twitch. [reacts] off for the key test. */
    private suspend fun bearer(keys: CoverApis.Keys, fresh: Boolean, reacts: Boolean): Bearer = tokenLock.withLock {
        val fp = keys.fingerprint(source)
        token?.takeIf { !fresh && tokenFor == fp && it.validAt(System.currentTimeMillis()) }?.let { return Bearer.Ok(it.accessToken) }
        token = null
        val reply = gate.request(
            "POST", Igdb.TOKEN_URL, fp,
            body = Igdb.tokenBody(keys.igdbClientId, keys.igdbClientSecret), contentType = JsonHttp.FORM_URLENCODED,
            // Twitch refuses a bad client id with 400 and a bad secret with 403: both are a refused key here.
            classify = { code -> if (code in setOf(400, 401, 403)) CoverApis.Status.KEY_REJECTED else CoverApis.status(code) },
            reacts = { reacts }
        )
        val failure: CoverApis.KeyTest = when (reply.status) {
            null -> CoverApis.KeyTest.Offline
            CoverApis.Status.OK -> {
                val issued = Igdb.parseToken(reply.json, System.currentTimeMillis())
                if (issued != null) {
                    token = issued; tokenFor = fp
                    return Bearer.Ok(issued.accessToken)
                }
                CoverApis.KeyTest.Unreadable
            }
            CoverApis.Status.KEY_REJECTED -> Igdb.tokenRefusal(reply.code, reply.json) ?: CoverApis.KeyTest.Rejected
            CoverApis.Status.RATE_LIMITED -> CoverApis.KeyTest.RateLimited
            else -> CoverApis.KeyTest.Busy
        }
        Bearer.Failed(failure)
    }

    private fun headers(keys: CoverApis.Keys, bearer: String) =
        mapOf("Client-ID" to keys.igdbClientId, "Authorization" to "Bearer $bearer")

    override suspend fun find(consoleId: String, fileName: String, title: String, regions: List<String>, nonBoxArt: Boolean): Answer {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return Answer.Miss
        val keys = settings.apiKeys.first()
        val fp = keys.fingerprint(source)
        if (!keys.configured(source) || !gate.usable(System.currentTimeMillis(), fp)) return Answer.Unavailable
        val query = GameTitleCleaner.clean(title.ifBlank { fileName })
        if (query.isBlank()) return Answer.Miss
        val body = Igdb.searchBody(query).toByteArray(Charsets.UTF_8)

        var fresh = false
        repeat(2) { attempt ->
            val bearer = (bearer(keys, fresh, reacts = true) as? Bearer.Ok)?.token ?: return Answer.Unavailable
            val reply = gate.request(
                "POST", Igdb.GAMES_URL, fp, headers(keys, bearer), body, "text/plain",
                // A first 401 usually is an expired token: it gets one new token before the key counts as refused.
                reacts = { !(attempt == 0 && it == CoverApis.Status.KEY_REJECTED) }
            )
            when (reply.status) {
                CoverApis.Status.OK -> {
                    val game = Igdb.pick(query, Igdb.parseGames(reply.json), Igdb.PLATFORM_IDS[system.repo]) ?: return Answer.Miss
                    return Answer.Found(Igdb.coverUrl(game.imageId ?: return Answer.Miss))
                }
                CoverApis.Status.KEY_REJECTED -> { if (attempt == 0) { fresh = true } else { return Answer.Unavailable } }
                CoverApis.Status.NOT_FOUND, CoverApis.Status.BAD_REQUEST -> return Answer.Miss
                else -> return Answer.Unavailable
            }
        }
        return Answer.Unavailable
    }

    /** Gets a token with the client id and secret, then asks IGDB for one game to see it accepts them. */
    suspend fun testKey(): CoverApis.KeyTest {
        val keys = settings.apiKeys.first()
        if (!keys.configured(source)) return CoverApis.KeyTest.Missing
        val fp = keys.fingerprint(source)
        gate.forget()
        val bearer = when (val b = bearer(keys, fresh = true, reacts = false)) {
            is Bearer.Ok -> b.token
            is Bearer.Failed -> {
                if (b.test == CoverApis.KeyTest.IdRejected || b.test == CoverApis.KeyTest.SecretRejected || b.test == CoverApis.KeyTest.Rejected) gate.refused(fp)
                return b.test
            }
        }
        val reply = gate.request(
            "POST", Igdb.GAMES_URL, fp, headers(keys, bearer), Igdb.PING_BODY.toByteArray(Charsets.UTF_8), "text/plain",
            reacts = { false }
        )
        return when (reply.status) {
            null -> CoverApis.KeyTest.Offline
            CoverApis.Status.OK -> CoverApis.KeyTest.Ok().also { gate.worked() }
            CoverApis.Status.KEY_REJECTED -> CoverApis.KeyTest.Rejected.also { gate.refused(fp) }
            CoverApis.Status.RATE_LIMITED -> CoverApis.KeyTest.RateLimited
            else -> CoverApis.KeyTest.Busy
        }
    }
}
