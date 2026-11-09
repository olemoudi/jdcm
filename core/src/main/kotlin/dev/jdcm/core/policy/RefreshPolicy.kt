package dev.jdcm.core.policy

import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Round
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * How often to ask the site, and for which rounds. The site is a small association's server:
 * the app never asks faster than its data changes. Results arrive from Friday evening to Monday;
 * during the week only times and fields move (the request windows close days before a round).
 */
object RefreshPolicy {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE

    /** Friday 15:00 to Monday 14:00: matches are being played and results published. */
    fun isMatchWindow(now: LocalDateTime): Boolean = when (now.dayOfWeek) {
        DayOfWeek.FRIDAY -> now.hour >= 15
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> true
        DayOfWeek.MONDAY -> now.hour < 14
        else -> false
    }

    /** With the app open: data older than this is refreshed (the cached data shows meanwhile). */
    fun foregroundMaxAgeMs(now: LocalDateTime): Long = if (isMatchWindow(now)) 10 * MINUTE else 2 * HOUR

    /** In the background, a group is refreshed when its freshest round is older than this. */
    fun backgroundMaxAgeMs(now: LocalDateTime): Long = when {
        now.hour < 7 -> Long.MAX_VALUE // nobody reads results at 4 a.m.; the morning run catches up
        isMatchWindow(now) -> 50 * MINUTE
        else -> 5 * HOUR
    }

    /** A full pass over every round once a day keeps far-off rounds (times set weeks ahead) honest. */
    const val FULL_PASS_MS = 24 * HOUR

    /**
     * Rounds to fetch now: all of them when the snapshot is new, a day old or misses some;
     * otherwise the ones near today (two weeks back for late results, three ahead for times and
     * fields) plus any past round still waiting for a result.
     */
    fun roundsToFetch(snapshot: GroupSnapshot, now: LocalDateTime, nowMs: Long): List<Round> {
        val rounds = snapshot.rounds
        if (rounds.isEmpty()) return emptyList()
        if (snapshot.missingRounds.isNotEmpty() || nowMs - snapshot.oldestFetch > FULL_PASS_MS) return rounds
        val today = now.toLocalDate()
        val near = rounds.filter { r ->
            val d = r.date ?: return@filter true
            val days = ChronoUnit.DAYS.between(today, d)
            days in -14..21
        }
        val awaitingResult = rounds.filter { r ->
            val d = r.date ?: return@filter false
            d.isBefore(today) && snapshot.roundMatches(r.id).any { !it.isBye && !it.hasResult }
        }.takeLast(3)
        return (near + awaitingResult).distinctBy { it.id }.sortedBy { it.number }
    }
}
