package dev.jdcm.core.weather

import dev.jdcm.core.model.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The chance of rain AEMET gives for one stretch of one day: `00-06`, `06-12`, `12-18`, `18-24` for
 * today and tomorrow, `00-12` and `12-24` for the two days after, the whole day after that.
 */
@Serializable
data class RainPeriod(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val fromHour: Int,
    val toHour: Int,
    val probability: Int,
) {
    val hours: Int get() = toHour - fromHour
    fun covers(hour: Int): Boolean = hour in fromHour until toHour
}

/** One municipality's forecast as AEMET last published it, and when the app read it. */
@Serializable
data class RainForecast(
    /** INE code, which is AEMET's id for the municipality. */
    val code: String,
    val place: String = "",
    /** When AEMET made it ("2026-11-18T13:23:08"), as AEMET writes it. */
    val elaborated: String = "",
    val fetchedAtMs: Long = 0,
    val periods: List<RainPeriod> = emptyList(),
)

/**
 * AEMET's public, keyless municipal forecast: `https://www.aemet.es/xml/municipios/localidad_<code>.xml`
 * (ISO-8859-15, decoded by the caller). Only the chance of rain is read. Tolerant: a day or a
 * stretch it cannot read is skipped, and an empty value (a stretch already gone) is no value.
 */
object AemetParser {
    private val DAY = Regex("""<dia\s+fecha="(\d{4}-\d{2}-\d{2})"\s*>(.*?)</dia>""", RegexOption.DOT_MATCHES_ALL)
    private val PROB = Regex("""<prob_precipitacion(?:\s+periodo="(\d{2})-(\d{2})")?\s*(?:/>|>\s*(\d{0,3})\s*</prob_precipitacion>)""")
    private val NAME = Regex("""<nombre>\s*(.*?)\s*</nombre>""", RegexOption.DOT_MATCHES_ALL)
    private val MADE = Regex("""<elaborado>\s*(.*?)\s*</elaborado>""")

    fun parse(xml: String, code: String, fetchedAtMs: Long): RainForecast? {
        if (!xml.contains("<prediccion")) return null
        val periods = DAY.findAll(xml).flatMap { day ->
            val date = runCatching { LocalDate.parse(day.groupValues[1]) }.getOrNull() ?: return@flatMap emptySequence()
            PROB.findAll(day.groupValues[2]).mapNotNull { p ->
                val value = p.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val from = p.groupValues[1].toIntOrNull() ?: 0
                val to = p.groupValues[2].toIntOrNull() ?: 24
                if (from !in 0..23 || to !in 1..24 || to <= from) null
                else RainPeriod(date, from, to, value.coerceIn(0, 100))
            }
        }.toList()
        if (periods.isEmpty()) return null
        return RainForecast(
            code = code,
            place = NAME.find(xml)?.groupValues?.get(1).orEmpty(),
            elaborated = MADE.find(xml)?.groupValues?.get(1).orEmpty(),
            fetchedAtMs = fetchedAtMs,
            periods = periods,
        )
    }
}
