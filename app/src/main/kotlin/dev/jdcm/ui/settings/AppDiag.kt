package dev.jdcm.ui.settings

import dev.jdcm.AppContainer
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.policy.PreMatchPolicy
import dev.jdcm.core.policy.RefreshPolicy
import dev.jdcm.diag.DiagSection
import dev.jdcm.notify.Reminders
import dev.jdcm.work.PreMatch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private fun at(ms: Long) = if (ms <= 0) "-" else stamp.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The app's own state for the diagnostic report: what every refresh and notification decision
 * reads. No names (children, schools, places): ids as numbers or 8-char prefixes, counts instead
 * of content. The match states the site used are listed by id and name, because the parser had
 * to guess the "played" ones before the season started (verify them here).
 */
suspend fun appDiagSections(c: AppContainer): List<DiagSection> {
    val s = c.settings.state.value
    val kids = c.kids.current()
    kids.forEach { c.repository.load(it.groupId) }
    val snaps = c.repository.snapshots.value
    val status = c.repository.status.value
    val now = LocalDateTime.now(MADRID)
    val nowMs = System.currentTimeMillis()

    val settings = DiagSection(
        "Settings",
        listOf(
            "theme=${s.theme} changes=${s.notifyChanges} results=${s.notifyResults} reminder=${s.notifyReminder}@${s.reminderHour}h showClosed=${s.showClosed}",
            "notifications allowed=${c.notifier.canPost()} lastBackgroundSync=${at(c.settings.lastBackgroundSyncMs)} matchWindow=${RefreshPolicy.isMatchWindow(now)}",
            "seenVersionCode=${c.settings.seenVersionCode} reminded=${c.settings.remindedMatchIds.size} onboardingDone=${c.settings.onboardingDone}",
            "installPermission=${dev.jdcm.ui.onboarding.Perms.canInstall(c.app)}" +
                (if (!dev.jdcm.ui.onboarding.Perms.canInstall(c.app)) " UPDATES CANNOT INSTALL" else "") +
                " bannerSnoozedUntil=${at(c.settings.installBannerSnoozedUntil)}",
        ),
    )
    val kidLines = kids.map { k ->
        "#${k.id.take(8)} sport=${k.sportId} comp=${k.competitionId} cat=${k.categoryId} group=#${k.groupId} team=#${k.teamId} color=${k.color}"
    }.ifEmpty { listOf("none") }

    val groupLines = snaps.values.sortedBy { it.group.id }.map { g ->
        val st = status[g.group.id]
        val results = g.matches.count { it.hasResult }
        val unknown = g.matches.count { it.status == MatchStatus.UNKNOWN }
        val flag = when {
            st?.error != null -> " LAST SYNC FAILED (${if (st.offline) "offline" else st.error?.take(60)})"
            g.missingRounds.isNotEmpty() -> " MISSING ${g.missingRounds.size} ROUNDS"
            else -> ""
        }
        "#${g.group.id} sport=${g.sportId} rounds=${g.rounds.size} matches=${g.matches.size} results=$results unknownState=$unknown " +
            "newest=${at(g.newestFetch)} oldest=${at(g.oldestFetch)} attempt=${at(g.lastAttemptMs)} followed=${kids.any { it.groupId == g.group.id }}$flag"
    }.ifEmpty { listOf("none cached") } + "cache on disk ${c.groups.sizeBytes() / 1024} KB in ${c.groups.ids().size} groups"

    val states = snaps.values.flatMap { it.matches }
        .groupingBy { "${it.statusId}:${it.statusName}:closed=${it.closed}:winner=${it.winnerId != 0}:score=${it.scoreKnown}:result=${it.hasResult}" }.eachCount()
        .entries.sortedByDescending { it.value }.map { "${it.key} ×${it.value}" }

    val due = Reminders.nextDue(kids, snaps, s.reminderHour, now, c.settings.remindedMatchIds)
    val reminder = listOf(
        if (due == null) "next: none" else "next: ${due.first} matches=${due.second.map { it.second.id }}",
    )
    // Pre-match checks: the alarm, the last time it fired, and the matches it watches (ids and times only).
    val watched = PreMatch.watched(kids, snaps, now).sortedBy { it.second.start }
    val preMatch = listOf(
        "on=${s.notifyChanges} exactAlarms=${PreMatch.canExact(c.app.getSystemService(android.app.AlarmManager::class.java))} " +
            "next=${at(c.settings.preMatchAlarmAtMs)} lastFired=${at(c.settings.preMatchLastCheckMs)}",
    ) + watched.take(6).map { (k, m) ->
        "#${k.id.take(8)} match=#${m.id} group=#${m.groupId} start=${m.start} inWindow=${PreMatchPolicy.inWindow(m, now)}"
    }.ifEmpty { listOf("no match with a time ahead") }
    return listOf(
        settings,
        DiagSection("Kids", kidLines),
        DiagSection("Groups (now ${at(nowMs)})", groupLines),
        DiagSection("Match states seen", states.ifEmpty { listOf("none") }),
        DiagSection("Reminder", reminder),
        DiagSection("Pre-match checks", preMatch),
    )
}
