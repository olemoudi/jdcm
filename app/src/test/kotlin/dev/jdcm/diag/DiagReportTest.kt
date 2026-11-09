package dev.jdcm.diag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DiagReportTest {
    private fun snapshot(log: String = "", sections: List<DiagSection> = emptyList()) = DiagSnapshot(
        app = AppInfo(
            "Demo", "dev.jdcm", "1.4.0-beta", 42, "release", "a1b2c3d4e5", "2026-11-03",
            Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-11-02T10:00:00Z"), "com.android.packageinstaller",
        ),
        device = DeviceInfo("Google", "Pixel 8", "15", 35, "es-ES"),
        now = Instant.parse("2026-11-03T11:00:00Z"),
        zone = ZoneId.of("Europe/Madrid"),
        processUptime = Duration.ofMinutes(75),
        health = DeviceHealth(listOf("notifications=y channels=alerts:high")),
        sections = sections,
        log = log,
        logBytesOnDisk = 2048,
    )

    @Test
    fun `header says which build, which phone and when`() {
        val r = snapshot().render()
        assertTrue(r.startsWith("== Demo diagnostics ==\n"))
        assertTrue(r.contains("app 1.4.0-beta (42) release git a1b2c3d4e5 2026-11-03"))
        assertTrue(r.contains("android 15 (API 35) · Google Pixel 8 · es-ES"))
        assertTrue(r.contains("now 2026-11-03 12:00:00 Europe/Madrid (UTC+01:00) · process up 1h15m"))
        assertTrue(r.contains("## Phone\nnotifications=y"))
        assertTrue(r.trimEnd().endsWith("== end =="))
    }

    @Test
    fun `an empty log still renders whole`() {
        val r = snapshot().render()
        assertTrue(r.contains("## Log (since -;"))
        assertTrue(r.trimEnd().endsWith("== end =="))
    }

    @Test
    fun `the log tail fits the budget and starts at a line`() {
        val log = (1..20_000).joinToString("\n") { "11-03 12:00:00.000 I Work: run n=$it" } + "\n"
        val r = snapshot(log).render(budget = 30_000)
        assertTrue(r.length <= 30_000 + 500, "length ${r.length}")
        val firstLogLine = r.substringAfter("newest last)\n").lineSequence().first()
        assertTrue(firstLogLine.startsWith("11-03 12:00:00.000 I Work: run n="), firstLogLine)
        assertTrue(r.contains("run n=20000\n== end =="))
    }

    @Test
    fun `secrets that reach the log are removed from the report`() {
        val r = snapshot("11-03 12:00:00.000 W Net: 401 for Bearer abc.def\n").render()
        assertFalse(r.contains("abc.def"))
    }

    @Test
    fun `entities the log mentions survive the cut, and the cut is announced`() {
        val ids = (0 until 50).map { "%08x".format(it) }
        val mentioned = mentionedIds("11-03 12:00:00.000 I Alarm: fired r=${ids[42]} late=3s\n")
        assertEquals(setOf(ids[42]), mentioned)
        val lines = capLines(ids, max = 10, mustKeep = { it in mentioned }) { "#$it ACTIVE" }
        assertEquals(12, lines.size)
        assertTrue(lines.contains("#${ids[42]} ACTIVE"))
        assertEquals("... 39 more not listed", lines.last())
    }
}
