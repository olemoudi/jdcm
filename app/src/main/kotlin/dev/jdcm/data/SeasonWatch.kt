package dev.jdcm.data

import dev.jdcm.AppContainer
import dev.jdcm.core.model.MADRID
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import kotlin.math.abs

/**
 * Which season the site is on, so a new one (July) needs no release: the explorer and the team
 * search list its competitions, and Home offers to pick each child's new team once their old
 * season is over. One HTML page a week at most, in the daytime; a failure is tried again a day
 * later; a season older than the build's, far from the held one or without a season's name is never
 * taken, and a change needs two reads a day apart.
 */
object SeasonWatch {
    private const val TAG = "Season"
    const val EVERY_MS = 7L * 24 * 3600 * 1000
    private const val RETRY_MS = 24L * 3600 * 1000
    private val lock = Mutex()

    /** «TEMPORADA 2027-2028», «Temporada 27/28»: a name that looks like a season, or the read is not believed. */
    private val SEASON_NAME = Regex("""\d{2,4}\s*[-/]\s*\d{2,4}""")

    /** A new id this far from the held one is a misread (ids went 10 → 12 between two seasons). */
    private const val MAX_STEP = 5

    suspend fun check(c: AppContainer, reason: String, nowMs: Long = System.currentTimeMillis()) = lock.withLock {
        val s = c.settings
        val since = nowMs - s.seasonCheckedMs
        if (since in 0 until EVERY_MS) return@withLock
        val hour = LocalTime.now(MADRID).hour
        if (hour < 8 || hour >= 22) {
            DiagLog.i(TAG, "check skipped ($reason): night, hour=$hour")
            return@withLock
        }
        // The attempt is stamped before asking (as Repository.sync's gate): whatever happens next — a
        // crash, a cancelled worker — the page is not asked again before a day has passed.
        s.seasonCheckedMs = nowMs - EVERY_MS + RETRY_MS
        try {
            val found = c.api.season()
            val held = s.activeSeasonId
            when {
                found == null -> DiagLog.w(TAG, "check ($reason): no season picker on the calendar page; kept #$held, retry in a day")
                found.id < EcmApi.SEASON_ID || abs(found.id - held) > MAX_STEP || !SEASON_NAME.containsMatchIn(found.name) ->
                    DiagLog.w(TAG, "check ($reason): implausible season #${found.id} (held #$held, build #${EcmApi.SEASON_ID}, name ok=${SEASON_NAME.containsMatchIn(found.name)}); ignored, retry in a day")
                found.id == held -> {
                    if (found.name != s.activeSeasonName) s.activeSeasonName = found.name
                    s.seasonPendingId = 0
                    DiagLog.i(TAG, "check ($reason): still #$held")
                    s.seasonCheckedMs = nowMs
                }
                // A change is believed when two reads a day apart agree: one odd page cannot move the
                // explorer to another season (or back) for a week.
                found.id == s.seasonPendingId -> {
                    DiagLog.i(TAG, "check ($reason): season #$held -> #${found.id} (confirmed)")
                    s.activeSeasonId = found.id
                    s.activeSeasonName = found.name
                    s.seasonPendingId = 0
                    s.seasonCheckedMs = nowMs
                }
                else -> {
                    s.seasonPendingId = found.id
                    DiagLog.i(TAG, "check ($reason): the site says #${found.id} (held #$held); confirming in a day")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too: a pattern Android's regex engine refuses fails a class's init with an Error.
            DiagLog.w(TAG, "check ($reason) failed; retry in a day", e)
        }
    }
}
