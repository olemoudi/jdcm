package dev.jdcm.core.parse

import dev.jdcm.core.model.Category
import dev.jdcm.core.model.Competition
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupedCategory
import dev.jdcm.core.model.GroupedGroup
import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.RoundPage
import dev.jdcm.core.model.Team
import dev.jdcm.core.model.Venue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/** The site answered something that is not what the endpoint returns (a login page, an error page, truncated JSON). */
class UnexpectedResponse(message: String) : Exception(message)

/**
 * Reads the JSON of juegosdeportivos.ecmadrid.org. Tolerant by design: every field may be null or
 * missing, unknown fields are ignored, and only a body that is not the endpoint's shape at all
 * throws [UnexpectedResponse] (the caller retries; the cache keeps what it had).
 *
 * Two shapes exist: DevExtreme list stores (`{"data":[…],"totalCount":…}`) and the MVC actions
 * of the calendar, which return a JSON *string* whose content is the JSON (they serialise twice).
 */
object SiteParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun competitions(body: String, sportId: Int): List<Competition> = listData(body).mapNotNull { o ->
        val id = o.int("Id")
        val name = o.str("Nombre")
        // Id 0 is the combo's empty item and -1 its "TODAS": neither is a competition.
        if (id <= 0 || name.isBlank()) null else Competition(id, o.int("IdDeporte").takeIf { it > 0 } ?: sportId, name.trim())
    }

    fun categories(body: String, competitionId: Int): List<Category> = listData(body).mapNotNull { o ->
        val id = o.int("Id")
        val name = o.str("Nombre")
        if (id <= 0 || name.isBlank()) null else Category(id, o.int("IdCompeticion").takeIf { it > 0 } ?: competitionId, name.trim())
    }

    fun groups(body: String): List<Group> = listData(body).mapNotNull { o ->
        val id = o.int("Id")
        val name = o.str("Nombre")
        if (id <= 0 || name.isBlank()) null
        else Group(id, name.trim(), o.str("Competicion").trim(), o.str("Categoria").trim(), o.str("Deporte").trim())
    }

    /** `/Calendarios/ObtenerDatosJornadaGrupo` (and the results page's twin). */
    fun roundPage(body: String): RoundPage {
        val root = unwrap(body) as? JsonObject ?: throw UnexpectedResponse("round page is not an object")
        if ("jornada" !in root && "jornadas" !in root) throw UnexpectedResponse("round page without jornada")
        val jornada = root["jornada"].obj()
        val rounds = root["jornadas"].arr().mapNotNull { round(it.obj()) }.sortedBy { it.number }
        val matches = jornada?.get("Enfrentamientos").arr().mapNotNull { match(it.obj()) }
        val group = matches.firstNotNullOfOrNull { m -> groupOf(jornada?.get("Enfrentamientos").arr(), m.groupId) }
            ?: jornada?.int("IdGrupo")?.takeIf { it > 0 }?.let { Group(it, "") }
        return RoundPage(
            group = group,
            round = round(jornada),
            previous = round(root["previous"].obj()),
            next = round(root["next"].obj()),
            rounds = rounds,
            matches = matches,
        )
    }

    /** `/Calendarios/ObtenerCalendarioAgrupado`: every group of a category (or sport) for one round number. */
    fun groupedRound(body: String): List<GroupedRound> {
        val root = unwrap(body) as? JsonArray ?: throw UnexpectedResponse("grouped calendar is not an array")
        return root.map { it.obj() ?: JsonObject(emptyMap()) }.map { c ->
            GroupedRound(
                competitionId = c.int("IdCompeticion"),
                competition = c.str("Competicion").trim(),
                categories = c["Categorias"].arr().map { it.obj() ?: JsonObject(emptyMap()) }.map { cat ->
                    GroupedCategory(
                        categoryId = cat.int("IdCategoria"),
                        name = cat.str("Categoria").trim(),
                        groups = cat["Grupos"].arr().map { it.obj() ?: JsonObject(emptyMap()) }.map { g ->
                            GroupedGroup(
                                groupId = g.int("IdGrupo"),
                                name = g.str("Grupo").trim(),
                                matches = g["Enfrentamientos"].arr().mapNotNull { match(it.obj()) },
                            )
                        },
                    )
                },
            )
        }
    }

    /** The jornada a group is on and its list of jornadas, from the same page. */
    private fun round(o: JsonObject?): Round? {
        if (o == null) return null
        val id = o.int("Id")
        if (id <= 0) return null
        return Round(id, o.int("NumeroJornada"), date(o.str("Fecha")))
    }

    private fun groupOf(arr: JsonArray, groupId: Int): Group? {
        val o = arr.firstOrNull { it.obj()?.int("IdGrupo") == groupId }?.obj() ?: return null
        return Group(groupId, o.str("Grupo").trim(), o.str("Competicion").trim(), o.str("Categoria").trim(), o.str("Deporte").trim())
    }

    fun match(o: JsonObject?): Match? {
        if (o == null) return null
        val id = o.int("Id")
        if (id <= 0) return null
        val home = team(o.int("IdEquipoLocal"), o.str("EquipoLocal"), o.str("URLEscudoEquipoLocal"), o.int("IdInstitucionLocal"))
        val away = team(o.int("IdEquipoVisitante"), o.str("EquipoVisitante"), o.str("URLEscudoEquipoVisitante"), o.int("IdInstitucionVisitante"))
        if (home == null && away == null) return null
        val start = dateTime(o.str("FechaHora"))
        val statusId = o.int("IdEstadoPartido")
        val statusName = o.str("EstadoPartido").trim()
        val unscheduled = statusId == 12 || "desprogram" in statusName.lowercase()
        // The site's own rule (Calendarios.cshtml): an accepted request (state 6) or an organiser's
        // change marks the match as modified, and "desprogramado" outranks both.
        val acceptedRequest = o["SolicitudesModificaciones"].arr().any { it.obj()?.int("IdEstadoSolicitud") == 6 }
        val modified = !unscheduled && (o.bool("CambioAdministrador") || acceptedRequest)
        return Match(
            id = id,
            roundId = o.int("IdJornada"),
            roundNumber = o.int("NumeroJornada"),
            groupId = o.int("IdGrupo"),
            home = home,
            away = away,
            start = start,
            timeKnown = start != null && (start.hour != 0 || start.minute != 0),
            roundDate = date(o.str("FechaJornada")),
            venue = venue(o),
            statusId = statusId,
            statusName = statusName,
            homeScore = o.int("ResultadoEquipoLocal"),
            awayScore = o.int("ResultadoEquipoVisitante"),
            winnerId = o.int("IdEquipoGanador"),
            partials = (1..5).map { o.str("ResultadoParcial$it").trim() }.filter { it.isNotEmpty() },
            closed = o.bool("Cerrado"),
            modified = modified,
            modifiedReason = o.str("MotivoCambioAdministrador").trim(),
            duration = o.str("Duracion").trim(),
        )
    }

    private fun team(id: Int, name: String, crest: String, institution: Int): Team? =
        if (id <= 0) null else Team(id, name.trim().ifEmpty { "Equipo $id" }, crest.trim(), institution)

    private fun venue(o: JsonObject): Venue? {
        val field = o.str("Campo").trim()
        if (field.isEmpty() || field.equals("sin campo asignado", ignoreCase = true)) return null
        val c = o["CampoJuego"].obj()
        val lat = c?.double("Latitud") ?: o.double("Latitud")
        val lng = c?.double("Longitud") ?: o.double("Longitud")
        return Venue(
            name = c?.str("Nombre")?.trim().orEmpty().ifEmpty { field },
            address = c?.str("Direccion")?.trim().orEmpty(),
            postcode = c?.str("CP")?.trim().orEmpty(),
            town = c?.str("Municipio")?.trim().orEmpty(),
            lat = lat?.takeIf { it != 0.0 },
            lng = lng?.takeIf { it != 0.0 },
        )
    }

    // --- tolerant JSON access -------------------------------------------------------------------

    /** Decodes, and decodes again when the body is a JSON string holding JSON (the MVC actions). */
    fun unwrap(body: String): JsonElement {
        val first = runCatching { json.parseToJsonElement(body.trim()) }.getOrElse {
            throw UnexpectedResponse("not JSON (${body.take(40).replace('\n', ' ')})")
        }
        val inner = (first as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return first
        return runCatching { json.parseToJsonElement(inner) }.getOrElse { throw UnexpectedResponse("string is not JSON") }
    }

    private fun listData(body: String): List<JsonObject> {
        val root = unwrap(body)
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray ?: throw UnexpectedResponse("list without data")
            is JsonArray -> root
            else -> throw UnexpectedResponse("list is not an object")
        }
        return data.mapNotNull { it.obj() }
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String): String = prim(k)?.contentOrNull.orEmpty()
    private fun JsonObject.int(k: String): Int = prim(k)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 0
    private fun JsonObject.double(k: String): Double? = prim(k)?.doubleOrNull
    private fun JsonObject.bool(k: String): Boolean = prim(k)?.booleanOrNull ?: false

    /** "2026-11-13T18:00:00" (sometimes with fractions or a zone the site does not mean): wall-clock Madrid. */
    fun dateTime(s: String): LocalDateTime? {
        if (s.length < 16) return null
        return runCatching { LocalDateTime.parse(s.take(19).let { if (it.length == 16) "$it:00" else it }) }.getOrNull()
            ?.takeIf { it.year > 2000 }
    }

    fun date(s: String): LocalDate? = dateTime(s)?.toLocalDate()
        ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()?.takeIf { it.year > 2000 }
}
