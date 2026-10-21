package com.tufein.valkyr.data.service

import android.content.Context
import com.tufein.valkyr.util.CoverApis
import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.ScreenScraper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the cover sources did today (hits, misses, errors per source) and ScreenScraper's state:
 * paused for the day, the last problem, the account's quota. Kept on the device only (its own
 * preferences file, never in a backup), so the Cover sources screen still shows it after a restart.
 */
@Singleton
class CoverSourceStats @Inject constructor(@param:ApplicationContext context: Context) {

    /** Why ScreenScraper is not answering, for the status line. */
    enum class Problem { QUOTA, LOGIN, BUSY, RATE_LIMITED, OFFLINE }

    data class State(
        val tallies: Map<CoverSources.Source, CoverSources.Tally> = emptyMap(),
        /** ScreenScraper waits until then (the daily quota is used up); 0 = not paused. */
        val pausedUntil: Long = 0,
        val problem: Problem? = null,
        val problemAt: Long = 0,
        /** The account as ScreenScraper last described it (name, threads, quota). */
        val user: ScreenScraper.User? = null,
        val userAt: Long = 0,
        /** 4.4.0: the last problem of TheGamesDB, SteamGridDB and IGDB (LOGIN here means "key refused"). */
        val problems: Map<CoverSources.Source, Problem> = emptyMap(),
        /** 4.4.0: those sources wait until then (TheGamesDB's monthly allowance is used up). */
        val pausedUntilBy: Map<CoverSources.Source, Long> = emptyMap(),
        /** The monthly allowance TheGamesDB last reported, with the time it did. */
        val allowance: Int? = null,
        val allowanceAt: Long = 0
    ) {
        fun problemOf(source: CoverSources.Source): Problem? = problems[source]
        fun pausedUntil(source: CoverSources.Source): Long = if (source == CoverSources.Source.SCREENSCRAPER) pausedUntil else pausedUntilBy[source] ?: 0L
        fun tally(source: CoverSources.Source, today: Long): CoverSources.Tally = (tallies[source] ?: CoverSources.Tally()).on(today)
    }

    private val prefs = context.getSharedPreferences("cover_sources43", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private fun load(): State {
        val tallies = CoverSources.Source.entries.associateWith { CoverSources.Tally.decode(prefs.getString("tally_${it.key}", null)) }
        val user = prefs.getString("ss_user_name", null)?.let { name ->
            ScreenScraper.User(
                name = name,
                maxThreads = prefs.getInt("ss_user_threads", ScreenScraper.DEFAULT_THREADS),
                requestsToday = prefs.getInt("ss_user_today", -1).takeIf { it >= 0 },
                maxRequestsPerDay = prefs.getInt("ss_user_max", -1).takeIf { it >= 0 }
            )
        }
        val own = CoverApis.OWN
        return State(
            problems = own.mapNotNull { src -> prefs.getString("problem_${src.key}", null)?.let { p -> Problem.entries.firstOrNull { it.name == p } }?.let { src to it } }.toMap(),
            pausedUntilBy = own.associateWith { prefs.getLong("paused_${it.key}", 0) }.filterValues { it > 0 },
            allowance = prefs.getInt("tgdb_allowance", -1).takeIf { it >= 0 },
            allowanceAt = prefs.getLong("tgdb_allowance_at", 0),
            tallies = tallies,
            pausedUntil = prefs.getLong("ss_paused_until", 0),
            problem = prefs.getString("ss_problem", null)?.let { p -> Problem.entries.firstOrNull { it.name == p } },
            problemAt = prefs.getLong("ss_problem_at", 0),
            user = user,
            userAt = prefs.getLong("ss_user_at", 0)
        )
    }

    @Synchronized
    fun count(source: CoverSources.Source, answer: CoverSources.Answer, now: Long = System.currentTimeMillis()) {
        val today = ScreenScraper.dayOf(now)
        val next = (_state.value.tallies[source] ?: CoverSources.Tally()).count(today, CoverSources.answerKind(answer))
        _state.value = _state.value.copy(tallies = _state.value.tallies + (source to next))
        prefs.edit().putString("tally_${source.key}", next.encode()).apply()
    }

    @Synchronized
    fun pauseUntil(until: Long) {
        _state.value = _state.value.copy(pausedUntil = until)
        prefs.edit().putLong("ss_paused_until", until).apply()
    }

    @Synchronized
    fun problem(problem: Problem?, now: Long = System.currentTimeMillis()) {
        if (_state.value.problem == problem) return
        _state.value = _state.value.copy(problem = problem, problemAt = if (problem == null) 0 else now)
        prefs.edit().apply {
            if (problem == null) remove("ss_problem") else putString("ss_problem", problem.name)
            putLong("ss_problem_at", if (problem == null) 0 else now)
        }.apply()
    }

    /** The problem of a 4.4.0 [source] (null = it answers again). */
    @Synchronized
    fun sourceProblem(source: CoverSources.Source, problem: Problem?) {
        if (_state.value.problems[source] == problem) return
        _state.value = _state.value.copy(problems = if (problem == null) _state.value.problems - source else _state.value.problems + (source to problem))
        prefs.edit().apply { if (problem == null) remove("problem_${source.key}") else putString("problem_${source.key}", problem.name) }.apply()
    }

    /** A 4.4.0 [source] waits until [until] (0 = not paused). */
    @Synchronized
    fun pauseSource(source: CoverSources.Source, until: Long) {
        _state.value = _state.value.copy(pausedUntilBy = if (until <= 0) _state.value.pausedUntilBy - source else _state.value.pausedUntilBy + (source to until))
        prefs.edit().putLong("paused_${source.key}", until.coerceAtLeast(0)).apply()
    }

    /** TheGamesDB's monthly allowance, as its last answer said. */
    @Synchronized
    fun allowance(left: Int, now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(allowance = left, allowanceAt = now)
        prefs.edit().putInt("tgdb_allowance", left).putLong("tgdb_allowance_at", now).apply()
    }

    @Synchronized
    fun user(user: ScreenScraper.User?, now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(user = user, userAt = if (user == null) 0 else now)
        prefs.edit().apply {
            if (user == null) {
                remove("ss_user_name"); remove("ss_user_threads"); remove("ss_user_today"); remove("ss_user_max"); remove("ss_user_at")
            } else {
                putString("ss_user_name", user.name)
                putInt("ss_user_threads", user.maxThreads)
                putInt("ss_user_today", user.requestsToday ?: -1)
                putInt("ss_user_max", user.maxRequestsPerDay ?: -1)
                putLong("ss_user_at", now)
            }
        }.apply()
    }
}
