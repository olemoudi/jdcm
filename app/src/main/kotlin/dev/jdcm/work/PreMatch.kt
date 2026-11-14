package dev.jdcm.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.jdcm.JdcmApp
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.policy.PreMatchPolicy
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.data.Kid
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Pre-match checks: from 4 h before a followed child's match until it starts, the child's group is
 * asked about every quarter of an hour, so a last-minute rain-off or time change reaches the family
 * as a notification ([PreMatchPolicy] decides when). One exact allow-while-idle alarm (Doze lets it
 * through and lifts the network ban for a few seconds) starts an expedited one-off [SyncWorker]; the
 * alarm is re-armed when it fires, after every sync, on boot and when the app is shown, like the eve
 * reminder's. Off with the "changes" notifications: without them a check has nobody to tell.
 *
 * Exact, not inexact: measured on the AVD (2026-11-14), an inexact while-idle alarm of an app in the
 * "frequent" standby bucket (not opened for a day) is held to 2 per hour, which turned a 15-minute
 * check into a 50-minute one. USE_EXACT_ALARM (Android 13+, granted at install) and
 * SCHEDULE_EXACT_ALARM (12, granted by default) allow exact ones; without them, inexact.
 */
object PreMatch {
    private const val TAG = "PreMatch"
    private const val REQUEST = 4402

    /** Every followed child's matches that are still to be watched, with the child. */
    fun watched(kids: List<Kid>, snapshots: Map<Int, GroupSnapshot>, now: LocalDateTime): List<Pair<Kid, Match>> =
        kids.flatMap { kid ->
            val s = snapshots[kid.groupId] ?: return@flatMap emptyList()
            Schedule.upcoming(s, kid.teamId, now).filter { PreMatchPolicy.watched(it, now) }.map { kid to it }
        }

    /** The followed groups with a child's match in its last hours now. */
    suspend fun groupsInWindow(app: JdcmApp, now: LocalDateTime): Set<Int> {
        val c = app.container
        val kids = c.kids.current()
        kids.forEach { c.repository.load(it.groupId) }
        return watched(kids, c.repository.snapshots.value, now).filter { PreMatchPolicy.inWindow(it.second, now) }.map { it.first.groupId }.toSet()
    }

    suspend fun arm(app: JdcmApp) {
        val c = app.container
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pending(app)
        if (!c.settings.state.value.notifyChanges) {
            am.cancel(pi)
            if (c.settings.preMatchAlarmAtMs != 0L) DiagLog.i(TAG, "off: change notifications are off")
            c.settings.preMatchAlarmAtMs = 0
            return
        }
        val kids = c.kids.current()
        kids.forEach { c.repository.load(it.groupId) }
        val now = LocalDateTime.now(MADRID)
        val last = c.settings.preMatchLastCheckMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(MADRID).toLocalDateTime() }
        val watched = watched(kids, c.repository.snapshots.value, now)
        val exact = canExact(am)
        val next = PreMatchPolicy.nextCheck(watched.map { it.second }, now, last, if (exact) PreMatchPolicy.EVERY else PreMatchPolicy.EVERY_INEXACT)
        if (next == null) {
            am.cancel(pi)
            if (c.settings.preMatchAlarmAtMs != 0L) DiagLog.i(TAG, "none ahead (watched=${watched.size})")
            c.settings.preMatchAlarmAtMs = 0
            return
        }
        val atMs = ZonedDateTime.of(next, MADRID).toInstant().toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 5_000)
        val armedExact = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
            .onFailure { DiagLog.w(TAG, "exact alarm refused: inexact instead", it) }.isSuccess
        if (!armedExact) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        // Re-armed after every sync: only a real move is worth a line.
        if (abs(c.settings.preMatchAlarmAtMs - atMs) > 60_000) {
            val soonest = watched.minByOrNull { it.second.start!! }?.second
            DiagLog.i(TAG, "armed for $next exact=$armedExact (next match #${soonest?.id} at ${soonest?.start}, watched=${watched.size})")
        }
        c.settings.preMatchAlarmAtMs = atMs
    }

    /** The alarm went off: check the groups whose child plays within the window, and arm the next one. */
    suspend fun fire(app: JdcmApp) {
        val c = app.container
        val now = LocalDateTime.now(MADRID)
        c.settings.preMatchLastCheckMs = System.currentTimeMillis()
        val groups = if (c.settings.state.value.notifyChanges) groupsInWindow(app, now) else emptySet()
        if (groups.isEmpty()) {
            DiagLog.i(TAG, "fired: no match in its last hours")
        } else {
            DiagLog.i(TAG, "fired: checking groups ${groups.map { "#$it" }}")
            SyncWorker.runPreMatch(app)
        }
        arm(app)
    }

    /** Android 12+ asks for a permission (see the manifest); before that exact alarms are free. */
    fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, PreMatchReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class PreMatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JdcmApp
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PreMatch.fire(app)
            } catch (e: Exception) {
                DiagLog.e("PreMatch", "fire failed", e)
            } finally {
                done.finish()
            }
        }
    }
}
