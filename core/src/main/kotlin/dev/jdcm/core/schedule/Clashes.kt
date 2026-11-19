package dev.jdcm.core.schedule

import dev.jdcm.core.model.Match
import dev.jdcm.core.model.Venue
import kotlin.math.abs

/**
 * Two of the family's children playing at nearly the same time in different places: one parent
 * cannot take both. [first] starts first. The keys are the children's ids (never names: the UI and
 * the notification look the names up).
 */
data class Clash(val first: Pair<String, Match>, val second: Pair<String, Match>) {
    fun other(key: String, matchId: Int): Pair<String, Match>? = when {
        first.first == key && first.second.id == matchId -> second
        second.first == key && second.second.id == matchId -> first
        else -> null
    }
}

object Clashes {
    /** A match without the site's duration is taken to last this long (the site writes 70 to 90 min). */
    const val DEFAULT_MINUTES = 75L

    /** Getting from one field to another across Madrid, at the least. */
    const val TRAVEL_MINUTES = 30L

    /**
     * Every pair of [entries] (child id, match) that clash: different children, different matches,
     * both on (time known, not cancelled, no result yet, field known), the same day, different places,
     * and the later one starting before the earlier one is over plus the trip. Siblings in the same
     * match, or at the same school, do not clash; nor does a match whose field is not set yet (nobody
     * knows it is elsewhere). Ordered by the first match's start.
     */
    fun find(entries: List<Pair<String, Match>>): List<Clash> {
        val on = entries.filter { (_, m) -> m.timeKnown && m.start != null && !m.isBye && !m.isCancelled && !m.hasResult && known(m.venue) }
            .distinctBy { (k, m) -> k to m.id }
            .sortedBy { it.second.start }
        val out = mutableListOf<Clash>()
        for (i in on.indices) for (j in i + 1 until on.size) {
            val a = on[i]
            val b = on[j]
            if (a.first == b.first || a.second.id == b.second.id) continue
            val sa = a.second.start!!
            val sb = b.second.start!!
            if (sa.toLocalDate() != sb.toLocalDate()) continue
            if (samePlace(a.second.venue, b.second.venue)) continue
            val freeAt = sa.plusMinutes(minutes(a.second) + TRAVEL_MINUTES)
            if (sb.isBefore(freeAt)) out += Clash(a, b)
        }
        return out
    }

    fun minutes(m: Match): Long = m.durationMinutes?.toLong()?.coerceIn(30, 180) ?: DEFAULT_MINUTES

    /** A field the site has named or placed: one without anything is «sin campo asignado». */
    fun known(v: Venue?): Boolean = v != null && (v.address.isNotBlank() || v.name.isNotBlank() || v.hasPosition)

    /**
     * The same school, whatever the court: the same spot on the map (within ~300 m), or the same
     * address in the same town (the site's name is the court, «PISTA 1 - EXTERIOR»; the address is the
     * school's). Without addresses, the same name in the same town. Unknown is never the same.
     */
    fun samePlace(a: Venue?, b: Venue?): Boolean {
        if (!known(a) || !known(b)) return false
        a!!; b!!
        // ~0.003° is ~300 m north-south and ~250 m east-west at Madrid's latitude.
        if (a.hasPosition && b.hasPosition && abs(a.lat!! - b.lat!!) < 0.003 && abs(a.lng!! - b.lng!!) < 0.003) return true
        val sameTown = a.town.isBlank() || b.town.isBlank() || a.town.trim().equals(b.town.trim(), ignoreCase = true)
        if (a.address.isNotBlank() && b.address.isNotBlank()) return sameTown && a.address.trim().equals(b.address.trim(), ignoreCase = true)
        if (a.address.isBlank() && b.address.isBlank() && a.name.isNotBlank()) return sameTown && a.name.trim().equals(b.name.trim(), ignoreCase = true)
        return false
    }
}
