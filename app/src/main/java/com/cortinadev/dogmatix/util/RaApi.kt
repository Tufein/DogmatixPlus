package com.cortinadev.dogmatix.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.roundToLong

/** One game of the user's "recently played" list, with how far they got in it. */
data class RaRecentGame(
    val gameId: Int,
    val title: String,
    /** RA's console id and name ("Mega Drive"); the name also picks the placeholder colour. */
    val consoleId: Int?,
    val consoleName: String,
    val iconUrl: String?,
    val lastPlayed: Long?,
    /** Achievements earned in either mode, of [possible]. */
    val achieved: Int,
    val achievedHardcore: Int,
    val possible: Int,
    val scoreAchieved: Int,
    val possibleScore: Int
) {
    /** 0..1 share of the game's achievements the user has. */
    val fraction: Float get() = if (possible > 0) (achieved.toFloat() / possible).coerceIn(0f, 1f) else 0f
    /** Every achievement earned in hardcore. */
    val mastered: Boolean get() = possible > 0 && achievedHardcore >= possible
    /** Every achievement earned, some only in softcore. */
    val completed: Boolean get() = possible > 0 && achieved >= possible
}

/** An achievement the user unlocked lately (the summary's RecentAchievements). */
data class RaRecentUnlock(
    val achievementId: Int,
    val gameId: Int,
    val gameTitle: String,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String,
    val hardcore: Boolean,
    val unlockedAt: Long?
)

/** `API_GetUserSummary.php`: who the user is and what they played lately. */
data class RaUserSummary(
    val user: String,
    val avatarUrl: String?,
    /** Hardcore points (RA's headline number). */
    val points: Int,
    val softcorePoints: Int,
    /** Weighted points: "RetroPoints", formerly "true points". */
    val truePoints: Int,
    /** Null when RA does not rank the user (no hardcore points, or untracked). */
    val rank: Int?,
    val totalRanked: Int?,
    val motto: String,
    val richPresence: String,
    val memberSince: Long?,
    val recentlyPlayed: List<RaRecentGame>,
    val recentUnlocks: List<RaRecentUnlock>
) {
    /** "Top N %" of ranked players, or null. */
    val topPercent: Int? get() = RaApi.topPercent(rank, totalRanked)
}

/** One achievement of a game, with the user's unlock. */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    /** Weighted points (RetroPoints); 0 when RA did not send it. */
    val truePoints: Int,
    val badgeName: String,
    /** "progression", "win_condition", "missable" or null. */
    val type: String?,
    val displayOrder: Int,
    val earned: Boolean,
    val earnedHardcore: Boolean,
    /** When it was earned (hardcore date when there is one); null when unknown or not earned. */
    val earnedAt: Long?,
    /** Share (0..1) of the game's players who have it, or null when RA did not say. */
    val rarity: Float?
) {
    val badgeUrl: String get() = RaApi.badgeUrl(badgeName, locked = false)
    /** RA's own greyed version of the badge. */
    val lockedBadgeUrl: String get() = RaApi.badgeUrl(badgeName, locked = true)
    val missable: Boolean get() = type.equals("missable", ignoreCase = true)
}

/** The highest award the user holds for a game. */
enum class RaAward { NONE, BEATEN_SOFTCORE, BEATEN, COMPLETED, MASTERED }

/** `API_GetGameInfoAndUserProgress.php`: a game's achievements and the user's progress in it. */
data class RaGameProgress(
    val gameId: Int,
    val title: String,
    val consoleName: String,
    val iconUrl: String?,
    /** In RA's display order. */
    val achievements: List<RaAchievement>,
    val total: Int,
    val earned: Int,
    val earnedHardcore: Int,
    val points: Int,
    val earnedPoints: Int,
    /** 0..1 */
    val completion: Float,
    val award: RaAward,
    val players: Int?
) {
    val hasAchievements: Boolean get() = total > 0
}

/** Why an RA call failed; the UI turns it into a sentence. */
enum class RaErrorKind { NO_ACCOUNT, BAD_KEY, NOT_FOUND, RATE_LIMITED, OFFLINE, SERVER, BAD_RESPONSE }

/**
 * RetroAchievements' web API for the user's own profile and per-game progress: URLs, and parsers
 * that never throw. RA's JSON is loose — numbers sometimes arrive as strings ("1234", "52.17%"),
 * empty maps arrive as `[]`, `Achievements` is an object keyed by id, and `DateEarned` is missing
 * or empty for what is not earned — so every field is read defensively. Pure JVM for the tests.
 */
object RaApi {

    const val API_BASE = "https://retroachievements.org/API/"
    const val MEDIA_BASE = "https://media.retroachievements.org"
    private const val DEFAULT_BADGE = "00000"

    // ---- URLs (the key is the user's own web API key; never log these) ----

    fun userSummaryUrl(user: String, key: String, recentGames: Int = 5, recentAchievements: Int = 5): String =
        "${API_BASE}API_GetUserSummary.php?z=${enc(user)}&y=${enc(key)}&u=${enc(user)}" +
            "&g=${recentGames.coerceIn(0, 50)}&a=${recentAchievements.coerceIn(0, 50)}"

    fun gameProgressUrl(gameId: Int, user: String, key: String): String =
        "${API_BASE}API_GetGameInfoAndUserProgress.php?z=${enc(user)}&y=${enc(key)}&g=$gameId&u=${enc(user)}&a=1"

    /** A media path from the API ("/Images/067895.png") as a full URL; full URLs stay as they are. */
    fun mediaUrl(path: String?): String? {
        val p = path?.trim().orEmpty()
        if (p.isEmpty() || p.equals("null", ignoreCase = true)) return null
        return when {
            p.startsWith("https://", ignoreCase = true) || p.startsWith("http://", ignoreCase = true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> MEDIA_BASE + p
            else -> "$MEDIA_BASE/$p"
        }
    }

    /** Badge image of an achievement; [locked] is RA's greyed `_lock` variant. */
    fun badgeUrl(badgeName: String, locked: Boolean): String {
        val name = badgeName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { DEFAULT_BADGE }
        return "$MEDIA_BASE/Badge/$name${if (locked) "_lock" else ""}.png"
    }

    fun avatarUrl(user: String): String? = user.trim().takeIf { it.isNotEmpty() }?.let { "$MEDIA_BASE/UserPic/${enc(it)}.png" }

    // ---- Parsers ----

    /** The user summary, or null when the body is not one (unknown user, error object, garbage). */
    fun parseUserSummary(json: String, fallbackUser: String = ""): RaUserSummary? {
        val root = parseObject(json) ?: return null
        return runCatching {
            val user = root.str("User", "user") ?: fallbackUser.trim().takeIf { root.el("TotalPoints", "ID") != null }
            if (user.isNullOrEmpty()) return@runCatching null
            val awarded = root.objectOrNull("Awarded")
            val recent = elements(root.el("RecentlyPlayed")).mapNotNull { runCatching { recentGame(it, awarded) }.getOrNull() }
                .distinctBy { it.gameId }
                .sortedByDescending { it.lastPlayed ?: Long.MIN_VALUE }
            val rank = root.int("Rank")?.takeIf { it > 0 }
            RaUserSummary(
                user = user,
                avatarUrl = mediaUrl(root.str("UserPic")) ?: avatarUrl(user),
                points = root.int("TotalPoints")?.coerceAtLeast(0) ?: 0,
                softcorePoints = root.int("TotalSoftcorePoints")?.coerceAtLeast(0) ?: 0,
                truePoints = root.int("TotalTruePoints")?.coerceAtLeast(0) ?: 0,
                rank = rank,
                totalRanked = root.int("TotalRanked")?.takeIf { it > 0 },
                motto = root.str("Motto").orEmpty(),
                richPresence = root.str("RichPresenceMsg").orEmpty(),
                memberSince = parseDate(root.str("MemberSince")),
                recentlyPlayed = recent,
                recentUnlocks = recentUnlocks(root.el("RecentAchievements"))
            )
        }.getOrNull()
    }

    /** A game with the user's progress, or null when RA does not know the game (or the body is no game). */
    fun parseGameProgress(json: String): RaGameProgress? {
        val root = parseObject(json) ?: return null
        return runCatching {
            val id = root.int("ID", "GameID") ?: 0
            val title = root.str("Title").orEmpty()
            if (id <= 0 && title.isEmpty()) return@runCatching null
            val players = root.int("NumDistinctPlayers", "players_total", "NumDistinctPlayersCasual")?.takeIf { it > 0 }
            val list = keyedObjects(root.el("Achievements")).mapNotNull { (key, o) -> runCatching { achievement(key, o, players) }.getOrNull() }
                .distinctBy { it.id }
                .sortedWith(compareBy<RaAchievement> { it.displayOrder }.thenBy { it.id })
            val total = if (list.isNotEmpty()) list.size else (root.int("NumAchievements", "achievements_published") ?: 0).coerceAtLeast(0)
            val earned = if (list.isNotEmpty()) list.count { it.earned } else (root.int("NumAwardedToUser") ?: 0).coerceIn(0, total)
            val earnedHardcore = if (list.isNotEmpty()) list.count { it.earnedHardcore } else (root.int("NumAwardedToUserHardcore") ?: 0).coerceIn(0, total)
            val points = if (list.isNotEmpty()) list.sumOf { it.points } else (root.int("points_total") ?: 0).coerceAtLeast(0)
            val earnedPoints = list.filter { it.earned }.sumOf { it.points }
            val completion = when {
                total > 0 && list.isNotEmpty() -> earned.toFloat() / total
                else -> root.double("UserCompletion")?.let { (it / 100.0).toFloat() }
                    ?: if (total > 0) earned.toFloat() / total else 0f
            }.coerceIn(0f, 1f)
            RaGameProgress(
                gameId = id,
                title = title,
                consoleName = root.str("ConsoleName").orEmpty(),
                iconUrl = mediaUrl(root.str("ImageIcon")),
                achievements = list,
                total = total,
                earned = earned,
                earnedHardcore = earnedHardcore,
                points = points,
                earnedPoints = earnedPoints,
                completion = completion,
                award = award(root.str("HighestAwardKind"), total, earned, earnedHardcore),
                players = players
            )
        }.getOrNull()
    }

    /** The error text of an RA error body (`{"message": …}`, `{"Error": …}`), or null. */
    fun errorMessage(json: String): String? {
        val root = parseObject(json) ?: return null
        return root.str("Error", "error", "message")
    }

    /** What an HTTP status (and the error body) means for the user. */
    fun errorKindFor(code: Int, body: String?): RaErrorKind {
        val message = body?.let { errorMessage(it) }.orEmpty()
        return when {
            code == 401 || code == 403 -> RaErrorKind.BAD_KEY
            code == 429 -> RaErrorKind.RATE_LIMITED
            code in 500..599 -> RaErrorKind.SERVER
            message.contains("api key", ignoreCase = true) || message.contains("unauthenticated", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) -> RaErrorKind.BAD_KEY
            // 422 is RA's "validation failed": an unknown user or game.
            code == 404 || code == 422 -> RaErrorKind.NOT_FOUND
            else -> RaErrorKind.BAD_RESPONSE
        }
    }

    /**
     * Why a 200 answer could not be parsed: an error object says so itself; a well-formed object or
     * list without the data (`{"ID": null}`, `[]`) means RA does not know the user or game; anything
     * else (an HTML page from a proxy, cut-off JSON) is unreadable.
     */
    fun unreadableKind(body: String): RaErrorKind {
        if (errorMessage(body) != null) return errorKindFor(200, body)
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
        return if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) RaErrorKind.NOT_FOUND else RaErrorKind.BAD_RESPONSE
    }

    /** "Top N %" for a rank among [total] ranked players (1..100), or null. */
    fun topPercent(rank: Int?, total: Int?): Int? {
        if (rank == null || total == null || rank <= 0 || total <= 0) return null
        return ceil(rank.toDouble() * 100.0 / total).toInt().coerceIn(1, 100)
    }

    /**
     * Order for the achievement rows of a game: what is still to earn first (in RA's order), then
     * what is earned, newest first. The badge grid keeps RA's own order.
     */
    fun rowsOrder(achievements: List<RaAchievement>): List<RaAchievement> {
        val (earned, locked) = achievements.partition { it.earned }
        return locked.sortedWith(compareBy<RaAchievement> { it.displayOrder }.thenBy { it.id }) +
            earned.sortedWith(compareByDescending<RaAchievement> { it.earnedAt ?: Long.MIN_VALUE }.thenBy { it.displayOrder })
    }

    /**
     * An RA date ("2023-03-17 01:13:43", UTC; also ISO with offset, a bare date or epoch seconds) as
     * epoch millis. Null for empty, "0000-00-00…" and anything unreadable.
     */
    fun parseDate(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("0000") || s.equals("null", ignoreCase = true)) return null
        s.toLongOrNull()?.let { n -> return (if (n > 100_000_000_000L) n else n * 1000).takeIf { it > 0 } }
        val iso = s.replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    // ---- Internals ----

    private fun recentGame(el: JsonElement, awarded: JsonObject?): RaRecentGame? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val id = o.int("GameID", "ID")?.takeIf { it > 0 } ?: return null
        // RA 2023+ moved the per-game numbers into a separate "Awarded" map keyed by game id.
        val a = awarded?.get(id.toString())?.takeIf { it.isJsonObject }?.asJsonObject
        fun pick(vararg names: String): Int? = a?.int(*names) ?: o.int(*names)
        val possible = (pick("NumPossibleAchievements") ?: o.int("AchievementsTotal") ?: 0).coerceAtLeast(0)
        val hardcore = (pick("NumAchievedHardcore") ?: 0).coerceAtLeast(0)
        val achieved = maxOf(pick("NumAchieved") ?: 0, hardcore)
        return RaRecentGame(
            gameId = id,
            title = o.str("Title", "GameTitle").orEmpty(),
            consoleId = o.int("ConsoleID")?.takeIf { it > 0 },
            consoleName = o.str("ConsoleName").orEmpty(),
            iconUrl = mediaUrl(o.str("ImageIcon", "GameIcon")),
            lastPlayed = parseDate(o.str("LastPlayed")),
            achieved = if (possible > 0) achieved.coerceAtMost(possible) else achieved,
            achievedHardcore = if (possible > 0) hardcore.coerceAtMost(possible) else hardcore,
            possible = possible,
            scoreAchieved = (pick("ScoreAchieved") ?: 0).coerceAtLeast(0),
            possibleScore = (pick("PossibleScore") ?: 0).coerceAtLeast(0)
        )
    }

    /** `{gameId: {achievementId: {...}}}` (or `[]`, or a flat list), newest first. */
    private fun recentUnlocks(el: JsonElement?): List<RaRecentUnlock> {
        val objects = ArrayList<JsonObject>()
        fun collect(e: JsonElement?, depth: Int) {
            if (e == null || depth > 2) return
            when {
                e.isJsonObject && e.asJsonObject.has("Title") -> objects += e.asJsonObject
                e.isJsonObject -> e.asJsonObject.entrySet().forEach { collect(it.value, depth + 1) }
                e.isJsonArray -> e.asJsonArray.forEach { collect(it, depth + 1) }
            }
        }
        collect(el, 0)
        return objects.mapNotNull { o ->
            runCatching {
                val id = o.int("ID", "AchievementID")?.takeIf { it > 0 } ?: return@runCatching null
                // IsAwarded "0" marks an entry RA lists without the unlock; skip it.
                if (o.has("IsAwarded") && o.int("IsAwarded") == 0) return@runCatching null
                RaRecentUnlock(
                    achievementId = id,
                    gameId = o.int("GameID") ?: 0,
                    gameTitle = o.str("GameTitle").orEmpty(),
                    title = o.str("Title").orEmpty(),
                    description = o.str("Description").orEmpty(),
                    points = (o.int("Points") ?: 0).coerceAtLeast(0),
                    badgeUrl = badgeUrl(badgeName(o.el("BadgeName")), locked = false),
                    hardcore = (o.int("HardcoreAchieved") ?: 0) > 0,
                    unlockedAt = parseDate(o.str("DateAwarded", "Date"))
                )
            }.getOrNull()
        }.distinctBy { it.achievementId }
            .sortedByDescending { it.unlockedAt ?: Long.MIN_VALUE }
    }

    private fun achievement(key: String?, o: JsonObject, players: Int?): RaAchievement? {
        val id = o.int("ID") ?: key?.trim()?.toIntOrNull() ?: return null
        if (id <= 0) return null
        val soft = o.str("DateEarned")
        val hard = o.str("DateEarnedHardcore")
        val earnedHardcore = isEarnedDate(hard)
        val earned = earnedHardcore || isEarnedDate(soft)
        val awarded = o.int("NumAwardedHardcore")?.takeIf { it >= 0 } ?: o.int("NumAwarded")?.takeIf { it >= 0 }
        val rarity = if (awarded != null && players != null && players > 0) (awarded.toFloat() / players).coerceIn(0f, 1f) else null
        return RaAchievement(
            id = id,
            title = o.str("Title").orEmpty(),
            description = o.str("Description").orEmpty(),
            points = (o.int("Points") ?: 0).coerceAtLeast(0),
            truePoints = (o.int("TrueRatio") ?: 0).coerceAtLeast(0),
            badgeName = badgeName(o.el("BadgeName")),
            type = o.str("type", "Type"),
            displayOrder = o.int("DisplayOrder") ?: 0,
            earned = earned,
            earnedHardcore = earnedHardcore,
            earnedAt = if (earnedHardcore) parseDate(hard) ?: parseDate(soft) else if (earned) parseDate(soft) else null,
            rarity = rarity
        )
    }

    private fun isEarnedDate(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        return t.isNotEmpty() && !t.startsWith("0000") && !t.equals("null", ignoreCase = true) && t != "0"
    }

    private fun award(kind: String?, total: Int, earned: Int, earnedHardcore: Int): RaAward {
        val k = kind?.lowercase().orEmpty()
        val fromApi = when {
            k.startsWith("master") -> RaAward.MASTERED
            k.startsWith("complet") -> RaAward.COMPLETED
            k == "beaten-hardcore" || k == "beaten_hardcore" -> RaAward.BEATEN
            k.startsWith("beaten") -> RaAward.BEATEN_SOFTCORE
            else -> RaAward.NONE
        }
        val fromCounts = when {
            total > 0 && earnedHardcore >= total -> RaAward.MASTERED
            total > 0 && earned >= total -> RaAward.COMPLETED
            else -> RaAward.NONE
        }
        return if (fromCounts.ordinal > fromApi.ordinal) fromCounts else fromApi
    }

    /** A badge name may arrive as a number (losing its leading zeros); RA's names have 5+ digits. */
    private fun badgeName(e: JsonElement?): String {
        if (e == null || !e.isJsonPrimitive) return DEFAULT_BADGE
        val p = e.asJsonPrimitive
        val s = if (p.isNumber) runCatching { p.asLong.toString().padStart(5, '0') }.getOrDefault("") else p.asString.trim()
        return s.ifEmpty { DEFAULT_BADGE }
    }

    private fun parseObject(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    /** Entries of an object keyed by id, or of an array (RA sends `[]` for an empty map). */
    private fun keyedObjects(el: JsonElement?): List<Pair<String?, JsonObject>> = when {
        el == null -> emptyList()
        el.isJsonObject -> el.asJsonObject.entrySet().mapNotNull { (k, v) -> if (v.isJsonObject) k to v.asJsonObject else null }
        el.isJsonArray -> el.asJsonArray.mapNotNull { v -> if (v.isJsonObject) null to v.asJsonObject else null }
        else -> emptyList()
    }

    /** The items of an array, or the values of an object. */
    private fun elements(el: JsonElement?): List<JsonElement> = when {
        el == null -> emptyList()
        el.isJsonArray -> el.asJsonArray.toList()
        el.isJsonObject -> el.asJsonObject.entrySet().map { it.value }
        else -> emptyList()
    }

    private fun JsonObject.el(vararg names: String): JsonElement? {
        for (n in names) {
            val v = get(n)
            if (v != null && !v.isJsonNull) return v
        }
        return null
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(vararg names: String): String? {
        val v = el(*names) ?: return null
        if (!v.isJsonPrimitive) return null
        return runCatching { v.asString.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.double(vararg names: String): Double? = el(*names)?.let { number(it) }

    private fun JsonObject.int(vararg names: String): Int? =
        double(*names)?.roundToLong()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    /** A number from a JSON number, a numeric string ("1,234", "52.17%", " 7 ") or a boolean. */
    private fun number(e: JsonElement): Double? {
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        val d = runCatching {
            when {
                p.isNumber -> p.asDouble
                p.isBoolean -> if (p.asBoolean) 1.0 else 0.0
                else -> {
                    val cleaned = p.asString.trim().removeSuffix("%").trim()
                        .replace(Regex(",(?=\\d{3}(?:\\D|$))"), "")
                        .replace(',', '.')
                    cleaned.toDoubleOrNull()
                }
            }
        }.getOrNull() ?: return null
        return d.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
