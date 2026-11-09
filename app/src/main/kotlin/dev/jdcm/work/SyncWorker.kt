package dev.jdcm.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.jdcm.JdcmApp
import dev.jdcm.data.SyncMode
import dev.jdcm.diag.DiagLog
import java.util.concurrent.TimeUnit

/**
 * Wakes every hour with a network; [dev.jdcm.core.policy.RefreshPolicy] decides whether the
 * children's groups are due (50 min on match weekends, 5 h on weekdays, nothing at night), so
 * most runs ask the site nothing. Changes it finds become notifications.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JdcmApp
        val c = app.container
        val kids = c.kids.current()
        if (kids.isEmpty()) {
            DiagLog.i(TAG, "run: no children followed")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        DiagLog.i(TAG, "run attempt=$runAttemptCount kids=${kids.size}")
        val outcomes = c.repository.syncKids(SyncMode.BACKGROUND, "worker")
        c.settings.lastBackgroundSyncMs = System.currentTimeMillis()
        // The repository re-arms the reminder after any sync that brought data (from here or a screen).
        DiagLog.i(TAG, "end ${outcomes.groupingBy { it }.eachCount()} in ${System.currentTimeMillis() - started}ms")
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val NAME = "sync"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
