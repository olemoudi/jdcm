package dev.jdcm.core.schedule

import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchOrder
import dev.jdcm.core.model.MatchStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** What a team has coming and what it has done, as a family reads it. */
object Schedule {
    /**
     * The next match the team has to turn up to: not played yet, not taken off the calendar,
     * today or later (a match today stays "next" until it has a result or ends + 3 h).
     */
    fun next(snapshot: GroupSnapshot, teamId: Int, now: LocalDateTime): Match? =
        upcoming(snapshot, teamId, now).firstOrNull { it.status != MatchStatus.UNSCHEDULED && it.status != MatchStatus.POSTPONED }
            ?: upcoming(snapshot, teamId, now).firstOrNull()

    fun upcoming(snapshot: GroupSnapshot, teamId: Int, now: LocalDateTime): List<Match> =
        snapshot.teamMatches(teamId).filter { m -> !m.isBye && !m.hasResult && isAhead(m, now) }

    fun last(snapshot: GroupSnapshot, teamId: Int): Match? =
        snapshot.teamMatches(teamId).filter { it.hasResult }.maxWithOrNull(MatchOrder)

    /** The weekend a match belongs to, anchored on its Saturday: Friday-evening games count with it. */
    fun weekendOf(day: LocalDate): LocalDate = when (day.dayOfWeek) {
        DayOfWeek.SATURDAY -> day
        DayOfWeek.SUNDAY -> day.minusDays(1)
        DayOfWeek.FRIDAY -> day.plusDays(1)
        else -> day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    }

    /** The matchday a match is listed under: its round's weekend, else its own. */
    fun matchdayOf(m: Match): LocalDate? = (m.roundDate ?: m.day)?.let(::weekendOf)

    /** Distinct matchdays (Saturdays) of these groups, ascending. */
    fun matchdays(snapshots: Collection<GroupSnapshot>): List<LocalDate> =
        snapshots.flatMap { s -> s.rounds.mapNotNull { it.date?.let(::weekendOf) } + s.matches.mapNotNull(::matchdayOf) }
            .distinct()
            .sorted()

    /** The matchday to show on opening: this weekend until Monday night, else the next one, else the last. */
    fun focusMatchday(days: List<LocalDate>, today: LocalDate): LocalDate? =
        days.firstOrNull { !it.plusDays(2).isBefore(today) } ?: days.lastOrNull()

    fun isAhead(m: Match, now: LocalDateTime): Boolean {
        val start = m.start
        return when {
            start == null -> m.day?.let { !it.isBefore(now.toLocalDate()) } ?: true
            m.timeKnown -> start.plusHours(3).isAfter(now)
            else -> !start.toLocalDate().isBefore(now.toLocalDate())
        }
    }
}
