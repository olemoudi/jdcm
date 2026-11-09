package dev.jdcm.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.jdcm.AppContainer
import dev.jdcm.BuildConfig
import dev.jdcm.R
import dev.jdcm.data.EcmApi
import dev.jdcm.data.Kid
import dev.jdcm.data.ThemeMode
import dev.jdcm.diag.DiagLog
import dev.jdcm.notify.Reminders
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.DoorRow
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.explore.BackBar
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.kidColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    c: AppContainer,
    onBack: () -> Unit,
    onAddKid: () -> Unit,
    onEditKid: (String) -> Unit,
    onChangelog: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val ctx = LocalContext.current
    val s by c.settings.state.collectAsState()
    val kids by c.kids.kids.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    var deleting by remember { mutableStateOf<Kid?>(null) }
    var canPost by remember { mutableStateOf(c.notifier.canPost()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        c.kids.load()
        // Back from the phone's notification settings: read the permission again.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { canPost = c.notifier.canPost() }
    }
    fun changed(f: (dev.jdcm.data.SettingsState) -> dev.jdcm.data.SettingsState) {
        c.settings.update(f)
        Reminders.rearm(ctx)
    }

    Scaffold(topBar = { BackBar(stringResource(R.string.settings), onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { Title(R.string.kids) }
            item {
                FlatCard {
                    kids.orEmpty().forEachIndexed { i, k ->
                        if (i > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onEditKid(k.id) }.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(kidColor(k.color)))
                            Spacer(Modifier.width(12.dp))
                            Crest(dev.jdcm.core.model.Team(k.teamId, k.teamName, k.crestPath), 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(k.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${k.competitionName.lowercase().replaceFirstChar { it.titlecase() }} · ${k.groupName} · ${k.teamName}",
                                    style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted, maxLines = 2,
                                )
                            }
                            IconButton(onClick = { deleting = k }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete_kid, k.name)) }
                        }
                    }
                    if (!kids.isNullOrEmpty()) Hairline()
                    DoorRow(stringResource(R.string.add_kid), null, Icons.Filled.Add, MaterialTheme.colorScheme.primary, onClick = onAddKid)
                }
            }

            item { Title(R.string.notifications) }
            item {
                FlatCard {
                    if (!canPost) {
                        DoorRow(
                            stringResource(R.string.notifications_blocked), stringResource(R.string.notifications_blocked_hint),
                            Icons.Outlined.NotificationsOff, JdcmColors.extra.modified,
                            onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                                }
                            },
                        )
                        Hairline()
                    }
                    SwitchRow(stringResource(R.string.notify_changes), stringResource(R.string.notify_changes_hint), s.notifyChanges) { v -> changed { it.copy(notifyChanges = v) } }
                    Hairline()
                    SwitchRow(stringResource(R.string.notify_results), stringResource(R.string.notify_results_hint), s.notifyResults) { v -> changed { it.copy(notifyResults = v) } }
                    Hairline()
                    SwitchRow(stringResource(R.string.notify_reminder), stringResource(R.string.notify_reminder_hint, s.reminderHour), s.notifyReminder) { v -> changed { it.copy(notifyReminder = v) } }
                    if (s.notifyReminder) {
                        LazyRow(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(listOf(18, 19, 20, 21, 22)) { h ->
                                FilterChip(selected = s.reminderHour == h, onClick = { changed { it.copy(reminderHour = h) } }, label = { Text("$h:00") })
                            }
                        }
                    }
                }
            }

            item { Title(R.string.appearance) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    val options = listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SYSTEM to R.string.theme_system)
                    options.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = s.theme == mode,
                            onClick = { c.settings.update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { FlatCard { UpdatesSection(Modifier.padding(vertical = 8.dp)) } }

            item { Title(R.string.about) }
            item {
                FlatCard {
                    DoorRow(stringResource(R.string.whats_new), null, Icons.Outlined.NewReleases, onClick = onChangelog)
                    Hairline()
                    DoorRow(stringResource(R.string.open_web), stringResource(R.string.data_source), Icons.AutoMirrored.Filled.OpenInNew, onClick = { actions.openUrl(EcmApi.CALENDAR_PAGE) })
                    Hairline()
                    DoorRow(stringResource(R.string.diagnostics), stringResource(R.string.diag_row_hint), Icons.Outlined.BugReport, onClick = onDiagnostics)
                }
                Text(
                    stringResource(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                    style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }

    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_kid, k.name)) },
            text = { Text(stringResource(R.string.delete_kid_text)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        c.kids.remove(k.id)
                        DiagLog.i("Kids", "removed #${k.id.take(8)}")
                        Reminders.rearm(ctx)
                    }
                    deleting = null
                }) { Text(stringResource(R.string.delete), color = JdcmColors.extra.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Title(res: Int) {
    Text(
        stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
