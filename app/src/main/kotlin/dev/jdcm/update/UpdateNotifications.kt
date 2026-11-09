package dev.jdcm.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jdcm.R

/** One low-importance, silent channel. Every post is guarded: a notification failure never breaks an update. */
object UpdateNotifications {
    private const val CHANNEL = "app_updates"
    private const val FOUND_ID = 7301
    private const val CONFIRM_ID = 7302
    private const val PROBLEM_ID = 7303
    @Volatile private var announced = 0

    /** The app's monochrome status-bar icon. */
    private val SMALL_ICON = R.drawable.ic_stat_jdcm

    private fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(ctx: Context): PendingIntent? =
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun post(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent?, ongoing: Boolean = false) {
        // Android 13+: without the runtime grant there is nothing to post (the Settings screen still shows the state).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    /** Once per versionCode. */
    fun notifyFound(ctx: Context, versionCode: Int, versionName: String) {
        if (versionCode == announced) return
        announced = versionCode
        post(ctx, FOUND_ID, ctx.getString(R.string.update_found_title, versionName), ctx.getString(R.string.update_found_text), openApp(ctx))
    }

    fun cancelFound(ctx: Context) {
        announced = 0
        runCatching { NotificationManagerCompat.from(ctx).cancel(FOUND_ID) }
    }

    /** Ongoing: a reflex swipe must not lose the only way to finish the install. Tap = Android's own dialog. */
    fun notifyConfirmationNeeded(ctx: Context, confirm: Intent) {
        val tap = PendingIntent.getActivity(ctx, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { NotificationManagerCompat.from(ctx).cancel(FOUND_ID) }
        post(ctx, CONFIRM_ID, ctx.getString(R.string.update_ready_title), ctx.getString(R.string.update_ready_text), tap, ongoing = true)
    }

    /** "Install unknown apps" is off for this app: the tap opens exactly that switch. */
    fun notifyNeedsPermission(ctx: Context) {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(ctx, 1, settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, PROBLEM_ID, ctx.getString(R.string.update_permission_title), ctx.getString(R.string.update_permission_text), tap)
    }

    fun notifyTokenProblem(ctx: Context) =
        post(ctx, PROBLEM_ID, ctx.getString(R.string.update_token_title), ctx.getString(R.string.update_token_text), openApp(ctx))

    fun cancelTokenProblem(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(PROBLEM_ID) }
    }

    /** An install Android refused (not one the user cancelled): why, and what to do. */
    fun notifyInstallFailed(ctx: Context, text: String) =
        post(ctx, FOUND_ID, ctx.getString(R.string.update_install_failed_title), text, openApp(ctx))

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).apply { cancel(FOUND_ID); cancel(CONFIRM_ID) } }
    }
}
