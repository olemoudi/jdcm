package dev.jdcm.core.standings

import dev.jdcm.core.model.Match
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.model.Team
import java.text.Collator
import java.util.Locale

/**
 * Points per result. The public site has no standings (its Clasificaciones page needs a login),
 * so the app computes them from the published results.
 *
 * Futsal is measured: the owner's friend's screenshot of an official table (8 rows) is W 2, D 1,
 * L 0, ordered by points and then goal difference. Basketball and volleyball follow the usual
 * school rule (W 2, L 1) until a real table says otherwise — the screen states the rule under
 * the table so nobody mistakes an estimate for the official one.
 */
data class PointsRule(val win: Int, val draw: Int, val loss: Int, val hasDraws: Boolean) {
    companion object {
        fun forSport(kind: SportKind): PointsRule = when (kind) {
            SportKind.FUTSAL -> PointsRule(win = 2, draw = 1, loss = 0, hasDraws = true)
            SportKind.BASKETBALL -> PointsRule(win = 2, draw = 1, loss = 1, hasDraws = false)
            SportKind.VOLLEYBALL -> PointsRule(win = 2, draw = 1, loss = 1, hasDraws = false)
            SportKind.OTHER -> PointsRule(win = 2, draw = 1, loss = 0, hasDraws = true)
        }
    }
}

enum class Outcome { WIN, DRAW, LOSS }

data class StandingRow(
    val position: Int,
    val team: Team,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val scored: Int,
    val conceded: Int,
    val points: Int,
    /** Oldest first, at most the last five played. */
    val form: List<Outcome>,
) {
    val difference: Int get() = scored - conceded
}

private class Tally {
    var won = 0
    var drawn = 0
    var lost = 0
    var scored = 0
    var conceded = 0
    val results = mutableListOf<Pair<Match, Outcome>>()

    fun add(m: Match, o: Outcome, mine: Int, theirs: Int) {
        when (o) {
            Outcome.WIN -> won++
            Outcome.DRAW -> drawn++
            Outcome.LOSS -> lost++
        }
        scored += mine
        conceded += theirs
        results += m to o
    }
}

object Standings {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es-ES")).apply { strength = Collator.PRIMARY }

    /**
     * Every team that appears in [matches] gets a row (so a table exists from matchday one),
     * counted only from matches with a published result. Order: points, goal difference, goals
     * scored, then name — the order the friend's official table follows.
     */
    fun compute(matches: List<Match>, rule: PointsRule): List<StandingRow> {
        val teams = LinkedHashMap<Int, Team>()
        for (m in matches) {
            m.home?.let { teams.putIfAbsent(it.id, it) }
            m.away?.let { teams.putIfAbsent(it.id, it) }
        }
        val tallies = teams.keys.associateWith { Tally() }
        for (m in matches.filter { it.hasResult }.distinctBy { it.id }) {
            val h = m.home ?: continue
            val a = m.away ?: continue
            tallies.getValue(h.id).add(m, outcome(m, h.id), m.homeScore, m.awayScore)
            tallies.getValue(a.id).add(m, outcome(m, a.id), m.awayScore, m.homeScore)
        }
        val rows = teams.values.map { t ->
            val x = tallies.getValue(t.id)
            val form = x.results
                .sortedWith(compareBy<Pair<Match, Outcome>>({ it.first.start }, { it.first.roundNumber }))
                .takeLast(5)
                .map { it.second }
            StandingRow(
                position = 0,
                team = t,
                played = x.won + x.drawn + x.lost,
                won = x.won,
                drawn = x.drawn,
                lost = x.lost,
                scored = x.scored,
                conceded = x.conceded,
                points = x.won * rule.win + x.drawn * rule.draw + x.lost * rule.loss,
                form = form,
            )
        }
        val sorted = rows.sortedWith(
            compareByDescending<StandingRow> { it.points }
                .thenByDescending { it.difference }
                .thenByDescending { it.scored }
                .thenComparator { a, b -> collator.compare(a.team.name, b.team.name) },
        )
        // Teams level on every criterion share a position ("3, 3, 5"): the app does not invent an order.
        val out = ArrayList<StandingRow>(sorted.size)
        sorted.forEachIndexed { i, r ->
            val prev = out.lastOrNull()
            val tied = prev != null && prev.points == r.points && prev.difference == r.difference && prev.scored == r.scored
            out += r.copy(position = if (tied) prev.position else i + 1)
        }
        return out
    }

    /**
     * The winner the site names wins (a forfeit or a committee decision can disagree with the
     * score); without one, the score decides.
     */
    fun outcome(m: Match, teamId: Int): Outcome {
        val home = m.home?.id == teamId
        val mine = if (home) m.homeScore else m.awayScore
        val theirs = if (home) m.awayScore else m.homeScore
        return when {
            m.winnerId == teamId -> Outcome.WIN
            m.winnerId != 0 && (m.winnerId == m.home?.id || m.winnerId == m.away?.id) -> Outcome.LOSS
            mine > theirs -> Outcome.WIN
            mine < theirs -> Outcome.LOSS
            else -> Outcome.DRAW
        }
    }
}
