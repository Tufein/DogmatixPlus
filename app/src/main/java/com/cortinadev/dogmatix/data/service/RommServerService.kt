package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommServerParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val TAG = "RommServerService"
/** Automatic checks are at most this often; "Refresh" in the Cloud hub always asks. */
private const val MIN_INTERVAL_MS = 3L * 60 * 1000

/** Why talking to RomM failed, so the screens can say it in the user's language. */
enum class RommErrorKind {
    /** URL or token missing. */
    NOT_SET_UP,
    /** No answer: offline, wrong address, server down, timeout. */
    UNREACHABLE,
    /** The certificate is not trusted (Settings → RomM → Server certificate). */
    TLS,
    /** 401: wrong token or password. */
    AUTH,
    /** 403: the token lacks a scope (client tokens are created with chosen scopes). */
    FORBIDDEN,
    /** 404: the route or the item does not exist on this server (version). */
    NOT_FOUND,
    /** Any other answer (5xx, an unreadable payload). */
    SERVER
}

/** [e] sorted into a [RommErrorKind]. */
fun rommErrorKind(e: Throwable): RommErrorKind {
    val chain = generateSequence(e) { it.cause }.take(6).toList()
    chain.firstNotNullOfOrNull { it as? JsonHttp.HttpException }?.let { http ->
        return when (http.code) {
            401 -> RommErrorKind.AUTH
            403 -> RommErrorKind.FORBIDDEN
            404, 405 -> RommErrorKind.NOT_FOUND
            else -> RommErrorKind.SERVER
        }
    }
    return when {
        chain.any { it is SSLException } -> RommErrorKind.TLS
        chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException } -> RommErrorKind.UNREACHABLE
        e is RommException && e.message?.contains("not set", ignoreCase = true) == true -> RommErrorKind.NOT_SET_UP
        e is RommException -> RommErrorKind.SERVER
        chain.any { it is IOException } -> RommErrorKind.UNREACHABLE
        else -> RommErrorKind.SERVER
    }
}

data class RommServerUser(val id: Int?, val username: String, val role: String, val avatarUrl: String?)

data class RommServerCounts(
    val platforms: Int? = null,
    val roms: Int? = null,
    val saves: Int? = null,
    val states: Int? = null,
    val screenshots: Int? = null,
    val totalBytes: Long? = null
)

/** What the app knows about the RomM server right now (Cloud hub, RomM settings header). */
data class RommServerInfo(
    /** URL and token are filled in. */
    val configured: Boolean = false,
    /** A check is running. */
    val refreshing: Boolean = false,
    /** The server answered the last check. */
    val reachable: Boolean = false,
    /** `5.3.1`; null when unknown (older servers, or not reached yet). */
    val version: String? = null,
    val user: RommServerUser? = null,
    val counts: RommServerCounts = RommServerCounts(),
    /** When the last check finished; 0 = never. */
    val checkedAt: Long = 0L,
    /** Why the last check failed (or part of it: a reachable server with a bad token says [RommErrorKind.AUTH]). */
    val errorKind: RommErrorKind? = null,
    /** The technical detail of [errorKind], for a second line; never contains the token. */
    val error: String? = null
)

/**
 * Asks the RomM server about itself — `/api/heartbeat` (version), `/api/users/me` (the account),
 * `/api/stats` (counts) — and keeps the answer in [info]. Checks when the RomM address or token
 * changes (debounced), when a screen asks ([refresh]), and otherwise at most every few minutes
 * ([refreshIfStale]). The version also goes to [RommClient.serverVersion].
 */
@OptIn(FlowPreview::class)
@Singleton
class RommServerService @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val rommClient: RommClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _info = MutableStateFlow(RommServerInfo())
    val info: StateFlow<RommServerInfo> = _info.asStateFlow()

    init {
        scope.launch {
            var first = true
            combine(settingsRepository.rommUrl, settingsRepository.rommToken) { url, token -> url.trim().trimEnd('/') to token.trim() }
                .distinctUntilChanged()
                // The first value is the stored one: check at once; later edits are typed, so wait.
                .debounce { if (first) { first = false; 0L } else 1_500L }
                .collect { (url, token) ->
                    if (url.isEmpty() || token.isEmpty()) {
                        rommClient.serverVersion = null
                        _info.value = RommServerInfo(configured = false)
                    } else {
                        // Another server (or account): forget what the old one said before asking.
                        _info.value = RommServerInfo(configured = true)
                        check()
                    }
                }
        }
    }

    /** Checks now ([force]) or only when the last check is older than a few minutes. Returns at once. */
    fun refresh(force: Boolean = true) {
        scope.launch { if (force) check() else refreshIfStaleNow() }
    }

    /** A check when the last one is older than a few minutes (screens call it when they open). */
    fun refreshIfStale() = refresh(force = false)

    private suspend fun refreshIfStaleNow() {
        val last = _info.value
        if (!last.refreshing && System.currentTimeMillis() - last.checkedAt >= MIN_INTERVAL_MS) check()
    }

    /** Runs a check and returns its result (also published in [info]). */
    suspend fun check(): RommServerInfo = lock.withLock { checkLocked() }

    private suspend fun checkLocked(): RommServerInfo {
        val url = settingsRepository.rommUrl.first().trim().trimEnd('/')
        val token = settingsRepository.rommToken.first().trim()
        if (url.isEmpty() || token.isEmpty()) {
            return RommServerInfo(configured = false).also { _info.value = it }
        }
        _info.update { it.copy(configured = true, refreshing = true) }
        var reachable = false
        var version: String? = null
        var failure: Throwable? = null

        try {
            version = RommServerParser.version(rommClient.heartbeat())
            reachable = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Some proxies hide the heartbeat; the account call below still tells.
            failure = e
            Log.w(TAG, "Heartbeat failed: ${e.javaClass.simpleName}")
        }
        if (version != null) rommClient.serverVersion = version

        var user: RommServerUser? = null
        try {
            user = rommClient.currentUser()?.let { u ->
                RommServerUser(u.id, u.username, u.role, RommServerParser.avatarUrl(url, u.avatarPath))
            }
            reachable = true
            if (failure != null && rommErrorKind(failure) != RommErrorKind.AUTH) failure = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null || rommErrorKind(e) == RommErrorKind.AUTH || rommErrorKind(e) == RommErrorKind.FORBIDDEN) failure = e
            if (e is JsonHttp.HttpException) reachable = true
            Log.w(TAG, "Account check failed: ${e.javaClass.simpleName}")
        }

        var counts = _info.value.counts
        if (reachable && (failure == null || rommErrorKind(failure) == RommErrorKind.NOT_FOUND)) {
            counts = runCatching { rommClient.stats() }
                .map { s -> RommServerCounts(s.platforms, s.roms, s.saves, s.states, s.screenshots, s.totalBytes) }
                .getOrElse { RommServerCounts() }
            if (counts.platforms == null) {
                // Servers without /api/stats: the platform list is cheap and always there.
                runCatching { rommClient.platforms().size }.getOrNull()?.let { counts = counts.copy(platforms = it) }
            }
        }

        val result = RommServerInfo(
            configured = true,
            refreshing = false,
            reachable = reachable,
            version = version ?: _info.value.version.takeIf { reachable },
            user = user,
            counts = counts,
            checkedAt = System.currentTimeMillis(),
            errorKind = failure?.let { rommErrorKind(it) },
            error = failure?.let { describe(it) }
        )
        _info.value = result
        return result
    }

    /** A short technical line for an error: never the request headers, so never the token. */
    private fun describe(e: Throwable): String = when (e) {
        is JsonHttp.HttpException -> "HTTP ${e.code}"
        else -> (e.message ?: e.javaClass.simpleName).take(160)
    }
}
