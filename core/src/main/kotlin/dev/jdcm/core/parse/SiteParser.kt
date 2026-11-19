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
import dev.jdcm.core.model.Season
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
        val neighbours = listOf("previous", "next").mapNotNull { k ->
            val o = root[k].obj() ?: return@mapNotNull null
            val r = round(o) ?: return@mapNotNull null
            val list = o["Enfrentamientos"] as? JsonArray ?: return@mapNotNull null
            r to list.mapNotNull { match(it.obj()) }
        }
        return RoundPage(
            group = group,
            round = round(jornada),
            previous = round(root["previous"].obj()),
            next = round(root["next"].obj()),
            rounds = rounds,
            matches = matches,
            neighbours = neighbours,
        )
    }

    /**
     * `/Calendarios/ObtenerCalendarioAgrupado`: every group of a category (or sport) for one round number.
     * It sends no round date (`FechaJornada`), which a replay is told by ([Match.isReplay]): the
     * day most of the answer's matches are on stands in for it (review 2026-11-18).
     */
    fun groupedRound(body: String): List<GroupedRound> {
        val parsed = groupedRoundAsSent(body)
        val roundDay = parsed.flatMap { it.categories }.flatMap { it.groups }.flatMap { it.matches }
            .mapNotNull { it.start?.toLocalDate() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return parsed
        return parsed.map { r ->
            r.copy(categories = r.categories.map { cat ->
                cat.copy(groups = cat.groups.map { g -> g.copy(matches = g.matches.map { m -> if (m.roundDate == null) m.copy(roundDate = roundDay) else m }) })
            })
        }
    }

    private fun groupedRoundAsSent(body: String): List<GroupedRound> {
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
            // A number, or a string holding one: "" or "-" is no score, not a 0.
            scoreKnown = o.isNumber("ResultadoEquipoLocal") && o.isNumber("ResultadoEquipoVisitante"),
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
    private fun JsonObject.isNumber(k: String): Boolean = prim(k)?.let { it.intOrNull != null || it.contentOrNull?.trim()?.toDoubleOrNull() != null } == true
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

    // --- the season (HTML) ----------------------------------------------------------------------

    /**
     * Compiled on first use, apart from the parser's own init. Android runs regexes on ICU, stricter than
     * the JVM the tests use (a lone `}` is an error there): no braces in these. A pattern ICU refused
     * would throw an Error from this object's init; SeasonWatch catches Throwable, the parser's other
     * reads never touch it.
     */
    private object SeasonPatterns {
        val id = Regex(""""Id"\s*:\s*(\d+)""")
        val name = Regex(""""Nombre"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val active = Regex(""""Activa"\s*:\s*true""")
        val value = Regex(""""value"\s*:\s*(\d+)""")
        val year = Regex("""\d\d\d\d""")
        val unicode = Regex("""\\u([0-9a-fA-F]+)""")
        val call = Regex("""cmbCalendarioTemporada["']\s*\)\s*\.\s*dxSelectBox\s*\(\s*""")
    }

    /**
     * The season the site's calendar page opens on, read from its season picker: the page writes the
     * seasons inline (`$("#cmbCalendarioTemporada").dxSelectBox({… "data":[{"Id":12,"Nombre":"TEMPORADA
     * 2026-2027",…,"Activa":true,…}, …] …, "value":12})`, measured 2026-11-19; its own script names the
     * picker first, `….dxSelectBox("instance")`, which is not a call with options). Only the picker's
     * options object is read, by brace depth: its own `value`, and the top-level entries of its `data`
     * (nothing nested in an entry, nor another picker's list, can pass for a season). An entry needs a
     * year in its name. The picker's value first (what a visitor sees), else the newest active season;
     * null when there is no such picker (a login wall, a redesign): the caller keeps what it had.
     */
    fun activeSeason(html: String): Season? {
        for (m in SeasonPatterns.call.findAll(html)) {
            val open = m.range.last + 1
            if (open >= html.length || html[open] != '{') continue
            val options = balanced(html, open) ?: continue
            seasonIn(options)?.let { return it }
        }
        return null
    }

    private fun seasonIn(options: String): Season? {
        val p = SeasonPatterns
        val dataAt = options.indexOf("\"data\"").takeIf { it >= 0 } ?: return null
        val arrayAt = options.indexOf('[', dataAt).takeIf { it >= 0 } ?: return null
        val array = balanced(options, arrayAt) ?: return null
        val seasons = topLevelObjects(array).mapNotNull { entry ->
            val flat = flatten(entry)
            val id = p.id.find(flat)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val name = p.name.find(flat)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            if (id <= 0 || !p.year.containsMatchIn(name)) return@mapNotNull null
            Triple(id, name, p.active.containsMatchIn(flat))
        }
        if (seasons.isEmpty()) return null
        val value = p.value.find(flatten(options))?.groupValues?.get(1)?.toIntOrNull()
        val picked = value?.let { v -> seasons.firstOrNull { it.first == v } }
            ?: seasons.filter { it.third }.maxByOrNull { it.first }
            ?: return null
        return Season(picked.first, picked.second)
    }

    /** The `{…}` or `[…]` starting at [start], strings and escapes respected; null when it never closes. */
    private fun balanced(s: String, start: Int): String? {
        var depth = 0
        var inString = false
        var quote = '"'
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == quote) inString = false
            } else when (c) {
                '"', '\'' -> { inString = true; quote = c }
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    /** The objects directly inside an array `[{…},{…}]`. */
    private fun topLevelObjects(array: String): List<String> {
        val out = mutableListOf<String>()
        var i = 1
        while (i < array.length - 1) {
            val c = array[i]
            if (c == '{') {
                val obj = balanced(array, i) ?: break
                out += obj
                i += obj.length
            } else if (c == '[' || c == '"' || c == '\'') {
                i += (balanced(array, i)?.length ?: (skipString(array, i) - i)).coerceAtLeast(1)
            } else i++
        }
        return out
    }

    private fun skipString(s: String, start: Int): Int {
        val quote = s[start]
        var i = start + 1
        while (i < s.length && s[i] != quote) { if (s[i] == '\\') i++; i++ }
        return i + 1
    }

    /** An object with whatever is nested in it blanked out: only its own keys and plain values remain. */
    private fun flatten(obj: String): String {
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var quote = '"'
        var i = 0
        while (i < obj.length) {
            val c = obj[i]
            val keep = depth <= 1
            if (inString) {
                if (keep) sb.append(c)
                if (c == '\\') { i++; if (keep && i < obj.length) sb.append(obj[i]) } else if (c == quote) inString = false
            } else when (c) {
                '"', '\'' -> { inString = true; quote = c; if (keep) sb.append(c) }
                '{', '[' -> { depth++; if (depth <= 1) sb.append(c) }
                '}', ']' -> { if (depth <= 1) sb.append(c); depth-- }
                else -> if (keep) sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun unescape(s: String): String =
        SeasonPatterns.unicode.replace(s) { m -> m.groupValues[1].take(4).toIntOrNull(16)?.toChar()?.toString().orEmpty() + m.groupValues[1].drop(4) }.replace("\\\"", "\"").replace("\\/", "/")
}
