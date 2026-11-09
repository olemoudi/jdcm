package dev.jdcm.data

import dev.jdcm.core.changes.MatchChange
import dev.jdcm.core.changes.MatchChanges
import dev.jdcm.core.model.Category
import dev.jdcm.core.model.Competition
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Team
import dev.jdcm.core.policy.RefreshPolicy
import dev.jdcm.diag.DiagLog
import dev.jdcm.core.parse.UnexpectedResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** What a screen shows about a group's sync: progress while it runs, the last failure after. */
data class SyncStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val offline: Boolean = false,
    val errorAtMs: Long = 0,
)

enum class SyncMode {
    /** Opening a screen: only when older than the foreground limit. */
    IF_STALE,
    /** Pull to refresh: now, the rounds near today (all of them if the snapshot is old). */
    FORCE,
    /** The periodic worker: only when older than the background limit. */
    BACKGROUND,
}

/** A team found while setting a child up: which group of the category it plays in. */
data class TeamInGroup(val team: Team, val group: Group)

enum class SyncOutcome { FRESH, FETCHED, FAILED }

class Repository(
    private val scope: CoroutineScope,
    private val api: EcmApi,
    private val cache: GroupCache,
    private val kids: KidStore,
    private val onChanges: suspend (List<Pair<Kid, MatchChange>>) -> Unit,
    /** After any sync that brought data: the eve-of-match alarm may need to move. */
    private val onFetched: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val snapshots: StateFlow<Map<Int, GroupSnapshot>> = cache.snapshots

    private val statusFlow = MutableStateFlow<Map<Int, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<Int, SyncStatus>> = statusFlow.asStateFlow()

    private val locks = HashMap<Int, Mutex>()
    private fun lock(groupId: Int) = synchronized(locks) { locks.getOrPut(groupId) { Mutex() } }

    suspend fun load(groupId: Int): GroupSnapshot? = cache.get(groupId)

    private fun now(): LocalDateTime = LocalDateTime.now(MADRID)

    /**
     * Brings a group up to date, defensively. The gate is the last *attempt* (not the last success),
     * so a site that keeps failing is asked at the policy's pace, never in a loop. Rounds go closest
     * to today first; each answer also refreshes its neighbours. One bad round does not stop the
     * others (only being offline does), and a sync never runs longer than [SYNC_BUDGET_MS]. What
     * was fetched is saved and reported even if the screen that started it goes away.
     */
    suspend fun sync(ref: GroupRef, mode: SyncMode, reason: String): SyncOutcome = lock(ref.groupId).withLock {
        val before = cache.get(ref.groupId)
        val now = now()
        val startedMs = nowMs()
        val limit = when (mode) {
            SyncMode.IF_STALE -> RefreshPolicy.foregroundMaxAgeMs(now)
            SyncMode.BACKGROUND -> RefreshPolicy.backgroundMaxAgeMs(now)
            SyncMode.FORCE -> 0L
        }
        val sinceAttempt = startedMs - (before?.lastAttemptMs ?: 0L)
        // A group that never loaded (its first fetch failed) is retried after a short pause, not
        // after the full gate: a child added while offline must not wait hours. A clock that was
        // ahead (negative age) never blocks.
        val gate = if (before?.rounds.isNullOrEmpty()) minOf(limit, EMPTY_RETRY_MS) else limit
        if (mode != SyncMode.FORCE && before != null && sinceAttempt in 0 until gate) return@withLock SyncOutcome.FRESH

        val tag = "#${ref.groupId}"
        var snap = (before ?: GroupSnapshot(Group(ref.groupId, ""), ref.sportId, ref.competitionId, ref.categoryId)).copy(lastAttemptMs = startedMs)
        var asked = 0
        var failed = 0
        var offline = false
        var lastError: Exception? = null
        try {
            if (snap.rounds.isEmpty()) {
                setStatus(ref.groupId) { SyncStatus(running = true) }
                asked++
                snap = fetchInto(snap, ref, 0)
            }
            val todo = RefreshPolicy.roundsToFetch(snap, now, nowMs(), limit)
            DiagLog.i(TAG, "sync $tag start mode=$mode reason=$reason due=${todo.size}/${snap.rounds.size} sinceAttempt=${sinceAttempt / 60_000}min")
            var done = 0
            for (r in todo) {
                setStatus(ref.groupId) { SyncStatus(running = true, done = done, total = todo.size) }
                done++
                // An earlier answer of this very sync may have brought this round as a neighbour.
                val stamped = snap.roundFetchedAt[r.id]
                if (stamped != null && stamped >= startedMs) continue
                if (!RefreshPolicy.isDue(snap, r, now, nowMs(), limit)) continue
                if (nowMs() - startedMs > SYNC_BUDGET_MS) {
                    DiagLog.w(TAG, "sync $tag out of time after $asked requests; the rest next time")
                    break
                }
                asked++
                try {
                    snap = fetchInto(snap, ref, r.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    lastError = e
                    offline = (e as? SourceException)?.offline == true
                    // Only an odd answer about this one round lets the others go ahead; the site
                    // being down, slow or refusing (5xx, 429, timeouts) ends the sync at once.
                    val roundSpecific = e is UnexpectedResponse || e.cause is UnexpectedResponse
                    DiagLog.w(TAG, "sync $tag round #${r.id} failed offline=$offline roundSpecific=$roundSpecific ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                    if (!roundSpecific) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            lastError = e
            offline = (e as? SourceException)?.offline == true
            DiagLog.w(TAG, "sync $tag first round failed offline=$offline ${e.javaClass.simpleName}: ${e.message?.take(60)}")
        } finally {
            // Saved, reported and cleared whatever happened, cancellation included.
            withContext(NonCancellable) {
                runCatching { cache.put(snap) }.onFailure { DiagLog.w(TAG, "sync $tag could not save: ${it.javaClass.simpleName}") }
                val err = lastError
                setStatus(ref.groupId) {
                    if (err != null && (offline || failed == asked)) SyncStatus(error = err.message ?: err.javaClass.simpleName, offline = offline, errorAtMs = nowMs())
                    else SyncStatus()
                }
                if (asked > failed) {
                    report(before, snap)
                    runCatching { onFetched() }
                }
            }
        }
        DiagLog.i(TAG, "sync $tag end asked=$asked failed=$failed in ${nowMs() - startedMs}ms matches=${snap.matches.size}")
        when {
            asked == 0 -> SyncOutcome.FRESH
            failed == asked -> SyncOutcome.FAILED
            else -> SyncOutcome.FETCHED
        }
    }

    /** One request: the site's answer for [roundId] (0 = current), checked and merged. */
    private suspend fun fetchInto(snap: GroupSnapshot, ref: GroupRef, roundId: Int): GroupSnapshot {
        val page = api.round(ref, roundId)
        val strangers = page.matches.count { it.groupId != 0 && it.groupId != ref.groupId }
        if (strangers > 0 && strangers == page.matches.size) {
            throw UnexpectedResponse("answer for another group (${page.matches.first().groupId})")
        }
        val got = page.round?.id
        if (roundId != 0 && got != null && got != roundId) DiagLog.w(TAG, "asked round #$roundId, got #$got")
        val merged = snap.merge(page, nowMs(), wasCurrentRequest = roundId == 0)
        if (merged.rejected.isNotEmpty()) {
            DiagLog.w(TAG, "#${ref.groupId} kept rounds ${merged.rejected} as they were: the answer had far fewer matches")
        }
        if (merged.dropped.isNotEmpty()) DiagLog.w(TAG, "#${ref.groupId} dropped rounds ${merged.dropped}: no longer listed by the site")
        return merged.snapshot
    }

    /** Followed teams' changes in this group, handed to the notifier. */
    private suspend fun report(before: GroupSnapshot?, after: GroupSnapshot) {
        val following = kids.current().filter { it.groupId == after.group.id }
        if (following.isEmpty()) return
        val changes = MatchChanges.between(before, after, following.map { it.teamId }.toSet(), now())
        if (changes.isEmpty()) return
        DiagLog.i(TAG, "changes #${after.group.id}: " + changes.joinToString { it.javaClass.simpleName + "@" + it.match.id })
        val pairs = changes.mapNotNull { c -> following.firstOrNull { it.teamId == c.teamId }?.let { it to c } }
        runCatching { onChanges(pairs) }.onFailure { DiagLog.e(TAG, "notify failed", it) }
    }

    private fun setStatus(groupId: Int, f: (SyncStatus?) -> SyncStatus) =
        statusFlow.update { it + (groupId to f(it[groupId])) }

    /** Every followed group, one after the other (the site sees one request at a time anyway). */
    suspend fun syncKids(mode: SyncMode, reason: String): List<SyncOutcome> =
        kids.current().map { it.ref }.distinctBy { it.groupId }.map { sync(it, mode, reason) }

    /**
     * Started from a screen but owned by the app: leaving the screen does not cancel it. The
     * returned job can be joined (pull to refresh) without tying its fate to the caller.
     */
    fun syncInBackground(ref: GroupRef, mode: SyncMode, reason: String): Job = scope.launch { sync(ref, mode, reason) }

    fun syncKidsInBackground(mode: SyncMode, reason: String): Job = scope.launch { syncKids(mode, reason) }

    // --- the explorer's lists: small, cached in memory for a few hours ------------------------

    private class Cached<T>(val value: T, val at: Long)
    private val memo = HashMap<String, Cached<Any>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> memo(key: String, ttlMs: Long = 6 * RefreshPolicy.HOUR, load: suspend () -> T): T {
        synchronized(memo) { memo[key] }?.takeIf { nowMs() - it.at < ttlMs }?.let { return it.value as T }
        val v = load()
        synchronized(memo) { memo[key] = Cached(v, nowMs()) }
        return v
    }

    suspend fun competitions(sportId: Int, includeClosed: Boolean): List<Competition> =
        memo("comp:$sportId:$includeClosed") { api.competitions(sportId, EcmApi.SEASON_ID, includeClosed) }

    suspend fun categories(competitionId: Int): List<Category> =
        memo("cat:$competitionId") { api.categories(competitionId) }

    suspend fun groups(sportId: Int, competitionId: Int, categoryId: Int): List<Group> =
        memo("grp:$sportId:$competitionId:$categoryId") { api.groups(sportId, competitionId, categoryId) }

    suspend fun groupedRound(sportId: Int, competitionId: Int, categoryId: Int, number: Int): List<GroupedRound> =
        memo("grouped:$sportId:$competitionId:$categoryId:$number", ttlMs = 10 * RefreshPolicy.MINUTE) {
            api.groupedRound(sportId, competitionId, categoryId, number)
        }

    /**
     * All teams of a category, with their group, to let a parent pick their child's school by
     * name instead of guessing a group number. Uses groups already cached; asks the site for the
     * others' current round (one request per group, [progress] after each).
     */
    suspend fun teamsOfCategory(
        sportId: Int,
        competitionId: Int,
        categoryId: Int,
        progress: (done: Int, total: Int) -> Unit,
    ): List<TeamInGroup> {
        val groups = groups(sportId, competitionId, categoryId)
        val out = mutableListOf<TeamInGroup>()
        groups.forEachIndexed { i, g ->
            progress(i, groups.size)
            val cached = cache.get(g.id)?.takeIf { it.matches.isNotEmpty() }
            val teams = cached?.teams ?: run {
                val ref = GroupRef(g.id, sportId, competitionId, categoryId)
                val page = api.round(ref, 0)
                val snap = (cache.get(g.id) ?: GroupSnapshot(g, sportId, competitionId, categoryId)).merge(page, nowMs(), true).snapshot
                cache.put(snap)
                snap.teams
            }
            teams.forEach { out += TeamInGroup(it, g) }
        }
        progress(groups.size, groups.size)
        return out
    }

    companion object {
        private const val TAG = "Repo"
        /** A sync never holds the group (and a worker) longer than this; the rest waits for the next one. */
        const val SYNC_BUDGET_MS = 3 * 60_000L
        /** A group with nothing loaded yet is tried again after this, whatever the mode's pace. */
        const val EMPTY_RETRY_MS = 10 * 60_000L
    }
}
