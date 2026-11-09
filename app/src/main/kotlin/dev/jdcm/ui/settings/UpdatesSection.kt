package dev.jdcm.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.jdcm.BuildConfig
import dev.jdcm.R
import dev.jdcm.update.ChannelSwitch
import dev.jdcm.update.UpdateCenter
import dev.jdcm.update.UpdateChannel
import dev.jdcm.update.UpdateUiState
import dev.jdcm.update.UpdateWorker
import dev.jdcm.update.Updater
import dev.jdcm.update.Updates
import dev.jdcm.update.channelSwitch
import kotlinx.coroutines.launch

/**
 * Settings → Updates: what is installed, one honest status line, Check now / Install now, the "Install unknown
 * apps" fix, Wi-Fi only, and the channel choice with where this phone stands (rwilco `UpdateChannelCard`, znhr
 * `UpdatesSection`). Put it in the app's settings screen; style it with the app's own cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdatesSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by UpdateCenter.state.collectAsState()
    val offer by UpdateCenter.channelOffer.collectAsState()
    var channel by remember { mutableStateOf<UpdateChannel?>(null) }
    var wifiOnly by remember { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(true) }
    var confirmAlpha by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        channel = Updates.prefs.channel()
        wifiOnly = Updates.prefs.wifiOnly()
    }
    // Re-read on resume: the user comes back from the system's "Install unknown apps" screen.
    LifecycleResumeEffect(Unit) {
        canInstall = ctx.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    fun follow(c: UpdateChannel) {
        channel = c
        scope.launch {
            Updates.prefs.setChannel(c)
            UpdateWorker.checkNow(ctx) // answer now, not at the next periodic check hours later (rwilco)
        }
    }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(statusLine(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (state as? UpdateUiState.Downloading)?.progress?.takeIf { !it.isNaN() }?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { UpdateWorker.checkNow(ctx) }) { Text(stringResource(R.string.update_check_now)) }
            val staged = (state as? UpdateUiState.ReadyToInstall)?.target ?: (state as? UpdateUiState.PendingConfirmation)?.target
            if (staged != null) {
                Button(onClick = { scope.launch { Updater(ctx).installReady() } }) {
                    Text(stringResource(R.string.update_install_now, staged.versionName))
                }
            }
        }
        if (!canInstall) {
            Text(stringResource(R.string.update_permission_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }
            }) { Text(stringResource(R.string.update_permission_fix)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.update_wifi_only), Modifier.weight(1f))
            Switch(checked = wifiOnly, onCheckedChange = { v -> wifiOnly = v; scope.launch { Updates.prefs.setWifiOnly(v) } })
        }

        Text(stringResource(R.string.update_channel_title), style = MaterialTheme.typography.titleSmall)
        // Segmented, not a switch: neither channel is the absence of the other.
        val options = listOf(UpdateChannel.BETA to R.string.update_channel_beta, UpdateChannel.ALPHA to R.string.update_channel_alpha)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (c, label) ->
                SegmentedButton(
                    selected = channel == c,
                    onClick = { if (c == UpdateChannel.ALPHA && channel != c) confirmAlpha = true else if (channel != c) follow(c) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        // Where this phone stands, in one line, ALWAYS: a choice that changes nothing visible looks like a dead control.
        val standing = when {
            state is UpdateUiState.NotPublished -> stringResource(R.string.update_standing_empty) to false
            offer == null -> null
            else -> when (val s = channelSwitch(BuildConfig.VERSION_CODE, offer!!.versionCode, offer!!.versionName)) {
                is ChannelSwitch.Immediate -> stringResource(R.string.update_standing_coming, s.versionName) to false
                ChannelSwitch.AlreadyOnIt -> stringResource(R.string.update_standing_current) to false
                is ChannelSwitch.WaitsForNextRelease -> stringResource(R.string.update_standing_waiting, s.channelVersionName) to true
                ChannelSwitch.Unknown -> stringResource(R.string.update_standing_empty) to false
            }
        }
        standing?.let { (text, alarming) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmAlpha) {
        AlertDialog(
            onDismissRequest = { confirmAlpha = false },
            title = { Text(stringResource(R.string.update_alpha_warn_title)) },
            // The asymmetry, said BEFORE the choice: going back to beta waits for beta to pass this phone.
            text = { Text(stringResource(R.string.update_alpha_warn_body)) },
            confirmButton = { TextButton(onClick = { confirmAlpha = false; follow(UpdateChannel.ALPHA) }) { Text(stringResource(R.string.update_alpha_warn_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmAlpha = false }) { Text(stringResource(R.string.update_cancel)) } },
        )
    }
}

@Composable
private fun statusLine(s: UpdateUiState): String = when (s) {
    UpdateUiState.Idle -> pluralStringResource(R.plurals.update_state_idle, UpdateWorker.PERIOD_HOURS.toInt(), UpdateWorker.PERIOD_HOURS.toInt())
    UpdateUiState.Checking, UpdateUiState.AlreadyChecking -> stringResource(R.string.update_state_checking)
    UpdateUiState.NeedsToken -> stringResource(R.string.update_state_needs_token)
    UpdateUiState.TokenRejected -> stringResource(R.string.update_token_text)
    UpdateUiState.NotPublished -> stringResource(R.string.update_standing_empty)
    UpdateUiState.WaitingForWifi -> stringResource(R.string.update_state_wifi)
    UpdateUiState.NeedsInstallPermission -> stringResource(R.string.update_permission_text)
    is UpdateUiState.UpToDate -> stringResource(R.string.update_state_up_to_date)
    is UpdateUiState.Downloading -> stringResource(R.string.update_state_downloading, s.target.versionName)
    is UpdateUiState.ReadyToInstall ->
        if (s.declined) stringResource(R.string.update_state_declined, s.target.versionName)
        else stringResource(R.string.update_state_ready, s.target.versionName)
    is UpdateUiState.PendingConfirmation -> stringResource(R.string.update_ready_text)
    is UpdateUiState.Failed -> stringResource(R.string.update_state_failed, s.step)
}
