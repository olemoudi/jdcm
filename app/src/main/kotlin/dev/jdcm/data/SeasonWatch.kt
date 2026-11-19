package dev.jdcm.data

import dev.jdcm.AppContainer
import dev.jdcm.core.model.MADRID
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime

/**
 * Which season the site is on, so a new one (July) needs no release: the explorer and the team
 * search list its competitions, and Home offers to pick each child's new team once their old
 * season is over. One HTML page a week at most, in the daytime; a failure is tried again a day
 * later; a season older than the build's is never taken.
 */
object SeasonWatch {
    private const val TAG = "Season"
    const val EVERY_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_MS = 24L * 3600 * 1000
    private val lock = Mutex()

    suspend fun check(c: AppContainer, reason: String, nowMs: Long = System.currentTimeMillis()) = lock.withLock {
        val s = c.settings
        val since = nowMs - s.seasonCheckedMs
        if (since in 0 until EVERY_MS) return@withLock
        val hour = LocalTime.now(MADRID).hour
        if (hour < 8 || hour >= 22) {
            DiagLog.i(TAG, "check skipped ($reason): night, hour=$hour")
            return@withLock
        }
        try {
            val found = c.api.season()
            when {
                found == null -> {
                    DiagLog.w(TAG, "check ($reason): no season picker on the calendar page; kept #${s.activeSeasonId}, retry in a day")
                    s.seasonCheckedMs = nowMs - EVERY_MS + RETRY_MS
                    return@withLock
                }
                found.id < EcmApi.SEASON_ID -> DiagLog.w(TAG, "check ($reason): the site says #${found.id}, older than the build's #${EcmApi.SEASON_ID}; ignored")
                found.id != s.activeSeasonId || found.name != s.activeSeasonName -> {
                    DiagLog.i(TAG, "check ($reason): season #${s.activeSeasonId} -> #${found.id}")
                    s.activeSeasonId = found.id
                    s.activeSeasonName = found.name
                }
                else -> DiagLog.i(TAG, "check ($reason): still #${found.id}")
            }
            s.seasonCheckedMs = nowMs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(TAG, "check ($reason) failed; retry in a day", e)
            s.seasonCheckedMs = nowMs - EVERY_MS + RETRY_MS
        }
    }
}
