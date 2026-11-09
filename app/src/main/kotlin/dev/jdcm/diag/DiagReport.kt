package dev.jdcm.diag

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AppInfo(
    val name: String,
    val applicationId: String,
    val versionName: String, // carries the channel when there is one: 1.4.0-alpha / 1.4.0-beta
    val versionCode: Long,
    val buildType: String,
    val gitSha: String,
    val gitDate: String,
    val installedAt: Instant?,
    val updatedAt: Instant?,
    val installer: String?,
)

data class DeviceInfo(val manufacturer: String, val model: String, val release: String, val sdk: Int, val locale: String)

/** One block of the report, already privacy-safe: ids, counts, enums, timestamps, rounded coordinates. */
data class DiagSection(val title: String, val lines: List<String>)

data class DiagSnapshot(
    val app: AppInfo,
    val device: DeviceInfo,
    val now: Instant,
    val zone: ZoneId,
    val processUptime: Duration?,
    val health: DeviceHealth?,
    /** App-specific: settings that change behaviour, each entity's state with every stamp decisions read, last runs. */
    val sections: List<DiagSection>,
    /** DiagLog.recent(...) — oldest first. */
    val log: String,
    val logBytesOnDisk: Long,
)

/** Fits a chat message comfortably (~25–30 k tokens) and stays far below Binder's ~1 MB transaction limit. */
const val REPORT_BUDGET_CHARS = 100_000

/**
 * The text the user copies. Pure: built from a snapshot, tested on the JVM (rwilco structure, znhr header and
 * trace). Starts with `== <app> diagnostics ==`, ends with `== end ==` so a reader can tell it arrived whole.
 */
fun DiagSnapshot.render(budget: Int = REPORT_BUDGET_CHARS): String {
    val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    fun stamp(at: Instant?) = at?.atZone(zone)?.format(full) ?: "-"
    val offset = zone.rules.getOffset(now)
    val head = buildString {
        appendLine("== ${app.name} diagnostics ==")
        appendLine("app ${app.versionName} (${app.versionCode}) ${app.buildType} git ${app.gitSha} ${app.gitDate} · ${app.applicationId}")
        appendLine("android ${device.release} (API ${device.sdk}) · ${device.manufacturer} ${device.model} · ${device.locale}")
        appendLine(
            "now ${stamp(now)} ${zone.id} (UTC$offset) · process up ${processUptime?.let(::span) ?: "-"} · " +
                "installed ${stamp(app.installedAt)} · updated ${stamp(app.updatedAt)} · installer ${app.installer ?: "-"}",
        )
        appendLine(
            "how to read: times are local (${zone.id}); log lines are 'MM-dd HH:mm:ss.SSS LEVEL Tag: message', " +
                "oldest first; levels I/W/E/F(crash); no keys, tokens or personal text by design; places rounded to ~1 km",
        )
        health?.let {
            appendLine()
            appendLine("## Phone")
            it.lines.forEach(::appendLine)
        }
        for (s in sections) {
            appendLine()
            appendLine("## ${s.title}")
            s.lines.forEach(::appendLine)
        }
        appendLine()
    }
    val room = (budget - head.length - 200).coerceAtLeast(8_000)
    val tail = if (log.length <= room) log
    else log.indexOf('\n', log.length - room).let { if (it < 0) log.takeLast(room) else log.substring(it + 1) }
    val firstStamp = tail.lineSequence().firstOrNull { it.length >= 18 && it[2] == '-' }?.take(18) ?: "-"
    return buildString {
        append(head)
        appendLine("## Log (since $firstStamp; ${tail.length / 1024} of ${log.length / 1024} KB shown, ${logBytesOnDisk / 1024} KB on disk; newest last)")
        append(tail)
        if (tail.isNotEmpty() && !tail.endsWith("\n")) appendLine()
        appendLine("== end ==")
    }.let(Redactor::redact)
}

/**
 * Ids the log mentions (`r=0f1e2d3c`, `id=…`): an app section that lists only the N most relevant entities
 * must still include these, or the report drops exactly the entity the log is talking about (rwilco 0.78.0).
 */
fun mentionedIds(log: String): Set<String> =
    Regex("\\b[a-z]{1,8}=([0-9a-f]{8})\\b").findAll(log).map { it.groupValues[1] }.toSet()

/** Cap a list of entity lines, keeping every [mustKeep] one, and say what was cut. */
fun <T> capLines(items: List<T>, max: Int, mustKeep: (T) -> Boolean, render: (T) -> String): List<String> {
    val kept = items.take(max) + items.drop(max).filter(mustKeep)
    val cut = items.size - kept.size
    return kept.map(render) + if (cut > 0) listOf("... $cut more not listed") else emptyList()
}

private fun span(d: Duration): String {
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h >= 48) "${h / 24}d${h % 24}h" else if (h > 0) "${h}h${m}m" else "${m}m"
}
