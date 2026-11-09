package dev.jdcm.ui.match

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jdcm.R
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Team
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.GroupRef
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.MatchArgs
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular
import java.time.Duration
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchSheet(args: MatchArgs, onDismiss: () -> Unit, onTeam: (GroupRef, Int) -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val actions = LocalActions.current
    val m = args.match
    val extra = JdcmColors.extra
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                listOfNotNull(stringResource(R.string.round_n, m.roundNumber), args.title.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, color = extra.muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
                BigTeam(m.home, m, args.highlightTeamId, Modifier.weight(1f)) { t -> args.ref?.let { onTeam(it, t.id) } }
                Column(Modifier.width(110.dp).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m.hasResult) {
                        Text("${m.homeScore} - ${m.awayScore}", style = MaterialTheme.typography.displaySmall.merge(Tabular), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.status_final), style = MaterialTheme.typography.labelLarge, color = extra.muted)
                    } else {
                        Text(fmt.time(m), style = MaterialTheme.typography.displaySmall.merge(Tabular), fontWeight = FontWeight.Bold,
                            color = if (m.modified) extra.modified else MaterialTheme.colorScheme.onSurface)
                        m.day?.let { Text(fmt.day(it), style = MaterialTheme.typography.labelLarge, color = extra.muted) }
                    }
                    fmt.statusLabel(m)?.takeIf { !m.hasResult }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = extra.danger, fontWeight = FontWeight.Bold)
                    }
                }
                BigTeam(m.away, m, args.highlightTeamId, Modifier.weight(1f)) { t -> args.ref?.let { onTeam(it, t.id) } }
            }
            if (m.partials.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    m.partials.joinToString("   "), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = extra.muted,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            val playable = !m.hasResult && m.status != MatchStatus.UNSCHEDULED
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playable && m.venue != null) {
                    ActionButton(Icons.Outlined.Directions, stringResource(R.string.action_directions), Modifier.weight(1f)) { actions.directions(m) }
                }
                if (playable && m.day != null) {
                    ActionButton(Icons.Outlined.EditCalendar, stringResource(R.string.action_calendar), Modifier.weight(1f)) { addToCalendar(ctx, m, fmt, args.title) }
                }
                ActionButton(Icons.Outlined.Share, stringResource(R.string.action_share), Modifier.weight(1f)) { share(ctx, m, fmt) }
            }
            Spacer(Modifier.height(12.dp))
            Hairline()
            InfoRow(Icons.Outlined.Event, stringResource(R.string.detail_when), fmt.whenLine(m))
            InfoRow(Icons.Outlined.Place, stringResource(R.string.detail_where), fmt.venueLine(m.venue))
            durationMinutes(m)?.let { InfoRow(Icons.Outlined.Timer, stringResource(R.string.detail_duration), "$it min") }
            if (m.modified) {
                InfoRow(Icons.Outlined.Info, stringResource(R.string.status_modified), m.modifiedReason.ifBlank { stringResource(R.string.modified_explained) }, extra.modified)
            }
            if (m.status == MatchStatus.UNSCHEDULED || m.status == MatchStatus.POSTPONED) {
                InfoRow(Icons.Outlined.Info, fmt.statusLabel(m).orEmpty(), stringResource(R.string.unscheduled_explained), extra.danger)
            }
            if (args.ref != null) {
                Hairline()
                listOfNotNull(m.home, m.away).forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onTeam(args.ref, t.id) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Crest(t, 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.see_team, t.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = extra.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigTeam(team: Team?, m: Match, highlight: Int, modifier: Modifier, onClick: (Team) -> Unit) {
    val won = m.hasResult && team != null && Standings.outcome(m, team.id) == Outcome.WIN
    Column(modifier.clickable(enabled = team != null) { team?.let(onClick) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(team, 60.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            team?.name ?: stringResource(R.string.rests), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            fontWeight = if (won || team?.id == highlight) FontWeight.Bold else FontWeight.Normal, maxLines = 3,
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 52.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, tint: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint ?: JdcmColors.extra.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint ?: JdcmColors.extra.muted)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun addToCalendar(ctx: Context, m: Match, fmt: Format, who: String) {
    val day = m.day ?: return
    val title = listOfNotNull(who.ifBlank { null }, "${m.home?.name} - ${m.away?.name}").joinToString(": ")
    val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, fmt.venueLine(m.venue))
        .putExtra(CalendarContract.Events.DESCRIPTION, "J${m.roundNumber} · JDCM")
    if (m.timeKnown && m.start != null) {
        val start = ZonedDateTime.of(m.start, MADRID)
        val minutes = (durationMinutes(m) ?: 75).coerceIn(30, 180).toLong()
        i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plus(Duration.ofMinutes(minutes)).toInstant().toEpochMilli())
    } else {
        val start = day.atStartOfDay(MADRID)
        i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusDays(1).toInstant().toEpochMilli())
    }
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        DiagLog.w("UI", "no calendar app")
    }
}

/** The site's "01:10:00" as 70; null when absent or zero. */
private fun durationMinutes(m: Match): Int? = runCatching {
    val p = m.duration.split(":")
    p[0].toInt() * 60 + p[1].toInt()
}.getOrNull()?.takeIf { it > 0 }

private fun share(ctx: Context, m: Match, fmt: Format) {
    val lines = buildList {
        add(fmt.whenLine(m))
        if (m.hasResult) add("${m.home?.name} ${m.homeScore} - ${m.awayScore} ${m.away?.name}")
        else add("${m.home?.name} - ${m.away?.name}")
        if (!m.hasResult && m.venue != null) {
            add(fmt.venueLine(m.venue))
            m.venue?.takeIf { it.hasPosition }?.let { add("https://www.google.com/maps/search/?api=1&query=${it.lat},${it.lng}") }
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
}
