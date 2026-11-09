package dev.jdcm.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * Everything the app holds about one group: what the cache stores and what every screen reads.
 * Rounds are fetched one by one (the site has no whole-season call), so each carries its own
 * fetch time and [merge] replaces exactly the rounds a page brought.
 */
@Serializable
data class GroupSnapshot(
    val group: Group,
    val sportId: Int,
    val competitionId: Int,
    val categoryId: Int,
    val rounds: List<Round> = emptyList(),
    val matches: List<Match> = emptyList(),
    /** Epoch ms of the last accepted fetch of each round, by round id. */
    val roundFetchedAt: Map<Int, Long> = emptyMap(),
    /** The round the site calls current (its IdJornada=0 answer), when known. */
    val currentRoundId: Int = 0,
    /**
     * When a sync last *tried* this group, whatever came of it. The sync gate reads this, not the
     * fetch times: a round the site keeps answering badly must not reopen the gate every hour.
     */
    val lastAttemptMs: Long = 0,
    /**
     * Rounds whose last answer had far fewer matches than held, with that answer's count. The
     * same smaller answer twice in a row is believed (a team withdrew); a one-off is not.
     */
    val shrinkSeen: Map<Int, Int> = emptyMap(),
) {
    val sport: SportKind get() = SportKind.of(sportId, group.sport)

    /** Every team of the group, crest included, in the order the site lists them. */
    val teams: List<Team>
        get() = matches.sortedBy { it.roundNumber }.flatMap { listOfNotNull(it.home, it.away) }.distinctBy { it.id }

    fun team(id: Int): Team? = matches.firstNotNullOfOrNull { m -> listOfNotNull(m.home, m.away).firstOrNull { it.id == id } }

    fun roundMatches(roundId: Int): List<Match> = matches.filter { it.roundId == roundId }.sortedWith(MatchOrder)

    fun teamMatches(teamId: Int): List<Match> = matches.filter { it.involves(teamId) }.sortedWith(MatchOrder)

    /** Oldest fetch among the rounds held: how stale the least fresh part is. */
    val oldestFetch: Long get() = rounds.minOfOrNull { roundFetchedAt[it.id] ?: 0L } ?: 0L
    val newestFetch: Long get() = roundFetchedAt.values.maxOrNull() ?: 0L

    /** Rounds never fetched yet (a new snapshot, or rounds the site added). */
    val missingRounds: List<Round> get() = rounds.filter { it.id !in roundFetchedAt }

    /**
     * Folds one answer of the site in, defensively:
     * - the group's id is ours, never the page's (only its names are taken);
     * - matches of another group are dropped;
     * - a round that comes back empty or with less than half the matches it had is *not* taken
     *   (a hiccup must not delete a child's match or lose its reminder): kept as it was, unstamped;
     * - a match that moved to another round is stored once (its old copy goes);
     * - rounds the site no longer lists go with their matches, unless the list shrank suspiciously.
     */
    fun merge(page: RoundPage, nowMs: Long, wasCurrentRequest: Boolean): Merged {
        var snap = this
        val named = page.group?.takeIf { it.name.isNotBlank() }
        if (named != null) {
            snap = snap.copy(
                group = group.copy(
                    name = named.name,
                    competition = named.competition.ifBlank { group.competition },
                    category = named.category.ifBlank { group.category },
                    sport = named.sport.ifBlank { group.sport },
                ),
            )
        }
        val accepted = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
        val today = Instant.ofEpochMilli(nowMs).atZone(MADRID).toLocalDate()
        val pages = listOfNotNull(page.round?.let { it to page.matches }) + page.neighbours
        for ((round, list) in pages.distinctBy { it.first.id }) {
            val mine = ours(list).size
            if (snap.acceptRound(round, mine)) {
                snap = snap.withRound(round, list, nowMs, today).copy(shrinkSeen = snap.shrinkSeen - round.id)
                accepted += round.id
            } else {
                snap = snap.copy(shrinkSeen = snap.shrinkSeen + (round.id to mine))
                rejected += round.id
            }
        }
        val before = snap.rounds.map { it.id }.toSet()
        snap = snap.withRoundList(page.rounds, accepted)
        val dropped = (before - snap.rounds.map { it.id }.toSet()).toList()
        if (wasCurrentRequest && page.round != null && page.round.id in accepted) snap = snap.copy(currentRoundId = page.round.id)
        return Merged(snap, accepted, rejected, dropped)
    }

    private fun ours(list: List<Match>): List<Match> = list.filter { it.groupId == group.id || it.groupId == 0 }

    /** Taken unless it shrank to less than half of what is held — and even then if it said so last time too. */
    private fun acceptRound(round: Round, incoming: Int): Boolean {
        val held = matches.count { it.roundId == round.id }
        return held < 2 || incoming * 2 >= held || shrinkSeen[round.id] == incoming
    }

    private fun withRound(round: Round, incoming: List<Match>, nowMs: Long, today: LocalDate): GroupSnapshot {
        val heldById = matches.associateBy { it.id }
        val fresh = ours(incoming)
            .map { it.copy(roundId = round.id, roundNumber = round.number, groupId = group.id) }
            .distinctBy { it.id }
            .map { m -> fillGaps(m, heldById[m.id]) }
            .map { m -> unbelievedResult(m, today) }
        val ids = fresh.map { it.id }.toSet()
        val kept = matches.filter { it.roundId != round.id && it.id !in ids }
        return copy(
            rounds = (rounds.filter { it.id != round.id } + round).sortedBy { it.number },
            matches = (kept + fresh).sortedWith(MatchOrder),
            roundFetchedAt = roundFetchedAt + (round.id to nowMs),
        )
    }

    /**
     * A date or venue missing from one answer is a gap, not news: the held value stays (else its
     * return would look like a change for every match and notify them all).
     */
    private fun fillGaps(m: Match, held: Match?): Match {
        if (held == null) return m
        var out = m
        if (m.start == null && held.start != null) out = out.copy(start = held.start, timeKnown = held.timeKnown, roundDate = m.roundDate ?: held.roundDate)
        if (m.venue == null && held.venue != null && m.status != MatchStatus.UNSCHEDULED) out = out.copy(venue = held.venue)
        return out
    }

    /**
     * A "result" for a match whose day has not come is not believed (an early closed flag, a stray
     * score): stored as pending, so the real result is announced and the eve reminder still fires.
     */
    private fun unbelievedResult(m: Match, today: LocalDate): Match {
        val day = m.day ?: return m
        if (!day.isAfter(today) || !m.hasResult) return m
        return m.copy(closed = false, winnerId = 0, homeScore = 0, awayScore = 0)
    }

    /**
     * The site's list of rounds, taken only when it is plausible: it must contain the rounds just
     * fetched, must not be much shorter than the one held, and rounds that hold results are never
     * dropped by it.
     */
    private fun withRoundList(listed: List<Round>, accepted: List<Int>): GroupSnapshot {
        if (listed.isEmpty()) return this
        val ids = listed.map { it.id }.toSet()
        if (!ids.containsAll(accepted)) return this
        if (rounds.size >= 4 && listed.size < rounds.size / 2) return this
        val withResults = rounds.filter { r -> r.id !in ids && matches.any { it.roundId == r.id && it.hasResult } }
        val keep = ids + withResults.map { it.id }
        return copy(
            rounds = (listed + withResults).sortedBy { it.number },
            matches = matches.filter { it.roundId in keep },
            roundFetchedAt = roundFetchedAt.filterKeys { it in keep },
            shrinkSeen = shrinkSeen.filterKeys { it in keep },
            currentRoundId = currentRoundId.takeIf { it in keep } ?: 0,
        )
    }

    /**
     * The round a family wants on screen today: the one being played this weekend, or the next
     * one, or the last one when the season is over. A weekend runs until Monday.
     */
    fun focusRound(today: LocalDate): Round? {
        if (rounds.isEmpty()) return null
        return rounds.firstOrNull { r -> r.date != null && !r.date.plusDays(2).isBefore(today) }
            ?: rounds.lastOrNull()
    }
}

/** What a merge did: the new snapshot, and which rounds it took, refused or dropped (for the log). */
data class Merged(val snapshot: GroupSnapshot, val accepted: List<Int>, val rejected: List<Int>, val dropped: List<Int> = emptyList())

/** By start (unknown times last within the day), then id: the order the site lists a round in. */
val MatchOrder: Comparator<Match> = compareBy<Match>(
    { it.day ?: LocalDate.MAX },
    { if (it.timeKnown) 0 else 1 },
    { it.start },
    { it.isBye },
    { it.id },
)
