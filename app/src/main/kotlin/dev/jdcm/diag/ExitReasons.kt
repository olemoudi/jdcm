package dev.jdcm.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * New entries of Android's own record of how this app's processes ended: crashes, ANRs, low-memory kills and,
 * above all, FORCE-STOPS (which cancel every alarm and job until the app is opened again). The only way to see
 * process deaths that are not crashes. A watermark file makes each one logged once. (znhr)
 */
internal object ExitReasons {
    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

    fun logNew(ctx: Context, seenFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            .filter { it.timestamp > seen }.sortedBy { it.timestamp }
        for (x in infos) {
            val at = stamp.format(Instant.ofEpochMilli(x.timestamp).atZone(ZoneId.systemDefault()))
            val msg = "process ended $at: ${name(x.reason)} (importance ${x.importance}, pss ${x.pss / 1024} MB)" +
                x.description?.let { " — ${it.take(200)}" }.orEmpty()
            val level = when (x.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> 'E'
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_LOW_MEMORY -> 'W'
                else -> 'I'
            }
            DiagLog.record(level, "Exit", msg, null)
            if (x.reason == ApplicationExitInfo.REASON_ANR) anrMainStack(x)?.let { DiagLog.record('E', "ANR", it, null) }
        }
        infos.maxOfOrNull { it.timestamp }?.let { seenFile.writeText(it.toString()) }
    }

    /** The main thread's stack from an ANR trace (the rest is noise). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrMainStack(x: ApplicationExitInfo): String? = runCatching {
        x.traceInputStream?.bufferedReader()?.use { r ->
            val lines = r.lineSequence().take(4_000).toList()
            val start = lines.indexOfFirst { it.startsWith("\"main\"") }
            if (start < 0) null else lines.drop(start).takeWhile { it.isNotBlank() }.take(40).joinToString("\n").take(5_000)
        }
    }.getOrNull()

    // Constants added in later APIs are plain ints: reading them on an older device is harmless.
    private fun name(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE RESOURCE USE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "FORCE-STOPPED by the user (alarms and jobs are gone until the app is opened)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other (killed by the system)"
        ApplicationExitInfo.REASON_FREEZER -> "frozen"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "updated"
        else -> "reason $r"
    }
}
