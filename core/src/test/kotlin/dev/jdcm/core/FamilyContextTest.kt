package dev.jdcm.core

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.Venue
import dev.jdcm.core.policy.BackgroundHealth
import dev.jdcm.core.policy.HealthFacts
import dev.jdcm.core.policy.HealthIssue
import dev.jdcm.core.schedule.Clashes
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** What 0.2.8 adds around a child's matches: the table after a result, the first leg, siblings' clashes, the phone's health. */
class FamilyContextTest {
    private val mine = team(1)
    private val other = team(2)
    private val third = team(3)
    private val fourth = team(4)
    private val futsal = PointsRule.forSport(dev.jdcm.core.model.SportKind.FUTSAL)

    // --- the table after a result -------------------------------------------------------------

    @Test
    fun `a result carries the team's place, the places it moved and its next match`() {
        // Round 1: mine lost, so it was last of four (level with nobody: 0 points, -2).
        val r1 = listOf(played(1, mine, other, 0, 2), played(2, third, fourth, 1, 1))
        val nextOne = match(4, fourth, mine, round = 3, start = LocalDateTime.of(2026, 11, 28, 10, 0))
        val before = snapshot(r1 + match(3, mine, third, round = 2, start = LocalDateTime.of(2026, 11, 21, 10, 0)) + nextOne)
        val after = snapshot(r1 + played(3, mine, third, 5, 0, round = 2) + nextOne)
        val c = MatchChanges.between(before, after, setOf(mine.id), LocalDateTime.of(2026, 11, 21, 13, 0)).single() as MatchChange.ResultIn
        assertNotNull(c.place)
        val place = c.place!!
        assertEquals(4, place.teams)
        assertEquals(4, place.previous)
        assertEquals(1, place.position, "2 points and +3: first")
        assertEquals(3, place.moved)
        assertEquals(4, c.next?.id)
    }

    @Test
    fun `a first result has a place but nothing to move from`() {
        val before = snapshot(listOf(match(1, mine, other)))
        val after = snapshot(listOf(played(1, mine, other, 1, 3)))
        val c = MatchChanges.between(before, after, setOf(mine.id), LocalDateTime.of(2026, 11, 14, 13, 0)).single() as MatchChange.ResultIn
        assertEquals(2, c.place?.position)
        assertNull(c.place?.previous)
        assertEquals(0, c.place?.moved)
    }

    @Test
    fun `a tie is told as such, with no move`() {
        val before = snapshot(listOf(match(1, mine, other), played(2, third, fourth, 1, 1)))
        val after = snapshot(listOf(played(1, mine, other, 2, 2), played(2, third, fourth, 1, 1)))
        val c = MatchChanges.between(before, after, setOf(mine.id), LocalDateTime.of(2026, 11, 14, 13, 0)).single() as MatchChange.ResultIn
        assertEquals(1, c.place?.position)
        assertTrue(c.place!!.tied, "level with the other team on every criterion")
        assertEquals(0, c.place!!.moved)
    }

    @Test
    fun `no place while rounds are missing, and the move counts from today's table`() {
        val before = snapshot(listOf(match(1, mine, other)))
        val after = snapshot(listOf(played(1, mine, other, 3, 0)))
        val partial = after.copy(rounds = after.rounds + dev.jdcm.core.model.Round(199, 9, null))
        val c = MatchChanges.between(before, partial, setOf(mine.id), LocalDateTime.of(2026, 11, 14, 13, 0)).single() as MatchChange.ResultIn
        assertNull(c.place, "a round never fetched: the table may be wrong")
        // An older copy missing a round must not invent a move: the base is today's matches minus the new result.
        val r1 = played(5, mine, third, 0, 4, round = 1)
        val stale = snapshot(listOf(match(6, mine, other, round = 2, start = LocalDateTime.of(2026, 11, 21, 10, 0))))
        val now = snapshot(listOf(r1, played(6, mine, other, 1, 0, round = 2)))
        val d = MatchChanges.between(stale, now, setOf(mine.id), LocalDateTime.of(2026, 11, 21, 13, 0)).single() as MatchChange.ResultIn
        assertEquals(3, d.place?.previous, "before this result: lost 0-4, behind both others")
    }

    @Test
    fun `the next match skips one rained off without a new date`() {
        val rainedOff = match(2, mine, third, round = 2, start = LocalDateTime.of(2026, 11, 21, 10, 0), status = "No Celebrado", statusId = 6)
        val later = match(3, fourth, mine, round = 3, start = LocalDateTime.of(2026, 11, 28, 10, 0))
        val before = snapshot(listOf(match(1, mine, other), rainedOff, later))
        val after = snapshot(listOf(played(1, mine, other, 2, 0), rainedOff, later))
        val c = MatchChanges.between(before, after, setOf(mine.id), LocalDateTime.of(2026, 11, 14, 13, 0)).single() as MatchChange.ResultIn
        assertEquals(3, c.next?.id)
    }

    @Test
    fun `no place for a team that has not played`() {
        assertNull(Standings.place(null, listOf(match(1, mine, other)), mine.id, futsal))
    }

    // --- the first leg ------------------------------------------------------------------------

    @Test
    fun `the previous meeting is the latest played match between the same two teams`() {
        val leg1 = played(1, other, mine, 2, 2, round = 1)
        val notUs = played(2, mine, third, 1, 0, round = 2)
        val leg2 = match(3, mine, other, round = 5, start = LocalDateTime.of(2027, 1, 16, 10, 0))
        val s = snapshot(listOf(leg1, notUs, leg2))
        assertEquals(1, Schedule.previousMeeting(s, leg2)?.id)
        assertNull(Schedule.previousMeeting(s, leg1), "the first leg has no earlier meeting")
        assertNull(Schedule.previousMeeting(s, notUs))
    }

    // --- siblings at the same time ------------------------------------------------------------

    private val alcobendas = Venue("Polideportivo A", "Calle 1", town = "Alcobendas", lat = 40.54, lng = -3.64)
    private val madrid = Venue("Colegio B", "Calle 2", town = "Madrid", lat = 40.42, lng = -3.70)

    private fun at(id: Int, h: Int, m: Int, venue: Venue?, duration: String = "") =
        match(id, team(10 + id), team(20 + id), start = LocalDateTime.of(2026, 11, 21, h, m), venue = venue).copy(duration = duration)

    @Test
    fun `two children in different places within the match and the trip clash`() {
        val a = at(1, 10, 0, alcobendas, "01:10:00")
        val b = at(2, 11, 30, madrid)
        val clash = Clashes.find(listOf("lucas" to a, "marta" to b)).single()
        assertEquals("lucas", clash.first.first)
        assertEquals(2, clash.other("lucas", 1)?.second?.id)
        assertEquals(1, clash.other("marta", 2)?.second?.id)
    }

    @Test
    fun `time enough for the trip is no clash`() {
        // 10:00 + 70 min + 30 min of travel = 11:40.
        assertTrue(Clashes.find(listOf("lucas" to at(1, 10, 0, alcobendas, "01:10:00"), "marta" to at(2, 11, 40, madrid))).isEmpty())
        assertTrue(Clashes.find(listOf("lucas" to at(1, 10, 0, alcobendas, "01:10:00"), "marta" to at(2, 11, 39, madrid))).size == 1)
    }

    @Test
    fun `the same field, the same match, one child, or a match off are no clash`() {
        val a = at(1, 10, 0, alcobendas)
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to at(2, 10, 30, alcobendas.copy(name = "POLIDEPORTIVO A")))).isEmpty(), "same field")
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to at(2, 10, 30, alcobendas.copy(name = "Pista 2", lat = 40.5401)))).isEmpty(), "same spot")
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to a)).isEmpty(), "twins in one match")
        assertTrue(Clashes.find(listOf("lucas" to a, "lucas" to at(2, 10, 30, madrid))).isEmpty(), "one child")
        val off = at(2, 10, 30, madrid).copy(statusId = 1, statusName = "Aplazado")
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to off)).isEmpty(), "postponed")
        val untimed = at(2, 0, 0, madrid)
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to untimed)).isEmpty(), "no time yet")
        val otherDay = at(2, 10, 30, madrid).let { it.copy(start = it.start!!.plusDays(1)) }
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to otherDay)).isEmpty(), "another day")
        assertTrue(Clashes.find(listOf("lucas" to a, "marta" to at(2, 10, 30, null))).isEmpty(), "field not set yet")
    }

    @Test
    fun `the same school is one place whatever the court, and a name alone needs the same town`() {
        val pista1 = Venue("PISTA 1 - EXTERIOR", "Calle del Colegio 3", town = "Madrid")
        val pista2 = Venue("PISTA 2 - CUBIERTA", "CALLE DEL COLEGIO 3", town = "Madrid")
        assertTrue(Clashes.samePlace(pista1, pista2), "two courts at one address, no coordinates")
        val poliA = Venue("POLIDEPORTIVO", "", town = "Getafe")
        val poliB = Venue("POLIDEPORTIVO", "", town = "Leganés")
        assertTrue(!Clashes.samePlace(poliA, poliB), "a generic name in two towns")
        assertTrue(!Clashes.samePlace(null, pista1))
        assertTrue(Clashes.find(listOf("lucas" to at(1, 10, 0, pista1), "marta" to at(2, 10, 30, pista2))).isEmpty())
    }

    // --- the phone's health ---------------------------------------------------------------------

    private val hour = 3_600_000L
    private val now = 100 * hour
    private val healthy = HealthFacts(
        notificationsAllowed = true, wantsNotifications = true, changesChannelOff = false, backgroundRestricted = false,
        batteryUnrestricted = false, dataSaverBlocks = false, online = true, exactAlarmsAllowed = true, wantsChanges = true,
        hasKids = true, lastBackgroundRunMs = now - hour, sinceMs = now - 50 * hour,
    )

    @Test
    fun `a phone that lets the app work has no issue`() {
        assertTrue(BackgroundHealth.issues(healthy, now).isEmpty())
        assertTrue(BackgroundHealth.issues(healthy.copy(notificationsAllowed = false, hasKids = false), now).isEmpty(), "no children, nothing to say")
    }

    @Test
    fun `issues come most serious first`() {
        val bad = healthy.copy(notificationsAllowed = false, backgroundRestricted = true)
        assertEquals(listOf(HealthIssue.NOTIFICATIONS_OFF, HealthIssue.BACKGROUND_RESTRICTED), BackgroundHealth.issues(bad, now))
        assertEquals(listOf(HealthIssue.CHANGES_CHANNEL_OFF), BackgroundHealth.issues(healthy.copy(changesChannelOff = true), now))
        assertTrue(BackgroundHealth.issues(healthy.copy(changesChannelOff = true, wantsChanges = false), now).isEmpty(), "changes off in the app too")
        assertTrue(BackgroundHealth.issues(healthy.copy(notificationsAllowed = false, wantsNotifications = false), now).isEmpty(), "all off in the app")
    }

    @Test
    fun `a check held back for over 8 hours says why`() {
        val late = healthy.copy(lastBackgroundRunMs = now - 9 * hour)
        assertEquals(listOf(HealthIssue.LATE_BATTERY), BackgroundHealth.issues(late, now))
        assertEquals(listOf(HealthIssue.LATE_DATA_SAVER), BackgroundHealth.issues(late.copy(dataSaverBlocks = true), now))
        assertEquals(listOf(HealthIssue.LATE_OTHER), BackgroundHealth.issues(late.copy(batteryUnrestricted = true), now))
        assertTrue(BackgroundHealth.issues(late.copy(online = false), now).isEmpty(), "offline: it cannot run, and says nothing about Android")
        assertTrue(BackgroundHealth.issues(healthy.copy(lastBackgroundRunMs = now - 7 * hour), now).isEmpty(), "a long Doze night")
    }

    @Test
    fun `lateness counts from when a check became possible`() {
        val neverRan = healthy.copy(lastBackgroundRunMs = 0)
        assertTrue(BackgroundHealth.issues(neverRan.copy(sinceMs = now - 2 * hour), now).isEmpty(), "just installed, rebooted or updated")
        assertEquals(listOf(HealthIssue.LATE_BATTERY), BackgroundHealth.issues(neverRan.copy(sinceMs = now - 9 * hour), now))
        assertTrue(BackgroundHealth.issues(healthy.copy(lastBackgroundRunMs = now - 20 * hour, sinceMs = now - hour), now).isEmpty(), "the phone was off")
        assertTrue(BackgroundHealth.issues(neverRan.copy(sinceMs = 0), now).isEmpty(), "unknown")
    }

    @Test
    fun `with every notice off in the app, the background is nobody's concern`() {
        val quiet = healthy.copy(wantsNotifications = false, wantsChanges = false)
        assertTrue(BackgroundHealth.issues(quiet.copy(backgroundRestricted = true), now).isEmpty())
        assertTrue(BackgroundHealth.issues(quiet.copy(lastBackgroundRunMs = now - 20 * hour), now).isEmpty())
        assertTrue(BackgroundHealth.issues(healthy.copy(lastBackgroundRunMs = now + hour), now).isEmpty(), "a clock that went back")
    }

    @Test
    fun `exact alarms matter only for the pre-match checks`() {
        assertEquals(listOf(HealthIssue.EXACT_ALARMS_OFF), BackgroundHealth.issues(healthy.copy(exactAlarmsAllowed = false), now))
        assertTrue(BackgroundHealth.issues(healthy.copy(exactAlarmsAllowed = false, wantsChanges = false), now).isEmpty())
    }
}
