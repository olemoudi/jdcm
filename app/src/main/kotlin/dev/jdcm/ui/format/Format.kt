package dev.jdcm.ui.format

import android.content.Context
import dev.jdcm.R
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Venue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Dates and times the way a family reads them: «Sáb 14 nov · 10:30», «Hora por confirmar». */
class Format(private val context: Context) {
    private val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun weekdayShort(d: LocalDate): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) }

    fun weekdayTwo(d: LocalDate): String = weekdayShort(d).take(2)

    fun monthShort(d: LocalDate): String = d.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').lowercase(locale)

    fun monthYear(d: LocalDate): String =
        d.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + d.year

    /** «Sáb 14 nov». */
    fun day(d: LocalDate): String = "${weekdayShort(d)} ${d.dayOfMonth} ${monthShort(d)}"

    /** «14 nov». */
    fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${monthShort(d)}"

    fun time(m: Match): String = if (m.timeKnown && m.start != null) m.start!!.format(time) else "--:--"

    /** «Sáb 14 nov · 10:30», or «Sáb 14 nov · hora por confirmar», or «Sin fecha». */
    fun whenLine(m: Match): String {
        val d = m.day ?: return context.getString(R.string.no_date)
        val t = if (m.timeKnown && m.start != null) m.start!!.format(time) else context.getString(R.string.time_tbc)
        return "${day(d)} · $t"
    }

    fun statusLabel(m: Match): String? = when (m.status) {
        MatchStatus.POSTPONED -> context.getString(R.string.status_postponed)
        MatchStatus.UNSCHEDULED -> context.getString(R.string.status_unscheduled)
        MatchStatus.NOT_HELD -> context.getString(R.string.status_not_held)
        MatchStatus.PLAYED -> context.getString(if (m.hasResult) R.string.status_final else R.string.status_no_score)
        else -> if (m.hasResult) context.getString(R.string.status_final) else null
    }

    fun venueLine(v: Venue?): String {
        if (v == null) return context.getString(R.string.no_venue)
        val place = listOf(v.address, v.town).filter { it.isNotBlank() }.joinToString(", ")
        return listOf(v.name, place).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** «hace 5 min», «hace 2 h», «ayer 18:04». */
    fun age(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val mins = ((nowMs - ms) / 60_000).coerceAtLeast(0)
        return when {
            ms <= 0 -> context.getString(R.string.age_never)
            mins < 1 -> context.getString(R.string.age_now)
            mins < 60 -> context.getString(R.string.age_minutes, mins.toInt())
            mins < 24 * 60 -> context.getString(R.string.age_hours, (mins / 60).toInt())
            else -> context.getString(R.string.age_days, (mins / (24 * 60)).toInt())
        }
    }
}
