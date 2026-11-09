package dev.jdcm.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Every date the site sends is Madrid wall-clock time without an offset. */
val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

/**
 * The three sports of the competition. The site's ids are stable (1, 2, 3) and its names are
 * upper-case Spanish; [OTHER] keeps the app working if a fourth one ever appears.
 */
enum class SportKind(val siteId: Int) {
    BASKETBALL(1),
    FUTSAL(2),
    VOLLEYBALL(3),
    OTHER(0);

    companion object {
        fun of(id: Int, name: String? = null): SportKind = entries.firstOrNull { it.siteId == id && it != OTHER }
            ?: name?.let { n ->
                val s = n.lowercase()
                when {
                    "balonc" in s -> BASKETBALL
                    "sala" in s || "fútbol" in s || "futbol" in s -> FUTSAL
                    "volei" in s || "vóley" in s || "voley" in s -> VOLLEYBALL
                    else -> null
                }
            }
            ?: OTHER
    }
}

@Serializable
data class Sport(val id: Int, val name: String) {
    val kind: SportKind get() = SportKind.of(id, name)
}

/** "ALEVÍN MIXTO" in a sport: the site calls it competición; for a family it is the age bracket. */
@Serializable
data class Competition(val id: Int, val sportId: Int, val name: String)

@Serializable
data class Category(val id: Int, val competitionId: Int, val name: String)

@Serializable
data class Group(
    val id: Int,
    val name: String,
    val competition: String = "",
    val category: String = "",
    val sport: String = "",
)

/** A jornada: a numbered matchday with the date of its weekend (the Saturday). */
@Serializable
data class Round(
    val id: Int,
    val number: Int,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate? = null,
)

@Serializable
data class Team(
    val id: Int,
    val name: String,
    /** Path on the site ("/documentos/…/Emblema_0061.jpg"), or empty when the school uploaded none. */
    val crestPath: String = "",
    val institutionId: Int = 0,
)

@Serializable
data class Venue(
    val name: String = "",
    val address: String = "",
    val postcode: String = "",
    val town: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
) {
    val hasPosition: Boolean get() = lat != null && lng != null && lat != 0.0 && lng != 0.0
}

/**
 * The status a match is in, as far as the app needs to tell them apart. The site only publishes
 * the catalogue of the states a change request can lead to (0, 1, 6, 12); the rest are read from
 * the state's name and the result fields — see [MatchStatus.of].
 */
enum class MatchStatus {
    SCHEDULED,
    PLAYED,
    POSTPONED,
    NOT_HELD,
    UNSCHEDULED,
    UNKNOWN;

    companion object {
        /** Ids from the site's own catalogue (`estadosPartidosSolicitudes`, measured 2026-11-09). */
        private val BY_ID = mapOf(0 to SCHEDULED, 1 to POSTPONED, 6 to NOT_HELD, 12 to UNSCHEDULED)

        fun of(id: Int, name: String): MatchStatus {
            val n = name.lowercase()
            return when {
                "desprogram" in n -> UNSCHEDULED
                "aplaz" in n || "suspend" in n -> POSTPONED
                "no celebrado" in n || "incomplet" in n -> NOT_HELD
                "pendiente" in n -> SCHEDULED
                "jugado" in n || "finaliz" in n || "celebrado" in n || "disputado" in n || "acta" in n || "cerrado" in n -> PLAYED
                else -> BY_ID[id] ?: UNKNOWN
            }
        }
    }
}

@Serializable
data class Match(
    val id: Int,
    val roundId: Int,
    val roundNumber: Int,
    val groupId: Int,
    /** Null on one side when the other team rests that matchday ("Descansa"). */
    val home: Team? = null,
    val away: Team? = null,
    @Serializable(with = LocalDateTimeSerializer::class) val start: LocalDateTime? = null,
    /** The site writes 00:00 for "time not set yet"; [start] then only carries the date. */
    val timeKnown: Boolean = false,
    @Serializable(with = LocalDateSerializer::class) val roundDate: LocalDate? = null,
    val venue: Venue? = null,
    val statusId: Int = 0,
    val statusName: String = "",
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val winnerId: Int = 0,
    /** Partial results (quarters, sets) as the site writes them, empty ones dropped. */
    val partials: List<String> = emptyList(),
    /** The minutes are closed: the result is final. */
    val closed: Boolean = false,
    /** Date, time or venue were changed by the organisation or by an accepted request. */
    val modified: Boolean = false,
    val modifiedReason: String = "",
    val duration: String = "",
) {
    val isBye: Boolean get() = home == null || away == null
    val status: MatchStatus get() = MatchStatus.of(statusId, statusName)

    /** The team that rests, for a bye. */
    val restingTeam: Team? get() = if (isBye) home ?: away else null

    fun involves(teamId: Int): Boolean = home?.id == teamId || away?.id == teamId

    fun opponentOf(teamId: Int): Team? = when (teamId) {
        home?.id -> away
        away?.id -> home
        else -> null
    }

    /**
     * Whether the score is a real, published result. A match the site still calls "pending"
     * with 0-0 is not one; a closed one, one with a winner, a score, or a played state is —
     * unless it was postponed or taken off the calendar.
     */
    val hasResult: Boolean
        get() {
            if (isBye) return false
            return when (status) {
                MatchStatus.POSTPONED, MatchStatus.UNSCHEDULED -> false
                MatchStatus.PLAYED -> true
                else -> closed || winnerId != 0 || homeScore + awayScore > 0
            }
        }

    /** The day it is played on: its own date if set, else the round's. */
    val day: LocalDate? get() = start?.toLocalDate() ?: roundDate
}

/** One jornada of a group as fetched: the rounds list comes with every fetch. */
data class RoundPage(
    val group: Group?,
    val round: Round?,
    val previous: Round?,
    val next: Round?,
    val rounds: List<Round>,
    val matches: List<Match>,
)

/** All groups of a category (or every category of a sport) for one round number. */
data class GroupedRound(
    val competitionId: Int,
    val competition: String,
    val categories: List<GroupedCategory>,
)

data class GroupedCategory(val categoryId: Int, val name: String, val groups: List<GroupedGroup>)

data class GroupedGroup(val groupId: Int, val name: String, val matches: List<Match>)

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
