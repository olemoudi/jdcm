package dev.jdcm.core

import dev.jdcm.core.policy.PreMatchPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PreMatchPolicyTest {
    private val mine = team(1)
    private val other = team(2)
    private val kickOff = LocalDateTime.of(2026, 11, 14, 10, 30)
    private val game = match(1, mine, other, start = kickOff)

    @Test
    fun `before the window the first check is its opening, four hours before`() {
        val friday = LocalDateTime.of(2026, 11, 13, 20, 0)
        assertEquals(kickOff.minusHours(4), PreMatchPolicy.nextCheck(listOf(game), friday, null))
        assertFalse(PreMatchPolicy.inWindow(game, friday))
    }

    @Test
    fun `inside the window checks follow each other a quarter of an hour apart`() {
        val now = LocalDateTime.of(2026, 11, 14, 8, 0)
        assertTrue(PreMatchPolicy.inWindow(game, now))
        // Never checked (or long ago): now.
        assertEquals(now, PreMatchPolicy.nextCheck(listOf(game), now, null))
        assertEquals(now, PreMatchPolicy.nextCheck(listOf(game), now, now.minusHours(1)))
        // Checked 5 minutes ago: 15 minutes after that one (13 with an inexact alarm, which comes late).
        assertEquals(now.plusMinutes(10), PreMatchPolicy.nextCheck(listOf(game), now, now.minusMinutes(5)))
        assertEquals(now.plusMinutes(8), PreMatchPolicy.nextCheck(listOf(game), now, now.minusMinutes(5), PreMatchPolicy.EVERY_INEXACT))
    }

    @Test
    fun `no check after kick-off, and the next match's window is next`() {
        val later = match(2, mine, other, start = LocalDateTime.of(2026, 11, 15, 12, 0))
        val now = kickOff.minusMinutes(5)
        assertEquals(LocalDateTime.of(2026, 11, 15, 8, 0), PreMatchPolicy.nextCheck(listOf(game, later), now, now.minusMinutes(1)))
        assertNull(PreMatchPolicy.nextCheck(listOf(game), kickOff, null))
    }

    @Test
    fun `cancelled, played, untimed and bye matches are not watched`() {
        val now = LocalDateTime.of(2026, 11, 14, 8, 0)
        val off = match(2, mine, other, status = "No Celebrado/Incompleto", statusId = 6, start = kickOff)
        val unscheduled = match(3, mine, other, status = "Desprogramado", statusId = 12, start = kickOff)
        val untimed = match(4, mine, other, start = kickOff.toLocalDate().atStartOfDay())
        val bye = match(5, mine, null, start = kickOff)
        val playedEarly = match(6, mine, other, status = "Disputado con Incidencias", statusId = 10, start = kickOff)
        val all = listOf(off, unscheduled, untimed, bye, playedEarly)
        assertTrue(all.none { PreMatchPolicy.watched(it, now) })
        assertNull(PreMatchPolicy.nextCheck(all, now, null))
    }

    @Test
    fun `a last check in the future (the clock went back) does not stall the checks`() {
        val now = LocalDateTime.of(2026, 11, 14, 8, 0)
        assertEquals(now, PreMatchPolicy.nextCheck(listOf(game), now, now.plusHours(3)))
    }
}
