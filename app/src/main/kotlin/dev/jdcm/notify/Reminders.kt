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
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.data.Kid
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * The eve-of-match reminder: «Mañana juega Lucía a las 10:30 en …» at the chosen hour the day
 * before. One inexact alarm for the earliest reminder due, re-armed after every sync, on boot,
 * and when it fires. An alarm (not a delayed job): WorkManager can be deferred hours in Doze, and
 * a reminder that arrives after the match is worse than none.
 */
object Reminders {
    private const val TAG = "Reminder"
    private const val REQUEST = 4401

    /** The next moment a reminder is due for any of [kids], with the matches it covers. */
    fun nextDue(
        kids: List<Kid>,
        snapshots: Map<Int, GroupSnapshot>,
        hour: Int,
        now: LocalDateTime,
        reminded: Set<String>,
    ): Pair<LocalDateTime, List<Pair<Kid, Match>>>? {
        val candidates = kids.mapNotNull { kid ->
            val s = snapshots[kid.groupId] ?: return@mapNotNull null
            val m = Schedule.upcoming(s, kid.teamId, now)
                .firstOrNull { it.status != MatchStatus.UNSCHEDULED && it.status != MatchStatus.POSTPONED && it.day != null }
                ?: return@mapNotNull null
            if (key(kid, m) in reminded) return@mapNotNull null
            val at = m.day!!.minusDays(1).atTime(hour, 0)
            // Too late for the eve (the time was set tonight, or the app was just installed): no reminder.
            if (!at.isAfter(now.minusMinutes(90))) return@mapNotNull null
            if (m.timeKnown && m.start != null && !m.start!!.isAfter(now)) return@mapNotNull null
            Triple(at, kid, m)
        }
        val first = candidates.minByOrNull { it.first }?.first ?: return null
        return first to candidates.filter { it.first == first }.map { it.second to it.third }
    }

    fun key(kid: Kid, m: Match) = "${kid.id.take(8)}:${m.id}:${m.day}"

    fun rearm(context: Context) {
        val app = context.applicationContext as JdcmApp
        CoroutineScope(Dispatchers.IO).launch { runCatching { arm(app) }.onFailure { DiagLog.e(TAG, "rearm failed", it) } }
    }

    suspend fun arm(app: JdcmApp) {
        val c = app.container
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pending(app)
        val s = c.settings.state.value
        if (!s.notifyReminder) {
            am.cancel(pi)
            return
        }
        val kids = c.kids.current()
        kids.forEach { c.repository.load(it.groupId) }
        val now = LocalDateTime.now(MADRID)
        val due = nextDue(kids, c.repository.snapshots.value, s.reminderHour, now, c.settings.remindedMatchIds)
        if (due == null) {
            am.cancel(pi)
            DiagLog.i(TAG, "none due")
            return
        }
        val atMs = ZonedDateTime.of(due.first, MADRID).toInstant().toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 5_000)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        DiagLog.i(TAG, "armed for ${due.first} matches=${due.second.map { it.second.id }}")
    }

    /** The alarm went off: post what is due (within the next ~36 h), remember it, arm the next. */
    suspend fun fire(app: JdcmApp) {
        val c = app.container
        val s = c.settings.state.value
        val now = LocalDateTime.now(MADRID)
        val kids = c.kids.current()
        kids.forEach { c.repository.load(it.groupId) }
        val reminded = c.settings.remindedMatchIds.toMutableSet()
        val tomorrow: LocalDate = now.toLocalDate().plusDays(1)
        if (s.notifyReminder) {
            for (kid in kids) {
                val snap = c.repository.snapshots.value[kid.groupId] ?: continue
                val m = Schedule.upcoming(snap, kid.teamId, now)
                    .firstOrNull { it.status != MatchStatus.UNSCHEDULED && it.status != MatchStatus.POSTPONED && it.day != null }
                    ?: continue
                // Due: the match is tomorrow, or later today when the alarm was late past midnight.
                if (m.day != tomorrow && m.day != now.toLocalDate()) continue
                if (key(kid, m) in reminded) continue
                c.notifier.reminder(kid, m)
                reminded += key(kid, m)
            }
            c.settings.remindedMatchIds = reminded
        }
        arm(app)
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.i("Reminder", "alarm fired")
        val app = context.applicationContext as JdcmApp
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.fire(app)
            } catch (e: Exception) {
                DiagLog.e("Reminder", "fire failed", e)
            } finally {
                done.finish()
            }
        }
    }
}

/** Alarms do not survive a reboot or an update: re-arm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.i("Reminder", "rearm after ${intent.action?.substringAfterLast('.')}")
        Reminders.rearm(context)
    }
}
