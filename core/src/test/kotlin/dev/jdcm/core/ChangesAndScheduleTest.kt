package dev.jdcm.core

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.MatchStatus
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
        val sunday = LocalDateTime.of(2026, 11, 15, 12, 0)
        assertTrue(MatchChanges.between(before, after, setOf(mine.id), sunday).single() is MatchChange.ResultIn)
        assertTrue(MatchChanges.between(null, after, setOf(mine.id), sunday).isEmpty())
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
    fun `the strip is every date a child plays on, weekend or not, and a rest on its round's day`() {
        val mine = team(1)
        val sat = match(1, mine, team(2), start = LocalDateTime.of(2026, 11, 14, 10, 30), round = 1)
        val thursdayReplay = match(2, mine, team(3), start = LocalDateTime.of(2026, 12, 10, 18, 30), round = 1)
        val sunday = match(3, mine, team(4), start = LocalDateTime.of(2026, 11, 22, 12, 0), round = 2)
        val rest = match(4, mine, null, start = null, round = 3).copy(roundDate = LocalDate.of(2026, 11, 28))
        val someoneElse = match(5, team(7), team(8), start = LocalDateTime.of(2026, 11, 21, 9, 0), round = 2)
        val s = snapshot(listOf(sat, thursdayReplay, sunday, rest, someoneElse))
        assertEquals(
            listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 10)),
            Schedule.matchDates(listOf(s to mine.id)),
            "Thursday the 10th is its own date; another team's Saturday is not one of this child's",
        )
        val dates = Schedule.matchDates(listOf(s to mine.id))
        assertEquals(LocalDate.of(2026, 11, 22), Schedule.focusDate(dates, LocalDate.of(2026, 11, 18)))
        assertEquals(LocalDate.of(2026, 11, 22), Schedule.focusDate(dates, LocalDate.of(2026, 11, 22)), "today's, all day")
        assertEquals(LocalDate.of(2026, 12, 10), Schedule.focusDate(dates, LocalDate.of(2027, 3, 1)), "after the season: the last one")
    }

    @Test
    fun `a rained-off match given a later day is a replay, and on`() {
        val rainedOff = match(1, team(1), team(2), status = "No Celebrado/Incompleto", statusId = 6)
        assertTrue(rainedOff.isCancelled, "on its own day: called off")
        val replay = rainedOff.copy(start = LocalDateTime.of(2026, 12, 10, 18, 30))
        assertTrue(replay.isReplay)
        assertFalse(replay.isCancelled, "the replay is going to be played")
        assertFalse(replay.copy(homeScore = 3, awayScore = 1).isReplay, "with a ruling it is a result, not a replay")
    }

    @Test
    fun `the match window runs from friday afternoon to monday noon`() {
        assertFalse(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 13, 14, 0)))
        assertTrue(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 13, 18, 0)))
        assertTrue(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 15, 23, 0)))
        assertFalse(RefreshPolicy.isMatchWindow(LocalDateTime.of(2026, 11, 16, 15, 0)))
    }

}
