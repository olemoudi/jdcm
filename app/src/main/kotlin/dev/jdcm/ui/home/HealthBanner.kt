package dev.jdcm.ui.home

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.policy.BackgroundHealth
import dev.jdcm.core.policy.HealthFacts
import dev.jdcm.core.policy.HealthIssue
import dev.jdcm.diag.DiagLog
import dev.jdcm.notify.Notifier
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.onboarding.Perms
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.work.PreMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How long «Más tarde» hides one issue: the same as the install banner's. */
const val HEALTH_BANNER_SNOOZE_MS = 3L * 24 * 3600 * 1000

/**
 * What Android is doing to the app's notices, read now. Every probe is wrapped: one that fails
 * reads as "fine" (the banner must never cry wolf). Bounded binder calls: call off the main thread.
 */
fun readHealthFacts(c: AppContainer, hasKids: Boolean, nowMs: Long = System.currentTimeMillis()): HealthFacts {
    val ctx = c.app
    val s = c.settings.state.value
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val am = ctx.getSystemService(ActivityManager::class.java)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val restricted = runCatching { am.isBackgroundRestricted }.getOrDefault(false) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
        }.getOrDefault(false))
    val changesOff = runCatching { nm.getNotificationChannel(Notifier.CH_CHANGES)?.importance == NotificationManager.IMPORTANCE_NONE }.getOrDefault(false)
    // Data saver lets Wi-Fi through: it is the cause only on a metered network.
    val dataSaver = runCatching { cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && cm.isActiveNetworkMetered }.getOrDefault(false)
    val online = runCatching {
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
    val bootMs = nowMs - SystemClock.elapsedRealtime()
    val autostart = dev.jdcm.oem.OemPower.xiaomiAutostart(ctx)
    @Suppress("DEPRECATION")
    val updatedMs = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    return HealthFacts(
        notificationsAllowed = Perms.notifications(ctx),
        wantsNotifications = s.notifyChanges || s.notifyResults || s.notifyReminder || s.rainAlerts,
        changesChannelOff = changesOff,
        backgroundRestricted = restricted,
        batteryUnrestricted = Perms.batteryUnrestricted(ctx),
        dataSaverBlocks = dataSaver,
        online = online,
        exactAlarmsAllowed = PreMatch.canExact(ctx.getSystemService(AlarmManager::class.java)),
        wantsChanges = s.notifyChanges,
        hasKids = hasKids,
        autostartOff = autostart == false,
        oemTip = !c.settings.oemTipDone && when (dev.jdcm.oem.OemPower.oem) {
            dev.jdcm.oem.Oem.OTHER -> false
            // Read on a Xiaomi: AUTOSTART_OFF speaks when it is off; the tip only when it cannot be read.
            dev.jdcm.oem.Oem.XIAOMI -> autostart == null
            // Samsung's sleeping apps do not touch an unrestricted battery.
            dev.jdcm.oem.Oem.SAMSUNG -> !Perms.batteryUnrestricted(ctx)
            else -> true
        },
        // lastBackgroundSyncMs: what builds before 0.2.8 stamped (after a sync with children).
        lastBackgroundRunMs = maxOf(c.settings.workerLastRunMs, c.settings.lastBackgroundSyncMs),
        sinceMs = maxOf(maxOf(c.settings.workerScheduledAtMs, bootMs), maxOf(updatedMs, c.settings.healthLateFixAtMs)),
    )
}

/** One line for the log and the diagnostic report: the inputs behind the banner, no names. */
fun describeHealth(f: HealthFacts, nowMs: Long = System.currentTimeMillis()): String {
    fun ago(ms: Long) = if (ms <= 0) "-" else "${(nowMs - ms) / 60_000}min"
    return "notif=${f.notificationsAllowed} wants=${f.wantsNotifications} changesChannelOff=${f.changesChannelOff} " +
        "restricted=${f.backgroundRestricted} battery=${if (f.batteryUnrestricted) "unrestricted" else "optimised"} " +
        "dataSaver=${f.dataSaverBlocks} online=${f.online} exact=${f.exactAlarmsAllowed} kids=${f.hasKids} " +
        "autostartOff=${f.autostartOff} oemTip=${f.oemTip} lastRun=${ago(f.lastBackgroundRunMs)} since=${ago(f.sinceMs)}"
}

/**
 * The phone's state for Home's health banner, read off the main thread when Home is first shown and
 * every time it comes back (the fix is in a system screen). Held by Home, outside its list: a lazy
 * item forgets its state when scrolled away and would read again on every scroll back.
 */
@Composable
fun rememberHealthFacts(c: AppContainer, hasKids: Boolean): HealthFacts? {
    var facts by remember { mutableStateOf<HealthFacts?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        // Added to a resumed screen, the observer is sent ON_RESUME at once: that is the first read.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumes, hasKids) {
        if (resumes == 0) return@LaunchedEffect
        facts = withContext(Dispatchers.IO) {
            runCatching { readHealthFacts(c, hasKids) }.onFailure { DiagLog.w("UI", "health facts unreadable", it) }.getOrNull()
        }
    }
    return facts
}

/**
 * Home's warning when the phone stops the app's notices from arriving: notifications off, the
 * battery «Restricted», the changes channel muted, the hourly check held back for hours (by the
 * battery optimisation, data saver or the maker's power manager), or exact alarms taken away. The
 * most serious one only, with the one tap that fixes it; «Más tarde» hides that issue for three
 * days. [facts] come from [rememberHealthFacts], so it goes away as soon as it is fixed.
 */
@Composable
fun HealthBanner(c: AppContainer, facts: HealthFacts?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    var snoozed by remember { mutableStateOf(c.settings.healthSnoozed) }
    val now = System.currentTimeMillis()
    val issue = facts?.let { f -> BackgroundHealth.issues(f, now).firstOrNull { (snoozed[it.name] ?: 0L) <= now } }
    LaunchedEffect(issue) {
        val f = facts
        if (issue != null && f != null) DiagLog.i("UI", "health banner: $issue ${describeHealth(f)}")
    }
    // What the banner says while it slides away (the issue is already gone by then).
    var shown by remember { mutableStateOf<HealthIssue?>(null) }
    if (issue != null) shown = issue

    AnimatedVisibility(issue != null) {
        val i = shown ?: return@AnimatedVisibility
        val lastRun = facts?.lastBackgroundRunMs ?: 0L
        val late = if (lastRun > 0) stringResource(R.string.health_late_text, fmt.age(lastRun)) else stringResource(R.string.health_late_never)
        val (title, text) = when (i) {
            HealthIssue.NOTIFICATIONS_OFF -> stringResource(R.string.health_notif_title) to stringResource(R.string.health_notif_text)
            HealthIssue.BACKGROUND_RESTRICTED -> stringResource(R.string.health_restricted_title) to stringResource(R.string.health_restricted_text)
            HealthIssue.AUTOSTART_OFF -> stringResource(R.string.health_autostart_title) to stringResource(R.string.health_autostart_text)
            HealthIssue.OEM_TIP -> stringResource(R.string.ob_oem_title, dev.jdcm.oem.OemPower.oem.brand) to
                (dev.jdcm.ui.onboarding.oemText(dev.jdcm.oem.OemPower.oem) ?: "")
            HealthIssue.CHANGES_CHANNEL_OFF -> stringResource(R.string.health_channel_title) to stringResource(R.string.health_channel_text)
            HealthIssue.LATE_BATTERY -> stringResource(R.string.health_late_title) to "$late ${stringResource(R.string.health_late_battery)}"
            HealthIssue.LATE_DATA_SAVER -> stringResource(R.string.health_late_title) to "$late ${stringResource(R.string.health_late_data)}"
            HealthIssue.LATE_OTHER -> stringResource(R.string.health_late_title) to "$late ${stringResource(R.string.health_late_other)}"
            HealthIssue.EXACT_ALARMS_OFF -> stringResource(R.string.health_exact_title) to stringResource(R.string.health_exact_text)
        }
        val fix = stringResource(
            when (i) {
                HealthIssue.NOTIFICATIONS_OFF, HealthIssue.CHANGES_CHANNEL_OFF -> R.string.health_fix_turn_on
                HealthIssue.LATE_BATTERY, HealthIssue.LATE_DATA_SAVER, HealthIssue.EXACT_ALARMS_OFF -> R.string.health_fix_allow
                HealthIssue.BACKGROUND_RESTRICTED, HealthIssue.LATE_OTHER, HealthIssue.AUTOSTART_OFF, HealthIssue.OEM_TIP -> R.string.health_fix_open
            },
        )
        val icon = when (i) {
            HealthIssue.NOTIFICATIONS_OFF, HealthIssue.CHANGES_CHANNEL_OFF -> Icons.Outlined.NotificationsOff
            HealthIssue.EXACT_ALARMS_OFF -> Icons.Outlined.Timer
            else -> Icons.Outlined.BatteryAlert
        }
        val extra = JdcmColors.extra
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .background(extra.modified.copy(alpha = 0.13f))
                .border(1.dp, extra.modified.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = extra.modified)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (i == HealthIssue.OEM_TIP) {
                    TextButton(onClick = {
                        c.settings.oemTipDone = true
                        val next = snoozed + (i.name to Long.MAX_VALUE)
                        snoozed = next
                        DiagLog.i("UI", "health banner: oem tip done")
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.oem_tip_done)) }
                } else TextButton(onClick = {
                    val next = snoozed + (i.name to System.currentTimeMillis() + HEALTH_BANNER_SNOOZE_MS)
                    snoozed = next
                    c.settings.healthSnoozed = next
                    DiagLog.i("UI", "health banner snoozed 3 d: $i")
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.banner_later)) }
                TextButton(onClick = {
                    DiagLog.i("UI", "health banner fix: $i")
                    if (i == HealthIssue.LATE_BATTERY || i == HealthIssue.LATE_DATA_SAVER || i == HealthIssue.LATE_OTHER) {
                        c.settings.healthLateFixAtMs = System.currentTimeMillis()
                    }
                    when (i) {
                        HealthIssue.NOTIFICATIONS_OFF -> Perms.openNotificationSettings(ctx)
                        HealthIssue.CHANGES_CHANNEL_OFF -> Perms.openChannelSettings(ctx, Notifier.CH_CHANGES)
                        HealthIssue.BACKGROUND_RESTRICTED -> Perms.openAppDetails(ctx)
                        // The maker's own switches first (Xiaomi, Huawei, OPPO, vivo…), else App info.
                        HealthIssue.AUTOSTART_OFF, HealthIssue.LATE_OTHER, HealthIssue.OEM_TIP ->
                            dev.jdcm.oem.OemPower.open(ctx, i.name, dev.jdcm.oem.OemPageKind.AUTOSTART, dev.jdcm.oem.OemPageKind.BATTERY)
                        HealthIssue.LATE_BATTERY -> Perms.askBatteryUnrestricted(ctx)
                        HealthIssue.LATE_DATA_SAVER -> Perms.openDataSaverSettings(ctx)
                        HealthIssue.EXACT_ALARMS_OFF -> Perms.openExactAlarmSettings(ctx)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(fix, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
