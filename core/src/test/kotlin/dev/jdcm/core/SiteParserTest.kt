package dev.jdcm.core

import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.parse.SiteParser
import dev.jdcm.core.parse.UnexpectedResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

class SiteParserTest {
    @Test
    fun `competitions drop the combo's empty and TODAS items`() {
        val list = SiteParser.competitions(fixture("competiciones.json"), sportId = 2)
        assertEquals(listOf(111, 112, 113, 115, 116, 117, 118), list.map { it.id })
        assertEquals("ALEVÍN MIXTO", list.first { it.id == 113 }.name)
        assertTrue(list.all { it.sportId == 2 })
    }

    @Test
    fun `categories and groups keep only real entries`() {
        assertEquals(listOf(306), SiteParser.categories(fixture("categorias.json"), 113).map { it.id })
        val groups = SiteParser.groups(fixture("grupos.json"))
        assertEquals((177..184).toList(), groups.map { it.id })
        assertEquals("Grupo 8", groups.last().name)
        assertEquals("Fútbol sala", groups.last().sport)
    }

    @Test
    fun `a round page is a JSON string holding JSON, with the jornada list and its matches`() {
        val page = SiteParser.roundPage(fixture("jornada_grupo.json"))
        assertEquals(9, page.rounds.size)
        assertEquals(1, page.round?.number)
        assertEquals(LocalDate.of(2026, 11, 14), page.round?.date)
        assertNull(page.previous, "jornada 0 on the site means none")
        assertEquals(2, page.next?.number)
        assertEquals("Grupo 1", page.group?.name)
        assertEquals("ALEVÍN MIXTO", page.group?.competition)

        val first = page.matches.first { it.id == 10379 }
        assertEquals("FEC SANTA JOAQUINA DE VEDRUNA", first.home?.name)
        assertEquals("NUESTRA SEÑORA DE LA CONSOLACIÓN \"A\"", first.away?.name)
        assertEquals(LocalDateTime.of(2026, 11, 13, 18, 0), first.start)
        assertTrue(first.timeKnown)
        assertEquals("CAMPO 1 IGLESIA - EXTERIOR", first.venue?.name)
        assertEquals("28034", first.venue?.postcode)
        assertTrue(first.venue!!.hasPosition)
        assertEquals(MatchStatus.SCHEDULED, first.status)
        assertFalse(first.hasResult, "0-0 pending is not a result")
        assertEquals("/documentos/wallimages/imagepath/Emblema_0061.jpg", first.home?.crestPath)
    }

    @Test
    fun `a team resting that matchday is a bye with the date only`() {
        val page = SiteParser.roundPage(fixture("jornada_grupo_baloncesto.json"))
        val bye = page.matches.single { it.isBye }
        assertEquals("EL VALLE LAS TABLAS", bye.restingTeam?.name)
        assertFalse(bye.timeKnown, "00:00 means no time set")
    }

    @Test
    fun `the grouped calendar lists every group of a category`() {
        val grouped = SiteParser.groupedRound(fixture("calendario_agrupado.json"))
        val groups = grouped.single().categories.single().groups
        assertEquals(113, grouped.single().competitionId)
        assertEquals(306, grouped.single().categories.single().categoryId)
        assertEquals(8, groups.size)
        assertTrue(groups.all { g -> g.matches.isNotEmpty() })
    }

    @Test
    fun `a login page or garbage is an unexpected response, not an empty list`() {
        assertThrows<UnexpectedResponse> { SiteParser.roundPage("<!DOCTYPE html><html>marcaPaginaInicioSesion</html>") }
        assertThrows<UnexpectedResponse> { SiteParser.competitions("{\"error\":1}", 2) }
    }

    @Test
    fun `missing and null fields never throw`() {
        val m = SiteParser.match(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"Id":5,"IdEquipoLocal":1,"EquipoLocal":null,"IdEquipoVisitante":2,"FechaHora":null,"Campo":"Sin campo asignado","ResultadoEquipoLocal":"3"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertNotNull(m)
        assertEquals("Equipo 1", m!!.home?.name)
        assertNull(m.start)
        assertNull(m.venue)
        assertEquals(3, m.homeScore)
    }

    @Test
    fun `status names map to what a family needs to tell apart`() {
        assertEquals(MatchStatus.POSTPONED, MatchStatus.of(1, "Aplazado"))
        assertEquals(MatchStatus.UNSCHEDULED, MatchStatus.of(12, "Desprogramado"))
        assertEquals(MatchStatus.NOT_HELD, MatchStatus.of(6, "No Celebrado/Incompleto"))
        assertEquals(MatchStatus.PLAYED, MatchStatus.of(3, "Jugado"))
        assertEquals(MatchStatus.PLAYED, MatchStatus.of(9, "Finalizado"))
        assertEquals(MatchStatus.UNKNOWN, MatchStatus.of(42, "Algo nuevo"))
    }

    @Test
    fun `a desprogramado match is never marked modified`() {
        val json = """{"Id":5,"IdEquipoLocal":1,"IdEquipoVisitante":2,"IdEstadoPartido":12,"EstadoPartido":"Desprogramado","CambioAdministrador":true}"""
        val m = SiteParser.match(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)!!
        assertFalse(m.modified)
        val json2 = """{"Id":6,"IdEquipoLocal":1,"IdEquipoVisitante":2,"SolicitudesModificaciones":[{"IdEstadoSolicitud":6}]}"""
        assertTrue(SiteParser.match(kotlinx.serialization.json.Json.parseToJsonElement(json2) as kotlinx.serialization.json.JsonObject)!!.modified)
    }
}
