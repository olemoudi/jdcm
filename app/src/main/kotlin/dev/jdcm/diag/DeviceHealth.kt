package dev.jdcm.diag

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Everything Android can do to stop this app working in the background, as it stands right now. Most
 * "it did not happen" reports are explained here before any code is read. Union of rwilco `PhoneState` /
 * `DiagCollector` and znhr `Health`. Written for minSdk 28+; every probe is wrapped: one failing reads "?".
 * Drop the lines the app does not depend on (no alarms → no exactAlarms; no location → no location line).
 */
data class DeviceHealth(val lines: List<String>) {
    companion object {
        /** Blocking parts are bounded (≤ 2 s each): call off the main thread. */
        fun read(ctx: Context, uniqueWorkNames: List<String> = emptyList()): DeviceHealth {
            fun yn(b: Boolean?) = when (b) { true -> "y"; false -> "n"; null -> "?" }
            fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            val pm = ctx.getSystemService(PowerManager::class.java)
            val am = ctx.getSystemService(ActivityManager::class.java)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val alarms = ctx.getSystemService(AlarmManager::class.java)
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lm = ctx.getSystemService(LocationManager::class.java)

            val notifications = runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrNull()
            // A channel muted by hand is invisible to areNotificationsEnabled(): list them.
            val channels = runCatching {
                nm.notificationChannels.joinToString(",") { "${it.id}:${importance(it.importance)}" }
            }.getOrDefault("?").ifEmpty { "-" }
            val exact = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
            else runCatching { alarms.canScheduleExactAlarms() }.getOrNull()
            val fsi = if (Build.VERSION.SDK_INT < 34) true else runCatching { nm.canUseFullScreenIntent() }.getOrNull()
            val batteryExempt = runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrNull()
            val restricted = runCatching { am.isBackgroundRestricted }.getOrNull()
            val bucket = runCatching {
                bucketName(ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            }.getOrDefault("?")
            val hibernation = runCatching {
                when (PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).get(2, TimeUnit.SECONDS)) {
                    UnusedAppRestrictionsConstants.API_30_BACKPORT, UnusedAppRestrictionsConstants.API_30,
                    UnusedAppRestrictionsConstants.API_31 -> true
                    UnusedAppRestrictionsConstants.DISABLED -> false
                    else -> null
                }
            }.getOrNull()
            val dataSaver = runCatching {
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            }.getOrNull()
            val powerSave = runCatching { pm.isPowerSaveMode }.getOrNull()
            val idle = runCatching { pm.isDeviceIdleMode }.getOrNull()
            val grant = when {
                has(Manifest.permission.ACCESS_FINE_LOCATION) -> "fine"
                has(Manifest.permission.ACCESS_COARSE_LOCATION) -> "coarse"
                else -> "none"
            } + if (Build.VERSION.SDK_INT >= 29 && has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "+background" else ""
            val locOn = runCatching { lm?.isLocationEnabled }.getOrNull()
            val net = runCatching {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (caps == null) "none" else buildString {
                    append(
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                            else -> "other"
                        },
                    )
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append("+vpn")
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) append(" UNVALIDATED")
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) append(" metered")
                }
            }.getOrDefault("?")
            val battery = runCatching {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (bm.isCharging) " charging" else ""}"
            }.getOrDefault("?")
            val freeMb = runCatching { ctx.filesDir.usableSpace / (1024 * 1024) }.getOrDefault(-1L)
            val works = uniqueWorkNames.map { name ->
                name + "=" + runCatching {
                    WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get(2, TimeUnit.SECONDS)
                        .firstOrNull { !it.state.isFinished }
                        ?.let { "${it.state} attempts=${it.runAttemptCount}" }
                        ?: "NOT SCHEDULED"
                }.getOrDefault("?")
            }
            return DeviceHealth(
                listOf(
                    "notifications=${yn(notifications)} channels=$channels fullScreenIntent=${yn(fsi)} exactAlarms=${yn(exact)}",
                    "battery=${when (batteryExempt) { true -> "unrestricted"; false -> "optimised"; null -> "?" }} " +
                        "backgroundRestricted=${yn(restricted)} bucket=$bucket hibernation=${yn(hibernation)} " +
                        "powerSave=${yn(powerSave)} dozing=${yn(idle)}",
                    "location=$grant/services=${when (locOn) { true -> "on"; false -> "OFF"; null -> "?" }} " +
                        "network=$net dataSaver=${yn(dataSaver)} charge=$battery storageFree=${freeMb}MB",
                ) + if (works.isEmpty()) emptyList() else listOf("work " + works.joinToString(" ")),
            )
        }

        fun importance(i: Int) = when (i) {
            NotificationManager.IMPORTANCE_NONE -> "OFF"
            NotificationManager.IMPORTANCE_MIN -> "min"
            NotificationManager.IMPORTANCE_LOW -> "low"
            NotificationManager.IMPORTANCE_DEFAULT -> "default"
            NotificationManager.IMPORTANCE_HIGH -> "high"
            else -> "$i"
        }

        fun bucketName(b: Int) = when (b) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> if (b in 1 until UsageStatsManager.STANDBY_BUCKET_ACTIVE) "exempt" else "bucket$b"
        }
    }
}
