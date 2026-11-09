package dev.jdcm.core

import dev.jdcm.core.model.SportKind
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StandingsTest {
    private val futsal = PointsRule.forSport(SportKind.FUTSAL)
    private val a = team(1, "CALASANCIO \"C\"")
    private val b = team(2, "SAGRADOS CORAZONES \"A\"")
    private val c = team(3, "CLARET \"B\"")
    private val d = team(4, "BVM IRLANDESAS")

    @Test
    fun `futsal counts 2 for a win, 1 for a draw, 0 for a loss, as the friend's official table does`() {
        // The screenshot's rows: 6-0-2 = 12, 5-1-2 = 11, 4-3-1 = 11, 3-1-4 = 7, 2-2-4 = 6.
        val rule = futsal
        listOf(Triple(6, 0, 2) to 12, Triple(5, 1, 2) to 11, Triple(4, 3, 1) to 11, Triple(3, 1, 4) to 7, Triple(2, 2, 4) to 6)
            .forEach { (wdl, pts) -> assertEquals(pts, wdl.first * rule.win + wdl.second * rule.draw + wdl.third * rule.loss) }
    }

    @Test
    fun `table orders by points, then goal difference, then goals scored`() {
        val matches = listOf(
            played(1, a, b, 3, 1, round = 1),
            played(2, c, d, 2, 2, round = 1),
            played(3, a, c, 1, 1, round = 2),
            played(4, b, d, 5, 0, round = 2),
        )
        val t = Standings.compute(matches, futsal)
        assertEquals(listOf(a, b, c, d).map { it.id }, t.map { it.team.id })
        val first = t.first()
        assertEquals(2, first.played); assertEquals(1, first.won); assertEquals(1, first.drawn); assertEquals(0, first.lost)
        assertEquals(4, first.scored); assertEquals(2, first.conceded); assertEquals(3, first.points)
        // b: W + L, +3; c: D + D, 0: b has 2 pts, c has 2 pts → b ahead on difference.
        assertEquals(2, t[1].points); assertEquals(2, t[2].points)
        assertEquals(listOf(Outcome.WIN, Outcome.DRAW), first.form)
    }

    @Test
    fun `teams with no result yet still get a row, and a fully level table shares positions`() {
        val t = Standings.compute(listOf(match(1, a, b), match(2, c, d)), futsal)
        assertEquals(4, t.size)
        assertEquals(listOf(1, 1, 1, 1), t.map { it.position })
        assertEquals(0, t.sumOf { it.played })
    }

    @Test
    fun `postponed, unscheduled and pending matches do not count`() {
        val t = Standings.compute(
            listOf(
                match(1, a, b, 3, 0, status = "Aplazado", statusId = 1),
                match(2, c, d, 0, 0),
                match(3, a, c, 2, 0, status = "Desprogramado", statusId = 12),
            ),
            futsal,
        )
        assertEquals(0, t.sumOf { it.played })
    }

    @Test
    fun `the site's named winner outranks the score (forfeits, committee decisions)`() {
        val m = match(1, a, b, 0, 0, winnerId = b.id, closed = true)
        val t = Standings.compute(listOf(m), futsal)
        assertEquals(b.id, t.first().team.id)
        assertEquals(Outcome.LOSS, Standings.outcome(m, a.id))
    }

    @Test
    fun `basketball gives a point for a loss`() {
        val rule = PointsRule.forSport(SportKind.BASKETBALL)
        val t = Standings.compute(listOf(played(1, a, b, 40, 38)), rule)
        assertEquals(2, t[0].points)
        assertEquals(1, t[1].points)
    }

    @Test
    fun `a result counted twice (same match in two fetches) counts once`() {
        val m = played(1, a, b, 3, 1)
        assertEquals(1, Standings.compute(listOf(m, m), futsal).first().played)
    }

    @Test
    fun `form keeps the last five, oldest first`() {
        val matches = (1..7).map { r -> played(r, a, b, if (r % 2 == 0) 1 else 3, 2, round = r) }
        val row = Standings.compute(matches, futsal).first { it.team.id == a.id }
        assertEquals(listOf(Outcome.WIN, Outcome.LOSS, Outcome.WIN, Outcome.LOSS, Outcome.WIN), row.form)
    }
}
