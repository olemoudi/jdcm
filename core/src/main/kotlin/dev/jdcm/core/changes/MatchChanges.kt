package dev.jdcm.core.changes

import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Venue
import java.time.LocalDateTime

/** Something about a followed team's match that a parent wants to hear about without opening the app. */
sealed interface MatchChange {
    val match: Match
    val teamId: Int

    /** The date or time moved, or the time was set for the first time. */
    data class Rescheduled(override val match: Match, override val teamId: Int, val before: Match) : MatchChange

    data class Relocated(override val match: Match, override val teamId: Int, val before: Venue?) : MatchChange

    /** Postponed, taken off the calendar, not held — or back on. */
    data class StatusChanged(override val match: Match, override val teamId: Int, val before: MatchStatus) : MatchChange

    data class ResultIn(override val match: Match, override val teamId: Int) : MatchChange
}

object MatchChanges {
    /**
     * What changed for [teamIds] between two snapshots of a group. Only matches both snapshots
     * hold are compared: the first sync of a group, or a round fetched for the first time, is
     * news to nobody. Past matches only report results; a change to a match long gone does not
     * matter to anyone.
     */
    fun between(before: GroupSnapshot?, after: GroupSnapshot, teamIds: Set<Int>, now: LocalDateTime): List<MatchChange> {
        if (before == null || teamIds.isEmpty()) return emptyList()
        val old = before.matches.associateBy { it.id }
        val out = mutableListOf<MatchChange>()
        val today = now.toLocalDate()
        for (m in after.matches.distinctBy { it.id }) {
            val teamId = teamIds.firstOrNull { m.involves(it) } ?: continue
            if (m.isBye) continue
            val o = old[m.id] ?: continue
            if (m.hasResult && !o.hasResult) {
                // A "result" for a match not played yet is the site's data being odd (an early
                // closed flag, a stray score), not news: never announce it.
                if (m.day?.isAfter(today) != false) continue
                out += MatchChange.ResultIn(m, teamId)
                continue
            }
            if (m.hasResult) continue
            val stillAhead = m.day?.let { !it.isBefore(now.toLocalDate().minusDays(1)) } ?: true
            if (!stillAhead) continue
            // Into a noteworthy state, or out of one ("back on"): both are news.
            val statusMoved = m.status != o.status && (m.status in NOTEWORTHY_STATUS || o.status in NOTEWORTHY_STATUS)
            if (statusMoved) {
                out += MatchChange.StatusChanged(m, teamId, o.status)
                continue
            }
            if (m.status == MatchStatus.UNSCHEDULED) continue
            // A date or venue that *disappears* is a gap in the answer, not a change worth a
            // notification (it would fire for every match, and again when the data returns).
            val moved = m.start != null && (m.start != o.start || m.timeKnown != o.timeKnown) && !(o.timeKnown && !m.timeKnown && m.day == o.day)
            if (moved) out += MatchChange.Rescheduled(m, teamId, o)
            if (m.venue != null && !sameVenue(m.venue, o.venue)) out += MatchChange.Relocated(m, teamId, o.venue)
        }
        return out
    }

    private val NOTEWORTHY_STATUS = setOf(MatchStatus.POSTPONED, MatchStatus.UNSCHEDULED, MatchStatus.NOT_HELD)

    /** Same place: names and coordinates rewritten by a re-save of the field are not a move. */
    private fun sameVenue(a: Venue?, b: Venue?): Boolean {
        if (a == null || b == null) return a == b
        return a.name.equals(b.name, ignoreCase = true) && a.address.equals(b.address, ignoreCase = true)
    }
}
