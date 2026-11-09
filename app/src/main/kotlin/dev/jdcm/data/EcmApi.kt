package dev.jdcm.data

import dev.jdcm.BuildConfig
import dev.jdcm.core.model.Category
import dev.jdcm.core.model.Competition
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.RoundPage
import dev.jdcm.core.parse.SiteParser
import dev.jdcm.core.parse.UnexpectedResponse
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Why a fetch failed, in the words the UI needs: no network, or the site itself. */
class SourceException(
    val offline: Boolean,
    message: String,
    cause: Throwable? = null,
    /** False when asking again cannot help (a 4xx, an absurd answer): no retries then. */
    val retryable: Boolean = true,
) : IOException(message, cause)

/**
 * The public JSON of juegosdeportivos.ecmadrid.org — the same calls its own calendar and results
 * pages make. One request at a time with a pause between them, a couple of gentle retries, and an
 * honest User-Agent: a small association's server must never notice this app.
 */
class EcmApi(private val http: OkHttpClient, private val base: HttpUrl = BASE) {
    private val gate = Mutex()
    @Volatile private var lastRequestAt = 0L

    suspend fun competitions(sportId: Int, seasonId: Int, includeClosed: Boolean): List<Competition> =
        get("competitions", "api/CompeticionesPorDeporte", "action" to "ObtenerCompeticionesDeporteListado",
            "IdDeporte" to sportId, "IdTemporada" to seasonId, "elementoTodas" to false, "soloActivas" to !includeClosed,
        ) { SiteParser.competitions(it, sportId) }

    suspend fun categories(competitionId: Int): List<Category> =
        get("categories", "api/CategoriasPorCompeticion", "action" to "ObtenerCategoriasCompeticionListado",
            "IdCompeticion" to competitionId,
        ) { SiteParser.categories(it, competitionId) }

    suspend fun groups(sportId: Int, competitionId: Int, categoryId: Int): List<Group> =
        get("groups", "api/GruposPorCategoria", "action" to "ObtenerGruposCategoriaListado",
            "IdCategoria" to categoryId, "IdCompeticion" to competitionId, "IdDeporte" to sportId,
        ) { SiteParser.groups(it) }

    /** One jornada of a group; [roundId] 0 = the one the site considers current. */
    suspend fun round(ref: GroupRef, roundId: Int): RoundPage =
        get("round", "Calendarios/ObtenerDatosJornadaGrupo",
            "IdDeporte" to ref.sportId, "IdCompeticion" to ref.competitionId, "IdCategoria" to ref.categoryId,
            "IdGrupo" to ref.groupId, "IdJornada" to roundId, "IdEquipo" to 0,
        ) { SiteParser.roundPage(it) }

    /** Every group of a category — or, with competitionId -1, every category of the sport — for one round number. */
    suspend fun groupedRound(sportId: Int, competitionId: Int, categoryId: Int, number: Int): List<GroupedRound> =
        get("grouped", "Calendarios/ObtenerCalendarioAgrupado",
            "IdDeporte" to sportId, "IdCompeticion" to competitionId, "IdCategoria" to categoryId,
            "NumeroJornada" to number, "IdEquipo" to 0,
        ) { SiteParser.groupedRound(it) }

    private suspend fun <T> get(name: String, path: String, vararg params: Pair<String, Any>, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegments(path).apply {
                params.forEach { (k, v) -> addQueryParameter(k, v.toString()) }
            }.build()
            var wait = 1_500L
            var attempt = 0
            while (true) {
                attempt++
                val started = System.currentTimeMillis()
                try {
                    val body = gate.withLock {
                        val since = System.currentTimeMillis() - lastRequestAt
                        if (since < MIN_GAP_MS) delay(MIN_GAP_MS - since)
                        try { fetch(url) } finally { lastRequestAt = System.currentTimeMillis() }
                    }
                    val parsed = parse(body)
                    DiagLog.i(TAG, "$name ok ${System.currentTimeMillis() - started}ms ${body.length}B")
                    return@withContext parsed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val retryable = if (e is SourceException) e.retryable else e is IOException || e is UnexpectedResponse
                    DiagLog.w(TAG, "$name failed attempt=$attempt ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    if (!retryable || attempt >= MAX_ATTEMPTS) throw asSourceException(e)
                    delay(wait)
                    wait *= 3
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun fetch(url: HttpUrl): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        http.newCall(req).execute().use { resp ->
            // A redirect to /Login (followed by OkHttp) shows up as HTML: the parser reports it.
            if (!resp.isSuccessful) throw SourceException(offline = false, "HTTP ${resp.code}", retryable = resp.code >= 500 || resp.code == 429)
            // The biggest real answer (a sport's whole round) is ~250 KB: anything near the cap is not ours.
            val source = resp.body.source()
            if (source.request(MAX_BODY_BYTES + 1)) throw SourceException(offline = false, "body over ${MAX_BODY_BYTES / 1024} KB", retryable = false)
            return source.buffer.readUtf8()
        }
    }

    private fun asSourceException(e: Exception): SourceException = when (e) {
        is SourceException -> e
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
        is java.net.NoRouteToHostException -> SourceException(offline = true, e.javaClass.simpleName, e)
        else -> SourceException(offline = false, e.message ?: e.javaClass.simpleName, e)
    }

    companion object {
        private const val TAG = "Source"
        val BASE: HttpUrl = "https://juegosdeportivos.ecmadrid.org/".toHttpUrl()
        const val SEASON_ID = 12 // TEMPORADA 2026-2027, the active one (inline in the site's pages)
        private const val MIN_GAP_MS = 350L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024
        val USER_AGENT = "JDCM/${BuildConfig.VERSION_NAME} (Android; family app; +https://github.com/olemoudi/jdcm)"

        /** The site's public pages, for "open on the web". */
        val CALENDAR_PAGE: String = BASE.resolve("Calendarios/CalendarioTemporada").toString()
        val RESULTS_PAGE: String = BASE.resolve("Resultados/ResultadosTemporada").toString()

        fun crestUrl(path: String): String? = path.takeIf { it.isNotBlank() }?.let { BASE.resolve(it)?.toString() }
    }
}

/** The four ids the site wants to serve a group's calendar. */
@kotlinx.serialization.Serializable
data class GroupRef(val groupId: Int, val sportId: Int, val competitionId: Int, val categoryId: Int)
