package dev.jdcm.ui.home

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.draw.clip
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
import dev.jdcm.core.model.Season
import dev.jdcm.core.schedule.Clash
import dev.jdcm.core.schedule.Clashes
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.data.Kid
import dev.jdcm.data.SyncMode
import dev.jdcm.data.SyncStatus
import dev.jdcm.diag.DiagLog
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
    /** A child's editor, straight at picking their team (a new season). */
    onEditKid: (String) -> Unit = {},
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
    // AEMET's chance of rain for the children's next matches: read in the app's scope (it outlives
    // the screen), and only what is older than a couple of hours (WeatherRepo).
    LaunchedEffect(kidsOrNull, snaps) {
        val watched = dev.jdcm.notify.RainWatch.watched(kidsOrNull.orEmpty(), snaps, java.time.LocalDateTime.now(MADRID))
        if (watched.isNotEmpty()) c.scope.launch { c.weather.refreshFor(watched.map { it.second }, "home") }
    }

    val kids = kidsOrNull.orEmpty()
    val visible = if (selectedKid == null) kids else kids.filter { it.id == selectedKid }.ifEmpty { kids }
    val today = LocalDate.now(MADRID)
    // Every date a visible child plays on, weekend or not (0.2.5).
    val days = remember(visible, snaps) { Schedule.matchDates(visible.mapNotNull { k -> snaps[k.groupId]?.let { it to k.teamId } }) }
    val focus = remember(days, today) { Schedule.focusDate(days, today) }
    val day = pickedDay?.let(LocalDate::ofEpochDay)?.takeIf { it in days } ?: focus
    // Two children at nearly the same time in different places, that date (any child: the filter only hides cards).
    // Off in Settings → «Avisar si dos hijos coinciden» (on by default).
    val settings by c.settings.state.collectAsState()
    val clashes = remember(kids, snaps, day, settings.clashAlerts) {
        if (!settings.clashAlerts) emptyList()
        else day?.let { d -> Clashes.find(kids.flatMap { k -> snaps[k.groupId]?.teamMatches(k.teamId).orEmpty().filter { it.day == d }.map { k.id to it } }) }.orEmpty()
    }
    // The season the site is on (SeasonWatch), for the children whose own one is over.
    val season = Season(c.settings.activeSeasonId, c.settings.activeSeasonName)
    // What the phone does to the notices: read here, not in the list item (that is rebuilt on every scroll back).
    val health = rememberHealthFacts(c, hasKids = kids.isNotEmpty())

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
            // Scrolls only when it does not fit (text at 200 %, a short phone); centred otherwise.
            kids.isEmpty() -> Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
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
                    // One item that is never 0 high: the list anchors on it at the top, so a banner that
                    // appears later (back from a system screen) pushes the list down instead of growing
                    // above the first visible item, out of sight (seen on the AVD).
                    item(key = "banners") {
                        Column(Modifier.fillMaxWidth().heightIn(min = 1.dp)) {
                            HealthBanner(c, health)
                            InstallPermissionBanner(c)
                        }
                    }
                    item(key = "pills") { KidPills(kids, selectedKid, { selectedKid = it }, onAdd = onAddKid) }
                    item(key = "strip") {
                        if (days.isNotEmpty()) {
                            MatchdayStrip(
                                days = days,
                                selected = day,
                                current = focus,
                                today = today,
                                dotsFor = { d ->
                                    visible.filter { k -> snaps[k.groupId]?.teamMatches(k.teamId)?.any { !it.isBye && it.day == d } == true }
                                        .map { kidColor(it.color) }
                                },
                                onSelect = { pickedDay = it.toEpochDay() },
                                onToday = { pickedDay = null },
                                onCalendar = { showPicker = true },
                            )
                            StripLegend(c, visible)
                            Hairline()
                        }
                    }
                    if (clashes.isNotEmpty()) item(key = "clashes-$day") {
                        LaunchedEffect(clashes) {
                            DiagLog.i("UI", "clash shown on $day: " + clashes.joinToString { "#${it.first.second.id}/#${it.second.second.id}" })
                        }
                        clashes.forEach { ClashCard(it, kids) }
                    }
                    visible.forEach { kid ->
                        kidSection(kid, snaps[kid.groupId], status[kid.groupId], day, season, onGroup, onTeam, onEditKid)
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
                        val target = days.firstOrNull { !it.isBefore(d) } ?: days.lastOrNull()
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
    season: Season,
    onGroup: (GroupRoute) -> Unit,
    onTeam: (TeamRoute) -> Unit,
    onEditKid: (String) -> Unit,
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
    // The site is on a newer season and this child's is over (nothing ahead): their new team.
    if (season.id > kid.seasonId) item(key = "s-${kid.id}") {
        val over = remember(snap) { snap == null || Schedule.upcoming(snap, kid.teamId, LocalDateTime.now(MADRID)).isEmpty() }
        if (over) NewSeasonRow(kid, season) { onEditKid(kid.id) }
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
            // The child's match on that date (or their rest), and the rest of its round, whatever days those are on.
            val ofDay = remember(snap, day) { snap.teamMatches(kid.teamId).filter { it.day == day }.sortedWith(MatchOrder) }
            // Every match the child plays that date, a rest only when nothing else (review 2026-11-18:
            // a replay can land on the same date as another match, and the second one vanished).
            val mineAll = remember(ofDay) { ofDay.filter { !it.isBye }.ifEmpty { ofDay.take(1) } }
            val mine = mineAll.firstOrNull()
            val others = remember(snap, mine) {
                mine?.let { m -> snap.matches.filter { it.roundId == m.roundId && mineAll.none { x -> x.id == it.id } }.sortedWith(MatchOrder) }.orEmpty()
            }
            val forecasts by dev.jdcm.ui.LocalContainer.current.weather.forecasts.collectAsState()
            val weather = remember(mineAll, forecasts) {
                val now = java.time.LocalDateTime.now(MADRID)
                mineAll.associate { it.id to dev.jdcm.core.weather.Weather.forMatch(it, forecasts, now) }
            }
            FlatCard(borderColor = if (mine != null && !mine.isBye) accent else null) {
                if (mine != null) {
                    mineAll.forEachIndexed { i, m ->
                        if (i > 0) Hairline()
                        MatchCard(
                            m,
                            onClick = { actions.openMatch(MatchArgs(m, ref, kid.teamId, accent, kid.name)) },
                            highlightTeamId = kid.teamId,
                            accent = accent,
                            highlightLabel = kid.name,
                            onDirections = actions.directions,
                            weather = weather[m.id],
                        )
                    }
                } else {
                    NoMatchRow(kid, snap, day)
                }
            }
            // The latest result stays in sight while the matchday on screen is still to be played.
            val last = remember(snap) { Schedule.last(snap, kid.teamId) }
            if (last != null && (mine == null || !mine.hasResult) && mineAll.none { it.id == last.id }) {
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

/** Two children at nearly the same time in different places: who, when and where, in their colours. */
@Composable
private fun ClashCard(clash: Clash, kids: List<Kid>) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val a = kids.firstOrNull { it.id == clash.first.first } ?: return
    val b = kids.firstOrNull { it.id == clash.second.first } ?: return
    val extra = JdcmColors.extra
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(extra.modified.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = extra.modified)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // No names in the title: «Lucía y Irene» wants «e» (the lines name both).
            Text(stringResource(R.string.clash_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            listOf(Triple(a, clash.first.second, clash.second.second), Triple(b, clash.second.second, clash.first.second)).forEach { (k, m, other) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(kidColor(k.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clash_line, fmt.time(m), k.name, fmt.placeVs(m.venue, other.venue)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.clash_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

/** «Nueva temporada 2027-2028»: the child's season is over and the site is on the next; one tap to pick the new team. */
@Composable
private fun NewSeasonRow(kid: Kid, season: Season, onPick: () -> Unit) {
    LaunchedEffect(kid.id, season.id) { DiagLog.i("UI", "new season offered for #${kid.id.take(8)} kidSeason=${kid.seasonId} site=${season.id}") }
    val extra = JdcmColors.extra
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(extra.link.copy(alpha = 0.10f)).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Autorenew, null, tint = extra.link)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (season.name.isBlank()) stringResource(R.string.season_new_title_plain) else stringResource(R.string.season_new_title, season.years),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.season_new_text, kid.name), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.season_new_fix), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NoMatchRow(kid: Kid, snap: GroupSnapshot, day: LocalDate?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val next = remember(snap, day) {
        day?.let { d -> snap.teamMatches(kid.teamId).filter { m -> !m.isBye && m.day?.isAfter(d) == true }.minByOrNull { it.day!! } }
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

/**
 * What the dots under the dates mean, said the first three times the app is opened: a dot was
 * not self-explanatory (the owner asked). One dot per child, in their colour.
 */
@Composable
private fun StripLegend(c: AppContainer, kids: List<Kid>) {
    val show = remember { c.settings.stripLegendShows < 3 }
    LaunchedEffect(show) {
        if (show && !c.stripLegendCounted) {
            c.stripLegendCounted = true
            c.settings.stripLegendShows = c.settings.stripLegendShows + 1
        }
    }
    if (!show || kids.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        kids.forEach { k ->
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(kidColor(k.color)))
            Text(k.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(if (kids.size == 1) R.string.strip_legend_one else R.string.strip_legend_many),
            style = MaterialTheme.typography.bodySmall,
            color = JdcmColors.extra.muted,
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
    title = kid.groupName, tab = tab, teamId = kid.teamId, kidColor = kid.color, kidId = kid.id,
)
