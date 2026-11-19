package dev.jdcm.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jdcm.JdcmApp
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.weather.MatchWeather
import dev.jdcm.core.weather.RainAlertPolicy
import dev.jdcm.core.weather.Weather
import dev.jdcm.data.Kid
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * The rain forecast for the children's matches, and the alerts about it (owner, 2026-11-18).
 *
 * Forecasts are read for the matches in the next [Weather.SHOW_DAYS] days whose venue is not under
 * a roof, whenever the hourly worker runs, a pre-match check runs, Home is opened, or the evening
 * alarm goes off. Alerts — only with Settings' switch on — follow [RainAlertPolicy]: over 60 %, at
 * 20:00 on the days before, as soon as seen in the last 24 hours, one per match per 24 hours.
 * The evening alarm is one inexact alarm at the next 20:00, armed while there is a match to watch.
 */
object RainWatch {
    private const val TAG = "Rain"
    private const val REQUEST = 4403

    /** Every followed child's match that a forecast is wanted for, with the child. */
    fun watched(kids: List<Kid>, snapshots: Map<Int, GroupSnapshot>, now: LocalDateTime): List<Pair<Kid, Match>> =
        kids.flatMap { kid ->
            val s = snapshots[kid.groupId] ?: return@flatMap emptyList()
            Schedule.upcoming(s, kid.teamId, now).filter { Weather.municipalityFor(it, now) != null }.map { kid to it }
        }

    private suspend fun watched(app: JdcmApp, now: LocalDateTime): List<Pair<Kid, Match>> {
        val c = app.container
        val kids = c.kids.current()
        kids.forEach { c.repository.load(it.groupId) }
        return watched(kids, c.repository.snapshots.value, now)
    }

    /**
     * Reads what is due and alerts what [RainAlertPolicy] allows. [reason] is for the report.
     * [throttled] (the hourly worker, the pre-match runs): at most one check every
     * [Weather.BACKGROUND_MAX_AGE_MS] (owner, 2026-11-18). The 20:00 check always runs — it is the
     * alert's own hour — and every background check reads AEMET at most every 6 hours too.
     */
    suspend fun check(app: JdcmApp, reason: String, throttled: Boolean = false, deadlineMs: Long = Long.MAX_VALUE) {
        // One check at a time: two at once (20:00 and a worker) would both alert (review 2026-11-18).
        val locked = withTimeoutOrNull(LOCK_WAIT_MS) { lock.lock(); true } ?: run {
            DiagLog.i(TAG, "check $reason: another check is running, skipped")
            return
        }
        try {
            checkLocked(app, reason, throttled, deadlineMs)
        } finally {
            if (locked) lock.unlock()
        }
    }

    private val lock = Mutex()
    private const val LOCK_WAIT_MS = 15_000L

    private suspend fun checkLocked(app: JdcmApp, reason: String, throttled: Boolean, deadlineMs: Long) {
        val c = app.container
        val nowMs = System.currentTimeMillis()
        if (throttled && nowMs - c.settings.lastRainCheckMs in 0 until Weather.BACKGROUND_MAX_AGE_MS) return
        val now = LocalDateTime.now(MADRID)
        val watched = watched(app, now)
        c.settings.lastRainCheckMs = nowMs
        if (watched.isEmpty()) return
        c.weather.refreshFor(watched.map { it.second }, reason, if (reason == "switched on") Weather.MAX_AGE_MS else Weather.BACKGROUND_MAX_AGE_MS, deadlineMs)
        if (!c.settings.state.value.rainAlerts) return
        if (!c.notifier.canPost()) {
            DiagLog.i(TAG, "check $reason: alerts on but notifications not allowed")
            return
        }
        val forecasts = c.weather.forecasts.value
        val alerted = c.settings.rainAlerted.toMutableMap()
        var posted = 0
        for ((kid, m) in watched.distinctBy { it.second.id to it.first.id }) {
            val code = Weather.municipalityFor(m, now) ?: continue
            val w = Weather.forMatch(m, forecasts[code], now) as? MatchWeather.Chance ?: continue
            val start = Weather.alertStart(m) ?: continue
            val last = alerted[m.id]?.let { Instant.ofEpochMilli(it).atZone(MADRID).toLocalDateTime() }
            if (!RainAlertPolicy.due(w.rain, start, now, last)) {
                if (w.alerting) DiagLog.i(TAG, "alert held match=#${m.id} rain=${w.rain} hour=${now.hour} lastAgoMin=${last?.let { java.time.Duration.between(it, now).toMinutes() }} reason=$reason")
                continue
            }
            c.notifier.rain(kid, m, w)
            // Stamped with the check's own time, the one the policy compares with tomorrow.
            alerted[m.id] = nowMs
            posted++
            DiagLog.i(TAG, "alert match=#${m.id} rain=${w.rain} stretch=${w.fromHour}-${w.toHour} code=$code reason=$reason")
        }
        if (posted > 0) c.settings.rainAlerted = alerted
    }

    /** The next 20:00, while alerts are on and there is a match to watch; otherwise none. */
    suspend fun arm(app: JdcmApp) {
        val c = app.container
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pending(app)
        val now = LocalDateTime.now(MADRID)
        // Off with Android's notifications off too: nothing to say, so no waking the phone for it.
        if (!c.settings.state.value.rainAlerts || !c.notifier.canPost() || watched(app, now).isEmpty()) {
            am.cancel(pi)
            if (c.settings.rainCheckAtMs != 0L) DiagLog.i(TAG, "evening check off")
            c.settings.rainCheckAtMs = 0
            return
        }
        val at = RainAlertPolicy.nextEvening(now)
        val atMs = ZonedDateTime.of(at, MADRID).toInstant().toEpochMilli()
        // Below Android 12 an inexact alarm may come up to 3/4 of the wait late — past 23:00, and the
        // night rule would drop it: exact there (no permission needed below 12).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        if (c.settings.rainCheckAtMs != atMs) DiagLog.i(TAG, "evening check armed for $at")
        c.settings.rainCheckAtMs = atMs
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, RainReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * 20:00: read the forecasts, alert what is due, arm tomorrow's. A broadcast has about a minute
 * (goAsync), so the reads stop starting after [BUDGET_MS] and each has a 10 s ceiling ([WeatherRepo]):
 * an ANR here would kill the process before tomorrow's alarm is armed (review 2026-11-18).
 */
class RainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.i("Rain", "evening check fired")
        val app = context.applicationContext as JdcmApp
        val done = goAsync()
        val started = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RainWatch.check(app, "evening", deadlineMs = started + BUDGET_MS)
                DiagLog.i("Rain", "evening check done in ${System.currentTimeMillis() - started}ms")
            } catch (e: Throwable) { // Errors too: a receiver that throws takes the process down
                DiagLog.e("Rain", "evening check failed", e)
            } finally {
                runCatching { RainWatch.arm(app) }.onFailure { DiagLog.e("Rain", "arm failed", it) }
                done.finish()
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 30_000L
    }
}
