package dev.jdcm.oem

/**
 * The phone makers whose own power managers stop a sideloaded app's background work beyond what
 * Android does (dontkillmyapp.com): Xiaomi (MIUI / HyperOS: «Inicio automático» off for apps from
 * outside its store, «Ahorro de batería» per app), Huawei and Honor («Inicio de aplicaciones»),
 * OPPO, realme and OnePlus (ColorOS: «Inicio automático», «Actividad en segundo plano»), vivo
 * («Inicio automático», «Alto consumo en segundo plano») and Samsung («Aplicaciones en suspensión»).
 * Without them, after a reboot or a swipe from recents nothing of the app runs until it is opened:
 * no checks, no eve reminder, no pre-match checks. Pure Kotlin: unit-tested.
 */
enum class Oem(val brand: String) {
    XIAOMI("Xiaomi"),
    SAMSUNG("Samsung"),
    HUAWEI("Huawei"),
    HONOR("Honor"),
    OPPO("OPPO"),
    REALME("realme"),
    ONEPLUS("OnePlus"),
    VIVO("vivo"),
    OTHER("");

    companion object {
        /** From `Build.MANUFACTURER` and `Build.BRAND` (Redmi and POCO are Xiaomi; an old Honor says HUAWEI/HONOR). */
        fun of(manufacturer: String?, brand: String?): Oem {
            val m = manufacturer.orEmpty().trim().lowercase()
            val b = brand.orEmpty().trim().lowercase()
            fun any(vararg names: String) = names.any { it == m || it == b }
            return when {
                any("xiaomi", "redmi", "poco") -> XIAOMI
                any("samsung") -> SAMSUNG
                any("honor") -> HONOR
                any("huawei") -> HUAWEI
                any("oneplus") -> ONEPLUS
                any("realme") -> REALME
                any("oppo") -> OPPO
                any("vivo", "iqoo") -> VIVO
                else -> OTHER
            }
        }
    }
}

enum class OemPageKind {
    /** «Inicio automático» and its kin: may the app be started in the background at all. */
    AUTOSTART,
    /** The maker's own battery restrictions for the app. */
    BATTERY,
}

/**
 * One page of the maker's settings app, by component (or by action within [pkg]). [withPackageExtras]:
 * the page wants `package_name` / `package_label` to open on this app (Xiaomi's battery saver).
 */
data class OemPage(
    val kind: OemPageKind,
    val pkg: String,
    val cls: String? = null,
    val action: String? = null,
    val withPackageExtras: Boolean = false,
)

/**
 * Where each maker keeps those switches, newest first; names change across versions, so every one is
 * tried in turn and the app's own info page is the last resort. Every package here must be in the
 * manifest's `<queries>` (OemTest checks it): without it Android 11+ hides them from this app.
 */
object OemPages {
    private val huawei = listOf(
        OemPage(OemPageKind.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        OemPage(OemPageKind.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        OemPage(OemPageKind.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
    private val coloros = listOf(
        OemPage(OemPageKind.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        OemPage(OemPageKind.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        OemPage(OemPageKind.AUTOSTART, "com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        OemPage(OemPageKind.AUTOSTART, "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )

    fun of(oem: Oem): List<OemPage> = when (oem) {
        Oem.XIAOMI -> listOf(
            OemPage(OemPageKind.AUTOSTART, "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            OemPage(OemPageKind.AUTOSTART, "com.miui.securitycenter", action = "miui.intent.action.OP_AUTO_START"),
            OemPage(OemPageKind.BATTERY, "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", withPackageExtras = true),
        )
        Oem.SAMSUNG -> listOf(
            OemPage(OemPageKind.BATTERY, "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            OemPage(OemPageKind.BATTERY, "com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Oem.HUAWEI -> huawei
        Oem.HONOR -> listOf(
            OemPage(OemPageKind.AUTOSTART, "com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ) + huawei
        Oem.OPPO, Oem.REALME -> coloros
        Oem.ONEPLUS -> listOf(
            OemPage(OemPageKind.AUTOSTART, "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ) + coloros
        Oem.VIVO -> listOf(
            OemPage(OemPageKind.AUTOSTART, "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            OemPage(OemPageKind.AUTOSTART, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            OemPage(OemPageKind.AUTOSTART, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Oem.OTHER -> emptyList()
    }

    /** Every maker package the app may open: the manifest's `<queries>` must list them all. */
    val packages: Set<String> get() = Oem.entries.flatMap { of(it) }.map { it.pkg }.toSet()
}
