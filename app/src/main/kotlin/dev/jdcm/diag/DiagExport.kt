package dev.jdcm.diag

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import dev.jdcm.R

/**
 * Puts the report on the clipboard; false when the system refused it. ClipData crosses Binder (~1 MB per
 * process, a String parcels as UTF-16): an oversized report throws TransactionTooLargeException or fails
 * silently on some OEMs — hence the 100 000-char budget and the guard. Marked sensitive (Android 13+): the
 * system's clipboard overlay does not preview it.
 */
fun Context.copyDiagnostics(text: String): Boolean = runCatching {
    val clip = ClipData.newPlainText(getString(R.string.diag_subject), text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}.onFailure { DiagLog.w("Diag", "copy refused (${text.length} chars)", it) }.isSuccess

/** Share sheet with the report as plain text (mail, chat apps, issue trackers). Call with an Activity context. */
fun Context.shareDiagnostics(text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_subject))
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(send, null)) }
        .onFailure { DiagLog.w("Diag", "share failed (${text.length} chars)", it) }
}
