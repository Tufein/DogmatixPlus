package com.tufein.valkyr.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Remote install (5.6.0): a game picked in RomM's web interface ("Install on device") becomes a normal download in
 * the queue of this handheld. Pure JVM for the tests; [com.tufein.valkyr.data.service.RommRemoteInstallService] does the asking.
 *
 * VERIFIED against the RomM source code (github.com/rommapp/romm main branch, read in October 2026:
 * `backend/endpoints/device/auth.py`, `device/install.py`, `sockets/devices.py`, `handler/device_install/`,
 * `endpoints/heartbeat.py`), NOT against a running server and not on a handheld:
 * - The server keeps a queue of install requests per registered device (in Redis, two days by default,
 *   `DEVICE_INSTALL_REQUEST_TTL_DAYS`). It is switched on with `DEVICE_INSTALL_ENABLED` (default true) and says so in
 *   `GET /api/heartbeat` → `DEVICE_INSTALL.ENABLED`.
 * - A request can only be queued for a device whose `capabilities` hold `remote_install: true`, and only a client
 *   token that is BOUND TO THAT DEVICE can claim and report. A token made by the plain "pair with code" of
 *   [RommPairing] is not bound to a device, so remote install pairs the handheld on its own, with RomM's device
 *   authorisation (RFC 8628 style): `POST /api/auth/device/init` gives a `user_code` that the user approves in the
 *   web interface (`/pair/device?user_code=`); the app polls `POST /api/auth/device/token` until the answer is an
 *   `access_token` (a token bound to a new or known device, with the scopes that were approved).
 * - Polling: `POST /api/devices/{id}/installs/claim` takes every pending request and returns ALL requests the device holds
 *   as `taken` (so the same request comes back until it ends); `PUT /api/devices/{id}/installs/{request}` with
 *   `{"status": "done" | "already_installed" | "failed", "reason": "…"}` ends one. The server also offers a socket
 *   (`/devices` namespace); this app does not use it, a poll is enough for a handheld and costs no open connection.
 * - A request lists `rom_id` and `file_ids`; the app downloads the game through the library row it already has for that
 *   RomM game (the whole game, as the normal download does), not file by file.
 * - "done" means "added to downloads": RomM's own notification text for it is "{game} was added to downloads on
 *   {device}" (`frontend/src/locales/en_US/notifications.json`), and its Install on device dialog shows
 *   "Added to downloads". The app therefore reports done as soon as the download is queued; what the queue does next
 *   (a failure, a stop) is the queue's business and is not sent back.
 */
object RommRemoteInstall {

    /** The device flag RomM checks before it queues a request. */
    const val CAPABILITY = "remote_install"
    const val CLIENT_NAME = "valkyr"

    /** What the device token is asked to be allowed (the server only grants what the account holds). */
    val SCOPES = listOf("me.read", "roms.read", "devices.read", "devices.write")

    /** The poll while the app is open, and the least time between two polls. */
    const val FOREGROUND_POLL_MS = 60_000L
    /** The background job (JobScheduler cannot run a periodic job more often than every 15 minutes). */
    const val BACKGROUND_POLL_MS = 15 * 60_000L

    /** Settings of this feature; all but the switch belong to this device only and never go into a backup ([SECRETS]). */
    const val ENABLED_KEY = "romm55_remote_install"
    const val DEVICE_TOKEN_KEY = "romm55_device_token"
    const val DEVICE_ID_KEY = "romm55_device_id"
    const val DEVICE_IDENTIFIER_KEY = "romm55_device_identifier"
    val SETTING_TYPES: Map<String, String> = mapOf(
        ENABLED_KEY to "b", DEVICE_TOKEN_KEY to "s", DEVICE_ID_KEY to "s", DEVICE_IDENTIFIER_KEY to "s"
    )
    val SECRETS: Set<String> = setOf(DEVICE_TOKEN_KEY, DEVICE_ID_KEY, DEVICE_IDENTIFIER_KEY)

    // ---- server support --------------------------------------------------------------------

    enum class Support { YES, NO, UNKNOWN }

    /** `DEVICE_INSTALL.ENABLED` of the heartbeat; [Support.UNKNOWN] for a server that does not list it (older than the feature). */
    fun support(heartbeat: JsonElement?): Support {
        val enabled = heartbeat?.takeIf { it.isJsonObject }?.asJsonObject?.get("DEVICE_INSTALL")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("ENABLED")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }
        return when (enabled) { true -> Support.YES; false -> Support.NO; null -> Support.UNKNOWN }
    }

    // ---- device authorisation ---------------------------------------------------------------

    data class Init(val deviceCode: String, val userCode: String, val verificationPath: String, val expiresInSeconds: Int, val intervalSeconds: Int)

    fun initBody(identifier: String, name: String, version: String): String = JsonObject().apply {
        addProperty("client_device_identifier", identifier)
        addProperty("name", name.take(255))
        addProperty("client", CLIENT_NAME)
        addProperty("platform", "android")
        if (version.isNotBlank()) addProperty("client_version", version.take(50))
        add("requested_scopes", JsonArray().also { a -> SCOPES.forEach(a::add) })
    }.toString()

    fun parseInit(status: Int, json: JsonElement?): Init? {
        if (status !in 200..299) return null
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val device = o.text("device_code").orEmpty()
        val user = o.text("user_code").orEmpty()
        if (device.isEmpty() || user.isEmpty()) return null
        val path = (o.text("verification_path_complete") ?: o.text("verification_path")).orEmpty()
        return Init(
            device, user, path,
            o.int("expires_in")?.coerceIn(30, 3_600) ?: 600,
            o.int("interval")?.coerceIn(2, 60) ?: 5
        )
    }

    /** The address where the user approves: the configured server plus the path RomM names (a path, never another host). */
    fun verificationUrl(base: String, path: String): String? {
        val p = path.trim()
        if (!p.startsWith("/") || p.startsWith("//") || p.contains("://") || p.any { it.isWhitespace() }) return null
        return base.trim().trimEnd('/') + p
    }

    /** `ABCD-EFGH` for reading aloud or comparing with the screen of the web interface. */
    fun displayUserCode(code: String): String = code.filter { it.isLetterOrDigit() }.uppercase().let { if (it.length == 8) it.substring(0, 4) + "-" + it.substring(4) else it }

    sealed interface PairPoll {
        /** Not approved yet; [slowDown] asks for a longer wait between polls. */
        data class Waiting(val slowDown: Boolean) : PairPoll
        data class Approved(val token: String, val deviceId: String, val scopes: List<String>) : PairPoll
        data object Denied : PairPoll
        data object Expired : PairPoll
        data class Failed(val status: Int) : PairPoll
    }

    fun parsePoll(status: Int, json: JsonElement?): PairPoll {
        val o = json?.takeIf { it.isJsonObject }?.asJsonObject
        if (status in 200..299) {
            val token = o?.text("access_token").orEmpty()
            val device = o?.text("device_id").orEmpty()
            if (!token.startsWith(RommPairing.TOKEN_PREFIX) || device.isEmpty()) return PairPoll.Failed(0)
            val scopes = (o?.get("scopes") as? JsonArray)?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
            return PairPoll.Approved(token, device, scopes)
        }
        if (status == 429) return PairPoll.Waiting(slowDown = true)
        if (status == 400) {
            // FastAPI wraps the error as {"detail": "authorization_pending"}.
            return when (o?.text("detail")) {
                "authorization_pending" -> PairPoll.Waiting(false)
                "slow_down" -> PairPoll.Waiting(true)
                "access_denied" -> PairPoll.Denied
                "expired_token" -> PairPoll.Expired
                else -> PairPoll.Failed(status)
            }
        }
        return PairPoll.Failed(status)
    }

    /** The wait before the next poll: RFC 8628 adds five seconds after `slow_down`. */
    fun nextInterval(current: Int, slowDown: Boolean): Int = if (slowDown) (current + 5).coerceAtMost(30) else current

    /** Scopes the claim needs; a token approved without them cannot work and is not kept. */
    fun scopesSufficient(scopes: List<String>): Boolean = "devices.write" in scopes && "roms.read" in scopes

    fun capabilitiesBody(): String = JsonObject().apply { add("capabilities", JsonObject().apply { addProperty(CAPABILITY, true) }) }.toString()

    // ---- the install queue ------------------------------------------------------------------

    data class Request(val id: String, val romId: Int, val fileIds: List<Int>)

    /**
     * The requests the device holds as `taken` (what `claim` returns: the ones it just took and the ones it took
     * before). Entries without an id or a rom id are left out.
     */
    fun parseClaim(json: JsonElement?): List<Request> {
        val array = json?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.text("id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
            val rom = o.int("rom_id")?.takeIf { it > 0 } ?: return@mapNotNull null
            if (o.text("status")?.let { it != "taken" } == true) return@mapNotNull null
            val files = (o.get("file_ids") as? JsonArray)?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty()
            Request(id, rom, files)
        }.distinctBy { it.id }
    }

    /** Why a claim did not give a list. */
    enum class ClaimFailure {
        /** 401/403/404: the token was revoked, is not bound to the device, or the device was removed in RomM. The pairing is of no use. */
        PAIRING_LOST,
        /** 404 "Installing on a device is disabled on this server": the pairing stays, the server may switch it on again. */
        DISABLED_ON_SERVER,
        /** Anything else (offline, server busy): try again at the next poll. */
        TEMPORARY
    }

    fun claimFailure(status: Int, body: String): ClaimFailure = when {
        status == 404 && body.contains("disabled", ignoreCase = true) -> ClaimFailure.DISABLED_ON_SERVER
        status == 401 || status == 403 || status == 404 -> ClaimFailure.PAIRING_LOST
        else -> ClaimFailure.TEMPORARY
    }

    fun reportBody(status: Report.Kind, reason: String?): String = JsonObject().apply {
        addProperty("status", status.wire)
        if (!reason.isNullOrBlank()) addProperty("reason", reason.take(500))
    }.toString()

    /** What the device tells the server about a request it has finished with. */
    data class Report(val kind: Kind, val reason: String? = null) {
        enum class Kind(val wire: String) { DONE("done"), ALREADY_INSTALLED("already_installed"), FAILED("failed") }
    }

    /** Reasons sent with a failure (shown by RomM in its notification; plain English, no paths, no names). */
    object Reasons {
        const val NOT_IN_LIBRARY = "This game is not in the Valkyr library of this device (add the RomM source for its platform and rescan)."
    }

    /** A library row that is this RomM game, reduced to what the decision needs. */
    data class Row(val key: Long, val fileName: String, val owned: Boolean)

    sealed interface Decision {
        /** Queue the row with this [Row.key]. */
        data class Start(val row: Row) : Decision
        /** The game is on the device already: tell the server and start nothing. */
        data object Owned : Decision
        data class Fail(val reason: String) : Decision
    }

    /** What to do with a new [request] given the library rows of its RomM game ([rows], any order). */
    fun decide(rows: List<Row>): Decision = when {
        rows.isEmpty() -> Decision.Fail(Reasons.NOT_IN_LIBRARY)
        rows.any { it.owned } -> Decision.Owned
        else -> Decision.Start(rows.minWith(compareBy<Row> { it.fileName.length }.thenBy { it.key }))
    }

    // ---- when to ask -------------------------------------------------------------------------

    /** Whether the open app should poll now: on, paired, and a minute since the last poll. */
    fun foregroundDue(enabled: Boolean, paired: Boolean, lastPollMs: Long, nowMs: Long): Boolean =
        enabled && paired && (lastPollMs <= 0 || nowMs - lastPollMs >= FOREGROUND_POLL_MS || nowMs < lastPollMs)

    private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
    private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
}
