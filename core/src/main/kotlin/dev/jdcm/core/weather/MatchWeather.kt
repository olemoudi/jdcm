package dev.jdcm.core.weather

import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Venue
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * What the weather says about a match being played (owner, 2026-11-18): the chance AEMET gives of
 * rain at its hour and its place, read as the chance it goes ahead. Rain-offs are announced at the
 * last minute (2026-11-14, a whole matchday), and this is the warning a family can have days before.
 */
sealed interface MatchWeather {
    /** Under a roof («CUBIERTO», «TECHADA», a «PABELLÓN»…): it is played whatever the weather. */
    data object Covered : MatchWeather

    /** [rain]% of rain in AEMET's stretch [fromHour]–[toHour] of the match's day, at [place]. */
    data class Chance(val rain: Int, val fromHour: Int, val toHour: Int, val place: String) : MatchWeather {
        /** The chance it is played, as far as the rain goes. */
        val play: Int get() = 100 - rain
        val alerting: Boolean get() = rain > Weather.ALERT_OVER
    }
}

object Weather {
    /** Shown only for matches less than this many days ahead (owner): beyond, the forecast is a guess. */
    const val SHOW_DAYS = 4L

    /** A chance of rain over this (strictly) is worth an alert, if the family asked for them (owner). */
    const val ALERT_OVER = 60

    /** How old a municipality's forecast may be before it is read again: AEMET makes a new one a few times a day. */
    const val MAX_AGE_MS = 2 * 60 * 60_000L

    /**
     * Whether a venue is under a roof. The site writes it after the last dash of the name —
     * «PABELLÓN - CUBIERTO», «PISTA CUBIERTA - TECHADA», «LATERAL FÚTBOL - TECHADO», «… - EXTERIOR» —
     * and the words, with their derivatives, are what count wherever they are (owner, 2026-11-18).
     */
    fun isCovered(venue: Venue?): Boolean {
        val words = MadridMunicipalities.normalise(venue?.name.orEmpty()).split(' ')
        return words.any { w -> COVERED_ROOTS.any { w.startsWith(it) } }
    }

    /**
     * Word beginnings that mean a roof. Not «interior»: a «patio interior» is open to the sky. And
     * whole words only, so «descubierto» is not «cubierto».
     */
    private val COVERED_ROOTS = listOf("cubiert", "techad", "techo", "pabellon", "gimnasio")

    /** The day a match is played and the hour the forecast is read at (untimed: the whole day). */
    private fun dayOf(m: Match): LocalDate? = m.start?.toLocalDate() ?: m.day

    /**
     * Whether a match gets a forecast at all: still to be played, not called off, on a day less than
     * [SHOW_DAYS] ahead (today counts while it has not started).
     */
    fun inWindow(m: Match, now: LocalDateTime): Boolean {
        if (m.isBye || m.hasResult || m.isCancelled || m.status == MatchStatus.PLAYED) return false
        val day = dayOf(m) ?: return false
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), day)
        if (days !in 0 until SHOW_DAYS) return false
        val start = m.start
        return !(m.timeKnown && start != null && !start.isAfter(now))
    }

    /** The municipality whose forecast a match needs, or null (covered, unknown town, out of the window). */
    fun municipalityFor(m: Match, now: LocalDateTime): String? {
        if (!inWindow(m, now) || isCovered(m.venue)) return null
        val v = m.venue ?: return null
        return MadridMunicipalities.codeFor(v.town, v.postcode)
    }

    /**
     * What to show for a match, or null when nothing is to be shown: out of the window, no venue,
     * or no forecast read for its place yet. A covered venue needs no forecast.
     */
    fun forMatch(m: Match, forecast: RainForecast?, now: LocalDateTime): MatchWeather? {
        if (!inWindow(m, now) || m.venue == null) return null
        if (isCovered(m.venue)) return MatchWeather.Covered
        val day = dayOf(m) ?: return null
        val p = periodFor(forecast ?: return null, day, if (m.timeKnown) m.start?.hour else null) ?: return null
        return MatchWeather.Chance(p.probability, p.fromHour, p.toHour, forecast.place)
    }

    /** The same, looking the match's municipality up in [forecasts] (keyed by INE code). */
    fun forMatch(m: Match, forecasts: Map<String, RainForecast>, now: LocalDateTime): MatchWeather? =
        forMatch(m, m.venue?.let { MadridMunicipalities.codeFor(it.town, it.postcode) }?.let { forecasts[it] }, now)

    /**
     * The narrowest stretch of [day] holding [hour] that AEMET gave a figure for; with no hour (an
     * untimed match), the whole day's, or else the widest there is.
     */
    fun periodFor(forecast: RainForecast, day: LocalDate, hour: Int?): RainPeriod? {
        val ofDay = forecast.periods.filter { it.date == day }
        return if (hour == null) ofDay.maxByOrNull { it.hours }
        else ofDay.filter { it.covers(hour) }.minByOrNull { it.hours }
    }
}

/**
 * When a rain alert may sound (owner, 2026-11-18): a chance over [Weather.ALERT_OVER]; on the days
 * before, at 20:00; in the last 24 hours, as soon as it is seen; never more than one per match in 24
 * hours. And never in the night (23:00–08:00): a "may be rained off" is not worth waking anybody
 * for, and the morning comes before any match does.
 */
object RainAlertPolicy {
    const val EVENING_HOUR = 20
    private const val QUIET_FROM = 23
    private const val QUIET_UNTIL = 8
    val SILENCE: Duration = Duration.ofHours(24)

    /** [start] is the match's hour, or its day at 00:00 when it has none; [lastAlertAt] the last alert about it. */
    fun due(rain: Int, start: LocalDateTime, now: LocalDateTime, lastAlertAt: LocalDateTime?): Boolean {
        if (rain <= Weather.ALERT_OVER || !start.isAfter(now)) return false
        if (now.hour >= QUIET_FROM || now.hour < QUIET_UNTIL) return false
        if (lastAlertAt != null && Duration.between(lastAlertAt, now) < SILENCE) return false
        return Duration.between(now, start) <= SILENCE || now.hour >= EVENING_HOUR
    }

    /** The next evening check: today's 20:00 if it is still ahead, else tomorrow's. */
    fun nextEvening(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(EVENING_HOUR, 0)
        return if (now.isBefore(today)) today else today.plusDays(1)
    }
}
