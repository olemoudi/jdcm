package dev.jdcm.diag

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dev.jdcm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Gathers the snapshot in one pass off the main thread. [sections] is the app's own state, privacy-safe:
 * settings that change behaviour, each entity's decision timestamps (ids as 8-char prefixes), last/next runs,
 * back-offs, errors. Requires BuildConfig.GIT_SHA / GIT_DATE (see the skill's build snippet).
 */
suspend fun collectDiagnostics(
    app: Application,
    uniqueWorkNames: List<String> = emptyList(),
    sections: suspend () -> List<DiagSection> = { emptyList() },
): DiagSnapshot = withContext(Dispatchers.IO) {
    val pm = app.packageManager
    val pi = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(app.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(app.packageName)
    }.getOrNull()
    DiagSnapshot(
        app = AppInfo(
            name = app.applicationInfo.loadLabel(pm).toString(),
            applicationId = app.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            gitSha = BuildConfig.GIT_SHA,
            gitDate = BuildConfig.GIT_DATE,
            installedAt = pi?.firstInstallTime?.let(Instant::ofEpochMilli),
            updatedAt = pi?.lastUpdateTime?.let(Instant::ofEpochMilli),
            installer = installer,
        ),
        device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Locale.getDefault().toLanguageTag()),
        now = Instant.now(),
        zone = ZoneId.systemDefault(),
        processUptime = runCatching {
            Duration.ofMillis(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        }.getOrNull(),
        health = runCatching { DeviceHealth.read(app, uniqueWorkNames) }.getOrNull(),
        sections = runCatching { sections() }.getOrElse {
            listOf(DiagSection("App state", listOf("unavailable: ${it.javaClass.simpleName}")))
        },
        log = DiagLog.recent(REPORT_BUDGET_CHARS),
        logBytesOnDisk = DiagLog.sizeBytes(),
    )
}
