package dev.jdcm.core.policy

import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import java.time.Duration
import java.time.LocalDateTime

/**
 * The last hours before a followed child's match. Rain-offs and time changes are often published at
 * the last minute (the first matchday, 2026-11-14, had 50 rain-offs that morning), so from [WINDOW]
 * before the start until the start the child's group is checked about every quarter of an hour.
 * Only matches that are still on and have a time: a match without one has no "last hours".
 */
object PreMatchPolicy {
    val WINDOW: Duration = Duration.ofHours(4)

    /** Between two checks, with an exact alarm (the owner asked for every 15-20 minutes). */
    val EVERY: Duration = Duration.ofMinutes(15)

    /**
     * With an inexact one (exact alarms not allowed): Android delivers it up to 75 % of the wait
     * late, so 13 minutes asked is 13-23 minutes lived.
     */
    val EVERY_INEXACT: Duration = Duration.ofMinutes(13)

    /** A pre-match sync leaves alone a group tried this recently (the app was just open, say). */
    const val MAX_AGE_MS = 10 * RefreshPolicy.MINUTE

    /** Still ahead, still on, with a time: worth watching when its window comes. */
    fun watched(m: Match, now: LocalDateTime): Boolean {
        val start = m.start ?: return false
        return !m.isBye && m.timeKnown && !m.isCancelled && !m.hasResult && m.status != MatchStatus.PLAYED && now.isBefore(start)
    }

    fun inWindow(m: Match, now: LocalDateTime): Boolean = watched(m, now) && !now.isBefore(m.start!!.minus(WINDOW))

    /**
     * When the next check is due for [matches], or null when none is ahead: the opening of the
     * nearest window, or [every] after [lastCheck] inside one (now if that is already past). A
     * check that would come after the start is not due: the match is on by then.
     */
    fun nextCheck(matches: Collection<Match>, now: LocalDateTime, lastCheck: LocalDateTime?, every: Duration = EVERY): LocalDateTime? {
        // A last check "in the future" is a clock that went back: it must not stall the checks.
        val last = lastCheck?.takeIf { !it.isAfter(now) }
        return matches.filter { watched(it, now) }.mapNotNull { m ->
            val start = m.start!!
            val open = start.minus(WINDOW)
            val at = if (now.isBefore(open)) open else last?.plus(every)?.takeIf { it.isAfter(now) } ?: now
            at.takeIf { it.isBefore(start) }
        }.minOrNull()
    }
}
