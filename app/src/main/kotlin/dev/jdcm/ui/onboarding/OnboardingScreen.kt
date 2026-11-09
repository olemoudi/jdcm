package dev.jdcm.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.theme.JdcmColors
import kotlinx.coroutines.delay

private enum class Step { WELCOME, NOTIFICATIONS, UPDATES, BATTERY, DONE }

/**
 * The first-run guide: what the app is, then each permission it needs with the reason in one
 * sentence. The install permission gets the most help (a picture of the switch to turn on and three
 * steps), because without it the self-updater can never install anything and the friend's phone
 * would stay on the first build forever. Every step can be skipped; Home keeps reminding about the
 * install permission (see [InstallPermissionBanner]).
 */
@Composable
fun OnboardingScreen(c: AppContainer, hasKids: Boolean, onFinish: (addKid: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    val notif by rememberOnResume(Perms::notifications)
    val install by rememberOnResume(Perms::canInstall)
    val battery by rememberOnResume(Perms::batteryUnrestricted)

    fun go(next: Step) {
        DiagLog.i("UI", "onboarding ${step.name.lowercase()} → ${next.name.lowercase()} notif=$notif install=$install battery=$battery")
        step = next
    }
    BackHandler(enabled = step != Step.WELCOME) { go(Step.entries[step.ordinal - 1]) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Dots(step.ordinal, Step.entries.size)
            AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f), label = "onboarding") { s ->
                when (s) {
                    Step.WELCOME -> Page(
                        icon = Icons.Outlined.SportsSoccer,
                        title = stringResource(R.string.ob_welcome_title),
                        text = stringResource(R.string.ob_welcome_text),
                        primary = stringResource(R.string.ob_start) to { go(Step.NOTIFICATIONS) },
                    )
                    Step.NOTIFICATIONS -> NotificationsPage(notif, onNext = { go(Step.UPDATES) })
                    Step.UPDATES -> Page(
                        icon = Icons.Filled.SystemUpdate,
                        title = stringResource(R.string.ob_updates_title),
                        text = stringResource(R.string.ob_updates_text),
                        done = if (install) stringResource(R.string.ob_updates_done) else null,
                        primary = if (install) stringResource(R.string.next) to { go(Step.BATTERY) }
                        else stringResource(R.string.ob_updates_open) to { Perms.openInstallSettings(ctx) },
                        secondary = if (install) null else stringResource(R.string.ob_not_now) to { go(Step.BATTERY) },
                    ) { InstallGuide(granted = install) }
                    Step.BATTERY -> Page(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = stringResource(R.string.ob_battery_title),
                        text = stringResource(R.string.ob_battery_text),
                        done = if (battery) stringResource(R.string.ob_battery_done) else null,
                        primary = if (battery) stringResource(R.string.next) to { go(Step.DONE) }
                        else stringResource(R.string.ob_battery_allow) to { Perms.askBatteryUnrestricted(ctx) },
                        secondary = if (battery) null else stringResource(R.string.ob_not_now) to { go(Step.DONE) },
                    )
                    Step.DONE -> Page(
                        icon = Icons.Filled.CheckCircle,
                        title = stringResource(R.string.ob_done_title),
                        text = stringResource(if (hasKids) R.string.ob_done_text_has_kids else R.string.ob_done_text),
                        primary = stringResource(if (hasKids) R.string.ob_go_home else R.string.ob_add_first) to {
                            DiagLog.i("UI", "onboarding finished notif=$notif install=$install battery=$battery")
                            onFinish(!hasKids)
                        },
                    ) {
                        if (!install) {
                            Spacer(Modifier.height(16.dp))
                            Warning(stringResource(R.string.ob_updates_warning))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsPage(granted: Boolean, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        DiagLog.i("UI", "notifications permission granted=$ok")
        asked++
    }
    // Android shows its dialog at most twice; after a second "no", only the settings page can turn it on.
    val viaSettings = Build.VERSION.SDK_INT < 33 || asked >= 2
    Page(
        icon = Icons.Filled.NotificationsActive,
        title = stringResource(R.string.ob_notif_title),
        text = stringResource(R.string.ob_notif_text),
        done = if (granted) stringResource(R.string.ob_notif_done) else null,
        primary = when {
            granted -> stringResource(R.string.next) to onNext
            viaSettings -> stringResource(R.string.ob_notif_settings) to { Perms.openNotificationSettings(ctx) }
            else -> stringResource(R.string.ob_notif_allow) to { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        },
        secondary = if (granted) null else stringResource(R.string.ob_not_now) to onNext,
    )
}

@Composable
private fun Page(
    icon: ImageVector,
    title: String,
    text: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    done: String? = null,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = JdcmColors.extra.muted, textAlign = TextAlign.Center)
            if (done != null) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = JdcmColors.extra.win)
                    Spacer(Modifier.width(8.dp))
                    Text(done, style = MaterialTheme.typography.titleMedium, color = JdcmColors.extra.win)
                }
            }
            extra()
            Spacer(Modifier.height(16.dp))
        }
        Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(primary.first, style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
            if (secondary != null) TextButton(onClick = secondary.second) { Text(secondary.first) }
        }
    }
}

/**
 * How to give the install permission, drawn: the row Android shows with its switch moving on, and
 * the three steps. Used by the first-run guide and by Home's reminder.
 */
@Composable
fun InstallGuide(granted: Boolean) {
    var on by remember { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        if (granted) { on = true; return@LaunchedEffect }
        while (true) { delay(1300); on = !on }
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        val border by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else JdcmColors.extra.line, label = "mock")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.5.dp, border, RoundedCornerShape(14.dp)).padding(16.dp).clearAndSetSemantics { },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFF2643B)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("JDCM", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ob_updates_mock_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null)
            }
        }
        if (!granted) {
            Spacer(Modifier.height(16.dp))
            listOf(R.string.ob_updates_step1, R.string.ob_updates_step2, R.string.ob_updates_step3).forEachIndexed { i, res ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            Warning(stringResource(R.string.ob_updates_warning))
        } else {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ob_updates_first_note), style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(JdcmColors.extra.modified.copy(alpha = 0.14f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, null, tint = JdcmColors.extra.modified)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Dots(current: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
        repeat(total) { i ->
            Box(
                Modifier.padding(horizontal = 4.dp).size(width = if (i == current) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                    .background(if (i <= current) MaterialTheme.colorScheme.primary else JdcmColors.extra.line),
            )
        }
    }
}
