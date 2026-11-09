package dev.jdcm.update

import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** App wiring: call [init] in Application.onCreate (after DiagLog.init). */
object Updates {
    lateinit var prefs: UpdatePrefs
        private set
    lateinit var source: ReleaseSource
        private set
    lateinit var http: OkHttpClient
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(app: Application, prefs: UpdatePrefs, source: ReleaseSource, http: OkHttpClient) {
        this.prefs = prefs
        this.source = source
        this.http = http
        UpdateWorker.schedule(app)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Coming back: check if the last check is stale. Leaving: install what was staged while the app was open.
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { UpdateWorker.runIfStale(app, prefs.lastCheckMs()) }
            }
            override fun onStop(owner: LifecycleOwner) {
                scope.launch { Updater(app).installIfReady() }
            }
        })
    }
}

enum class UpdateCheckOutcome { UP_TO_DATE, INSTALL_STARTED, READY, TRANSIENT_FAILURE, INSTALL_FAILURE, NOT_ATTEMPTED }

/**
 * Checks the followed channel, downloads and verifies, installs. The updater is the one thing that cannot be fixed
 * remotely: every step fails closed, says why in the diagnostic log, and nothing escapes as an exception.
 * Integrity: exact size + SHA-256 from the manifest → the platform's own parse of the file (package, versionCode,
 * channel suffix) → Android's signature check at install. From znhr (private repo) and rwilco (public repo).
 */
class Updater(private val context: Context) {
    private val prefs get() = Updates.prefs
    private val source get() = Updates.source

    /** [manual]: the user tapped "Check now" — Wi-Fi-only and the post-decline hold do not apply. */
    suspend fun checkAndUpdate(manual: Boolean = false): UpdateCheckOutcome = try {
        attempt(manual)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        DiagLog.e(TAG, "update check failed unexpectedly", t)
        UpdateCenter.report(UpdateUiState.Failed(t.javaClass.simpleName))
        UpdateCheckOutcome.TRANSIENT_FAILURE
    }

    private suspend fun attempt(manual: Boolean): UpdateCheckOutcome {
        if (!manual && wifiOnlyBlocks()) {
            UpdateCenter.reportDeclined(UpdateUiState.WaitingForWifi) // never "up to date": nothing was checked
            return UpdateCheckOutcome.NOT_ATTEMPTED
        }
        // Four triggers (periodic, app start, boot, manual) overlapped and aborted each other's sessions (walcott).
        if (!mutex.tryLock()) {
            UpdateCenter.reportDeclined(UpdateUiState.AlreadyChecking)
            return UpdateCheckOutcome.NOT_ATTEMPTED
        }
        try {
            return doCheck(manual)
        } catch (e: CancellationException) {
            UpdateCenter.reportAbandoned()
            throw e
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun wifiOnlyBlocks(): Boolean {
        if (!prefs.wifiOnly()) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private suspend fun doCheck(manual: Boolean): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        prefs.setLastCheckMs(System.currentTimeMillis())
        val channel = prefs.channel()
        UpdateCenter.report(UpdateUiState.Checking)
        // Up to 3 tries, only while it fails: a clear answer (published, not published, token rejected) ends it.
        var result: ManifestResult = ManifestResult.Failed("not tried")
        var wait = 1_500L
        for (i in 0 until 3) {
            result = source.manifest(channel)
            if (result !is ManifestResult.Failed) break
            if (i < 2) { delay(wait); wait *= 3 }
        }
        val offer = when (val r = result) {
            is ManifestResult.Ok -> r.manifest
            ManifestResult.NeedsToken -> {
                UpdateCenter.report(UpdateUiState.NeedsToken)
                return@withContext UpdateCheckOutcome.NOT_ATTEMPTED
            }
            ManifestResult.TokenRejected -> {
                UpdateCenter.report(UpdateUiState.TokenRejected)
                runCatching { UpdateNotifications.notifyTokenProblem(context) }
                return@withContext UpdateCheckOutcome.NOT_ATTEMPTED
            }
            ManifestResult.NotPublished -> {
                UpdateCenter.channelOffers(null)
                UpdateCenter.report(UpdateUiState.NotPublished)
                return@withContext UpdateCheckOutcome.NOT_ATTEMPTED
            }
            is ManifestResult.RateLimited, is ManifestResult.Failed -> {
                DiagLog.w(TAG, "manifest for ${channel.file}: $r")
                UpdateCenter.report(UpdateUiState.Failed("fetch"))
                return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
            }
        }
        UpdateCenter.channelOffers(offer)
        runCatching { UpdateNotifications.cancelTokenProblem(context) }
        val installed = installedVersionCode()
        if (!offer.isNewerThan(installed)) {
            // A download the manifest no longer names (a withdrawn release) must never be installed later.
            discardDownload(context)
            UpdateCenter.report(UpdateUiState.UpToDate(installed))
            return@withContext UpdateCheckOutcome.UP_TO_DATE
        }
        val staged = stagedApk(offer, installed, channel)
        if (!manual && held(offer.versionCode)) {
            UpdateCenter.report(if (staged != null) UpdateUiState.ReadyToInstall(offer, declined = true) else UpdateUiState.Failed("install (held)"))
            return@withContext UpdateCheckOutcome.NOT_ATTEMPTED
        }
        if (staged != null && installPending()) {
            UpdateCenter.report(UpdateUiState.PendingConfirmation(offer))
            return@withContext UpdateCheckOutcome.INSTALL_STARTED
        }
        // Without "Install unknown apps" for this app nothing can be installed: say so before spending data.
        if (!context.packageManager.canRequestPackageInstalls()) {
            UpdateCenter.report(UpdateUiState.NeedsInstallPermission)
            runCatching { UpdateNotifications.notifyNeedsPermission(context) }
            return@withContext UpdateCheckOutcome.NOT_ATTEMPTED
        }
        val apk = staged ?: download(offer) ?: return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        val archive = archiveOf(apk)
        val rejection = rejectionReason(
            archive?.packageName, archive?.let(::versionCodeOf), archive?.versionName,
            context.packageName, installed, channel, manifestVersionCode = offer.versionCode,
        )
        if (rejection != null) {
            DiagLog.e(TAG, "refusing the downloaded file: $rejection")
            apk.delete()
            UpdateCenter.report(UpdateUiState.Failed("bad download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        // Installing replaces the running app: never under the user's finger. It installs on the next onStop.
        if (visible()) {
            UpdateCenter.report(UpdateUiState.ReadyToInstall(offer))
            return@withContext UpdateCheckOutcome.READY
        }
        val started = runCatching { install(apk, userAsked = false) }
            .onFailure { if (it is CancellationException) throw it; DiagLog.e(TAG, "install failed", it) }
        when {
            started.isFailure -> { UpdateCenter.report(UpdateUiState.Failed("install")); UpdateCheckOutcome.INSTALL_FAILURE }
            started.getOrNull() == false -> { UpdateCenter.report(UpdateUiState.ReadyToInstall(offer)); UpdateCheckOutcome.READY }
            else -> UpdateCheckOutcome.INSTALL_STARTED
        }
    }

    private suspend fun download(offer: ChannelManifest): File? {
        val free = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < offer.size * 2 + 20L * 1024 * 1024) {
            UpdateCenter.report(UpdateUiState.Failed("no space"))
            return null
        }
        val request = source.apkRequest(offer) ?: run {
            DiagLog.e(TAG, "refusing to download ${offer.versionName}: untrusted source '${offer.apk}' or no token")
            UpdateCenter.report(UpdateUiState.Failed("untrusted source"))
            return null
        }
        runCatching { UpdateNotifications.notifyFound(context, offer.versionCode, offer.versionName) }
        UpdateCenter.report(UpdateUiState.Downloading(offer))
        val client = apkClient(Updates.http)
        var shown = -1f
        var backoff = 1_500L
        for (i in 0 until 3) {
            try {
                return downloadVerified(client, request, offer, context.cacheDir, MAX_APK_BYTES) { written ->
                    val f = (written.toFloat() / offer.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    // Every 2 %: the bar moves, the screen does not recompose per 64 KB.
                    if (f - shown >= 0.02f || f >= 1f) { shown = f; UpdateCenter.report(UpdateUiState.Downloading(offer, f)) }
                }
            } catch (e: BadDownloadException) {
                DiagLog.e(TAG, "download of ${offer.versionName} rejected: ${e.message}")
                break
            } catch (e: IOException) {
                DiagLog.w(TAG, "download attempt ${i + 1} of ${offer.versionName} failed (part kept)", e)
            }
            if (i < 2) { delay(backoff); backoff *= 3 }
        }
        runCatching { UpdateNotifications.cancelFound(context) }
        UpdateCenter.report(UpdateUiState.Failed("download"))
        return null
    }

    /** Settings → "Install now": a staged, verified update, installed with the user present. */
    suspend fun installReady(): Boolean = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, APK_FILE)
        if (!f.exists()) { DiagLog.w(TAG, "install now: no staged file (Android may have cleared the cache)"); return@withContext false }
        if (!stillCurrent(f)) return@withContext false
        runCatching { install(f, userAsked = true) }
            .onFailure { if (it is CancellationException) throw it; DiagLog.e(TAG, "install now failed", it) }
            .getOrDefault(false)
    }

    /** The app went to the background: install what was staged meanwhile, re-verified. Cheap when there is nothing. */
    suspend fun installIfReady(): Boolean = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext false
        try {
            val f = File(context.cacheDir, APK_FILE)
            if (!f.exists() || installPending()) return@withContext false
            if (otherScreenOnTop()) {
                DiagLog.i(TAG, "a screen this app opened is on top: install at the next trip to the background")
                return@withContext false
            }
            UpdateCenter.channelOffer.value?.versionCode?.let { if (held(it)) return@withContext false }
            if (!stillCurrent(f)) return@withContext false
            val a = archiveOf(f) ?: run { f.delete(); return@withContext false }
            val rejection = rejectionReason(
                a.packageName, versionCodeOf(a), a.versionName, context.packageName, installedVersionCode(), prefs.channel(), null,
            )
            if (rejection != null) {
                DiagLog.w(TAG, "staged file not installed: $rejection")
                f.delete()
                return@withContext false
            }
            val r = runCatching { install(f, userAsked = false) }
                .onFailure { if (it is CancellationException) throw it; DiagLog.e(TAG, "background install failed", it) }
            if (r.isFailure) UpdateCenter.report(UpdateUiState.Failed("install"))
            r.getOrDefault(false)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun held(versionCode: Int): Boolean {
        val (code, until) = prefs.hold()
        return code == versionCode && System.currentTimeMillis() < until
    }

    /** Exactly what the last manifest seen in this process names; otherwise deleted (withdrawn/older release). */
    private fun stillCurrent(f: File): Boolean {
        val offer = UpdateCenter.channelOffer.value ?: return false // not seen yet: keep it, don't install it now
        val ok = offer.verifiable(MAX_APK_BYTES) &&
            stagedIsCurrent(archiveOf(f)?.let(::versionCodeOf), f.length(), sha256Of(f), offer)
        if (!ok) {
            DiagLog.w(TAG, "staged file no longer matches the manifest: discarded")
            f.delete()
        }
        return ok
    }

    private fun stagedApk(offer: ChannelManifest, installed: Int, channel: UpdateChannel): File? {
        val f = File(context.cacheDir, APK_FILE)
        if (!f.exists()) return null
        val a = archiveOf(f)
        val ok = a != null &&
            rejectionReason(a.packageName, versionCodeOf(a), a.versionName, context.packageName, installed, channel, offer.versionCode) == null &&
            stagedIsCurrent(versionCodeOf(a), f.length(), sha256Of(f), offer)
        if (!ok) f.delete() // a staged APK must give way to the release that replaced it (rwilco 08db37b)
        return if (ok) f else null
    }

    /** Another app's screen on top of this app's task (a picker, Android settings): installing would kill it mid-use. */
    private fun otherScreenOnTop(): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).appTasks.any { t ->
            t.taskInfo?.topActivity?.packageName.let { it != null && it != context.packageName }
        }
    }.getOrDefault(false)

    private suspend fun visible(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun installedVersionCode(): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    private fun archiveOf(apk: File): PackageInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()

    private fun versionCodeOf(p: PackageInfo): Int = p.longVersionCode.toInt()

    private fun installPending(): Boolean =
        runCatching { context.packageManager.packageInstaller.mySessions.any { it.isCommitted } }.getOrDefault(false)

    /**
     * Streams [apk] into a PackageInstaller session and commits it. Unless [userAsked], gives up (false) when the app
     * is on screen again by then. USER_ACTION_NOT_REQUIRED only works with UPDATE_PACKAGES_WITHOUT_USER_ACTION
     * declared in the manifest (rwilco set one without the other: every update still prompted).
     */
    private suspend fun install(apk: File, userAsked: Boolean): Boolean {
        val versionCode = archiveOf(apk)?.let(::versionCodeOf) ?: 0
        val installer = context.packageManager.packageInstaller
        runCatching { // our own leftovers only; their failure callbacks are ignored
            installer.mySessions.forEach { AbandonedSessions.remember(it.sessionId); installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                if (!userAsked && visible()) {
                    DiagLog.i(TAG, "the app came back to the screen: install postponed")
                    AbandonedSessions.remember(id)
                    session.abandon()
                    return false
                }
                val status = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                    .putExtra(InstallReceiver.EXTRA_VERSION_CODE, versionCode)
                    .putExtra(InstallReceiver.EXTRA_USER_ASKED, userAsked)
                // MUTABLE: the installer adds the status extras to this intent.
                val pending = PendingIntentCompat.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT, true)!!
                DiagLog.i(TAG, "installing $versionCode (session $id, userAsked=$userAsked)")
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
        return true
    }

    companion object {
        private const val TAG = "Update"
        /** Process-wide: Updater is created per call. */
        private val mutex = Mutex()
        /** Comfortably above the release APK size (a few MB). */
        const val MAX_APK_BYTES = 60L * 1024 * 1024

        fun discardDownload(context: Context) {
            runCatching {
                File(context.cacheDir, APK_FILE).delete()
                context.cacheDir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".part") }?.forEach { it.delete() }
            }
        }
    }
}
