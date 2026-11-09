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
import kotlinx.coroutines.CancellationException
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

class Repository(
    private val api: EcmApi,
    private val cache: GroupCache,
    private val kids: KidStore,
    private val onChanges: suspend (List<Pair<Kid, MatchChange>>) -> Unit,
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
     * Brings a group up to date by fetching, one by one, the rounds the policy names. A failure
     * keeps everything already held (and every round fetched before it in this pass), and is
     * reported in [status]. Returns true when the group was fetched (or was fresh enough).
     */
    suspend fun sync(ref: GroupRef, mode: SyncMode, reason: String): Boolean = lock(ref.groupId).withLock {
        val before = cache.get(ref.groupId)
        val now = now()
        val age = nowMs() - (before?.newestFetch ?: 0L)
        val limit = when (mode) {
            SyncMode.IF_STALE -> RefreshPolicy.foregroundMaxAgeMs(now)
            SyncMode.BACKGROUND -> RefreshPolicy.backgroundMaxAgeMs(now)
            SyncMode.FORCE -> 0L
        }
        val complete = before != null && before.rounds.isNotEmpty() && before.missingRounds.isEmpty()
        if (complete && age < limit) return@withLock true

        val tag = "#${ref.groupId}"
        DiagLog.i(TAG, "sync $tag start mode=$mode reason=$reason age=${age / 60_000}min rounds=${before?.rounds?.size ?: 0}")
        val started = nowMs()
        var snap = before ?: GroupSnapshot(Group(ref.groupId, ""), ref.sportId, ref.competitionId, ref.categoryId)
        var fetched = 0
        try {
            val fetchedIds = HashSet<Int>()
            if (snap.rounds.isEmpty()) {
                setStatus(ref.groupId) { SyncStatus(running = true, done = 0, total = 0) }
                val page = api.round(ref, 0)
                snap = snap.merge(page, nowMs(), wasCurrentRequest = true)
                page.round?.let { fetchedIds += it.id }
                fetched++
                cache.put(snap)
            }
            val todo = RefreshPolicy.roundsToFetch(snap, now, nowMs()).filter { it.id !in fetchedIds }
            val total = todo.size + fetched
            setStatus(ref.groupId) { SyncStatus(running = true, done = fetched, total = total) }
            for (r in todo) {
                val page = api.round(ref, r.id)
                snap = snap.merge(page, nowMs(), wasCurrentRequest = false)
                fetched++
                setStatus(ref.groupId) { SyncStatus(running = true, done = fetched, total = total) }
                // Saved as it goes: a long first sync interrupted half way keeps its half.
                if (fetched % 4 == 0) cache.put(snap)
            }
            cache.put(snap)
            setStatus(ref.groupId) { SyncStatus() }
            DiagLog.i(TAG, "sync $tag done rounds=$fetched in ${nowMs() - started}ms matches=${snap.matches.size}")
            report(before, snap)
            true
        } catch (e: CancellationException) {
            if (fetched > 0) cache.put(snap)
            setStatus(ref.groupId) { SyncStatus() }
            throw e
        } catch (e: Exception) {
            if (fetched > 0) cache.put(snap)
            val offline = (e as? SourceException)?.offline == true
            DiagLog.w(TAG, "sync $tag failed after $fetched rounds offline=$offline ${e.javaClass.simpleName}")
            setStatus(ref.groupId) { SyncStatus(error = e.message ?: e.javaClass.simpleName, offline = offline, errorAtMs = nowMs()) }
            if (fetched > 0) report(before, snap)
            false
        }
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
    suspend fun syncKids(mode: SyncMode, reason: String): Int {
        val list = kids.current()
        var ok = 0
        for (ref in list.map { it.ref }.distinctBy { it.groupId }) {
            if (sync(ref, mode, reason)) ok++
        }
        return ok
    }

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
                val snap = (cache.get(g.id) ?: GroupSnapshot(g, sportId, competitionId, categoryId)).merge(page, nowMs(), true)
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
    }
}
