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
        set(v) = d.edit { putStringSet("reminded", v.toList().takeLast(60).toSet()) }

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

    /** Home's install-permission reminder stays hidden until this time ("Más tarde"). */
    var installBannerSnoozedUntil: Long
        get() = d.getLong("installBannerSnoozedUntil", 0)
        set(v) = d.edit { putLong("installBannerSnoozedUntil", v) }
}
