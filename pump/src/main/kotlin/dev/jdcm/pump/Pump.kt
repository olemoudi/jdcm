package dev.jdcm.pump

import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.policy.RefreshPolicy
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.PriorityQueue
import kotlin.math.abs

/** How much one run may ask, and how fresh each piece of data must be. */
data class Limits(
    /** Requests per run, retries included. */
    val maxRequests: Int = 60,
    /** Wall time per run; whatever is left waits for the next one. */
    val maxRunMs: Long = 4 * 60_000L,
    /**
     * Rounds around this weekend's (core's "hot" rounds) are refreshed when older than this. The
     * workflow runs every 3 h in the daytime, so every run refreshes them (owner, 2026-11-16).
     */
    val hotMaxAgeMs: Long = 2 * RefreshPolicy.HOUR,
    /**
     * Due-ness is judged this far ahead: a round that falls due before the next run is taken now,
     * so a 6-hour round is not left for 9 hours because it was 5 h 59 min old at a run.
     */
    val slackMs: Long = 45 * RefreshPolicy.MINUTE,
    /** The catalogue of teams (3 requests) is refreshed about once a day. */
    val catalogMaxAgeMs: Long = 20 * RefreshPolicy.HOUR,
    /** This many odd answers in a row and the run stops: the site is acting strangely. */
    val maxOddInARow: Int = 3,
)

data class RunResult(
    /** Something new to publish (data fetched, or the list of groups changed). */
    val publish: Boolean,
    val requests: Int,
    val fetched: Int,
    val failed: Int,
    /** Why the run stopped early, if it did. */
    val stopped: String?,
)

/**
 * One run of the data pump: read back what the previous run published, ask the site for what is
 * due (closest to today first, within the run's budget), merge it with :core's defensive merge,
 * and write the static JSON the PWA reads.
 */
class Pump(
    private val source: Source,
    private val config: PumpConfig,
    private val prevDir: File?,
    private val outDir: File,
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Job {
        val priority: Long
        val order: Int
    }

    private data class RoundJob(val ref: GroupRef, val roundId: Int, override val priority: Long, override val order: Int) : Job

    private data class CatalogJob(val sportId: Int, override val priority: Long, override val order: Int) : Job

    fun run(force: Boolean = false): RunResult {
        val startedMs = clock()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(startedMs), MADRID)
        val today = now.toLocalDate()
        val hot = if (force) 0L else limits.hotMaxAgeMs
        val prevIndex = prevDir?.let { readOrNull(File(it, "index.json"), IndexOut.serializer()) }
        var catalog = prevDir?.let { readOrNull(File(it, "catalog.json"), CatalogOut.serializer()) }
        val snaps = LinkedHashMap<Int, GroupSnapshot>()
        for (g in config.groups) {
            val held = prevDir?.let { readOrNull(File(it, "state/${g.groupId}.json"), GroupSnapshot.serializer()) }
            snaps[g.groupId] = held?.takeIf { it.group.id == g.groupId }
                ?: GroupSnapshot(Group(g.groupId, ""), g.sportId, g.competitionId, g.categoryId)
        }

        // --- plan: everything due, closest to today first --------------------------------------
        val queue = PriorityQueue<Job>(compareBy<Job>({ it.priority }, { it.order }))
        fun planRounds(order: Int, ref: GroupRef) {
            val snap = snaps.getValue(ref.groupId)
            if (snap.rounds.isEmpty()) {
                queue += RoundJob(ref, 0, priority = -100, order = order)
                return
            }
            for (r in RefreshPolicy.roundsToFetch(snap, now, startedMs + limits.slackMs, hot)) {
                val days = r.date?.let { abs(ChronoUnit.DAYS.between(today, it)) } ?: 1_000
                queue += RoundJob(ref, r.id, priority = days * 10, order = order)
            }
        }
        config.groups.forEachIndexed { i, g -> planRounds(i, g.ref) }
        val catalogDue = catalog == null || force ||
            startedMs + limits.slackMs - catalog.fetchedAt >= limits.catalogMaxAgeMs || startedMs < catalog.fetchedAt
        if (catalogDue) {
            // A missing catalogue goes first (nobody can add a child without it); a stale one after
            // this weekend's rounds (priority 0..60) and before the weeks around them.
            val priority = if (catalog == null) -50L else 65L
            SPORTS.forEachIndexed { i, s -> queue += CatalogJob(s, priority, i) }
        }
        Log.i("plan", "groups=${config.groups.size} jobs=${queue.size} catalogDue=$catalogDue force=$force")

        // --- run: one request at a time, within the budget -------------------------------------
        var fetched = 0
        var failed = 0
        var oddInARow = 0
        var stopped: String? = null
        val errors = HashMap<Int, String>()
        val attempted = HashSet<Int>()
        val catalogSports = HashMap<Int, CatalogSport>()
        var catalogFailures = 0
        while (queue.isNotEmpty()) {
            val job = queue.poll()
            if (source.requests >= limits.maxRequests) { stopped = "budget of ${limits.maxRequests} requests"; break }
            if (clock() - startedMs > limits.maxRunMs) { stopped = "time budget"; break }
            try {
                when (job) {
                    is RoundJob -> {
                        val snap = snaps.getValue(job.ref.groupId)
                        if (job.roundId != 0) {
                            // An earlier answer of this very run may have brought it as a neighbour.
                            val stamped = snap.roundFetchedAt[job.roundId]
                            if (stamped != null && stamped >= startedMs) continue
                            val round = snap.rounds.firstOrNull { it.id == job.roundId } ?: continue
                            if (!RefreshPolicy.isDue(snap, round, now, clock() + limits.slackMs, hot)) continue
                        }
                        attempted += job.ref.groupId
                        snaps[job.ref.groupId] = snap.copy(lastAttemptMs = clock())
                        val wasEmpty = snap.rounds.isEmpty()
                        snaps[job.ref.groupId] = fetchInto(snaps.getValue(job.ref.groupId), job.ref, job.roundId)
                        fetched++
                        oddInARow = 0
                        errors -= job.ref.groupId
                        // A group just loaded for the first time: its other rounds are due now.
                        if (wasEmpty && snaps.getValue(job.ref.groupId).rounds.isNotEmpty()) planRounds(job.order, job.ref)
                    }
                    is CatalogJob -> {
                        val sport = Views.catalogSport(job.sportId, source.grouped(job.sportId, 1))
                        val held = catalog?.sports?.firstOrNull { it.id == job.sportId }
                        // The catalogue is defended like a round: an answer with far fewer groups is not taken.
                        if (held != null && Views.groupCount(sport) * 2 < Views.groupCount(held)) {
                            Log.w("catalog", "sport=${job.sportId} groups=${Views.groupCount(sport)} held=${Views.groupCount(held)}: kept")
                            catalogFailures++
                        } else {
                            catalogSports[job.sportId] = sport
                            Log.i("catalog", "sport=${job.sportId} groups=${Views.groupCount(sport)}")
                        }
                        fetched++
                        oddInARow = 0
                    }
                }
            } catch (e: OddAnswer) {
                failed++
                oddInARow++
                when (job) {
                    is RoundJob -> errors[job.ref.groupId] = e.message ?: "odd answer"
                    is CatalogJob -> catalogFailures++
                }
                Log.w("fetch", "${describe(job)} odd answer: ${e.message?.take(80)}")
                if (oddInARow >= limits.maxOddInARow) { stopped = "$oddInARow odd answers in a row"; break }
            } catch (e: SiteDown) {
                failed++
                when (job) {
                    is RoundJob -> errors[job.ref.groupId] = e.message ?: "site down"
                    is CatalogJob -> catalogFailures++
                }
                Log.w("fetch", "${describe(job)} site down: ${e.message?.take(80)}")
                stopped = "site down (${e.message?.take(40)})"
                break
            }
        }
        if (stopped != null) Log.w("run", "stopped: $stopped; the rest waits for the next run")

        if (catalogSports.isNotEmpty()) {
            val sports = SPORTS.mapNotNull { id -> catalogSports[id] ?: catalog?.sports?.firstOrNull { it.id == id } }
            // Fully refreshed only when every sport answered; otherwise the next run tries again.
            val complete = catalogFailures == 0 && SPORTS.all { it in catalogSports }
            catalog = CatalogOut(
                generatedAt = iso(clock()),
                fetchedAt = if (complete) startedMs else catalog?.fetchedAt ?: 0,
                sports = sports,
            )
        }

        val heldIds = prevIndex?.groups?.map { it.groupId }?.toSet()
        val configChanged = heldIds != config.groups.map { it.groupId }.toSet()
        val publish = fetched > 0 || configChanged
        val result = RunResult(publish, source.requests, fetched, failed, stopped)
        Log.i("run", "requests=${source.requests} fetched=$fetched failed=$failed publish=$publish ms=${clock() - startedMs}")
        if (!publish) return result

        // --- write: a fresh tree, the whole of the data branch ---------------------------------
        val generatedAt = iso(clock())
        outDir.mkdirs()
        val indexGroups = config.groups.map { g ->
            val snap = snaps.getValue(g.groupId)
            write(File(outDir, "state/${g.groupId}.json"), GroupSnapshot.serializer(), snap)
            write(File(outDir, "groups/${g.groupId}.json"), GroupOut.serializer(), Views.group(snap, g.ref, generatedAt))
            val previousError = prevIndex?.groups?.firstOrNull { it.groupId == g.groupId }?.lastError
            IndexGroup(
                groupId = g.groupId, sportId = g.sportId, competitionId = g.competitionId, categoryId = g.categoryId,
                name = snap.group.name, competition = snap.group.competition, category = snap.group.category,
                sport = snap.group.sport.ifBlank { SportKind.of(g.sportId).name },
                newestFetch = snap.newestFetch,
                lastAttemptMs = snap.lastAttemptMs,
                lastError = errors[g.groupId] ?: previousError.takeIf { g.groupId !in attempted },
            )
        }
        catalog?.let { write(File(outDir, "catalog.json"), CatalogOut.serializer(), Views.withFollowed(it, config.groups.map { g -> g.groupId }.toSet())) }
        val index = IndexOut(
            generatedAt = generatedAt,
            nextDueAt = nextDueAt(snaps.values, catalog, now),
            catalogAt = catalog?.fetchedAt ?: 0,
            groups = indexGroups,
            lastRun = RunOut(generatedAt, source.requests, fetched, failed, stopped),
        )
        write(File(outDir, "index.json"), IndexOut.serializer(), index)
        File(outDir, "README.md").writeText(README)
        return result
    }

    /** One request: the site's answer for [roundId] (0 = current), checked and merged. */
    private fun fetchInto(snap: GroupSnapshot, ref: GroupRef, roundId: Int): GroupSnapshot {
        val page = source.round(ref, roundId)
        val strangers = page.matches.count { it.groupId != 0 && it.groupId != ref.groupId }
        if (strangers > 0 && strangers == page.matches.size) {
            throw OddAnswer("answer for another group (${page.matches.first().groupId})")
        }
        val got = page.round?.id
        if (roundId != 0 && got != null && got != roundId) Log.w("fetch", "group=${ref.groupId} asked round=$roundId got=$got")
        val merged = snap.merge(page, clock(), wasCurrentRequest = roundId == 0)
        if (merged.rejected.isNotEmpty()) Log.w("merge", "group=${ref.groupId} kept rounds ${merged.rejected}: the answer had far fewer matches")
        if (merged.dropped.isNotEmpty()) Log.w("merge", "group=${ref.groupId} dropped rounds ${merged.dropped}: no longer listed")
        Log.i("fetch", "group=${ref.groupId} round=$roundId accepted=${merged.accepted}")
        return merged.snapshot
    }

    /** When the earliest piece of data falls due again (judged [Limits.slackMs] early, like the run does). */
    private fun nextDueAt(snaps: Collection<GroupSnapshot>, catalog: CatalogOut?, now: LocalDateTime): Long {
        val nowMs = clock()
        var next = Long.MAX_VALUE
        for (s in snaps) {
            if (s.rounds.isEmpty()) return nowMs
            for (r in s.rounds) {
                val at = s.roundFetchedAt[r.id] ?: return nowMs
                next = minOf(next, at + RefreshPolicy.maxAgeMs(r, now, limits.hotMaxAgeMs) - limits.slackMs)
            }
        }
        next = minOf(next, (catalog?.fetchedAt ?: 0) + limits.catalogMaxAgeMs - limits.slackMs)
        return next
    }

    private fun describe(job: Job): String = when (job) {
        is RoundJob -> "group=${job.ref.groupId} round=${job.roundId}"
        is CatalogJob -> "catalog sport=${job.sportId}"
    }

    private fun <T> readOrNull(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!file.exists()) return null
        return runCatching { PumpJson.decodeFromString(serializer, file.readText()) }
            .onFailure { Log.w("read", "unreadable ${file.name}: ${it.javaClass.simpleName}; starting it afresh") }
            .getOrNull()
    }

    private fun <T> write(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        file.writeText(PumpJson.encodeToString(serializer, value))
    }

    companion object {
        /** Basketball, futsal, volleyball: the site's ids (stable since 2026-11-09). */
        val SPORTS = listOf(SportKind.BASKETBALL.siteId, SportKind.FUTSAL.siteId, SportKind.VOLLEYBALL.siteId)

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

        private val README = """
            |# JDCM PWA data
            |
            |Generated by `.github/workflows/pwa-data.yml` on `main` (the `:pump` module) from the public
            |pages of juegosdeportivos.ecmadrid.org. This branch is force-pushed as a single commit on every
            |run: never edit it, never base anything on it. Read by the PWA at https://makensi.es/jdcm/.
            |
            |- `index.json`: the followed groups and how fresh they are.
            |- `groups/<id>.json`: one group's rounds, matches and computed standings.
            |- `catalog.json`: every sport, category, group and team (to add a child).
            |- `state/<id>.json`: the pump's own memory of each group (read back by the next run).
            |""".trimMargin()
    }
}
