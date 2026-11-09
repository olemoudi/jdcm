package dev.jdcm.core

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.Venue
import dev.jdcm.core.policy.RefreshPolicy
import dev.jdcm.core.schedule.Schedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ChangesAndScheduleTest {
    private val mine = team(1, "MI EQUIPO")
    private val other = team(2, "RIVAL")
    private val far = team(3, "OTRO")
    private val thursday = LocalDateTime.of(2026, 11, 12, 20, 0)

    @Test
    fun `a new time, a new field and a postponement are each reported once, for followed teams only`() {
        val before = snapshot(listOf(match(1, mine, other), match(2, far, team(4))))
        val after = snapshot(
            listOf(
                match(1, mine, other, start = LocalDateTime.of(2026, 11, 14, 12, 0), venue = Venue("Pabellón", "Otra calle 2")),
                match(2, far, team(4), status = "Aplazado", statusId = 1),
            ),
        )
        val changes = MatchChanges.between(before, after, setOf(mine.id), thursday)
        assertEquals(2, changes.size)
        assertTrue(changes.any { it is MatchChange.Rescheduled })
        assertTrue(changes.any { it is MatchChange.Relocated })
    }

    @Test
    fun `postponed outranks a time change in the same sync`() {
        val before = snapshot(listOf(match(1, mine, other)))
        val after = snapshot(listOf(match(1, mine, other, status = "Aplazado", statusId = 1, start = LocalDateTime.of(2026, 11, 21, 9, 0))))
        val c = MatchChanges.between(before, after, setOf(mine.id), thursday).single()
        assertTrue(c is MatchChange.StatusChanged)
        assertEquals(MatchStatus.SCHEDULED, (c as MatchChange.StatusChanged).before)
    }

    @Test
    fun `a result coming in is reported, and nothing on a first sync`() {
        val before = snapshot(listOf(match(1, mine, other)))
        val after = snapshot(listOf(played(1, mine, other, 3, 2)))
        assertTrue(MatchChanges.between(before, after, setOf(mine.id), thursday).single() is MatchChange.ResultIn)
        assertTrue(MatchChanges.between(null, after, setOf(mine.id), thursday).isEmpty())
    }

    @Test
    fun `the time being set for the first time counts as rescheduled`() {
        val before = snapshot(listOf(match(1, mine, other, start = LocalDateTime.of(2026, 11, 14, 0, 0))))
        val after = snapshot(listOf(match(1, mine, other, start = LocalDateTime.of(2026, 11, 14, 10, 30))))
        assertTrue(MatchChanges.between(before, after, setOf(mine.id), thursday).single() is MatchChange.Rescheduled)
    }

    @Test
    fun `next match skips what is played and what was taken off the calendar`() {
        val s = snapshot(
            listOf(
                played(1, mine, other, 1, 0, round = 1),
                match(2, mine, far, status = "Desprogramado", statusId = 12, start = LocalDateTime.of(2026, 11, 21, 10, 0), round = 2),
                match(3, other, mine, start = LocalDateTime.of(2026, 11, 28, 11, 0), round = 3),
            ),
        )
        val now = LocalDateTime.of(2026, 11, 16, 9, 0)
        assertEquals(3, Schedule.next(s, mine.id, now)?.id)
        assertEquals(1, Schedule.last(s, mine.id)?.id)
    }

    @Test
    fun `a match today stays next until three hours after it starts`() {
        val s = snapshot(listOf(match(1, mine, other, start = LocalDateTime.of(2026, 11, 14, 10, 30))))
        assertEquals(1, Schedule.next(s, mine.id, LocalDateTime.of(2026, 11, 14, 13, 0))?.id)
        assertEquals(null, Schedule.next(s, mine.id, LocalDateTime.of(2026, 11, 14, 13, 31))?.id)
    }

    @Test
    fun `friday evening games belong to the saturday's matchday, and the focus holds until monday`() {
        assertEquals(LocalDate.of(2026, 11, 14), Schedule.weekendOf(LocalDate.of(2026, 11, 13)))
        assertEquals(LocalDate.of(2026, 11, 14), Schedule.weekendOf(LocalDate.of(2026, 11, 15)))
        val days = listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 21))
        assertEquals(days[0], Schedule.focusMatchday(days, LocalDate.of(2026, 11, 16)))
        assertEquals(days[1], Schedule.focusMatchday(days, LocalDate.of(2026, 11, 17)))
        assertEquals(days[1], Schedule.focusMatchday(days, LocalDate.of(2027, 3, 1)), "after the season: the last one")
    }

    @Test
    fun `the match window runs from friday afternoon to monday noon`() {
        assertFalse(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 13, 14, 0)))
        assertTrue(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 13, 18, 0)))
        assertTrue(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 15, 23, 0)))
        assertFalse(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 16, 15, 0)))
    }

    @Test
    fun `a day-old or incomplete snapshot fetches every round, a fresh one only those near today`() {
        val rounds = (1..9).map { Round(100 + it, it, SAT_14.plusWeeks((it - 1).toLong())) }
        val s = snapshot(listOf(match(1, mine, other)), rounds)
        val now = LocalDateTime.of(2026, 11, 12, 20, 0)
        val nowMs = 10 * RefreshPolicy.HOUR
        assertEquals(9, RefreshPolicy.roundsToFetch(s.copy(roundFetchedAt = emptyMap()), now, nowMs).size)
        val fresh = s.copy(roundFetchedAt = rounds.associate { it.id to nowMs - RefreshPolicy.HOUR })
        // Today is Thursday 12th: rounds within three weeks ahead are 14th, 21st, 28th (and Dec 3? no: 5th > 21 days).
        assertEquals(listOf(1, 2, 3), RefreshPolicy.roundsToFetch(fresh, now, nowMs).map { it.number })
        val stale = s.copy(roundFetchedAt = rounds.associate { it.id to nowMs - 25 * RefreshPolicy.HOUR })
        assertEquals(9, RefreshPolicy.roundsToFetch(stale, now, nowMs).size)
    }
}
