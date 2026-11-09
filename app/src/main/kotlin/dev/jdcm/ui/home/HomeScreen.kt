package dev.jdcm.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.MatchOrder
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.Kid
import dev.jdcm.data.SyncMode
import dev.jdcm.data.SyncStatus
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.MatchArgs
import dev.jdcm.ui.components.EmptyState
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.FreshnessLine
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.KidPills
import dev.jdcm.ui.components.MatchCard
import dev.jdcm.ui.components.MatchdayStrip
import dev.jdcm.ui.components.NotLoadedYet
import dev.jdcm.ui.components.SectionHeader
import dev.jdcm.ui.components.sportIcon
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.nav.GroupRoute
import dev.jdcm.ui.nav.TeamRoute
import dev.jdcm.ui.onboarding.InstallPermissionBanner
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.kidColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    c: AppContainer,
    onSettings: () -> Unit,
    onAddKid: () -> Unit,
    onExplore: () -> Unit,
    onGroup: (GroupRoute) -> Unit,
    onTeam: (TeamRoute) -> Unit,
) {
    val kidsOrNull by c.kids.kids.collectAsState()
    val snaps by c.repository.snapshots.collectAsState()
    val status by c.repository.status.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedKid by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { c.kids.load() }
    // Loading only: the app's own start (JdcmApp, "shown") refreshes the children's groups.
    LaunchedEffect(kidsOrNull) { kidsOrNull?.forEach { c.repository.load(it.groupId) } }

    val kids = kidsOrNull.orEmpty()
    val visible = if (selectedKid == null) kids else kids.filter { it.id == selectedKid }.ifEmpty { kids }
    val today = LocalDate.now(MADRID)
    val days = remember(visible, snaps) { Schedule.matchdays(visible.mapNotNull { snaps[it.groupId] }) }
    val focus = remember(days, today) { Schedule.focusMatchday(days, today) }
    val day = pickedDay?.let(LocalDate::ofEpochDay)?.takeIf { it in days } ?: focus

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tab_matches), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.AccountCircle, stringResource(R.string.settings), Modifier.size(30.dp)) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        when {
            kidsOrNull == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            kids.isEmpty() -> Column(Modifier.fillMaxSize().padding(pad)) {
                InstallPermissionBanner(c)
                Spacer(Modifier.weight(1f))
                EmptyState(
                    Icons.Filled.FamilyRestroom,
                    stringResource(R.string.empty_title),
                    stringResource(R.string.empty_text),
                    primary = stringResource(R.string.add_kid) to onAddKid,
                    secondary = stringResource(R.string.explore_all) to onExplore,
                )
                Spacer(Modifier.weight(1f))
            }
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try { c.repository.syncKidsInBackground(SyncMode.FORCE, "pull").join() } finally { refreshing = false }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "banner") { InstallPermissionBanner(c) }
                    item(key = "pills") { KidPills(kids, selectedKid, { selectedKid = it }, onAdd = onAddKid) }
                    item(key = "strip") {
                        if (days.isNotEmpty()) {
                            MatchdayStrip(
                                days = days,
                                selected = day,
                                current = focus,
                                today = today,
                                dotsFor = { d ->
                                    visible.filter { k -> snaps[k.groupId]?.matches?.any { it.involves(k.teamId) && !it.isBye && Schedule.matchdayOf(it) == d } == true }
                                        .map { kidColor(it.color) }
                                },
                                onSelect = { pickedDay = it.toEpochDay() },
                                onToday = { pickedDay = null },
                                onCalendar = { showPicker = true },
                            )
                            Hairline()
                        }
                    }
                    visible.forEach { kid ->
                        kidSection(kid, snaps[kid.groupId], status[kid.groupId], day, onGroup, onTeam)
                    }
                    item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val target = days.firstOrNull { !it.plusDays(1).isBefore(d) } ?: days.lastOrNull()
                        pickedDay = target?.toEpochDay()
                    }
                    showPicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** One child on Home: their match of the matchday first, the rest of the group's after, the doors at the foot. */
private fun LazyListScope.kidSection(
    kid: Kid,
    snap: GroupSnapshot?,
    status: SyncStatus?,
    day: LocalDate?,
    onGroup: (GroupRoute) -> Unit,
    onTeam: (TeamRoute) -> Unit,
) {
    item(key = "h-${kid.id}") {
        val accent = kidColor(kid.color)
        val position = remember(snap) {
            snap?.let { s ->
                val rows = Standings.compute(s.matches, PointsRule.forSport(s.sport))
                rows.firstOrNull { it.team.id == kid.teamId }?.takeIf { it.played > 0 }
            }
        }
        val kind = dev.jdcm.core.model.SportKind.of(kid.sportId, kid.sportName)
        Spacer(Modifier.height(8.dp))
        SectionHeader(
            title = kid.name,
            subtitle = "${kid.competitionName.lowercase().replaceFirstChar { it.titlecase() }} · ${kid.groupName} · ${kid.teamName}",
            icon = sportIcon(kind),
            accent = accent,
            trailing = position?.let { stringResource(R.string.position_points, it.position, it.points) },
            modifier = Modifier.clickable { onGroup(groupRoute(kid, tab = 1)) },
        )
    }
    item(key = "b-${kid.id}") {
        val actions = LocalActions.current
        val accent = kidColor(kid.color)
        var expanded by rememberSaveable(kid.id) { mutableStateOf(false) }
        val ref = kid.ref
        Column {
            if (snap == null || snap.rounds.isEmpty()) {
                FlatCard {
                    val c2 = dev.jdcm.ui.LocalContainer.current
                    NotLoadedYet(status, onRetry = { c2.repository.syncInBackground(ref, SyncMode.FORCE, "retry") })
                    if (status?.error != null) {
                        TextButton(onClick = { onGroup(groupRoute(kid, 0)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.open_group))
                        }
                    }
                }
                return@Column
            }
            val matches = remember(snap, day) {
                snap.matches.filter { Schedule.matchdayOf(it) == day }.sortedWith(MatchOrder)
            }
            val mine = matches.firstOrNull { it.involves(kid.teamId) }
            val others = matches.filter { it !== mine }
            FlatCard(borderColor = if (mine != null && !mine.isBye) accent else null) {
                if (mine != null) {
                    MatchCard(
                        mine,
                        onClick = { actions.openMatch(MatchArgs(mine, ref, kid.teamId, accent, kid.name)) },
                        highlightTeamId = kid.teamId,
                        accent = accent,
                        onDirections = actions.directions,
                    )
                } else {
                    NoMatchRow(kid, snap, day)
                }
            }
            // The latest result stays in sight while the matchday on screen is still to be played.
            val last = remember(snap) { Schedule.last(snap, kid.teamId) }
            if (last != null && (mine == null || !mine.hasResult) && last.id != mine?.id) {
                LastResultRow(kid, last) { actions.openMatch(MatchArgs(last, ref, kid.teamId, accent, kid.name)) }
            }
            if (others.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rest_of_round, others.count { !it.isBye }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = JdcmColors.extra.link,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = JdcmColors.extra.link)
                }
                AnimatedVisibility(expanded) {
                    FlatCard {
                        others.forEachIndexed { i, m ->
                            if (i > 0) Hairline()
                            MatchCard(m, onClick = { actions.openMatch(MatchArgs(m, ref, kid.teamId, accent)) }, compact = true, showDate = true)
                        }
                    }
                }
            } else Spacer(Modifier.height(4.dp))
            FooterLinks(
                listOf(
                    stringResource(R.string.link_calendar) to { onGroup(groupRoute(kid, 0)) },
                    stringResource(R.string.link_standings) to { onGroup(groupRoute(kid, 1)) },
                    stringResource(R.string.link_team) to { onTeam(TeamRoute(kid.groupId, kid.sportId, kid.competitionId, kid.categoryId, kid.teamId)) },
                ),
            )
            FreshnessLine(status, snap.newestFetch)
        }
    }
}

@Composable
private fun NoMatchRow(kid: Kid, snap: GroupSnapshot, day: LocalDate?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val next = remember(snap, day) {
        day?.let { d -> snap.teamMatches(kid.teamId).firstOrNull { m -> !m.isBye && (Schedule.matchdayOf(m)?.isAfter(d) == true) } }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.EventBusy, null, tint = JdcmColors.extra.muted)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.no_match_this_round, kid.name), style = MaterialTheme.typography.bodyLarge)
            if (next != null) {
                Text(
                    stringResource(R.string.next_on, fmt.whenLine(next)),
                    style = MaterialTheme.typography.bodySmall,
                    color = JdcmColors.extra.muted,
                )
            }
        }
    }
}

@Composable
private fun LastResultRow(kid: Kid, m: dev.jdcm.core.model.Match, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val extra = JdcmColors.extra
    val home = m.home?.id == kid.teamId
    val mine = if (home) m.homeScore else m.awayScore
    val theirs = if (home) m.awayScore else m.homeScore
    val outcome = Standings.outcome(m, kid.teamId)
    val (label, color) = when (outcome) {
        dev.jdcm.core.standings.Outcome.WIN -> stringResource(R.string.res_win, mine, theirs) to extra.win
        dev.jdcm.core.standings.Outcome.DRAW -> stringResource(R.string.res_draw, mine, theirs) to extra.draw
        dev.jdcm.core.standings.Outcome.LOSS -> stringResource(R.string.res_loss, mine, theirs) to extra.loss
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.last_result), style = MaterialTheme.typography.labelLarge, color = extra.muted)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.vs_team, m.opponentOf(kid.teamId)?.name.orEmpty()) + (m.day?.let { " · " + fmt.dayMonth(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** Marca's links under a competition: "Resultados · Clasificación · Trofeos". */
@Composable
fun FooterLinks(links: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        links.forEach { (label, go) ->
            TextButton(onClick = go, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(label, color = JdcmColors.extra.link, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

fun groupRoute(kid: Kid, tab: Int) = GroupRoute(
    kid.groupId, kid.sportId, kid.competitionId, kid.categoryId,
    title = kid.groupName, tab = tab, teamId = kid.teamId, kidColor = kid.color,
)
