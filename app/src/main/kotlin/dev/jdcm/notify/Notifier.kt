package dev.jdcm.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jdcm.R
import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.Kid
import dev.jdcm.data.Settings
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.MainActivity
import dev.jdcm.ui.format.Format

/**
 * Posts what a parent wants to know without opening the app. Each kind has its own channel, so
 * the phone's own settings can silence results but keep time changes. Every post or suppression
 * writes one line to the diagnostic log (never the names).
 */
class Notifier(private val context: Context, private val settings: Settings) {
    private val fmt get() = Format(context)

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_CHANGES, context.getString(R.string.channel_changes), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_changes_desc) },
                NotificationChannel(CH_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_results_desc) },
                NotificationChannel(CH_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_reminders_desc) },
            ),
        )
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun changes(list: List<Pair<Kid, MatchChange>>) {
        val s = settings.state.value
        for ((kid, c) in list) {
            val wanted = if (c is MatchChange.ResultIn) s.notifyResults else s.notifyChanges
            if (!wanted) {
                DiagLog.i(TAG, "suppressed ${c.javaClass.simpleName}@${c.match.id}: off in settings")
                continue
            }
            val (title, text) = describe(kid, c)
            post(
                channel = if (c is MatchChange.ResultIn) CH_RESULTS else CH_CHANGES,
                id = notificationId(c.match.id, if (c is MatchChange.ResultIn) 1 else 2),
                title = title,
                text = text,
                kid = kid,
                match = c.match,
                kind = c.javaClass.simpleName,
            )
        }
    }

    fun reminder(kid: Kid, m: Match) {
        val opponent = m.opponentOf(kid.teamId)?.name.orEmpty()
        val time = if (m.timeKnown) fmt.time(m) else context.getString(R.string.time_tbc)
        val text = context.getString(R.string.notif_reminder_text, time, opponent, fmt.venueLine(m.venue))
        post(CH_REMINDERS, notificationId(m.id, 3), context.getString(R.string.notif_reminder_title, kid.name), text, kid, m, "Reminder")
    }

    private fun describe(kid: Kid, c: MatchChange): Pair<String, String> {
        val m = c.match
        val opponent = m.opponentOf(kid.teamId)?.name.orEmpty()
        val base = context.getString(R.string.notif_match_text, fmt.whenLine(m), opponent)
        return when (c) {
            is MatchChange.ResultIn -> {
                val home = m.home?.id == kid.teamId
                val mine = if (home) m.homeScore else m.awayScore
                val theirs = if (home) m.awayScore else m.homeScore
                val title = when (Standings.outcome(m, kid.teamId)) {
                    Outcome.WIN -> context.getString(R.string.notif_result_win, kid.name, mine, theirs)
                    Outcome.LOSS -> context.getString(R.string.notif_result_loss, kid.name, mine, theirs)
                    Outcome.DRAW -> context.getString(R.string.notif_result_draw, kid.name, mine, theirs)
                }
                title to context.getString(R.string.notif_result_text, m.home?.name.orEmpty(), m.homeScore, m.awayScore, m.away?.name.orEmpty())
            }
            is MatchChange.Rescheduled -> {
                val firstTime = !c.before.timeKnown && m.timeKnown && c.before.day == m.day
                if (firstTime) context.getString(R.string.notif_time_set, kid.name) to base
                else context.getString(R.string.notif_rescheduled, kid.name) to
                    base + "\n" + context.getString(R.string.notif_before, fmt.whenLine(c.before))
            }
            is MatchChange.Relocated -> context.getString(R.string.notif_relocated, kid.name) to
                (base + "\n" + fmt.venueLine(m.venue))
            is MatchChange.StatusChanged -> when (m.status) {
                MatchStatus.POSTPONED -> context.getString(R.string.notif_postponed, kid.name)
                MatchStatus.UNSCHEDULED -> context.getString(R.string.notif_unscheduled, kid.name)
                MatchStatus.NOT_HELD -> context.getString(R.string.notif_not_held, kid.name)
                else -> context.getString(R.string.notif_back_on, kid.name)
            } to base
        }
    }

    private fun post(channel: String, id: Int, title: String, text: String, kid: Kid, match: Match, kind: String) {
        if (!canPost()) {
            DiagLog.i(TAG, "suppressed $kind@${match.id}: notifications not allowed")
            return
        }
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_GROUP, match.groupId)
                .putExtra(MainActivity.EXTRA_MATCH, match.id)
                .putExtra(MainActivity.EXTRA_KID, kid.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_jdcm)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(KID_COLORS[kid.color.mod(KID_COLORS.size)])
            .setCategory(if (channel == CH_REMINDERS) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
        val v = match.venue
        if (v != null && v.hasPosition && match.status != MatchStatus.UNSCHEDULED && !match.hasResult) {
            val maps = PendingIntent.getActivity(
                context, id + 1,
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${v.lat},${v.lng}?q=${v.lat},${v.lng}(${Uri.encode(v.name)})")),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, context.getString(R.string.action_directions), maps)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
            DiagLog.i(TAG, "posted $kind@${match.id} channel=$channel")
        } catch (e: SecurityException) {
            DiagLog.w(TAG, "post refused $kind@${match.id}: ${e.javaClass.simpleName}")
        }
    }

    private fun notificationId(matchId: Int, kind: Int) = 10_000 + (matchId % 200_000) * 4 + kind

    companion object {
        private const val TAG = "Notify"
        const val CH_CHANGES = "changes"
        const val CH_RESULTS = "results"
        const val CH_REMINDERS = "reminders"

        /** Kid colours as ARGB, shared with the UI's palette. */
        val KID_COLORS = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFF06292.toInt(), 0xFF81C784.toInt(),
            0xFFFFD54F.toInt(), 0xFFBA68C8.toInt(), 0xFFFF8A65.toInt(),
        )
    }
}
