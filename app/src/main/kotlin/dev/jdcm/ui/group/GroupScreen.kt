package dev.jdcm.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.EcmApi
import dev.jdcm.data.GroupRef
import dev.jdcm.data.SyncMode
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.MatchArgs
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.FreshnessLine
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.MatchCard
import dev.jdcm.ui.components.StandingsTable
import dev.jdcm.ui.components.sportName
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.nav.GroupRoute
import dev.jdcm.ui.nav.TeamRoute
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular
import dev.jdcm.ui.theme.kidColor
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(c: AppContainer, route: GroupRoute, onBack: () -> Unit, onTeam: (TeamRoute) -> Unit) {
    val ref = GroupRef(route.groupId, route.sportId, route.competitionId, route.categoryId)
    val snaps by c.repository.snapshots.collectAsState()
    val status by c.repository.status.collectAsState()
    val snap = snaps[route.groupId]
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(route.tab) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(route.groupId) {
        c.repository.load(route.groupId)
        c.repository.syncInBackground(ref, SyncMode.IF_STALE, "group screen")
    }
    val accent = route.kidColor.takeIf { it >= 0 }?.let(::kidColor)
    val kind = SportKind.of(route.sportId, snap?.group?.sport)
    val actions = LocalActions.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(snap?.group?.name?.ifBlank { null } ?: route.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(snap?.group?.competition?.ifBlank { null }?.lowercase()?.replaceFirstChar { it.titlecase() }, sportName(kind, snap?.group?.sport.orEmpty()).ifBlank { null })
                        if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { actions.openUrl(EcmApi.CALENDAR_PAGE) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_web)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf(R.string.tab_rounds, R.string.tab_standings, R.string.tab_teams).forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { refreshing = true; try { c.repository.syncInBackground(ref, SyncMode.FORCE, "pull").join() } finally { refreshing = false } } },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (snap == null || snap.rounds.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize()) { item { dev.jdcm.ui.components.NotLoadedYet(status[route.groupId], onRetry = { c.repository.syncInBackground(ref, SyncMode.FORCE, "retry") }) } }
                } else when (tab) {
                    0 -> RoundsTab(snap, ref, route.teamId, accent, route.kidName, status[route.groupId])
                    1 -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            val rule = PointsRule.forSport(snap.sport)
                            val rows = remember(snap) { Standings.compute(snap.matches, rule) }
                            FlatCard {
                                StandingsTable(rows, rule, route.teamId, accent, onTeam = { onTeam(TeamRoute(ref.groupId, ref.sportId, ref.competitionId, ref.categoryId, it)) })
                            }
                            FreshnessLine(status[route.groupId], snap.newestFetch)
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            FlatCard {
                                snap.teams.sortedBy { it.name }.forEachIndexed { i, t ->
                                    if (i > 0) Hairline()
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                            .background(if (t.id == route.teamId) (accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.14f) else Color.Transparent)
                                            .clickable { onTeam(TeamRoute(ref.groupId, ref.sportId, ref.competitionId, ref.categoryId, t.id)) }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Crest(t, 32.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (t.id == route.teamId) FontWeight.Bold else FontWeight.Normal)
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The web's jornada navigation, as a strip: J1 … J9 with dates, arrows, and the round's matches. */
@Composable
private fun RoundsTab(snap: GroupSnapshot, ref: GroupRef, teamId: Int, accent: Color?, kidName: String, status: dev.jdcm.data.SyncStatus?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val actions = LocalActions.current
    val focus = remember(snap) { snap.focusRound(LocalDate.now(MADRID)) }
    var selectedId by rememberSaveable { mutableIntStateOf(focus?.id ?: 0) }
    val rounds = snap.rounds
    val index = rounds.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: rounds.indexOf(focus).coerceAtLeast(0)
    val round = rounds.getOrNull(index)
    val strip = rememberLazyListState()
    LaunchedEffect(index) { strip.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                IconButton(onClick = { rounds.getOrNull(index - 1)?.let { selectedId = it.id } }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_round))
                }
                LazyRow(state = strip, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(rounds, key = { it.id }) { r ->
                        val sel = r.id == round?.id
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(role = Role.Tab) { selectedId = r.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.round_short, r.number), style = MaterialTheme.typography.labelLarge.merge(Tabular),
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else if (r.id == focus?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold)
                            Text(r.date?.let(fmt::dayMonth) ?: "—", style = MaterialTheme.typography.labelSmall,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else JdcmColors.extra.muted)
                        }
                    }
                }
                IconButton(onClick = { rounds.getOrNull(index + 1)?.let { selectedId = it.id } }, enabled = index < rounds.lastIndex) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_round))
                }
            }
            Hairline()
            Spacer(Modifier.height(10.dp))
        }
        item {
            val matches = round?.let { snap.roundMatches(it.id) }.orEmpty()
            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.round_not_loaded), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted,
                )
            } else {
                FlatCard {
                    matches.forEachIndexed { i, m ->
                        if (i > 0) Hairline()
                        MatchCard(
                            m,
                            onClick = { actions.openMatch(MatchArgs(m, ref, teamId, accent, snap.group.name)) },
                            highlightTeamId = teamId,
                            accent = accent,
                            highlightLabel = kidName.ifBlank { null },
                            onDirections = actions.directions,
                        )
                    }
                }
            }
            FreshnessLine(status, round?.let { snap.roundFetchedAt[it.id] } ?: 0)
            Spacer(Modifier.height(24.dp))
        }
    }
}
