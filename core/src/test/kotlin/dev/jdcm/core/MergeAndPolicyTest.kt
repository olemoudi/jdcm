package dev.jdcm.core

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.RoundPage
import dev.jdcm.core.model.Venue
import dev.jdcm.core.parse.SiteParser
import dev.jdcm.core.policy.RefreshPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The site answering oddly must never cost data, move a group, or flood the phone with notifications. */
class MergeAndPolicyTest {
    private val a = team(1, "A")
    private val b = team(2, "B")
    private val c = team(3, "C")
    private val d = team(4, "D")
    private val r1 = Round(101, 1, SAT_14)
    private val r2 = Round(102, 2, SAT_14.plusWeeks(1))
    private val thursday = LocalDateTime.of(2026, 11, 12, 20, 0)

    private fun page(round: Round?, vararg ms: dev.jdcm.core.model.Match, rounds: List<Round> = listOf(r1, r2), group: Group? = null) =
        RoundPage(group, round, null, null, rounds, ms.toList())

    private fun held() = snapshot(listOf(match(1, a, b, round = 1), match(2, c, d, round = 1), match(3, a, c, round = 2)), listOf(r1, r2))

    @Test
    fun `the group keeps its own id whatever the page says, and other groups' matches are dropped`() {
        val stranger = match(9, a, b, round = 1).copy(groupId = 999)
        val m = held().merge(page(r1, match(1, a, b, round = 1), match(2, c, d, round = 1), stranger, group = Group(999, "Grupo 9")), 5, false)
        assertEquals(7, m.snapshot.group.id)
        assertEquals("Grupo 9", m.snapshot.group.name, "names are taken")
        assertFalse(m.snapshot.matches.any { it.id == 9 })
    }

    @Test
    fun `an empty or much smaller round is refused, the child's match stays and the round stays unstamped`() {
        val before = held()
        val empty = before.merge(page(r1), 5, false)
        assertEquals(listOf(101), empty.rejected)
        assertEquals(before.roundMatches(101).map { it.id }, empty.snapshot.roundMatches(101).map { it.id })
        assertEquals(before.roundFetchedAt[101], empty.snapshot.roundFetchedAt[101])

        val big = snapshot((1..6).map { match(it, team(it * 10), team(it * 10 + 1), round = 1) }, listOf(r1, r2))
        assertEquals(listOf(101), big.merge(page(r1, match(1, team(10), team(11), round = 1), match(2, team(20), team(21), round = 1)), 5, false).rejected)
        assertEquals(listOf(101), big.merge(page(r1, *(1..3).map { match(it, team(it * 10), team(it * 10 + 1), round = 1) }.toTypedArray()), 5, false).accepted)
    }

    @Test
    fun `a match that moved to another round is stored once`() {
        val moved = match(1, a, b, round = 2)
        val s = held().merge(page(r2, moved, match(3, a, c, round = 2)), 5, false).snapshot
        assertEquals(1, s.matches.count { it.id == 1 })
        assertEquals(102, s.matches.single { it.id == 1 }.roundId)
    }

    @Test
    fun `rounds the site stops listing go with their matches, unless the list shrank suspiciously`() {
        val s = held().merge(page(r2, match(3, a, c, round = 2), rounds = listOf(r2)), 5, false).snapshot
        assertEquals(listOf(102), s.rounds.map { it.id })
        assertTrue(s.matches.none { it.roundId == 101 })

        val many = (1..10).map { Round(100 + it, it, SAT_14.plusWeeks(it.toLong() - 1)) }
        val big = snapshot(listOf(match(1, a, b, round = 1)), many)
        assertEquals(10, big.merge(page(r2, match(3, a, c, round = 2), rounds = listOf(r2)), 5, false).snapshot.rounds.size)
    }

    @Test
    fun `one answer refreshes the round and its neighbours (the site sends them along)`() {
        val parsed = SiteParser.roundPage(fixture("jornada_grupo.json"))
        assertEquals(listOf(2), parsed.neighbours.map { it.first.number }, "previous is the site's Jornada 0 placeholder")
        assertEquals(5, parsed.neighbours.single().second.size)
        val fresh = GroupSnapshot(Group(177, ""), 2, 113, 306)
        val m = fresh.merge(parsed, 5, wasCurrentRequest = true)
        assertEquals(setOf(2596, 2597), m.accepted.toSet())
        assertEquals(10, m.snapshot.matches.size)
        assertEquals(2596, m.snapshot.currentRoundId)
        assertEquals(9, m.snapshot.rounds.size)
        assertEquals(7, m.snapshot.missingRounds.size)
    }

    @Test
    fun `per round ages, this weekend at the caller's pace, the weeks around every 6 h, the rest daily`() {
        val rounds = (1..9).map { Round(100 + it, it, SAT_14.plusWeeks((it - 1).toLong())) }
        val nowMs = 100 * RefreshPolicy.HOUR
        val s = snapshot(listOf(match(1, a, b)), rounds).copy(roundFetchedAt = rounds.associate { it.id to nowMs - RefreshPolicy.HOUR })
        // Thursday 12th, an hour after everything was fetched, at a 50-minute pace: only this weekend's round.
        assertEquals(listOf(1), RefreshPolicy.roundsToFetch(s, thursday, nowMs, 50 * RefreshPolicy.MINUTE).map { it.number })
        // A pull to refresh: the rounds within three weeks, closest first; far ones wait for six hours.
        assertEquals(listOf(1, 2, 3), RefreshPolicy.roundsToFetch(s, thursday, nowMs, 0).map { it.number })
        // A day later, everything.
        assertEquals(9, RefreshPolicy.roundsToFetch(s, thursday, nowMs + 24 * RefreshPolicy.HOUR, 50 * RefreshPolicy.MINUTE).size)
        // Never fetched: due at once.
        assertEquals(9, RefreshPolicy.roundsToFetch(s.copy(roundFetchedAt = emptyMap()), thursday, nowMs, 5 * RefreshPolicy.HOUR).size)
        // At night in the background nothing is due.
        assertTrue(RefreshPolicy.roundsToFetch(s, thursday, nowMs, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `a date or venue that disappears, or a result before the match, is not news`() {
        val before = snapshot(listOf(match(1, a, b, start = LocalDateTime.of(2026, 11, 21, 10, 30), round = 2)))
        val noDate = snapshot(listOf(match(1, a, b, start = null, round = 2)))
        val noVenue = snapshot(listOf(match(1, a, b, start = LocalDateTime.of(2026, 11, 21, 10, 30), venue = null, round = 2)))
        val early = snapshot(listOf(match(1, a, b, 0, 0, closed = true, start = LocalDateTime.of(2026, 11, 21, 10, 30), round = 2)))
        assertTrue(MatchChanges.between(before, noDate, setOf(a.id), thursday).isEmpty())
        assertTrue(MatchChanges.between(before, noVenue, setOf(a.id), thursday).isEmpty())
        assertTrue(MatchChanges.between(before, early, setOf(a.id), thursday).isEmpty())
    }

    @Test
    fun `a match held twice is compared once`() {
        val m = match(1, a, b, start = LocalDateTime.of(2026, 11, 21, 10, 30), round = 2)
        val before = snapshot(listOf(m))
        val after = snapshot(listOf(m.copy(start = LocalDateTime.of(2026, 11, 21, 12, 0)), m.copy(start = LocalDateTime.of(2026, 11, 21, 12, 0))))
        assertEquals(1, MatchChanges.between(before, after, setOf(a.id), thursday).count { it is MatchChange.Rescheduled })
    }

    @Test
    fun `a venue change with the same names is not a move`() {
        val before = snapshot(listOf(match(1, a, b, venue = Venue("Campo 1", "Calle Mayor 1", lat = 40.0, lng = -3.0))))
        val after = snapshot(listOf(match(1, a, b, venue = Venue("CAMPO 1", "calle mayor 1", lat = 40.0001, lng = -3.0))))
        assertTrue(MatchChanges.between(before, after, setOf(a.id), thursday).isEmpty())
    }

    private val sunday = java.time.ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, dev.jdcm.core.model.MADRID).toInstant().toEpochMilli()

    @Test
    fun `the same smaller answer twice is believed (a team withdrew), once is not`() {
        val big = snapshot((1..6).map { match(it, team(it * 10), team(it * 10 + 1), round = 1) }, listOf(r1, r2))
        val small = page(r1, match(1, team(10), team(11), round = 1))
        val once = big.merge(small, 5, false)
        assertEquals(listOf(101), once.rejected)
        val twice = once.snapshot.merge(small, 6, false)
        assertEquals(listOf(101), twice.accepted)
        assertEquals(1, twice.snapshot.roundMatches(101).size)
    }

    @Test
    fun `a date or venue missing from one answer keeps the held one`() {
        val held = snapshot(listOf(match(1, a, b, round = 1), match(2, c, d, round = 1)), listOf(r1, r2))
        val gap = held.merge(page(r1, match(1, a, b, start = null, round = 1), match(2, c, d, venue = null, round = 1)), sunday, false).snapshot
        assertEquals(held.matches.first { it.id == 1 }.start, gap.matches.first { it.id == 1 }.start)
        assertEquals(held.matches.first { it.id == 2 }.venue, gap.matches.first { it.id == 2 }.venue)
    }

    @Test
    fun `a result dated in the future is stored as pending, and announced when it is real`() {
        val nextWeek = LocalDateTime.of(2026, 11, 21, 10, 30)
        val held = snapshot(listOf(match(1, a, b, start = nextWeek, round = 2)), listOf(r1, r2))
        val odd = held.merge(page(r2, match(1, a, b, 3, 0, closed = true, start = nextWeek, round = 2)), sunday, false).snapshot
        assertFalse(odd.matches.single().hasResult)
        val saturday = java.time.ZonedDateTime.of(2026, 11, 21, 13, 0, 0, 0, dev.jdcm.core.model.MADRID).toInstant().toEpochMilli()
        val real = odd.merge(page(r2, match(1, a, b, 3, 0, closed = true, start = nextWeek, round = 2)), saturday, false).snapshot
        assertTrue(real.matches.single().hasResult)
        assertTrue(MatchChanges.between(odd, real, setOf(a.id), LocalDateTime.of(2026, 11, 21, 13, 0)).single() is MatchChange.ResultIn)
    }

    @Test
    fun `a round list without the round just fetched is ignored, and rounds with results are never dropped`() {
        val held = snapshot(listOf(played(1, a, b, 2, 1, round = 1), match(3, a, c, round = 2)), listOf(r1, r2))
        val r3 = Round(103, 3, SAT_14.plusWeeks(2))
        val odd = held.merge(page(r2, match(3, a, c, round = 2), rounds = listOf(r3)), sunday, false)
        assertEquals(listOf(101, 102), odd.snapshot.rounds.map { it.id })
        val shrunk = held.merge(page(r2, match(3, a, c, round = 2), rounds = listOf(r2)), sunday, false)
        assertEquals(listOf(101, 102), shrunk.snapshot.rounds.map { it.id }, "round 1 holds a result")
        assertTrue(shrunk.snapshot.matches.any { it.id == 1 })
    }

    @Test
    fun `a fetch stamp from the future does not block a refresh`() {
        val s = snapshot(listOf(match(1, a, b)), listOf(r1)).copy(roundFetchedAt = mapOf(101 to 10 * RefreshPolicy.HOUR))
        assertTrue(RefreshPolicy.isDue(s, r1, thursday, 5 * RefreshPolicy.HOUR, 50 * RefreshPolicy.MINUTE))
    }
}
