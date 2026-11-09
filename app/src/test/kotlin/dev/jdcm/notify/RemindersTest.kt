package dev.jdcm.notify

import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.Team
import dev.jdcm.data.Kid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RemindersTest {
    private val mine = Team(1, "MINE")
    private val other = Team(2, "OTHER")
    private val kid = Kid("kid-0001-aaaa", "Lucía", 0, 2, "Fútbol sala", 113, "ALEVÍN", 306, "ALEVÍN", 7, "Grupo 1", 1, "MINE")

    private fun m(id: Int, start: LocalDateTime, status: String = "Pendiente de Jugar", statusId: Int = 0) = Match(
        id = id, roundId = 100 + id, roundNumber = id, groupId = 7, home = mine, away = other, start = start,
        timeKnown = start.hour != 0, roundDate = start.toLocalDate(), statusName = status, statusId = statusId,
    )

    private fun snap(vararg matches: Match) = mapOf(
        7 to GroupSnapshot(Group(7, "Grupo 1"), 2, 113, 306, matches.map { Round(it.roundId, it.roundNumber, it.roundDate) }, matches.toList()),
    )

    @Test
    fun `the reminder is due at the chosen hour the day before`() {
        val due = Reminders.nextDue(listOf(kid), snap(m(1, LocalDateTime.of(2026, 11, 14, 10, 30))), 20, LocalDateTime.of(2026, 11, 12, 9, 0), emptySet())
        assertEquals(LocalDateTime.of(2026, 11, 13, 20, 0), due?.first)
        assertEquals(1, due?.second?.single()?.second?.id)
    }

    @Test
    fun `a match whose eve has passed, or already reminded, gets no reminder`() {
        val match = m(1, LocalDateTime.of(2026, 11, 14, 10, 30))
        assertNull(Reminders.nextDue(listOf(kid), snap(match), 20, LocalDateTime.of(2026, 11, 13, 22, 0), emptySet()))
        assertNull(Reminders.nextDue(listOf(kid), snap(match), 20, LocalDateTime.of(2026, 11, 12, 9, 0), setOf(Reminders.key(kid, match))))
    }

    @Test
    fun `postponed and unscheduled matches are skipped for the next real one`() {
        val due = Reminders.nextDue(
            listOf(kid),
            snap(m(1, LocalDateTime.of(2026, 11, 14, 10, 30), "Aplazado", 1), m(2, LocalDateTime.of(2026, 11, 21, 12, 0))),
            20, LocalDateTime.of(2026, 11, 12, 9, 0), emptySet(),
        )
        assertEquals(2, due?.second?.single()?.second?.id)
    }

    @Test
    fun `a friday evening match is reminded on thursday`() {
        val due = Reminders.nextDue(listOf(kid), snap(m(1, LocalDateTime.of(2026, 11, 13, 18, 0))), 20, LocalDateTime.of(2026, 11, 11, 9, 0), emptySet())
        assertEquals(LocalDateTime.of(2026, 11, 12, 20, 0), due?.first)
    }
}
