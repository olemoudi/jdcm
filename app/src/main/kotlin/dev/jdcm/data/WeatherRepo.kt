package dev.jdcm.data

import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.weather.AemetParser
import dev.jdcm.core.weather.RainForecast
import dev.jdcm.core.weather.Weather
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.time.LocalDateTime

/**
 * AEMET's public municipal forecast, without a key: `https://www.aemet.es/xml/municipios/localidad_<code>.xml`
 * (owner, 2026-11-18). One file per municipality covers seven days; the app reads only the chance of
 * rain, only for the municipalities of the children's matches in the next [Weather.SHOW_DAYS] days,
 * and never more often than every [Weather.MAX_AGE_MS] with Home open, [Weather.BACKGROUND_MAX_AGE_MS]
 * in the background (AEMET makes a few a day). One request at a
 * time, a second apart, with the app's honest User-Agent; the forecasts are kept on disk so Home
 * shows the last one read when it opens offline.
 */
class WeatherRepo(context: android.content.Context, shared: OkHttpClient) {
    /** A forecast is a nicety: nobody waits a minute for it, and the 20:00 broadcast can't (review 2026-11-18). */
    private val http: OkHttpClient = shared.newBuilder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** When a municipality last failed: not asked again for [RETRY_AFTER_FAILURE_MS] (in memory, under [gate]). */
    private val failedAt = HashMap<String, Long>()
    private val file = JsonFile(File(context.filesDir, "forecasts.json"), ListSerializer(RainForecast.serializer()))
    private val mutable = MutableStateFlow<Map<String, RainForecast>>(emptyMap())
    val forecasts: StateFlow<Map<String, RainForecast>> = mutable.asStateFlow()
    private val gate = Mutex()
    private var loaded = false

    private suspend fun load() {
        if (loaded) return
        val read = file.read().orEmpty()
        loaded = true
        if (read.isNotEmpty()) mutable.update { now -> read.associateBy { it.code } + now }
    }

    /**
     * Reads the forecasts the [matches] need that are older than [Weather.MAX_AGE_MS]. Quiet about
     * failures (a forecast is a nicety): the last one read stays, and the next call tries again.
     * Returns how many were read.
     */
    suspend fun refreshFor(
        matches: Collection<Match>,
        reason: String,
        maxAgeMs: Long = Weather.MAX_AGE_MS,
        deadlineMs: Long = Long.MAX_VALUE,
    ): Int {
        withTimeoutOrNull(GATE_WAIT_MS) { gate.lock() } ?: run {
            DiagLog.i(TAG, "forecasts reason=$reason: another read is running, skipped")
            return 0
        }
        try {
            return refreshLocked(matches, reason, maxAgeMs, deadlineMs)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun refreshLocked(matches: Collection<Match>, reason: String, maxAgeMs: Long, deadlineMs: Long): Int {
        load()
        val now = LocalDateTime.now(MADRID)
        val nowMs = System.currentTimeMillis()
        val codes = matches.mapNotNull { Weather.municipalityFor(it, now) }.toSet()
        val stale = codes.filter { code -> mutable.value[code]?.let { nowMs - it.fetchedAtMs !in 0 until maxAgeMs } ?: true }
        // A municipality that just failed (404, a broken answer) is not asked on every Home update.
        val due = stale.filter { code -> failedAt[code]?.let { nowMs - it !in 0 until RETRY_AFTER_FAILURE_MS } ?: true }
        if (due.size < stale.size) DiagLog.i(TAG, "forecasts reason=$reason: ${stale - due.toSet()} failed lately, not asked")
        if (due.isEmpty()) return 0
        var read = 0
        for ((i, code) in due.withIndex()) {
            if (i > 0) delay(GAP_MS)
            if (System.currentTimeMillis() > deadlineMs) {
                DiagLog.w(TAG, "forecasts reason=$reason: out of time, ${due.size - i} not read")
                break
            }
            try {
                val forecast = fetch(code, System.currentTimeMillis())
                if (forecast == null) {
                    failedAt[code] = System.currentTimeMillis()
                    DiagLog.w(TAG, "aemet $code: no forecast in the answer")
                    continue
                }
                failedAt.remove(code)
                mutable.update { it + (code to forecast) }
                read++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt[code] = System.currentTimeMillis()
                DiagLog.w(TAG, "aemet $code failed", e)
            }
        }
        if (read > 0) runCatching { file.write(mutable.value.values.toList()) }.onFailure { DiagLog.w(TAG, "forecasts not saved", it) }
        DiagLog.i(TAG, "forecasts reason=$reason asked=${due.size} read=$read codes=$due")
        return read
    }

    private suspend fun fetch(code: String, nowMs: Long): RainForecast? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://www.aemet.es/xml/municipios/localidad_$code.xml")
            .header("User-Agent", EcmApi.USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val source = resp.body.source()
            if (source.request(MAX_BYTES + 1)) throw IOException("too big")
            AemetParser.parse(String(source.buffer.readByteArray(), Charset.forName("ISO-8859-15")), code, nowMs)
        }
    }

    companion object {
        private const val TAG = "Weather"
        private const val GAP_MS = 1_000L
        private const val GATE_WAIT_MS = 15_000L
        private const val RETRY_AFTER_FAILURE_MS = 30 * 60_000L
        private const val MAX_BYTES = 512L * 1024
    }
}
