package dev.jdcm.oem

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.jdcm.diag.DiagLog

/**
 * This phone's maker and its power-manager pages (OemPages), opened safely: each candidate is checked
 * (exists, exported) before it is started, a refusal falls through to the next one, and Android's own
 * app info page is the last resort. Every outcome is logged with the component, never with names.
 */
object OemPower {
    private val real: Oem by lazy { Oem.of(Build.MANUFACTURER, Build.BRAND) }

    /**
     * Debug builds only: a maker to pretend to be (device prefs `debugOem`, set with run-as on the AVD),
     * to walk the guide, Settings and Home as a Xiaomi or an OPPO would. Release builds never read it.
     */
    @Volatile var debugOverride: Oem? = null

    val oem: Oem get() = debugOverride ?: real

    fun intent(ctx: Context, page: OemPage): Intent {
        val i = if (page.cls != null) Intent().setClassName(page.pkg, page.cls) else Intent(page.action).setPackage(page.pkg)
        if (page.withPackageExtras) {
            i.putExtra("package_name", ctx.packageName)
            i.putExtra("package_label", runCatching { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }.getOrDefault("JDCM"))
        }
        return i
    }

    /** The pages of [kinds] this phone really has (installed, exported), in order of preference. */
    fun available(ctx: Context, vararg kinds: OemPageKind): List<OemPage> = OemPages.of(oem)
        .filter { it.kind in kinds }
        .filter { p ->
            runCatching {
                val ri = ctx.packageManager.resolveActivity(intent(ctx, p), PackageManager.MATCH_DEFAULT_ONLY)
                ri?.activityInfo?.exported == true
            }.getOrDefault(false)
        }

    /** Whether there is any of the maker's pages to send the family to (else the app info page does). */
    fun hasPage(ctx: Context): Boolean = available(ctx, OemPageKind.AUTOSTART, OemPageKind.BATTERY).isNotEmpty()

    /**
     * Opens the first page of [kinds] that starts; else the app's info page (battery and, on most makers,
     * «Inicio automático» hang from it). [why] goes to the log.
     */
    fun open(ctx: Context, why: String, vararg kinds: OemPageKind) {
        for (p in available(ctx, *kinds)) {
            val i = intent(ctx, p)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                DiagLog.i("UI", "oem page opened ($why): ${p.pkg}/${p.cls ?: p.action}")
                return
            } catch (e: ActivityNotFoundException) {
                DiagLog.w("UI", "oem page missing ($why): ${p.pkg}/${p.cls ?: p.action}")
            } catch (e: SecurityException) {
                DiagLog.w("UI", "oem page refused ($why): ${p.pkg}/${p.cls ?: p.action}")
            }
        }
        DiagLog.i("UI", "oem page ($why): none for $oem; app info instead")
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { DiagLog.w("UI", "cannot open app info", it) }
    }

    /**
     * Xiaomi's «Inicio automático» for this app, as MIUI/HyperOS keeps it: an app-op read through
     * `android.miui.AppOpsUtils.getApplicationAutoStart` (0 = allowed, 1 = refused, as app-op modes).
     * Null on other makers, or when the phone cannot say: then nothing is claimed either way.
     */
    fun xiaomiAutostartMode(ctx: Context): Int? {
        if (oem != Oem.XIAOMI) return null
        return runCatching {
            val m = Class.forName("android.miui.AppOpsUtils").getDeclaredMethod("getApplicationAutoStart", Context::class.java, String::class.java)
            m.invoke(null, ctx, ctx.packageName) as Int
        }.getOrNull()
    }

    /** True on, false off, null unknown (not a Xiaomi, or it could not be read). */
    fun xiaomiAutostart(ctx: Context): Boolean? = when (xiaomiAutostartMode(ctx)) {
        0 -> true
        1 -> false
        else -> null
    }
}
