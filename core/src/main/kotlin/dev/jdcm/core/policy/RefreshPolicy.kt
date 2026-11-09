package dev.jdcm.core.policy

import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Round
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * How often to ask the site, and for which rounds. The site is a small association's server:
 * the app never asks faster than its data changes. Results arrive from Friday evening to Monday;
 * during the week only times and fields move (the request windows close days before a round).
 *
 * Each round has its own age limit by how close it is to today, so a sync usually asks for one
 * round (this weekend's) and its answer refreshes the neighbours too (the site sends the previous
 * and next rounds with it).
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

    /** With the app open: the group is looked at again after this (the cached data shows meanwhile). */
    fun foregroundMaxAgeMs(now: LocalDateTime): Long = if (isMatchWindow(now)) 10 * MINUTE else 2 * HOUR

    /** In the background, a group is looked at again after this. */
    fun backgroundMaxAgeMs(now: LocalDateTime): Long = when {
        now.hour < 7 -> Long.MAX_VALUE // nobody reads results at 4 a.m.; the morning run catches up
        isMatchWindow(now) -> 50 * MINUTE
        else -> 5 * HOUR
    }

    /** Rounds around this weekend's (results, last-minute changes): refreshed at the caller's pace. */
    private const val HOT_DAYS_BEFORE = 3L
    private const val HOT_DAYS_AFTER = 6L
    /** The weeks around it (times and fields being set): every few hours is plenty. */
    const val NEAR_MAX_AGE_MS = 6 * HOUR
    /** Everything else, once a day: far-off rounds get their times set weeks ahead. */
    const val FAR_MAX_AGE_MS = 24 * HOUR

    /** Max age of one round's data, given the caller's pace for the hot ones ([hotMaxAgeMs], 0 = now). */
    fun maxAgeMs(round: Round, now: LocalDateTime, hotMaxAgeMs: Long): Long {
        val forced = hotMaxAgeMs <= 0
        val near = if (forced) 0 else maxOf(NEAR_MAX_AGE_MS, hotMaxAgeMs)
        val far = if (forced) NEAR_MAX_AGE_MS else maxOf(FAR_MAX_AGE_MS, hotMaxAgeMs)
        val d = round.date ?: return near
        return when (ChronoUnit.DAYS.between(now.toLocalDate(), d)) {
            in -HOT_DAYS_BEFORE..HOT_DAYS_AFTER -> hotMaxAgeMs.coerceAtLeast(0)
            in -14L..21L -> near
            else -> far
        }
    }

    fun isDue(snapshot: GroupSnapshot, round: Round, now: LocalDateTime, nowMs: Long, hotMaxAgeMs: Long): Boolean {
        val at = snapshot.roundFetchedAt[round.id] ?: return true
        val age = nowMs - at
        // A stamp from the future (the phone's clock was ahead for a while) must not block refreshes.
        return age < 0 || age >= maxAgeMs(round, now, hotMaxAgeMs)
    }

    /**
     * Rounds to fetch now, closest to today first (if the site fails half way, what matters most
     * was asked first). A pull to refresh ([hotMaxAgeMs] 0) takes the weeks around today now and
     * far-off rounds only when six hours old.
     */
    fun roundsToFetch(snapshot: GroupSnapshot, now: LocalDateTime, nowMs: Long, hotMaxAgeMs: Long): List<Round> {
        val today = now.toLocalDate()
        return snapshot.rounds
            .filter { isDue(snapshot, it, now, nowMs, hotMaxAgeMs) }
            .sortedBy { r -> r.date?.let { abs(ChronoUnit.DAYS.between(today, it)) } ?: Long.MAX_VALUE }
    }
}
