package dev.jdcm.pump

import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Round
import dev.jdcm.core.model.RoundPage
import dev.jdcm.core.parse.SiteParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

/**
 * Serves the site's real answers: a group's current round from its fixture, any other round of
 * its list as that round with no matches (enough for the planner), and the futsal grouped
 * calendar. [down] makes every request fail the way a dead site does.
 */
class FixtureSource(
    private val pages: Map<Int, String> = mapOf(177 to "j1_g177_resultado.json", 139 to "jornada_grupo_baloncesto.json"),
    var down: Boolean = false,
    var odd: Boolean = false,
    var grouped: (Int) -> String = { sport -> if (sport == 2) fixture("calendario_agrupado.json") else "[]" },
) : Source {
    override var requests = 0
    val asked = mutableListOf<String>()

    override fun round(ref: GroupRef, roundId: Int): RoundPage {
        requests++
        asked += "r${ref.groupId}:$roundId"
        if (down) throw SiteDown("HTTP 503")
        if (odd) throw OddAnswer("HTTP 404")
        val page = SiteParser.roundPage(fixture(pages.getValue(ref.groupId)))
        if (roundId == 0 || roundId == page.round?.id) return page
        val round = page.rounds.firstOrNull { it.id == roundId } ?: throw OddAnswer("no such round")
        return page.copy(round = round, previous = null, next = null, matches = emptyList(), neighbours = emptyList())
    }

    override fun grouped(sportId: Int, number: Int): List<GroupedRound> {
        requests++
        asked += "g$sportId:$number"
        if (down) throw SiteDown("HTTP 503")
        return SiteParser.groupedRound(grouped(sportId))
    }
}

class PumpTest {
    @TempDir lateinit var tmp: File

    private val futsal = FollowedGroup(177, 2, 113, 306, "futsal")
    private val basket = FollowedGroup(139, 1, 107, 212, "basket")

    /** Monday after the first matchday, 10:00 in Madrid. */
    private var nowMs = LocalDateTime.of(2026, 11, 16, 10, 0).atZone(MADRID).toInstant().toEpochMilli()

    private fun pump(source: Source, groups: List<FollowedGroup>, prev: File?, out: String, limits: Limits = Limits()) =
        Pump(source, PumpConfig(groups), prev, File(tmp, out), limits, clock = { nowMs })

    private fun json(file: File) = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `a first run loads every round of the group, the catalogue, and writes what the PWA reads`() {
        val source = FixtureSource()
        val result = pump(source, listOf(futsal), null, "a").run()

        assertTrue(result.publish)
        assertEquals("r177:0", source.asked.first(), "a group never loaded asks for the current round first")
        assertTrue("g2:1" in source.asked, "the catalogue is fetched when there is none")
        // Round 0 brought rounds 1 and 2; the other seven are asked one by one (closest first).
        assertEquals(1 + 7 + 3, source.requests)

        val index = json(File(tmp, "a/index.json"))
        val g = index["groups"]!!.jsonArray.single().jsonObject
        assertEquals("Grupo 1", g["name"]!!.jsonPrimitive.content)
        assertNull(g["lastError"])

        val view = json(File(tmp, "a/groups/177.json"))
        assertEquals("FUTSAL", view["sport"]!!.jsonPrimitive.content)
        val played = view["matches"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "10379" }
        assertEquals("PLAYED", played["status"]!!.jsonPrimitive.content)
        assertEquals("true", played["hasResult"]!!.jsonPrimitive.content)
        assertEquals("10", played["homeScore"]!!.jsonPrimitive.content)
        assertEquals("HOME", played["winner"]!!.jsonPrimitive.content)
        assertEquals("2026-11-14", played["matchday"]!!.jsonPrimitive.content, "a Friday-evening match counts with its Saturday")
        // A pending match's 0-0 is no result: no score is published for it.
        val pending = view["matches"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "10376" }
        assertEquals("false", pending["hasResult"]!!.jsonPrimitive.content)
        assertNull(pending["homeScore"])
        val standings = view["standings"]!!.jsonObject
        assertEquals("true", standings["measured"]!!.jsonPrimitive.content)
        assertEquals(10, standings["rows"]!!.jsonArray.size, "every team of the group has a row from matchday one")
        val top = standings["rows"]!!.jsonArray.first().jsonObject
        assertEquals("2", top["points"]!!.jsonPrimitive.content)

        val catalog = json(File(tmp, "a/catalog.json"))
        val groups = catalog["sports"]!!.jsonArray.flatMap { s ->
            s.jsonObject["competitions"]!!.jsonArray.flatMap { c ->
                c.jsonObject["categories"]!!.jsonArray.flatMap { it.jsonObject["groups"]!!.jsonArray.map { g -> g.jsonObject } }
            }
        }
        assertEquals(8, groups.size)
        assertEquals("true", groups.first { it["id"]!!.jsonPrimitive.content == "177" }["followed"]!!.jsonPrimitive.content)
        assertTrue(groups.all { it["teams"]!!.jsonArray.isNotEmpty() })
        assertTrue(File(tmp, "a/state/177.json").exists())
        assertTrue(File(tmp, "a/README.md").exists())
    }

    @Test
    fun `a run minutes after another asks nothing and publishes nothing`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        nowMs += 20 * 60_000
        val source = FixtureSource()
        val result = pump(source, listOf(futsal), File(tmp, "a"), "b").run()
        assertEquals(0, source.requests)
        assertFalse(result.publish)
        assertFalse(File(tmp, "b").exists())
    }

    @Test
    fun `the next scheduled run, three hours later, refreshes this weekend's round and little else`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        nowMs += 3 * 3_600_000
        val source = FixtureSource()
        val result = pump(source, listOf(futsal), File(tmp, "a"), "b").run()
        assertTrue(result.publish)
        assertTrue(source.requests in 1..2, "asked ${source.asked}")
        assertTrue(source.asked.none { it.startsWith("g") }, "the catalogue waits a day")
        val before = json(File(tmp, "a/index.json"))["nextDueAt"]!!.jsonPrimitive.content.toLong()
        assertTrue(before <= nowMs, "the index said this run would have work")
    }

    @Test
    fun `a site that is down ends the run at once and the published data stays`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        nowMs += 3 * 3_600_000
        val source = FixtureSource(down = true)
        val result = pump(source, listOf(futsal), File(tmp, "a"), "b").run()
        assertEquals(1, source.requests)
        assertFalse(result.publish)
        assertNotNull(result.stopped)
    }

    @Test
    fun `odd answers in a row stop the run`() {
        val source = FixtureSource(odd = true)
        val groups = (1..5).map { FollowedGroup(1000 + it, 2, 113, 306) }
        val result = pump(source, groups, null, "a", Limits(maxOddInARow = 3)).run()
        assertEquals(3, source.requests)
        assertTrue(result.stopped!!.contains("odd"))
        // The list of groups changed (there was none): the empty groups are published with their error.
        assertTrue(result.publish)
        val index = json(File(tmp, "a/index.json"))
        assertEquals("HTTP 404", index["groups"]!!.jsonArray.first().jsonObject["lastError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the request budget is never exceeded, and what matters most goes first`() {
        val source = FixtureSource()
        val result = pump(source, listOf(futsal, basket), null, "a", Limits(maxRequests = 4)).run()
        assertEquals(4, source.requests)
        assertEquals(listOf("r177:0", "r139:0", "g1:1", "g2:1"), source.asked)
        assertTrue(result.stopped!!.contains("budget"))
    }

    @Test
    fun `basketball tables say their points rule is an assumption`() {
        pump(FixtureSource(), listOf(basket), null, "a").run()
        val view = json(File(tmp, "a/groups/139.json"))
        assertEquals("BASKETBALL", view["sport"]!!.jsonPrimitive.content)
        assertEquals("false", view["standings"]!!.jsonObject["measured"]!!.jsonPrimitive.content)
        assertEquals("false", view["standings"]!!.jsonObject["rule"]!!.jsonObject["hasDraws"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a catalogue answer with far fewer groups than held is not taken`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        nowMs += 24 * 3_600_000
        // The same competition, its category now holding a single group.
        val source = FixtureSource(grouped = { sport ->
            if (sport != 2) "[]"
            else """[{"IdCompeticion":113,"Competicion":"ALEVÍN MIXTO","Categorias":[{"IdCategoria":306,"Categoria":"ALEVÍN MIXTO","Grupos":[{"IdGrupo":177,"Grupo":"Grupo 1","Enfrentamientos":[]}]}]}]"""
        })
        pump(source, listOf(futsal), File(tmp, "a"), "b").run()
        val catalog = json(File(tmp, "b/catalog.json"))
        val futsalSport = catalog["sports"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "2" }
        val groups = futsalSport["competitions"]!!.jsonArray.flatMap { c -> c.jsonObject["categories"]!!.jsonArray.flatMap { it.jsonObject["groups"]!!.jsonArray } }
        assertEquals(8, groups.size)
    }

    @Test
    fun `a group added to the list is published even when nothing could be fetched`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        nowMs += 10 * 60_000
        val source = FixtureSource(down = true)
        val result = pump(source, listOf(futsal, basket), File(tmp, "a"), "b").run()
        assertTrue(result.publish)
        val index = json(File(tmp, "b/index.json"))
        assertEquals(listOf("177", "139"), index["groups"]!!.jsonArray.map { it.jsonObject["groupId"]!!.jsonPrimitive.content })
        // The followed group keeps everything it had.
        assertEquals(json(File(tmp, "a/state/177.json")), json(File(tmp, "b/state/177.json")))
    }

    @Test
    fun `round dates and the matchday of every match come from the site's round`() {
        pump(FixtureSource(), listOf(futsal), null, "a").run()
        val view = json(File(tmp, "a/groups/177.json"))
        val rounds = view["rounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(9, rounds.size)
        assertEquals("2026-11-21", rounds[1]["date"]!!.jsonPrimitive.content)
        assertTrue(rounds.all { it["fetchedAt"]!!.jsonPrimitive.content.toLong() > 0 })
        assertEquals(Round(2596, 1, java.time.LocalDate.of(2026, 11, 14)).date.toString(), rounds[0]["date"]!!.jsonPrimitive.content)
    }
}
