package dev.jdcm.pump

import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.RoundPage
import dev.jdcm.core.parse.SiteParser
import dev.jdcm.core.parse.UnexpectedResponse
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** The site is down, slow or refusing (5xx, 429, timeouts, no network): the run stops asking at once. */
class SiteDown(message: String, cause: Throwable? = null) : IOException(message, cause)

/** An odd answer about one request (a 404, a login page, JSON of the wrong shape): the other jobs go ahead. */
class OddAnswer(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What the pump asks the site. One implementation talks HTTP; the tests serve the fixtures. */
interface Source {
    /** One jornada of a group; [roundId] 0 = the one the site considers current. */
    fun round(ref: GroupRef, roundId: Int): RoundPage

    /** Every group of a sport for one round number (no scores: used for the catalogue of teams only). */
    fun grouped(sportId: Int, number: Int): List<GroupedRound>

    /** Requests sent so far (retries included): the run's budget is counted on this. */
    val requests: Int
}

/**
 * The public JSON of juegosdeportivos.ecmadrid.org, asked the way the Android app asks it but
 * slower: one request at a time, [minGapMs] apart, one gentle retry, an honest User-Agent. A small
 * association's server must never notice this job.
 */
class HttpSource(
    private val base: String = BASE,
    private val minGapMs: Long = 1_500,
    private val retryWaitMs: Long = 5_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Source {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var lastAt = 0L
    override var requests = 0
        private set

    override fun round(ref: GroupRef, roundId: Int): RoundPage = get(
        "Calendarios/ObtenerDatosJornadaGrupo",
        "IdDeporte" to ref.sportId, "IdCompeticion" to ref.competitionId, "IdCategoria" to ref.categoryId,
        "IdGrupo" to ref.groupId, "IdJornada" to roundId, "IdEquipo" to 0,
    ) { SiteParser.roundPage(it) }

    override fun grouped(sportId: Int, number: Int): List<GroupedRound> = get(
        "Calendarios/ObtenerCalendarioAgrupado",
        "IdDeporte" to sportId, "IdCompeticion" to -1, "IdCategoria" to 0, "NumeroJornada" to number, "IdEquipo" to 0,
    ) { SiteParser.groupedRound(it) }

    private fun <T> get(path: String, vararg params: Pair<String, Any>, parse: (String) -> T): T {
        val query = params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), Charsets.UTF_8) }
        val uri = URI.create("$base$path?$query")
        var attempt = 0
        while (true) {
            attempt++
            try {
                val body = fetch(uri)
                return try {
                    parse(body)
                } catch (e: UnexpectedResponse) {
                    throw OddAnswer("unexpected answer: ${e.message}", e)
                }
            } catch (e: OddAnswer) {
                throw e
            } catch (e: SiteDown) {
                // One gentle retry for a timeout or a 5xx; never for a 429 (the site asked us to stop).
                if (attempt >= MAX_ATTEMPTS || e.message?.startsWith("HTTP 429") == true) throw e
                Log.w("retry", "path=$path attempt=$attempt ${e.message}")
                sleep(retryWaitMs)
            }
        }
    }

    private fun fetch(uri: URI): String {
        val since = System.currentTimeMillis() - lastAt
        if (lastAt > 0 && since < minGapMs) sleep(minGapMs - since)
        requests++
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .GET()
            .build()
        try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            resp.body().use { stream ->
                val code = resp.statusCode()
                if (code == 429 || code >= 500) throw SiteDown("HTTP $code")
                if (code !in 200..299) throw OddAnswer("HTTP $code")
                // The biggest real answer (a sport's whole round) is ~250 KB: anything near the cap is not ours.
                val bytes = stream.readNBytes(MAX_BODY_BYTES + 1)
                if (bytes.size > MAX_BODY_BYTES) throw OddAnswer("body over ${MAX_BODY_BYTES / 1024} KB")
                return bytes.toString(Charsets.UTF_8)
            }
        } catch (e: HttpTimeoutException) {
            throw SiteDown("timeout", e)
        } catch (e: OddAnswer) {
            throw e
        } catch (e: SiteDown) {
            throw e
        } catch (e: IOException) {
            throw SiteDown("${e.javaClass.simpleName}: ${e.message?.take(80)}", e)
        } finally {
            lastAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val BASE = "https://juegosdeportivos.ecmadrid.org/"
        const val VERSION = "1"
        const val USER_AGENT = "JDCM-PWA-data/$VERSION (+https://github.com/olemoudi/jdcm)"
        private const val MAX_ATTEMPTS = 2
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}

/** Plain `key=value` lines: the Actions log is the only diagnostics this job has. Never names, only ids. */
object Log {
    var sink: (String) -> Unit = { println(it) }
    fun i(tag: String, msg: String) = sink("I $tag $msg")
    fun w(tag: String, msg: String) = sink("W $tag $msg")
}
