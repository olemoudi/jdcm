package dev.jdcm.core

import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.Team
import dev.jdcm.core.model.Venue
import java.time.LocalDate
import java.time.LocalDateTime

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun team(id: Int, name: String = "T$id") = Team(id, name)

fun match(
    id: Int,
    home: Team?,
    away: Team?,
    homeScore: Int = 0,
    awayScore: Int = 0,
    status: String = "Pendiente de Jugar",
    statusId: Int = 0,
    start: LocalDateTime? = LocalDateTime.of(2026, 11, 14, 10, 30),
    round: Int = 1,
    winnerId: Int = 0,
    closed: Boolean = false,
    venue: Venue? = Venue("Campo 1", "Calle Mayor 1"),
) = Match(
    id = id,
    roundId = 100 + round,
    roundNumber = round,
    groupId = 7,
    home = home,
    away = away,
    start = start,
    timeKnown = start != null && (start.hour != 0 || start.minute != 0),
    roundDate = start?.toLocalDate(),
    venue = venue,
    statusId = statusId,
    statusName = status,
    homeScore = homeScore,
    awayScore = awayScore,
    winnerId = winnerId,
    closed = closed,
)

fun played(id: Int, home: Team, away: Team, h: Int, a: Int, round: Int = 1) =
    match(id, home, away, h, a, status = "Jugado", statusId = 2, round = round, closed = true,
        start = LocalDateTime.of(2026, 11, 14, 10, 30).plusWeeks((round - 1).toLong()))

fun snapshot(matches: List<Match>, rounds: List<Round> = matches.map { Round(it.roundId, it.roundNumber, it.roundDate) }.distinctBy { it.id }) =
    GroupSnapshot(
        group = Group(7, "Grupo 1", "ALEVÍN MIXTO", "ALEVÍN MIXTO", "Fútbol sala"),
        sportId = 2,
        competitionId = 113,
        categoryId = 306,
        rounds = rounds.sortedBy { it.number },
        matches = matches,
        roundFetchedAt = rounds.associate { it.id to 1L },
    )

val SAT_14: LocalDate = LocalDate.of(2026, 11, 14)
