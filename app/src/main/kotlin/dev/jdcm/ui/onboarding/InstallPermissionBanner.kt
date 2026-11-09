package dev.jdcm.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.theme.JdcmColors

/** How long "Más tarde" hides the reminder: often enough to be fixed, rare enough not to nag. */
const val INSTALL_BANNER_SNOOZE_MS = 3L * 24 * 3600 * 1000

/**
 * Home's reminder while the app may not install its own updates: says what is lost, and opens the
 * same drawn guide the first-run screens use. "Más tarde" hides it for three days. It goes away by
 * itself once the permission is on (read again every time the app comes back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallPermissionBanner(c: AppContainer) {
    val ctx = LocalContext.current
    val granted by rememberOnResume(Perms::canInstall)
    var snoozedUntil by remember { mutableLongStateOf(c.settings.installBannerSnoozedUntil) }
    var guide by remember { mutableStateOf(false) }
    val show = !granted && System.currentTimeMillis() >= snoozedUntil
    LaunchedEffect(show) { if (show) DiagLog.i("UI", "install-permission banner shown") }
    LaunchedEffect(granted) {
        if (granted && guide) {
            DiagLog.i("UI", "install permission granted from the banner")
            guide = false
        }
    }

    AnimatedVisibility(show) {
        val extra = JdcmColors.extra
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .background(extra.modified.copy(alpha = 0.13f))
                .border(1.dp, extra.modified.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.SystemUpdate, null, tint = extra.modified)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.banner_updates_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.banner_updates_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    snoozedUntil = System.currentTimeMillis() + INSTALL_BANNER_SNOOZE_MS
                    c.settings.installBannerSnoozedUntil = snoozedUntil
                    DiagLog.i("UI", "install-permission banner snoozed 3 d")
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.banner_later)) }
                TextButton(onClick = { guide = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.banner_updates_fix), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (guide) {
        ModalBottomSheet(onDismissRequest = { guide = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Text(stringResource(R.string.ob_updates_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.ob_updates_text), style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted)
                InstallGuide(granted = granted)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { Perms.openInstallSettings(ctx) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.ob_updates_open))
                }
            }
        }
    }
}
