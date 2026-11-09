package dev.jdcm.diag

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.Locale

class RedactorTest {
    @AfterEach
    fun reset() {
        Redactor.knownSecrets = { emptyList() }
    }

    // Fake tokens assembled at run time, so no scanner mistakes this file for a leak.
    private val classicPat = "ghp" + "_" + "A".repeat(36)
    private val finePat = "github" + "_pat_" + "11ABCDEFG0" + "_".padEnd(30, 'x')

    @Test
    fun `tokens and keys in any shape are removed`() {
        val cases = mapOf(
            "auth eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig done" to "auth <jwt> done",
            "pat $classicPat end" to "pat <token> end",
            "fine $finePat end" to "fine <token> end",
            "Authorization: Bearer abc.def-ghi" to "Authorization: Bearer <redacted>",
            "GET /v1?lat=40&api_key=s3cr3t&x=1" to "GET /v1?lat=40&api_key=<redacted>&x=1",
            "Unexpected char 0x0a at 40 in api_key value: s3cr3tvalue" to "Unexpected char 0x0a at 40 in api_key value: <redacted>",
            "mail ana.perez@example.com now" to "mail <email> now",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, Redactor.redact(input), input) }
    }

    @Test
    fun `ordinary text is unchanged`() {
        val s = "sync ok n=12 took=340ms r=0f1e2d3c x=1"
        assertEquals(s, Redactor.redact(s))
    }

    @Test
    fun `known secret values are removed wherever they appear`() {
        Redactor.knownSecrets = { listOf("hunter2-correct-horse") }
        assertFalse(Redactor.redact("failed with hunter2-correct-horse inside").contains("hunter2"))
    }

    @Test
    fun `coarse uses a dot whatever the locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("40.42,-3.70", Redactor.coarse(40.41678, -3.70379))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `tags are stable and short`() {
        assertEquals(Redactor.tag("place", 40.42, -3.70), Redactor.tag("place", 40.42, -3.70))
        assertEquals(4, Redactor.tag("x").length)
        assertEquals("0f1e2d3c", Redactor.shortId("0f1e2d3c-aaaa-bbbb"))
    }
}
