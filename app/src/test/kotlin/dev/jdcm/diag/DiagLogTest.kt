package dev.jdcm.diag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** JVM: without init() the log lives in memory, which is exactly what these tests read. */
class DiagLogTest {
    @BeforeEach
    fun reset() = DiagLog.resetForTests()

    private fun lines() = DiagLog.recent(1_000_000).lines().filter { it.isNotBlank() }

    @Test
    fun `lines are kept in memory before init and never throw`() {
        DiagLog.i("Test", "hello n=3")
        DiagLog.e("Test", "failed", RuntimeException("boom"))
        val all = lines()
        assertTrue(all.any { it.endsWith(" I Test: hello n=3") }, all.toString())
        assertTrue(all.any { it.contains(" E Test: failed") }, all.toString())
        assertTrue(all.first().contains("Diag: clock zone"), "first line names the zone: ${all.first()}")
    }

    @Test
    fun `events are capped per minute but problems have their own budget`() {
        repeat(130) { DiagLog.i("Flood", "event $it") }
        DiagLog.w("Test", "still visible")
        val all = lines()
        assertEquals(120, all.count { it.contains(" I Flood: ") })
        assertTrue(all.any { it.contains(" W Test: still visible") })
    }

    @Test
    fun `identical lines are folded into a count`() {
        repeat(5) { DiagLog.i("Net", "offline") }
        DiagLog.i("Net", "online")
        val all = lines()
        assertEquals(1, all.count { it.endsWith("Net: offline") })
        assertTrue(all.any { it.endsWith("previous line repeated 4 more times") }, all.toString())
    }

    @Test
    fun `a different tag is not folded`() {
        DiagLog.i("A", "same")
        DiagLog.i("B", "same")
        assertEquals(2, lines().count { it.endsWith(": same") })
    }

    @Test
    fun `one event is one line and secrets never land`() {
        DiagLog.i("Test", "line one\nfake line two token=abcdef123456")
        val line = lines().last()
        assertTrue(line.contains("line one⏎fake line two"), line)
        assertTrue(line.contains("token=<redacted>"), line)
        assertFalse(line.contains("abcdef123456"))
    }

    @Test
    fun `a warning keeps its cause on the same line`() {
        DiagLog.w("Sync", "push failed", IllegalStateException("boom", RuntimeException("root")))
        val line = lines().last()
        assertTrue(line.contains("W Sync: push failed | IllegalStateException: boom ← RuntimeException: root"), line)
    }

    @Test
    fun `long lines are cut`() {
        DiagLog.i("Test", "x".repeat(5_000))
        assertTrue(lines().last().length < 1_100)
    }
}
