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
        get() = p.getInt("seenVersionCode", 0)
        set(v) = p.edit { putInt("seenVersionCode", v) }

    /** Match ids already reminded, so a reminder is never posted twice (kept short). */
    var remindedMatchIds: Set<String>
        get() = p.getStringSet("reminded", emptySet()).orEmpty()
        set(v) = p.edit { putStringSet("reminded", v.toList().takeLast(60).toSet()) }

    var lastBackgroundSyncMs: Long
        get() = p.getLong("lastBgSync", 0)
        set(v) = p.edit { putLong("lastBgSync", v) }

    var askedNotifications: Boolean
        get() = p.getBoolean("askedNotif", false)
        set(v) = p.edit { putBoolean("askedNotif", v) }
}
