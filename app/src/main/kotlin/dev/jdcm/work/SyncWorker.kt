package dev.jdcm.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.jdcm.JdcmApp
import dev.jdcm.core.model.MADRID
import dev.jdcm.data.SyncMode
import dev.jdcm.diag.DiagLog
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Wakes every hour with a network; [dev.jdcm.core.policy.RefreshPolicy] decides whether the
 * children's groups are due (50 min on match weekends, 5 h on weekdays, nothing at night), so
 * most runs ask the site nothing. Changes it finds become notifications. Also runs once, expedited,
 * for each pre-match check ([PreMatch]): only the groups with a child's match in its last hours.
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
        if (inputData.getBoolean(KEY_PRE_MATCH, false)) return preMatch(app)
        val started = System.currentTimeMillis()
        DiagLog.i(TAG, "run attempt=$runAttemptCount kids=${kids.size}")
        val outcomes = c.repository.syncKids(SyncMode.BACKGROUND, "worker")
        c.settings.lastBackgroundSyncMs = System.currentTimeMillis()
        // The repository re-arms the reminder after any sync that brought data (from here or a screen).
        DiagLog.i(TAG, "end ${outcomes.groupingBy { it }.eachCount()} in ${System.currentTimeMillis() - started}ms")
        return Result.success()
    }

    /** The groups are worked out again here: the run may come later than the alarm that asked for it. */
    private suspend fun preMatch(app: JdcmApp): Result {
        val started = System.currentTimeMillis()
        val groups = PreMatch.groupsInWindow(app, LocalDateTime.now(MADRID))
        if (groups.isEmpty()) {
            DiagLog.i(TAG, "pre-match run: no match in its last hours any more")
            return Result.success()
        }
        DiagLog.i(TAG, "pre-match run attempt=$runAttemptCount groups=${groups.map { "#$it" }} late=${(started - app.container.settings.preMatchLastCheckMs) / 1000}s")
        val outcomes = app.container.repository.syncKidGroups(groups, SyncMode.PRE_MATCH, "pre-match")
        DiagLog.i(TAG, "pre-match end ${outcomes.groupingBy { it }.eachCount()} in ${System.currentTimeMillis() - started}ms")
        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val NAME = "sync"
        private const val PRE_MATCH = "sync-prematch"
        private const val KEY_PRE_MATCH = "preMatch"

        /**
         * One pre-match check, now. Expedited from Android 12 (runs at once, in Doze too, with the
         * network); before that, and when the expedited quota is spent, as soon as Android allows.
         * KEEP: a check still waiting for a network is not queued twice.
         */
        fun runPreMatch(ctx: Context) {
            val b = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_PRE_MATCH to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Below Android 12 an expedited worker must run as a foreground service (getForegroundInfo): not worth it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(ctx).enqueueUniqueWork(PRE_MATCH, ExistingWorkPolicy.KEEP, b.build())
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
