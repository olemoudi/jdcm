package dev.jdcm.pump

import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.model.Team
import dev.jdcm.core.model.Venue
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import kotlinx.serialization.Serializable
import java.text.Collator
import java.util.Locale

/*
 * What the PWA reads. Everything a screen needs that :core already knows how to work out (a
 * match's status, whether its score is a result, who won, the matchday it belongs to, the table)
 * is worked out here, so the PWA only formats and filters: the logic exists once.
 * Schema changes must stay backward compatible (old PWA builds keep reading new files for a
 * while): add fields, never rename or retype them, and bump [SCHEMA] only for a breaking change.
 */

const val SCHEMA = 1

@Serializable
data class TeamOut(val id: Int, val name: String, val crest: String = "")

@Serializable
data class VenueOut(
    val name: String,
    val address: String = "",
    val postcode: String = "",
    val town: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class MatchOut(
    val id: Int,
    val roundId: Int,
    val roundNumber: Int,
    val home: TeamOut? = null,
    val away: TeamOut? = null,
    /** Madrid wall-clock "2026-11-14T10:30"; the date only matters when [timeKnown] is false. */
    val start: String? = null,
    val timeKnown: Boolean = false,
    /** The day it is played on: its own date, else its round's. */
    val day: String? = null,
    /** The Saturday of the weekend it is listed under (Home's matchday strip). */
    val matchday: String? = null,
    /** SCHEDULED, PLAYED, POSTPONED, NOT_HELD, UNSCHEDULED or UNKNOWN (core's MatchStatus). */
    val status: String,
    val statusName: String = "",
    val hasResult: Boolean = false,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    /** HOME, AWAY or DRAW once there is a result (the site's named winner wins over the score). */
    val winner: String? = null,
    val partials: List<String> = emptyList(),
    val modified: Boolean = false,
    val modifiedReason: String = "",
    val duration: String = "",
    val venue: VenueOut? = null,
)

@Serializable
data class RoundOut(val id: Int, val number: Int, val date: String? = null, val fetchedAt: Long = 0)

@Serializable
data class RuleOut(val win: Int, val draw: Int, val loss: Int, val hasDraws: Boolean)

@Serializable
data class StandingOut(
    val position: Int,
    val team: TeamOut,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val scored: Int,
    val conceded: Int,
    val points: Int,
    /** Oldest first, at most five: W, D or L. */
    val form: List<String>,
)

@Serializable
data class StandingsOut(
    val rule: RuleOut,
    /** False when the points rule is an assumption (basketball, volleyball): the PWA says so under the table. */
    val measured: Boolean,
    val rows: List<StandingOut>,
)

@Serializable
data class GroupInfo(val id: Int, val name: String, val competition: String, val category: String, val sport: String)

/** `groups/<id>.json`. */
@Serializable
data class GroupOut(
    val v: Int = SCHEMA,
    val generatedAt: String,
    val ref: GroupRef,
    val group: GroupInfo,
    /** FUTSAL, BASKETBALL, VOLLEYBALL or OTHER. */
    val sport: String,
    val rounds: List<RoundOut>,
    val currentRoundId: Int = 0,
    val newestFetch: Long = 0,
    val oldestFetch: Long = 0,
    val lastAttemptMs: Long = 0,
    val matches: List<MatchOut>,
    val standings: StandingsOut,
)

@Serializable
data class IndexGroup(
    val groupId: Int,
    val sportId: Int,
    val competitionId: Int,
    val categoryId: Int,
    val name: String,
    val competition: String,
    val category: String,
    val sport: String,
    val newestFetch: Long = 0,
    val lastAttemptMs: Long = 0,
    /** The last run could not reach this group (the site down, an odd answer): the data shown is older. */
    val lastError: String? = null,
)

@Serializable
data class RunOut(val at: String, val requests: Int, val fetched: Int, val failed: Int, val stopped: String? = null)

/** `index.json`: small, read on every start. */
@Serializable
data class IndexOut(
    val v: Int = SCHEMA,
    val generatedAt: String,
    /** Epoch ms when the earliest piece of data becomes due again: the workflow skips Java until then. */
    val nextDueAt: Long,
    /** How often the data is refreshed, for the PWA's freshness line. */
    val refreshHours: Int = 3,
    val refreshFromHour: Int = 7,
    val refreshToHour: Int = 22,
    val catalogAt: Long = 0,
    val groups: List<IndexGroup>,
    val lastRun: RunOut,
)

@Serializable
data class CatalogGroup(val id: Int, val name: String, val followed: Boolean = false, val teams: List<TeamOut>)

@Serializable
data class CatalogCategory(val id: Int, val name: String, val groups: List<CatalogGroup>)

@Serializable
data class CatalogCompetition(val id: Int, val name: String, val categories: List<CatalogCategory>)

@Serializable
data class CatalogSport(val id: Int, val kind: String, val competitions: List<CatalogCompetition>)

/** `catalog.json`: every sport, competition, category, group and team the site lists (read when adding a child). */
@Serializable
data class CatalogOut(val v: Int = SCHEMA, val generatedAt: String, val fetchedAt: Long, val sports: List<CatalogSport>)

object Views {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es-ES")).apply { strength = Collator.PRIMARY }

    fun team(t: Team?): TeamOut? = t?.let { TeamOut(it.id, it.name, it.crestPath) }

    fun venue(v: Venue?): VenueOut? = v?.let { VenueOut(it.name, it.address, it.postcode, it.town, it.lat, it.lng) }

    fun match(m: Match): MatchOut {
        val result = m.hasResult
        val winner = if (!result) null else when (Standings.outcome(m, m.home?.id ?: 0)) {
            Outcome.WIN -> "HOME"
            Outcome.LOSS -> "AWAY"
            Outcome.DRAW -> "DRAW"
        }
        return MatchOut(
            id = m.id,
            roundId = m.roundId,
            roundNumber = m.roundNumber,
            home = team(m.home),
            away = team(m.away),
            start = m.start?.toString(),
            timeKnown = m.timeKnown,
            day = m.day?.toString(),
            matchday = Schedule.matchdayOf(m)?.toString(),
            status = m.status.name,
            statusName = m.statusName,
            hasResult = result,
            // A score is shown only when it is a result: a 0-0 the site still calls pending is not one.
            homeScore = if (result) m.homeScore else null,
            awayScore = if (result) m.awayScore else null,
            winner = winner,
            partials = m.partials,
            modified = m.modified,
            modifiedReason = m.modifiedReason,
            duration = m.duration,
            venue = venue(m.venue),
        )
    }

    fun rule(kind: SportKind): PointsRule = PointsRule.forSport(kind)

    fun group(snap: GroupSnapshot, ref: GroupRef, generatedAt: String): GroupOut {
        val rule = rule(snap.sport)
        val rows = Standings.compute(snap.matches, rule).map { r ->
            StandingOut(
                position = r.position,
                team = team(r.team)!!,
                played = r.played, won = r.won, drawn = r.drawn, lost = r.lost,
                scored = r.scored, conceded = r.conceded, points = r.points,
                form = r.form.map { o -> when (o) { Outcome.WIN -> "W"; Outcome.DRAW -> "D"; Outcome.LOSS -> "L" } },
            )
        }
        return GroupOut(
            generatedAt = generatedAt,
            ref = ref,
            group = GroupInfo(snap.group.id, snap.group.name, snap.group.competition, snap.group.category, snap.group.sport),
            sport = snap.sport.name,
            rounds = snap.rounds.map { RoundOut(it.id, it.number, it.date?.toString(), snap.roundFetchedAt[it.id] ?: 0) },
            currentRoundId = snap.currentRoundId,
            newestFetch = snap.newestFetch,
            oldestFetch = snap.oldestFetch,
            lastAttemptMs = snap.lastAttemptMs,
            matches = snap.matches.map(::match),
            standings = StandingsOut(
                rule = RuleOut(rule.win, rule.draw, rule.loss, rule.hasDraws),
                // Measured from an official table for futsal only (CLAUDE.md "Standings are not public").
                measured = snap.sport == SportKind.FUTSAL,
                rows = rows,
            ),
        )
    }

    /** One sport's part of the catalogue, from its grouped calendar of one round: every group and its teams. */
    fun catalogSport(sportId: Int, rounds: List<GroupedRound>): CatalogSport = CatalogSport(
        id = sportId,
        kind = SportKind.of(sportId).name,
        competitions = rounds.filter { it.competitionId > 0 }.map { c ->
            CatalogCompetition(
                id = c.competitionId,
                name = c.competition,
                categories = c.categories.filter { it.categoryId > 0 }.map { cat ->
                    CatalogCategory(
                        id = cat.categoryId,
                        name = cat.name,
                        groups = cat.groups.filter { it.groupId > 0 }.map { g ->
                            val teams = g.matches.flatMap { listOfNotNull(it.home, it.away) }.distinctBy { it.id }
                                .sortedWith { a, b -> collator.compare(a.name, b.name) }
                                .map { team(it)!! }
                            CatalogGroup(g.groupId, g.name, teams = teams)
                        }.sortedWith(compareBy({ groupNumber(it.name) }, { it.name })),
                    )
                }.sortedWith { a, b -> collator.compare(a.name, b.name) },
            )
        }.sortedWith { a, b -> collator.compare(a.name, b.name) },
    )

    /** "Grupo 10" after "Grupo 9". */
    private fun groupNumber(name: String): Int = Regex("\\d+").find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    fun groupCount(s: CatalogSport): Int = s.competitions.sumOf { c -> c.categories.sumOf { it.groups.size } }

    fun withFollowed(catalog: CatalogOut, followed: Set<Int>): CatalogOut = catalog.copy(
        sports = catalog.sports.map { s ->
            s.copy(competitions = s.competitions.map { c ->
                c.copy(categories = c.categories.map { cat ->
                    cat.copy(groups = cat.groups.map { g -> g.copy(followed = g.id in followed) })
                })
            })
        },
    )
}
