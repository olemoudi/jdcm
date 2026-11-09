package dev.jdcm.core.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Everything the app holds about one group: what the cache stores and what every screen reads.
 * Rounds are fetched one by one (the site has no whole-season call), so each carries its own
 * fetch time and [merge] replaces exactly the round a page brought.
 */
@Serializable
data class GroupSnapshot(
    val group: Group,
    val sportId: Int,
    val competitionId: Int,
    val categoryId: Int,
    val rounds: List<Round> = emptyList(),
    val matches: List<Match> = emptyList(),
    /** Epoch ms of the last successful fetch of each round, by round id. */
    val roundFetchedAt: Map<Int, Long> = emptyMap(),
    /** The round the site calls current (its IdJornada=0 answer), when known. */
    val currentRoundId: Int = 0,
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

    fun merge(page: RoundPage, nowMs: Long, wasCurrentRequest: Boolean): GroupSnapshot {
        val round = page.round ?: return copy(rounds = page.rounds.ifEmpty { rounds })
        val kept = matches.filter { it.roundId != round.id }
        val incoming = page.matches.map { if (it.roundId == 0) it.copy(roundId = round.id, roundNumber = round.number) else it }
        val g = page.group?.takeIf { it.name.isNotBlank() } ?: group
        return copy(
            group = g.copy(
                competition = g.competition.ifBlank { group.competition },
                category = g.category.ifBlank { group.category },
                sport = g.sport.ifBlank { group.sport },
            ),
            rounds = page.rounds.ifEmpty { (rounds + round).distinctBy { it.id }.sortedBy { it.number } },
            matches = (kept + incoming).sortedWith(MatchOrder),
            roundFetchedAt = roundFetchedAt + (round.id to nowMs),
            currentRoundId = if (wasCurrentRequest) round.id else currentRoundId,
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

/** By start (unknown times last within the day), then id: the order the site lists a round in. */
val MatchOrder: Comparator<Match> = compareBy<Match>(
    { it.day ?: LocalDate.MAX },
    { if (it.timeKnown) 0 else 1 },
    { it.start },
    { it.isBye },
    { it.id },
)
