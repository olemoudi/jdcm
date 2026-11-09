package dev.jdcm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdcm.R
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.StandingRow
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular

/**
 * The table from the friend's screenshot: the school's name on its own line (long names are
 * whole, never cut), the numbers under it in fixed columns J G E P TF TC AVG Ptos. The followed
 * team's row is tinted in its child's colour.
 */
@Composable
fun StandingsTable(
    rows: List<StandingRow>,
    rule: PointsRule,
    highlightTeamId: Int,
    accent: Color?,
    onTeam: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = JdcmColors.extra
    val cols = buildList {
        add(stringResource(R.string.col_played))
        add(stringResource(R.string.col_won))
        if (rule.hasDraws) add(stringResource(R.string.col_drawn))
        add(stringResource(R.string.col_lost))
        add(stringResource(R.string.col_for))
        add(stringResource(R.string.col_against))
        add(stringResource(R.string.col_diff))
        add(stringResource(R.string.col_points))
    }
    Column(modifier.fillMaxWidth()) {
        if (rows.isNotEmpty() && rows.all { it.played == 0 }) {
            Text(
                stringResource(R.string.standings_not_started),
                style = MaterialTheme.typography.bodySmall, color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            cols.forEachIndexed { i, c ->
                Text(
                    c, Modifier.weight(if (i == cols.lastIndex) 1.2f else 1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Hairline()
        rows.forEach { r ->
            val mine = r.team.id == highlightTeamId
            val nums = buildList {
                add(r.played); add(r.won); if (rule.hasDraws) add(r.drawn); add(r.lost)
                add(r.scored); add(r.conceded); add(r.difference); add(r.points)
            }
            val desc = stringResource(R.string.standing_row_desc, r.position, r.team.name, r.points, r.played, r.won, r.lost)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (mine) (accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onTeam(r.team.id) }
                    .semantics(mergeDescendants = true) { contentDescription = desc }
                    .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        r.position.toString(), style = MaterialTheme.typography.labelLarge.merge(Tabular),
                        color = if (mine) MaterialTheme.colorScheme.onSurface else extra.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Crest(r.team, size = 30.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.team.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                            color = if (mine) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (r.form.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            FormDots(r.form)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth()) {
                        nums.forEachIndexed { i, n ->
                            Text(
                                n.toString(), Modifier.weight(if (i == nums.lastIndex) 1.2f else 1f), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                                fontWeight = if (i == nums.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Hairline(Modifier.padding(start = 56.dp))
        }
        val rule = if (rule.hasDraws) stringResource(R.string.standings_rule_draws, rule.win, rule.draw, rule.loss)
        else stringResource(R.string.standings_rule, rule.win, rule.loss)
        Text(
            stringResource(R.string.standings_footnote, rule),
            style = MaterialTheme.typography.bodySmall, color = extra.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun FormDots(form: List<Outcome>) {
    val extra = JdcmColors.extra
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        form.forEach { o ->
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    when (o) {
                        Outcome.WIN -> extra.win
                        Outcome.DRAW -> extra.draw
                        Outcome.LOSS -> extra.loss
                    },
                ),
            )
        }
    }
}
