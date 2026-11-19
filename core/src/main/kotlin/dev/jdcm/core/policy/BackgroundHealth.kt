package dev.jdcm.core.policy

/**
 * What the phone is doing to the app's notices, as Home's health banner reads it. Every fact is
 * read fresh from Android by the app; this decides what (if anything) to tell the family.
 */
data class HealthFacts(
    /** The app may post notifications at all (Android's switch and, on 13+, the permission). */
    val notificationsAllowed: Boolean,
    /** Any kind of notice is on in the app's own settings (changes, results, reminder, rain). */
    val wantsNotifications: Boolean,
    /** Change notices are on in the app, but their Android channel is switched off. */
    val changesChannelOff: Boolean,
    /** Battery set to «Restricted» by the user, or Android's restricted standby bucket. */
    val backgroundRestricted: Boolean,
    val batteryUnrestricted: Boolean,
    /** Data saver on, the app not exempt, and the phone on a metered network now (data saver lets Wi-Fi through). */
    val dataSaverBlocks: Boolean,
    /** The phone has a working network right now (a late check offline says nothing). */
    val online: Boolean,
    /** Android 12/12L only can say no; true elsewhere. */
    val exactAlarmsAllowed: Boolean,
    /** Change notices are on (the pre-match checks depend on them). */
    val wantsChanges: Boolean,
    val hasKids: Boolean,
    /** The maker's «Inicio automático» is read as off (Xiaomi only can say): nothing runs after a reboot or a swipe. */
    val autostartOff: Boolean = false,
    /**
     * A maker whose power manager stops sideloaded apps and whose switch cannot be read (Huawei, OPPO,
     * vivo…, a Xiaomi that cannot say, a Samsung still optimised), and the family has not said it is done.
     */
    val oemTip: Boolean = false,
    /** When the hourly background check last ran (0 = never seen). */
    val lastBackgroundRunMs: Long,
    /** Since when it could have run: the latest of its scheduling, the phone's boot and the app's update. */
    val sinceMs: Long,
)

enum class HealthIssue {
    /** Nothing will arrive. */
    NOTIFICATIONS_OFF,
    /** Nothing runs in the background. */
    BACKGROUND_RESTRICTED,
    /** The maker's «Inicio automático» is off (Xiaomi): after a reboot or a swipe from recents, nothing runs. */
    AUTOSTART_OFF,
    /** No notice of time, place or status changes. */
    CHANGES_CHANNEL_OFF,
    /** Android is holding the hourly check back: notices come late. Fixed by turning the battery optimisation off. */
    LATE_BATTERY,
    /** As [LATE_BATTERY], but data saver is what stops it. */
    LATE_DATA_SAVER,
    /** As [LATE_BATTERY] with the battery already unrestricted: the maker's own power manager. */
    LATE_OTHER,
    /** Android 12/12L: the pre-match checks may come late. */
    EXACT_ALARMS_OFF,
    /** Once: the maker's own step (oem/OemPages), on a phone where it cannot be read. «Ya está» ends it. */
    OEM_TIP,
}

object BackgroundHealth {
    /**
     * The hourly check counts as late after this long without a run. Deep Doze spaces maintenance
     * windows up to ~6 h overnight; past 8 h it is Android holding the app back (the "rare" bucket
     * allows one job a day), not the night.
     */
    const val LATE_AFTER_MS = 8 * 60 * 60_000L

    /** Every issue that applies, the most serious first; empty when the phone lets the app work. */
    fun issues(f: HealthFacts, nowMs: Long): List<HealthIssue> {
        if (!f.hasKids) return emptyList()
        val out = mutableListOf<HealthIssue>()
        if (f.wantsNotifications && !f.notificationsAllowed) out += HealthIssue.NOTIFICATIONS_OFF
        // The background only matters to the family for its notices: with every one off in the app, nothing to say.
        if (f.wantsNotifications && f.backgroundRestricted) out += HealthIssue.BACKGROUND_RESTRICTED
        if (f.wantsNotifications && f.autostartOff) out += HealthIssue.AUTOSTART_OFF
        if (f.notificationsAllowed && f.wantsChanges && f.changesChannelOff) out += HealthIssue.CHANGES_CHANNEL_OFF
        if (f.wantsNotifications && !f.backgroundRestricted && f.online && isLate(f, nowMs)) {
            out += when {
                f.dataSaverBlocks -> HealthIssue.LATE_DATA_SAVER
                !f.batteryUnrestricted -> HealthIssue.LATE_BATTERY
                else -> HealthIssue.LATE_OTHER
            }
        }
        if (f.notificationsAllowed && f.wantsChanges && !f.exactAlarmsAllowed) out += HealthIssue.EXACT_ALARMS_OFF
        if (f.wantsNotifications && f.oemTip && HealthIssue.AUTOSTART_OFF !in out) out += HealthIssue.OEM_TIP
        return out
    }

    /** No run for [LATE_AFTER_MS], counted from the last run or from when one became possible. A clock that went back never counts. */
    fun isLate(f: HealthFacts, nowMs: Long): Boolean {
        val from = maxOf(f.lastBackgroundRunMs, f.sinceMs)
        if (from <= 0) return false
        return nowMs - from > LATE_AFTER_MS
    }
}
