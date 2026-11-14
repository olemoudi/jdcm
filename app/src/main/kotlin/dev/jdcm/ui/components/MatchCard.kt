package dev.jdcm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdcm.R
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.MatchStatus
import dev.jdcm.core.model.Team
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular
import java.time.LocalDateTime

/**
 * One match, the way Marca draws it: a status line, the two teams on their own rows with the
 * score at the right, and a side button for the one action that matters (how to get there). The
 * followed team is bold with its child's colour; the winner is bold once there is a result.
 */
@Composable
fun MatchCard(
    match: Match,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightTeamId: Int = 0,
    accent: Color? = null,
    /** The child's name, shown as a tag in their colour next to their team (a bare dot said nothing). */
    highlightLabel: String? = null,
    compact: Boolean = false,
    showDate: Boolean = true,
    onDirections: ((Match) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val extra = JdcmColors.extra
    if (match.isBye) {
        ByeRow(match.restingTeam, highlightTeamId, accent, highlightLabel, modifier)
        return
    }
    val result = match.hasResult
    val description = remember(match, result) {
        buildString {
            append(match.home?.name).append(if (result) " ${match.homeScore}" else "").append(", ")
            append(match.away?.name).append(if (result) " ${match.awayScore}" else "").append(". ")
            append(fmt.whenLine(match)).append(". ")
            fmt.statusLabel(match)?.let { append(it).append(". ") }
            if (!compact) append(fmt.venueLine(match.venue))
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        if (accent != null && highlightTeamId != 0 && match.involves(highlightTeamId)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 8.dp else 12.dp)) {
            StatusLine(match, fmt, showDate)
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    TeamLine(match.home, match, highlightTeamId, accent, highlightLabel, compact)
                    TeamLine(match.away, match, highlightTeamId, accent, highlightLabel, compact)
                }
                Spacer(Modifier.width(8.dp))
                RightColumn(match, fmt, compact)
            }
            if (!compact && match.venue != null && !result && match.status != MatchStatus.UNSCHEDULED) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = extra.muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(fmt.venueLine(match.venue), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val canGo = onDirections != null && match.venue?.hasPosition == true && !result && match.status != MatchStatus.UNSCHEDULED
        if (canGo && !compact) {
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(extra.line))
            Box(Modifier.widthIn(min = 56.dp).fillMaxHeight().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                IconButton(onClick = { onDirections?.invoke(match) }) {
                    Icon(Icons.Outlined.Directions, stringResource(R.string.action_directions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(match: Match, fmt: Format, showDate: Boolean) {
    val extra = JdcmColors.extra
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val now = LocalDateTime.now(MADRID)
        val live = match.timeKnown && match.start != null && !match.hasResult &&
            !now.isBefore(match.start) && now.isBefore(match.start!!.plusMinutes(100))
        when {
            match.status == MatchStatus.UNSCHEDULED -> Label(stringResource(R.string.status_unscheduled).uppercase(), extra.danger)
            match.status == MatchStatus.POSTPONED -> Label(stringResource(R.string.status_postponed).uppercase(), extra.danger)
            match.status == MatchStatus.NOT_HELD -> Label(stringResource(R.string.status_not_held).uppercase(), extra.danger)
            // Played "with incidents": the site leaves the score empty until it is ruled on.
            match.status == MatchStatus.PLAYED && !match.hasResult -> Label(stringResource(R.string.status_no_score).uppercase(), extra.muted)
            match.hasResult -> Text(
                stringResource(R.string.status_final),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.Underline,
            )
            live -> Label(stringResource(R.string.status_live).uppercase(), MaterialTheme.colorScheme.primary)
        }
        if (match.modified && match.status != MatchStatus.UNSCHEDULED && !match.hasResult) {
            Label(stringResource(R.string.status_modified).uppercase(), extra.modified)
        }
        if (showDate || !match.timeKnown) {
            val d = match.day
            Text(
                if (d != null) fmt.day(d) else stringResource(R.string.no_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp), color = color)
}

@Composable
private fun TeamLine(team: Team?, match: Match, highlightTeamId: Int, accent: Color?, label: String?, compact: Boolean) {
    val winner = match.hasResult && team != null && dev.jdcm.core.standings.Standings.outcome(match, team.id) == dev.jdcm.core.standings.Outcome.WIN
    val mine = team != null && team.id == highlightTeamId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crest(team, size = if (compact) 22.dp else 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            team?.name.orEmpty(),
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (winner || mine) FontWeight.Bold else FontWeight.Normal,
            color = if (mine && accent != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (mine && accent != null && !label.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            KidTag(label, accent)
        }
    }
}

@Composable
private fun RightColumn(match: Match, fmt: Format, compact: Boolean) {
    val extra = JdcmColors.extra
    if (match.hasResult) {
        val homeWins = dev.jdcm.core.standings.Standings.outcome(match, match.home?.id ?: 0) == dev.jdcm.core.standings.Outcome.WIN
        val awayWins = dev.jdcm.core.standings.Standings.outcome(match, match.away?.id ?: 0) == dev.jdcm.core.standings.Outcome.WIN
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Score(match.homeScore, homeWins, compact)
            Score(match.awayScore, awayWins, compact)
        }
    } else {
        val tbc = !match.timeKnown
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (tbc) "--:--" else fmt.time(match),
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).merge(Tabular),
                color = when {
                    match.status == MatchStatus.UNSCHEDULED || match.status == MatchStatus.POSTPONED -> extra.muted
                    match.modified -> extra.modified
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (match.status == MatchStatus.UNSCHEDULED) TextDecoration.LineThrough else null,
            )
            if (tbc && !compact) Text(stringResource(R.string.time_tbc_short), style = MaterialTheme.typography.labelSmall, color = extra.muted)
        }
    }
}

@Composable
private fun Score(value: Int, bold: Boolean, compact: Boolean) {
    Text(
        value.toString(),
        style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).merge(Tabular),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.heightIn(min = if (compact) 22.dp else 28.dp),
    )
}

@Composable
private fun ByeRow(team: Team?, highlightTeamId: Int, accent: Color?, label: String?, modifier: Modifier) {
    val mine = team != null && team.id == highlightTeamId
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(team, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            team?.name.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mine && accent != null && !label.isNullOrBlank()) {
            KidTag(label, accent)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.rests), style = MaterialTheme.typography.labelLarge, color = JdcmColors.extra.muted)
    }
}

/**
 * The child's name on their team: their colour as the background, dark text on it (the palette
 * is light enough for that to read in both themes).
 */
@Composable
fun KidTag(name: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF111214),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
