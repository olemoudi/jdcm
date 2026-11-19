package dev.jdcm.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What the app needs from Android, read fresh each time (the user changes them in system screens). */
object Perms {
    fun notifications(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** "Install unknown apps" for this app: without it the self-updater can never install anything. */
    fun canInstall(ctx: Context): Boolean = runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun batteryUnrestricted(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /**
     * Android 12+ installs an update without asking (USER_ACTION_NOT_REQUIRED); on 10 and 11 every update waits for
     * one tap on its notification. What the guide promises depends on it.
     */
    val silentUpdates: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * "Pause app activity if unused" (hibernation): three months unopened — a summer without matches — and Android stops
     * the app's checks and updates and, on 13+, removes its alerts permission. True while it applies to this app; null
     * when the phone has no such feature or cannot say. Blocking parts bounded: call from a coroutine.
     */
    suspend fun pausesIfUnused(ctx: Context): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            when (PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).get(2, TimeUnit.SECONDS)) {
                UnusedAppRestrictionsConstants.API_30_BACKPORT, UnusedAppRestrictionsConstants.API_30,
                UnusedAppRestrictionsConstants.API_31 -> true
                UnusedAppRestrictionsConstants.DISABLED -> false
                else -> null
            }
        }.getOrNull()
    }

    /** The system page holding that switch (App info on 12+); launch it for a result (the API 30 backport needs it). */
    fun pauseIfUnusedIntent(ctx: Context): Intent? =
        runCatching { IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName) }
            .onFailure { DiagLog.w("UI", "no page for the pause-if-unused switch", it) }
            .getOrNull()

    fun openInstallSettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")), "install")

    fun openNotificationSettings(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName), "notifications")

    /** The system's own one-tap dialog; sideloaded, so no store policy forbids asking. */
    @SuppressLint("BatteryLife")
    fun askBatteryUnrestricted(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")), "battery")

    /** App info: battery («Restringida»), and the maker's own background switches hang from it. */
    fun openAppDetails(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")), "app details")

    fun openChannelSettings(ctx: Context, channelId: String) = start(
        ctx,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
        "channel",
    )

    /** Data saver's per-app exemption («Datos sin restricciones»). */
    fun openDataSaverSettings(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${ctx.packageName}")), "data saver")

    /** «Alarmas y recordatorios» (Android 12+; only 12/12L can take it away from this app). */
    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return openAppDetails(ctx)
        start(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")), "exact alarms")
    }

    private fun start(ctx: Context, i: Intent, what: String) {
        // From a non-Activity context Android refuses without a new task (0.2.0's first build did).
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            DiagLog.w("UI", "cannot open $what settings: ${e.javaClass.simpleName}")
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/** A permission's state, read again every time the screen comes back (from a system settings page). */
@Composable
fun rememberOnResume(read: (Context) -> Boolean): State<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(read(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) state.value = read(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return state
}
