package dev.jdcm.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.jdcm.R
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.launch

/** Terminal status of an install session: needs the user, success, or failure (znhr + rwilco). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (AbandonedSessions.claim(sessionId)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        DiagLog.i("Install", "session $sessionId status $status ${message.orEmpty().take(200)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateCenter.report(UpdateUiState.PendingConfirmation(UpdateCenter.channelOffer.value))
                // Always a notification carrying the system's dialog: background activity starts are blocked since
                // Android 10, and a dialog popping over whatever the user is doing is hostile (rwilco 0.68.0).
                runCatching { UpdateNotifications.notifyConfirmationNeeded(context, Intent(confirm)) }
                if (intent.getBooleanExtra(EXTRA_USER_ASKED, false)) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Idle)
                Updater.discardDownload(context)
            }
            else -> {
                UpdateNotifications.cancel(context)
                val setback = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> InstallSetback.DECLINED
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallSetback.SIGNATURE_CONFLICT
                    PackageInstaller.STATUS_FAILURE_STORAGE -> InstallSetback.STORAGE
                    else -> InstallSetback.OTHER
                }
                val offer = UpdateCenter.channelOffer.value
                if (keepsApkAfter(setback) && offer != null) {
                    // Declined: not an error. The verified file stays for a one-tap "Install now" (walcott 0.84.0).
                    UpdateCenter.report(UpdateUiState.ReadyToInstall(offer, declined = true))
                } else {
                    UpdateCenter.report(UpdateUiState.Failed("install status $status"))
                    Updater.discardDownload(context)
                    val why = when (setback) {
                        InstallSetback.STORAGE -> context.getString(R.string.update_install_storage)
                        InstallSetback.SIGNATURE_CONFLICT -> context.getString(R.string.update_install_conflict)
                        else -> context.getString(R.string.update_install_other, message.orEmpty().take(80).ifBlank { "$status" })
                    }
                    runCatching { UpdateNotifications.notifyInstallFailed(context, why) }
                }
                val code = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
                if (code > 0) {
                    val pending = goAsync()
                    Updates.scope.launch {
                        try {
                            Updates.prefs.setHold(code, holdUntil(setback, System.currentTimeMillis()))
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION = "dev.jdcm.update.INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "dev.jdcm.update.VERSION_CODE"
        const val EXTRA_USER_ASKED = "dev.jdcm.update.USER_ASKED"
    }
}

/** After an update replaced the app (protected broadcast, non-exported receiver is fine): clean up. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DiagLog.i("Update", "app replaced; now running the new version")
        UpdateNotifications.cancel(context)
        Updater.discardDownload(context)
    }
}
