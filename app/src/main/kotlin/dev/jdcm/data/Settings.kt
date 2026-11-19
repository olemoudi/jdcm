package dev.jdcm.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class SettingsState(
    val theme: ThemeMode = ThemeMode.DARK,
    val notifyChanges: Boolean = true,
    val notifyResults: Boolean = true,
    val notifyReminder: Boolean = true,
    /** Hour of the eve-of-match reminder, local time. */
    val reminderHour: Int = 20,
    /** Explorer: also list competitions the site closed. */
    val showClosed: Boolean = false,
    /** Alert when AEMET gives a child's match over 60 % of rain. On for everybody, off in Settings (owner, 2026-11-18). */
    val rainAlerts: Boolean = true,
    /** Two children at nearly the same time in different places: Home's card and the eve reminder's line. On for everybody, off in Settings (owner, 2026-11-19). */
    val clashAlerts: Boolean = true,
)

/** Small, synchronous preferences (SharedPreferences), exposed as one state flow. */
class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /**
     * What belongs to this install on this phone (permissions guide done, worker enqueued, what was
     * reminded or seen). Not in the backup rules: restored onto a new phone, these would skip the
     * permission guide and leave the background sync unscheduled.
     */
    private val d = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = mutable.asStateFlow()

    private fun read() = SettingsState(
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", null)!!) }.getOrDefault(ThemeMode.DARK),
        notifyChanges = p.getBoolean("notifyChanges", true),
        notifyResults = p.getBoolean("notifyResults", true),
        notifyReminder = p.getBoolean("notifyReminder", true),
        reminderHour = p.getInt("reminderHour", 20).coerceIn(17, 22),
        showClosed = p.getBoolean("showClosed", false),
        rainAlerts = p.getBoolean("rainAlerts", true),
        clashAlerts = p.getBoolean("clashAlerts", true),
    )

    fun update(f: (SettingsState) -> SettingsState) {
        val n = f(mutable.value)
        p.edit {
            putString("theme", n.theme.name)
            putBoolean("notifyChanges", n.notifyChanges)
            putBoolean("notifyResults", n.notifyResults)
            putBoolean("notifyReminder", n.notifyReminder)
            putInt("reminderHour", n.reminderHour)
            putBoolean("showClosed", n.showClosed)
            putBoolean("rainAlerts", n.rainAlerts)
            putBoolean("clashAlerts", n.clashAlerts)
        }
        mutable.value = n
    }

    /** The versionCode whose «Novedades» the user has seen; 0 on a fresh install. */
    var seenVersionCode: Int
        get() = d.getInt("seenVersionCode", 0)
        set(v) = d.edit { putInt("seenVersionCode", v) }

    /** Match ids already reminded, so a reminder is never posted twice (kept short). */
    var remindedMatchIds: Set<String>
        get() = d.getStringSet("reminded", emptySet()).orEmpty()
        // Keys end in the match's date: the 60 newest stay (a set has no order to "take last" by).
        set(v) = d.edit { putStringSet("reminded", v.sortedBy { it.substringAfterLast(':') }.takeLast(60).toSet()) }

    var lastBackgroundSyncMs: Long
        get() = d.getLong("lastBgSync", 0)
        set(v) = d.edit { putLong("lastBgSync", v) }

    /** The versionCode that last enqueued the periodic sync (re-enqueued after each update only). */
    var scheduledForVersion: Int
        get() = d.getInt("scheduledForVersion", 0)
        set(v) = d.edit { putInt("scheduledForVersion", v) }

    /** The first-run guide (permissions) was completed or skipped through to the end. */
    var onboardingDone: Boolean
        get() = d.getBoolean("onboardingDone", false)
        set(v) = d.edit { putBoolean("onboardingDone", v) }

    /** How many app openings have shown the strip's legend (it explains the dots three times, then goes). */
    var stripLegendShows: Int
        get() = d.getInt("stripLegendShows", 0)
        set(v) = d.edit { putInt("stripLegendShows", v) }

    /** When the pre-match alarm last fired: the next check is due a quarter of an hour after it. */
    var preMatchLastCheckMs: Long
        get() = d.getLong("preMatchLastCheck", 0)
        set(v) = d.edit { putLong("preMatchLastCheck", v) }

    /** When the pre-match alarm is set to go off (0 = none), for the diagnostic report. */
    var preMatchAlarmAtMs: Long
        get() = d.getLong("preMatchAlarmAt", 0)
        set(v) = d.edit { putLong("preMatchAlarmAt", v) }

    /**
     * The last rain alert per match, as "matchId:epochMs": at most one per match in 24 hours
     * (RainAlertPolicy). Kept short: the newest 40.
     */
    var rainAlerted: Map<Int, Long>
        get() = d.getStringSet("rainAlerted", emptySet()).orEmpty().mapNotNull { e ->
            val id = e.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val at = e.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
            id to at
        }.toMap()
        set(v) = d.edit { putStringSet("rainAlerted", v.entries.sortedBy { it.value }.takeLast(40).map { "${it.key}:${it.value}" }.toSet()) }

    /** When the last rain check ran: the background ones wait 6 hours between them (RainWatch). */
    var lastRainCheckMs: Long
        get() = d.getLong("lastRainCheck", 0)
        set(v) = d.edit { putLong("lastRainCheck", v) }

    /** When the evening rain check is set to go off (0 = none), for the diagnostic report. */
    var rainCheckAtMs: Long
        get() = d.getLong("rainCheckAt", 0)
        set(v) = d.edit { putLong("rainCheckAt", v) }

    /** Home's install-permission reminder stays hidden until this time ("Más tarde"). */
    var installBannerSnoozedUntil: Long
        get() = d.getLong("installBannerSnoozedUntil", 0)
        set(v) = d.edit { putLong("installBannerSnoozedUntil", v) }

    /** When the hourly background check last started, children or not: what Home's health banner measures. */
    var workerLastRunMs: Long
        get() = d.getLong("workerLastRun", 0)
        set(v) = d.edit { putLong("workerLastRun", v) }

    /** When this build enqueued the hourly check: no run can be expected before it. */
    var workerScheduledAtMs: Long
        get() = d.getLong("workerScheduledAt", 0)
        set(v) = d.edit { putLong("workerScheduledAt", v) }

    /**
     * When a «checks held back» warning was last acted on: lateness counts from here again, so a
     * fixed phone is not told the next cause while it waits for its first check.
     */
    var healthLateFixAtMs: Long
        get() = d.getLong("healthLateFixAt", 0)
        set(v) = d.edit { putLong("healthLateFixAt", v) }

    /** Home's health banner: each issue's «Más tarde», as issue name → hidden until (epoch ms). */
    var healthSnoozed: Map<String, Long>
        get() = d.getStringSet("healthSnoozed", emptySet()).orEmpty().mapNotNull { e ->
            val until = e.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
            e.substringBefore(':') to until
        }.toMap()
        set(v) = d.edit { putStringSet("healthSnoozed", v.map { "${it.key}:${it.value}" }.toSet()) }

    /**
     * The season the site's calendar opens on (SeasonWatch reads it weekly); the build's own until then.
     * Never older than the build's: a stale or odd page cannot take the explorer back a season.
     */
    var activeSeasonId: Int
        get() = d.getInt("activeSeasonId", 0).coerceAtLeast(EcmApi.SEASON_ID)
        set(v) = d.edit { putInt("activeSeasonId", v) }

    /**
     * «TEMPORADA 2027-2028», as the site names [activeSeasonId]; empty while it is the build's own (also
     * when a later build raised the floor above the season stored: that name is another season's).
     */
    var activeSeasonName: String
        get() = if (d.getInt("activeSeasonId", 0) >= EcmApi.SEASON_ID) d.getString("activeSeasonName", "").orEmpty() else ""
        set(v) = d.edit { putString("activeSeasonName", v) }

    /** A season the site showed once and not yet confirmed by a second read (0 = none). */
    var seasonPendingId: Int
        get() = d.getInt("seasonPendingId", 0)
        set(v) = d.edit { putInt("seasonPendingId", v) }

    /** When the season was last looked up (or, after a failure, a day before it may be tried again). */
    var seasonCheckedMs: Long
        get() = d.getLong("seasonChecked", 0)
        set(v) = d.edit { putLong("seasonChecked", v) }
}
