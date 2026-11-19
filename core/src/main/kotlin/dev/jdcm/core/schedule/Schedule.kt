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

    /**
     * The last time the two teams of [m] met in this group with a result (the first leg, for a
     * second one): another match, same two teams, played. Null for a bye or a first meeting.
     */
    fun previousMeeting(snapshot: GroupSnapshot, m: Match): Match? {
        val a = m.home?.id ?: return null
        val b = m.away?.id ?: return null
        return snapshot.matches
            .filter { it.id != m.id && it.hasResult && it.involves(a) && it.involves(b) }
            .filter { x -> m.day?.let { d -> x.day?.isAfter(d) != true } ?: true }
            .maxWithOrNull(MatchOrder)
    }

    /** The weekend a match belongs to, anchored on its Saturday: Friday-evening games count with it. */
    fun weekendOf(day: LocalDate): LocalDate = when (day.dayOfWeek) {
        DayOfWeek.SATURDAY -> day
        DayOfWeek.SUNDAY -> day.minusDays(1)
        DayOfWeek.FRIDAY -> day.plusDays(1)
        else -> day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    }

    /**
     * Every date the given teams play on, ascending: the strip on Home (owner, 2026-11-18: «todas las
     * fechas en las que haya partido, sea finde o no»). A match on its own day (a replay on the day it
     * is replayed, not its round's); a rest on its round's day, so a weekend off still says «descansa».
     * [entries] are each child's group and team.
     */
    fun matchDates(entries: Collection<Pair<GroupSnapshot, Int>>): List<LocalDate> =
        entries.flatMap { (s, team) -> s.teamMatches(team).mapNotNull { it.day } }.distinct().sorted()

    /** The date to show on opening: today's or the next one with a match, else the last there was. */
    fun focusDate(dates: List<LocalDate>, today: LocalDate): LocalDate? =
        dates.firstOrNull { !it.isBefore(today) } ?: dates.lastOrNull()

    fun isAhead(m: Match, now: LocalDateTime): Boolean {
        val start = m.start
        return when {
            start == null -> m.day?.let { !it.isBefore(now.toLocalDate()) } ?: true
            m.timeKnown -> start.plusHours(3).isAfter(now)
            else -> !start.toLocalDate().isBefore(now.toLocalDate())
        }
    }
}
