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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdcm.diag.DiagLog

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

    fun openInstallSettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")), "install")

    fun openNotificationSettings(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName), "notifications")

    /** The system's own one-tap dialog; sideloaded, so no store policy forbids asking. */
    @SuppressLint("BatteryLife")
    fun askBatteryUnrestricted(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")), "battery")

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
