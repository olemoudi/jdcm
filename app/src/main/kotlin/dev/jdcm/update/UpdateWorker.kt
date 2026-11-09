package dev.jdcm.update

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.jdcm.diag.DiagLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks every [PERIOD_HOURS] h (hourly was znhr's most frequent wake, for releases that come a few times a week),
 * once when the app comes back if the last check is older than [FOCUS_GUARD_MS] (persisted: a fresh process must not
 * re-check), and on "Check now". One-off checks are UNIQUE work: walcott queued 300 of them after a week offline.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        DiagLog.i("Update", "check (attempt ${runAttemptCount + 1}${if (manual) ", manual" else ""})")
        return when (Updater(applicationContext).checkAndUpdate(manual)) {
            UpdateCheckOutcome.TRANSIENT_FAILURE -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "update-periodic"
        private const val ON_START = "update-on-start"
        private const val MANUAL = "update-manual"
        private const val KEY_MANUAL = "manual"
        const val PERIOD_HOURS = 6L
        const val FOCUS_GUARD_MS = 15 * 60 * 1000L
        private val last = AtomicLong(0)
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun wm(ctx: Context) = runCatching { WorkManager.getInstance(ctx) }.getOrNull()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm(ctx)?.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** App came to the foreground. [lastCheckMs]: when the last check started (persisted). */
        fun runIfStale(ctx: Context, lastCheckMs: Long) {
            if (lastCheckMs > 0 && System.currentTimeMillis() - lastCheckMs in 0 until FOCUS_GUARD_MS) return
            val now = SystemClock.elapsedRealtime()
            val prev = last.get()
            if (prev != 0L && now - prev < FOCUS_GUARD_MS) return
            if (!last.compareAndSet(prev, now)) return
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm(ctx)?.enqueueUniqueWork(ON_START, ExistingWorkPolicy.KEEP, req)
        }

        /** "Check now", and right after the user picks a channel: no constraints, replaces a queued one. */
        fun checkNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            wm(ctx)?.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
