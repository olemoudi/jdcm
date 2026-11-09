package dev.jdcm.update

import android.content.Context
import androidx.core.content.edit
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object AlreadyChecking : UpdateUiState
    data object NeedsToken : UpdateUiState
    data object TokenRejected : UpdateUiState
    data object NotPublished : UpdateUiState
    data object WaitingForWifi : UpdateUiState
    data object NeedsInstallPermission : UpdateUiState
    data class UpToDate(val installedVersionCode: Int) : UpdateUiState
    /** [progress] 0…1, NaN until the first bytes: every long task shows its %. */
    data class Downloading(val target: ChannelManifest, val progress: Float = Float.NaN) : UpdateUiState
    /** Verified and staged. Installs when the app goes to the background; [declined]: the user cancelled the dialog. */
    data class ReadyToInstall(val target: ChannelManifest, val declined: Boolean = false) : UpdateUiState
    data class PendingConfirmation(val target: ChannelManifest?) : UpdateUiState
    data class Failed(val step: String) : UpdateUiState
}

/** Process-wide update state for the UI (malachi/znhr). Every transition goes to the diagnostic log. */
object UpdateCenter {
    private val mutable = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutable
    private val offered = MutableStateFlow<ChannelManifest?>(null)
    /** What the followed channel offers, as last read (the channel card's "where this phone stands"). */
    val channelOffer: StateFlow<ChannelManifest?> = offered

    internal fun report(s: UpdateUiState) {
        val old = mutable.value
        if (s != old && !(s is UpdateUiState.Downloading && old is UpdateUiState.Downloading)) DiagLog.i("Update", describe(s))
        mutable.value = s
    }

    /** A check that declined to run must not hide a download or a waiting confirmation. */
    internal fun reportDeclined(s: UpdateUiState) {
        when (mutable.value) {
            is UpdateUiState.Downloading, is UpdateUiState.PendingConfirmation, is UpdateUiState.ReadyToInstall -> Unit
            else -> report(s)
        }
    }

    internal fun reportAbandoned() {
        when (mutable.value) {
            UpdateUiState.Checking, is UpdateUiState.Downloading -> mutable.value = UpdateUiState.Idle
            else -> Unit
        }
    }

    internal fun channelOffers(m: ChannelManifest?) { offered.value = m }

    private fun describe(s: UpdateUiState): String = when (s) {
        is UpdateUiState.UpToDate -> "up to date (${s.installedVersionCode})"
        is UpdateUiState.Downloading -> "downloading ${s.target.versionName}"
        is UpdateUiState.ReadyToInstall -> "staged ${s.target.versionName}${if (s.declined) " (declined)" else ""}; installs in the background"
        is UpdateUiState.PendingConfirmation -> "waiting for the user to confirm ${s.target?.versionName.orEmpty()}"
        is UpdateUiState.Failed -> "FAILED at ${s.step}"
        else -> s.javaClass.simpleName
    }
}

/** Sessions we abandoned ourselves: their failure callbacks are not install failures (znhr). */
internal object AbandonedSessions {
    private val ids = ArrayDeque<Int>()
    fun remember(sessionId: Int) = synchronized(ids) {
        if (sessionId in ids) return@synchronized
        ids.addLast(sessionId)
        while (ids.size > 8) ids.removeFirst()
    }
    fun claim(sessionId: Int): Boolean = synchronized(ids) { ids.remove(sessionId) }
}

/** The update settings and bookkeeping. Implement on the app's DataStore if it has one; this default needs nothing. */
interface UpdatePrefs {
    suspend fun channel(): UpdateChannel
    suspend fun setChannel(c: UpdateChannel)
    suspend fun wifiOnly(): Boolean
    suspend fun setWifiOnly(v: Boolean)
    /** A version left alone after a declined/failed install, and until when. */
    suspend fun hold(): Pair<Int, Long>
    suspend fun setHold(versionCode: Int, untilMs: Long)
    suspend fun lastCheckMs(): Long
    suspend fun setLastCheckMs(ms: Long)
}

/** SharedPreferences-backed [UpdatePrefs]. [defaultChannel]: BETA for everybody; ALPHA for an owner-only app. */
class SharedPrefsUpdatePrefs(context: Context, private val defaultChannel: UpdateChannel = UpdateChannel.BETA) : UpdatePrefs {
    private val p = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
    override suspend fun channel() = runCatching { UpdateChannel.valueOf(p.getString("channel", null)!!) }.getOrDefault(defaultChannel)
    override suspend fun setChannel(c: UpdateChannel) = p.edit { putString("channel", c.name) }
    override suspend fun wifiOnly() = p.getBoolean("wifiOnly", false)
    override suspend fun setWifiOnly(v: Boolean) = p.edit { putBoolean("wifiOnly", v) }
    override suspend fun hold() = p.getInt("holdCode", 0) to p.getLong("holdUntil", 0)
    override suspend fun setHold(versionCode: Int, untilMs: Long) = p.edit { putInt("holdCode", versionCode); putLong("holdUntil", untilMs) }
    override suspend fun lastCheckMs() = p.getLong("lastCheck", 0)
    override suspend fun setLastCheckMs(ms: Long) = p.edit { putLong("lastCheck", ms) }
}
