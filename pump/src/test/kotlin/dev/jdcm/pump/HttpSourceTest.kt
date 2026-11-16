package dev.jdcm.pump

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.Collections

/** Politeness, measured against a local server: one at a time, spaced, honest, and gentle on failure. */
class HttpSourceTest {
    private lateinit var server: HttpServer
    private val hits = Collections.synchronizedList(mutableListOf<Pair<Long, Map<String, String>>>())
    @Volatile private var status = 200
    @Volatile private var body = fixture("j1_g177_resultado.json")
    private val sleeps = mutableListOf<Long>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits += System.currentTimeMillis() to mapOf(
                "ua" to ex.requestHeaders.getFirst("User-Agent").orEmpty(),
                "query" to ex.requestURI.rawQuery.orEmpty(),
            )
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun source() = HttpSource(
        base = "http://127.0.0.1:${server.address.port}/",
        minGapMs = 300,
        retryWaitMs = 10,
        sleep = { sleeps += it; Thread.sleep(it) },
    )

    private val ref = GroupRef(177, 2, 113, 306)

    @Test
    fun `requests are spaced, carry an honest User-Agent and the site's own parameters`() {
        val s = source()
        s.round(ref, 0)
        s.round(ref, 2597)
        assertEquals(2, hits.size)
        assertTrue(hits[1].first - hits[0].first >= 290, "gap ${hits[1].first - hits[0].first} ms")
        assertTrue(hits[0].second.getValue("ua").startsWith("JDCM-PWA-data/"))
        assertTrue(hits[0].second.getValue("ua").contains("github.com/olemoudi/jdcm"))
        assertEquals("IdDeporte=2&IdCompeticion=113&IdCategoria=306&IdGrupo=177&IdJornada=2597&IdEquipo=0", hits[1].second["query"])
        assertEquals(2, s.requests)
    }

    @Test
    fun `a 5xx is retried once, then the site counts as down`() {
        status = 503
        val s = source()
        assertThrows<SiteDown> { s.round(ref, 0) }
        assertEquals(2, hits.size)
        assertEquals(2, s.requests)
    }

    @Test
    fun `a 429 is never retried`() {
        status = 429
        val s = source()
        assertThrows<SiteDown> { s.round(ref, 0) }
        assertEquals(1, hits.size)
    }

    @Test
    fun `a 404 or a login page is an odd answer about that request only`() {
        status = 404
        assertThrows<OddAnswer> { source().round(ref, 0) }
        status = 200
        body = "<html>Login</html>"
        assertThrows<OddAnswer> { source().round(ref, 0) }
        assertEquals(2, hits.size, "neither is retried")
    }

    @Test
    fun `nobody listening is a site down`() {
        val s = HttpSource(base = "http://127.0.0.1:1/", minGapMs = 0, retryWaitMs = 1)
        assertThrows<SiteDown> { s.grouped(2, 1) }
    }
}
