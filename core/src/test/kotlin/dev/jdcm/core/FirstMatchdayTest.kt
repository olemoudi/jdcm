package dev.jdcm.core

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.parse.SiteParser
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The real answers of the first matchday (2026-11-14, rain): results, rain-offs ("No Celebrado"),
 * a match played "with incidents" whose score the site left null, and a match taken off the calendar.
 */
class FirstMatchdayTest {
    private val saturday = LocalDateTime.of(2026, 11, 14, 11, 0)
    private val saturdayMs = saturday.atZone(MADRID).toInstant().toEpochMilli()

    private fun page(name: String) = SiteParser.roundPage(fixture(name))

    @Test
    fun `a match played with incidents and no score is not a 0-0`() {
        val p = page("j1_g136_incidencias.json")
        val incidents = p.matches.single { it.id == 8845 }
        assertEquals(MatchStatus.PLAYED, incidents.status)
        assertFalse(incidents.scoreKnown)
        assertFalse(incidents.hasResult)
        val played = p.matches.single { it.id == 8843 }
        assertTrue(played.hasResult)
        assertEquals(32 to 11, played.homeScore to played.awayScore)
        // Only the played one counts: four teams with one game each would be wrong.
        val rows = Standings.compute(p.matches, PointsRule.forSport(dev.jdcm.core.model.SportKind.BASKETBALL))
        assertEquals(2, rows.count { it.played > 0 })
    }

    @Test
    fun `rain-offs are not held, have no result, and are cancelled`() {
        val p = page("j1_g170_lluvia.json")
        val off = p.matches.filter { it.status == MatchStatus.NOT_HELD }
        assertEquals(4, off.size)
        assertTrue(off.all { !it.hasResult && it.isCancelled && !it.scoreKnown })
        val unscheduled = page("j1_g220_desprogramado.json").matches.single { it.id == 11867 }
        assertEquals(MatchStatus.UNSCHEDULED, unscheduled.status)
        assertTrue(unscheduled.isCancelled)
    }

    @Test
    fun `a played state without score fields (the grouped calendar) is no result`() {
        val o = JsonObject(
            mapOf(
                "Id" to JsonPrimitive(1), "IdEquipoLocal" to JsonPrimitive(2), "IdEquipoVisitante" to JsonPrimitive(3),
                "IdEstadoPartido" to JsonPrimitive(11), "EstadoPartido" to JsonPrimitive("Disputado sin Incidencias"),
                "FechaHora" to JsonPrimitive("2026-11-13T18:00:00"),
            ),
        )
        val m = SiteParser.match(o)!!
        assertEquals(MatchStatus.PLAYED, m.status)
        assertFalse(m.hasResult)
    }

    @Test
    fun `the week's snapshot against today's answer announces the result`() {
        val empty = GroupSnapshot(Group(177, ""), 2, 113, 306)
        val nov9 = LocalDateTime.of(2026, 11, 9, 19, 0).atZone(MADRID).toInstant().toEpochMilli()
        val before = empty.merge(page("jornada_grupo.json"), nov9, true).snapshot
        val after = before.merge(page("j1_g177_resultado.json"), saturdayMs, true).snapshot
        val changes = MatchChanges.between(before, after, setOf(1194), saturday)
        val result = changes.single() as MatchChange.ResultIn
        assertEquals(10379, result.match.id)
        assertEquals(10 to 0, result.match.homeScore to result.match.awayScore)
    }

    @Test
    fun `a rain-off of a followed match is announced once, and only for its teams`() {
        val live = page("j1_g170_lluvia.json")
        val pending = live.copy(
            matches = live.matches.map {
                if (it.status != MatchStatus.NOT_HELD) it
                else it.copy(statusId = 0, statusName = "Pendiente de Jugar", scoreKnown = true)
            },
        )
        val empty = GroupSnapshot(Group(170, ""), 2, 112, 303)
        val before = empty.merge(pending, saturdayMs - 3_600_000, true).snapshot
        val after = before.merge(live, saturdayMs, true).snapshot
        val off = after.matches.single { it.id == 10061 }
        val followed = off.home!!.id
        val changes = MatchChanges.between(before, after, setOf(followed), saturday)
        val c = changes.single() as MatchChange.StatusChanged
        assertEquals(10061, c.match.id)
        assertEquals(MatchStatus.SCHEDULED, c.before)
        assertEquals(MatchStatus.NOT_HELD, c.match.status)
        // The same answer again is no news.
        assertTrue(MatchChanges.between(after, after.merge(live, saturdayMs + 900_000, true).snapshot, setOf(followed), saturday).isEmpty())
    }
}
