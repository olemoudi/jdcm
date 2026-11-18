package dev.jdcm.core

import dev.jdcm.core.model.Venue
import dev.jdcm.core.weather.AemetParser
import dev.jdcm.core.weather.MadridMunicipalities
import dev.jdcm.core.weather.MatchWeather
import dev.jdcm.core.weather.RainAlertPolicy
import dev.jdcm.core.weather.RainForecast
import dev.jdcm.core.weather.RainPeriod
import dev.jdcm.core.weather.Weather
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The rain forecast for a match (0.2.4): AEMET's keyless XML, the roof, the window, the alerts. */
class WeatherTest {
    private val a = team(1, "A")
    private val b = team(2, "B")

    /** Real: Madrid as AEMET published it on 2026-11-18 13:23 (ISO-8859-15). */
    private val madrid: RainForecast by lazy {
        val bytes = requireNotNull(javaClass.getResource("/fixtures/aemet_28079.xml")).readBytes()
        requireNotNull(AemetParser.parse(String(bytes, charset("ISO-8859-15")), "28079", fetchedAtMs = 1))
    }

    @Test
    fun `aemet's municipal xml gives the chance of rain by stretch, finer for the nearer days`() {
        assertEquals("Madrid", madrid.place)
        assertEquals("2026-11-18T13:23:08", madrid.elaborated)
        val d18 = LocalDate.of(2026, 11, 18)
        // Today's stretches gone by are empty: no value, not 0.
        assertNull(madrid.periods.firstOrNull { it.date == d18 && it.fromHour == 0 && it.toHour == 6 })
        assertEquals(65, madrid.periods.single { it.date == d18 && it.fromHour == 6 && it.toHour == 12 }.probability)
        // Days 3 and 4: halves; after that the whole day only.
        val d21 = LocalDate.of(2026, 11, 21)
        assertEquals(listOf(0 to 24, 0 to 12, 12 to 24), madrid.periods.filter { it.date == d21 }.map { it.fromHour to it.toHour })
        assertEquals(listOf(0 to 24), madrid.periods.filter { it.date == LocalDate.of(2026, 11, 22) }.map { it.fromHour to it.toHour })
    }

    @Test
    fun `the narrowest stretch holding the hour, or the whole day for an untimed match`() {
        val d19 = LocalDate.of(2026, 11, 19)
        assertEquals(6 to 12, Weather.periodFor(madrid, d19, 10)!!.let { it.fromHour to it.toHour })
        assertEquals(0 to 24, Weather.periodFor(madrid, d19, null)!!.let { it.fromHour to it.toHour })
        assertEquals(12 to 24, Weather.periodFor(madrid, LocalDate.of(2026, 11, 21), 16)!!.let { it.fromHour to it.toHour })
        assertNull(Weather.periodFor(madrid, LocalDate.of(2026, 12, 1), 10), "a day AEMET says nothing about")
    }

    @Test
    fun `a venue's town is its municipality, in either article order, or by madrid's postcodes`() {
        assertEquals("28079", MadridMunicipalities.codeFor("Madrid"))
        assertEquals("28134", MadridMunicipalities.codeFor("San Sebastián de los Reyes"))
        assertEquals("28006", MadridMunicipalities.codeFor("ALCOBENDAS"))
        assertEquals("28127", MadridMunicipalities.codeFor("Las Rozas de Madrid"))
        assertEquals("28127", MadridMunicipalities.codeFor("Las Rozas"), "a name the town starts with, when only one does")
        assertEquals("28079", MadridMunicipalities.codeFor("", "28034"), "no town, a capital postcode")
        assertNull(MadridMunicipalities.codeFor("", "28109"))
        assertNull(MadridMunicipalities.codeFor("Toledo"))
        assertEquals(179, MadridMunicipalities.NAMES.size)
    }

    @Test
    fun `the words for a roof, wherever they are, as the site writes them`() {
        fun v(name: String) = Venue(name = name, town = "Madrid")
        assertTrue(Weather.isCovered(v("PABELLÓN - CUBIERTO")))
        assertTrue(Weather.isCovered(v("PISTA CUBIERTA - TECHADA")))
        assertTrue(Weather.isCovered(v("LATERAL FÚTBOL - TECHADO")))
        assertTrue(Weather.isCovered(v("POLIDEPORTIVO - CUBIERTO")))
        assertFalse(Weather.isCovered(v("CAMPO FÚTBOL SALA - EXTERIOR")))
        assertFalse(Weather.isCovered(v("AZOTEA - EXTERIOR")))
        assertFalse(Weather.isCovered(v("PATIO INTERIOR - EXTERIOR")), "an inner courtyard is open to the sky")
        assertFalse(Weather.isCovered(v("PISTA DESCUBIERTA")))
        assertFalse(Weather.isCovered(null))
    }

    @Test
    fun `shown only for matches less than four days ahead, still to be played`() {
        val now = LocalDateTime.of(2026, 11, 18, 16, 0)
        val outside = Venue("CAMPO FÚTBOL SALA - EXTERIOR", town = "Madrid")
        fun m(start: LocalDateTime, status: String = "Pendiente de Jugar", statusId: Int = 0) =
            match(1, a, b, start = start, status = status, statusId = statusId, venue = outside)

        assertEquals(MatchWeather.Chance(0, 6, 12, "Madrid"), Weather.forMatch(m(LocalDateTime.of(2026, 11, 19, 10, 30)), madrid, now))
        val saturday = Weather.forMatch(m(LocalDateTime.of(2026, 11, 21, 16, 0)), madrid, now) as MatchWeather.Chance
        assertEquals(85, saturday.rain)
        assertTrue(saturday.alerting)
        assertNull(Weather.forMatch(m(LocalDateTime.of(2026, 11, 22, 10, 0)), madrid, now), "four days ahead: not shown")
        assertNull(Weather.forMatch(m(LocalDateTime.of(2026, 11, 18, 12, 0)), madrid, now), "already started")
        assertNull(Weather.forMatch(m(LocalDateTime.of(2026, 11, 19, 10, 30), "No Celebrado/Incompleto", 6), madrid, now), "called off")
        assertEquals(
            MatchWeather.Covered,
            Weather.forMatch(m(LocalDateTime.of(2026, 11, 21, 16, 0)).copy(venue = Venue("PABELLÓN - CUBIERTO", town = "Madrid")), null, now),
            "a roof needs no forecast",
        )
        assertNull(Weather.forMatch(m(LocalDateTime.of(2026, 11, 21, 16, 0)), null, now), "no forecast read yet")
        assertEquals("28079", Weather.municipalityFor(m(LocalDateTime.of(2026, 11, 21, 16, 0)), now))
    }

    @Test
    fun `an alert at 20 h on the days before, as soon as seen in the last 24 h, one per 24 h, none at night`() {
        val match = LocalDateTime.of(2026, 11, 21, 10, 30) // Saturday
        fun due(now: LocalDateTime, rain: Int = 75, last: LocalDateTime? = null) = RainAlertPolicy.due(rain, match, now, last)

        assertFalse(due(LocalDateTime.of(2026, 11, 19, 12, 0)), "two days before, at noon: wait for the evening")
        assertTrue(due(LocalDateTime.of(2026, 11, 19, 20, 5)), "two days before, at 20 h")
        assertFalse(due(LocalDateTime.of(2026, 11, 19, 20, 5), rain = 60), "60 % is not over 60 %")
        assertTrue(due(LocalDateTime.of(2026, 11, 20, 11, 0), last = LocalDateTime.of(2026, 11, 19, 10, 0)), "in the last 24 h, as soon as seen")
        assertFalse(due(LocalDateTime.of(2026, 11, 20, 11, 0), last = LocalDateTime.of(2026, 11, 19, 20, 5)), "but never twice in 24 h")
        assertFalse(due(LocalDateTime.of(2026, 11, 21, 7, 30)), "not in the night, even on the day")
        assertTrue(due(LocalDateTime.of(2026, 11, 21, 8, 0)), "the morning of the match")
        assertFalse(due(LocalDateTime.of(2026, 11, 21, 11, 0)), "once it has started, nothing")
        assertEquals(LocalDateTime.of(2026, 11, 19, 20, 0), RainAlertPolicy.nextEvening(LocalDateTime.of(2026, 11, 19, 19, 59)))
        assertEquals(LocalDateTime.of(2026, 11, 20, 20, 0), RainAlertPolicy.nextEvening(LocalDateTime.of(2026, 11, 19, 20, 0)))
    }

    @Test
    fun `a forecast survives the trip through the cache file`() {
        val json = kotlinx.serialization.json.Json.encodeToString(RainForecast.serializer(), madrid)
        assertEquals(madrid, kotlinx.serialization.json.Json.decodeFromString(RainForecast.serializer(), json))
        assertNotNull(RainPeriod(LocalDate.of(2026, 11, 21), 12, 24, 85).takeIf { it.covers(16) && !it.covers(24) })
    }
}
