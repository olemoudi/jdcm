package dev.jdcm.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.GroupRef
import dev.jdcm.data.SyncMode
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.MatchArgs
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.DoorRow
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.FormDots
import dev.jdcm.ui.components.FreshnessLine
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.InfoChip
import dev.jdcm.ui.components.MatchCard
import dev.jdcm.ui.nav.GroupRoute
import dev.jdcm.ui.nav.KidEditorRoute
import dev.jdcm.ui.nav.TeamRoute
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.kidColor
import java.time.LocalDateTime

/** A team's season on one page: where it stands, what is next, every result and fixture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(c: AppContainer, route: TeamRoute, onBack: () -> Unit, onGroup: (GroupRoute) -> Unit, onFollow: (KidEditorRoute) -> Unit) {
    val ref = GroupRef(route.groupId, route.sportId, route.competitionId, route.categoryId)
    val snaps by c.repository.snapshots.collectAsState()
    val status by c.repository.status.collectAsState()
    val kids by c.kids.kids.collectAsState()
    val snap = snaps[route.groupId]
    LaunchedEffect(route.groupId) {
        c.kids.load()
        c.repository.load(route.groupId)
        c.repository.sync(ref, SyncMode.IF_STALE, "team screen")
    }
    val team = snap?.team(route.teamId)
    val kid = kids.orEmpty().firstOrNull { it.teamId == route.teamId && it.groupId == route.groupId }
    val accent = kid?.let { kidColor(it.color) }
    val actions = LocalActions.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(snap?.group?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (snap == null || team == null) {
            Column(Modifier.fillMaxSize().padding(pad)) { FreshnessLine(status[route.groupId] ?: dev.jdcm.data.SyncStatus(running = true), 0) }
            return@Scaffold
        }
        val rule = PointsRule.forSport(snap.sport)
        val row = remember(snap) { Standings.compute(snap.matches, rule).firstOrNull { it.team.id == team.id } }
        val now = LocalDateTime.now(MADRID)
        val next = remember(snap) { Schedule.next(snap, team.id, now) }
        val all = remember(snap) { snap.teamMatches(team.id) }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(team, 84.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(team.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        snap.group.competition.lowercase().replaceFirstChar { it.titlecase() } + " · " + snap.group.name,
                        style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (row != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InfoChip(stringResource(R.string.position_points, row.position, row.points), MaterialTheme.colorScheme.primary)
                            InfoChip(
                                if (rule.hasDraws) stringResource(R.string.record_draws, row.won, row.drawn, row.lost)
                                else stringResource(R.string.record, row.won, row.lost),
                            )
                            if (row.form.isNotEmpty()) FormDots(row.form)
                        }
                    }
                    if (kid != null) {
                        Spacer(Modifier.height(10.dp))
                        InfoChip(stringResource(R.string.team_of, kid.name), accent ?: MaterialTheme.colorScheme.onSurface)
                    } else {
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { onFollow(KidEditorRoute(presetTeamId = team.id, presetGroupId = route.groupId, presetSportId = route.sportId, presetCompetitionId = route.competitionId, presetCategoryId = route.categoryId)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.follow_as_kid))
                        }
                    }
                }
            }
            if (next != null) {
                item {
                    Text(stringResource(R.string.next_match), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    FlatCard(borderColor = accent ?: MaterialTheme.colorScheme.primary) {
                        MatchCard(next, onClick = { actions.openMatch(MatchArgs(next, ref, team.id, accent, snap.group.name)) }, highlightTeamId = team.id, accent = accent, onDirections = actions.directions)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Text(stringResource(R.string.season), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                FlatCard {
                    all.forEachIndexed { i, m ->
                        if (i > 0) Hairline()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.round_short, m.roundNumber), modifier = Modifier.width(44.dp).padding(start = 12.dp),
                                style = MaterialTheme.typography.labelMedium, color = JdcmColors.extra.muted, fontWeight = FontWeight.Bold,
                            )
                            MatchCard(m, onClick = { actions.openMatch(MatchArgs(m, ref, team.id, accent, snap.group.name)) }, modifier = Modifier.weight(1f), highlightTeamId = team.id, accent = accent, compact = true)
                        }
                    }
                }
                DoorRow(
                    title = stringResource(R.string.group_standings),
                    icon = Icons.Outlined.FormatListNumbered,
                    onClick = { onGroup(GroupRoute(ref.groupId, ref.sportId, ref.competitionId, ref.categoryId, snap.group.name, tab = 1, teamId = team.id, kidColor = kid?.color ?: -1)) },
                )
                FreshnessLine(status[route.groupId], snap.newestFetch)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
