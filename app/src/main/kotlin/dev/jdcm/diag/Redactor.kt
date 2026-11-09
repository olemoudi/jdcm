package dev.jdcm.diag

import java.util.Locale

/**
 * Safety net: keys, tokens, JWTs and e-mail addresses never reach the log or the report, whatever shape they
 * arrive in (exception messages quote them: OkHttp's header error printed an API key — znhr 0.9.4). The policy
 * is still "don't log personal data"; this only catches what slips through.
 */
object Redactor {
    private val patterns = listOf(
        Regex("eyJ[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]*){0,2}") to "<jwt>",
        Regex("(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{10,}") to "<token>",
        Regex("AIza[0-9A-Za-z_-]{35}") to "<google-key>",
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}") to "<token>",
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+") to "Bearer <redacted>",
        Regex("(?i)\\b((?:api_?key|apikey|access_token|refresh_token|id_token|token|key|secret|password|passwd|sig|signature|auth)=)[^&\\s\"']+") to "$1<redacted>",
        // OkHttp: "Unexpected char 0x0a at 40 in api_key value: <value>"
        Regex("(?i)((?:api_?key|token|secret|authorization|password)\\S*\\s+value:\\s*)\\S+") to "$1<redacted>",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "<email>",
    )

    /** The stored secrets themselves (wire it to the secrets store at start-up): exact values, any shape. */
    @Volatile var knownSecrets: () -> List<String> = { emptyList() }

    fun redact(s: String): String {
        var out = patterns.fold(s) { acc, (re, with) -> re.replace(acc, with) }
        runCatching { knownSecrets() }.getOrDefault(emptyList())
            .forEach { k -> if (k.length >= 8) out = out.replace(k, "<secret>") }
        return out
    }

    private val control = Regex("[\\u0000-\\u001f\\u007f\\u2028\\u2029]")

    /** One event, one line: a message can never fake more lines. */
    fun oneLine(s: String): String = control.replace(s, "⏎")

    /** About a kilometre: tells two places apart, cannot find a door. Locale.ROOT: always a dot. */
    fun coarse(lat: Double, lng: Double): String = String.format(Locale.ROOT, "%.2f,%.2f", lat, lng)

    /** A stable 4-hex tag that joins every line about the same thing without naming it (rounded values collide). */
    fun tag(vararg parts: Any?): String =
        String.format(Locale.ROOT, "%04x", parts.joinToString("|").hashCode() and 0xffff)

    /** The short id convention used in every line and in the report: `r=0f1e2d3c`. */
    fun shortId(id: String): String = id.replace("-", "").take(8)
}
