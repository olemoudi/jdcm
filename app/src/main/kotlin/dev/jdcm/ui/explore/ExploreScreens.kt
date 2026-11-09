package dev.jdcm.ui.explore

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.jdcm.core.model.Category
import dev.jdcm.core.model.Competition
import dev.jdcm.core.model.Group
import dev.jdcm.core.model.GroupedRound
import dev.jdcm.core.model.MatchOrder
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.schedule.Schedule
import dev.jdcm.data.EcmApi
import dev.jdcm.data.GroupRef
import dev.jdcm.ui.LocalActions
import dev.jdcm.ui.MatchArgs
import dev.jdcm.ui.components.DoorRow
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.MatchCard
import dev.jdcm.ui.components.SectionHeader
import dev.jdcm.ui.components.sportIcon
import dev.jdcm.ui.components.sportName
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.nav.ExploreCompetitionRoute
import dev.jdcm.ui.nav.GroupRoute
import dev.jdcm.ui.nav.GroupedRoundRoute
import dev.jdcm.ui.theme.JdcmColors

/** A list that loads from the site: spinner, error with retry, or content. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val offline: Boolean) : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    var attempt by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, attempt) {
        state = Load.Loading
        state = try {
            Load.Ok(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed((e as? dev.jdcm.data.SourceException)?.offline == true)
        }
    }
    return state to { attempt++ }
}

@Composable
fun <T> LoadBox(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (load.offline) R.string.sync_offline else R.string.sync_failed),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
        is Load.Ok -> content(load.value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(onSport: (Int) -> Unit, onSettings: () -> Unit) {
    val actions = LocalActions.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tab_explore), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.AccountCircle, stringResource(R.string.settings), Modifier.size(30.dp)) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Text(
                    stringResource(R.string.explore_intro), style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(listOf(SportKind.FUTSAL, SportKind.BASKETBALL, SportKind.VOLLEYBALL)) { k ->
                FlatCard(Modifier.padding(vertical = 5.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { onSport(k.siteId) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(sportIcon(k), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(sportName(k), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.muted)
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                DoorRow(stringResource(R.string.open_web), stringResource(R.string.open_web_hint), Icons.AutoMirrored.Filled.OpenInNew, onClick = { actions.openUrl(EcmApi.CALENDAR_PAGE) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSportScreen(
    c: AppContainer,
    sportId: Int,
    onBack: () -> Unit,
    onCompetition: (ExploreCompetitionRoute) -> Unit,
    onGrouped: (GroupedRoundRoute) -> Unit,
) {
    val settings by c.settings.state.collectAsState()
    val kind = SportKind.of(sportId)
    val (load, retry) = rememberLoad(sportId, settings.showClosed) { c.repository.competitions(sportId, settings.showClosed) }
    val sport = sportName(kind)
    Scaffold(
        topBar = { BackBar(sport, onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                DoorRow(
                    stringResource(R.string.whole_round), stringResource(R.string.whole_round_sport_hint, sport),
                    Icons.Outlined.CalendarViewWeek, MaterialTheme.colorScheme.primary,
                    onClick = { onGrouped(GroupedRoundRoute(sportId, -1, 0, sport)) },
                )
                Hairline()
                Text(stringResource(R.string.categories), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp))
            }
            item {
                LoadBox(load, retry) { list: List<Competition> ->
                    FlatCard {
                        list.forEachIndexed { i, comp ->
                            if (i > 0) Hairline()
                            DoorRow(
                                comp.name.lowercase().replaceFirstChar { it.titlecase() },
                                ageHint(comp.name)?.let { stringResource(R.string.age_hint, it) },
                                onClick = { onCompetition(ExploreCompetitionRoute(sportId, comp.id, comp.name)) },
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { c.settings.update { it.copy(showClosed = !it.showClosed) } }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.show_closed), Modifier.weight(1f))
                    Switch(checked = settings.showClosed, onCheckedChange = { v -> c.settings.update { it.copy(showClosed = v) } })
                }
            }
        }
    }
}

/** School-sport ages per category, as a hint only (the organiser's rules decide). */
fun ageHint(name: String): String? {
    val n = name.lowercase()
    return when {
        "prebenjam" in n -> "6-7"
        "benjam" in n -> "8-9"
        "alev" in n -> "10-11"
        "infantil" in n -> "12-13"
        "cadete" in n -> "14-15"
        "juvenil" in n -> "16-17"
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreCompetitionScreen(
    c: AppContainer,
    route: ExploreCompetitionRoute,
    onBack: () -> Unit,
    onGroup: (GroupRoute) -> Unit,
    onGrouped: (GroupedRoundRoute) -> Unit,
) {
    val title = route.competitionName.lowercase().replaceFirstChar { it.titlecase() }
    val (cats, retryCats) = rememberLoad(route.competitionId) { c.repository.categories(route.competitionId) }
    var categoryId by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { BackBar(title, onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                LoadBox(cats, retryCats) { list: List<Category> ->
                    val chosen = list.firstOrNull { it.id == categoryId } ?: list.firstOrNull()
                    Column {
                        if (list.size > 1) {
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                list.forEach { cat ->
                                    FilterChip(selected = cat.id == chosen?.id, onClick = { categoryId = cat.id }, label = { Text(cat.name) })
                                }
                            }
                        }
                        if (chosen == null) {
                            Text(stringResource(R.string.nothing_here), Modifier.padding(16.dp))
                        } else {
                            GroupsList(c, route, chosen, title, onGroup, onGrouped)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsList(
    c: AppContainer,
    route: ExploreCompetitionRoute,
    cat: Category,
    title: String,
    onGroup: (GroupRoute) -> Unit,
    onGrouped: (GroupedRoundRoute) -> Unit,
) {
    val (groups, retry) = rememberLoad(cat.id) { c.repository.groups(route.sportId, route.competitionId, cat.id) }
    DoorRow(
        stringResource(R.string.whole_round), stringResource(R.string.whole_round_hint),
        Icons.Outlined.CalendarViewWeek, MaterialTheme.colorScheme.primary,
        onClick = { onGrouped(GroupedRoundRoute(route.sportId, route.competitionId, cat.id, title)) },
    )
    Hairline()
    Text(stringResource(R.string.groups), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp))
    LoadBox(groups, retry) { list: List<Group> ->
        FlatCard {
            list.forEachIndexed { i, g ->
                if (i > 0) Hairline()
                DoorRow(g.name, null, Icons.Outlined.Groups, onClick = {
                    onGroup(GroupRoute(g.id, route.sportId, route.competitionId, cat.id, title = g.name))
                })
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** The web's "TODOS" view: every group's matches for one jornada number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedRoundScreen(c: AppContainer, route: GroupedRoundRoute, onBack: () -> Unit, onGroup: (GroupRoute) -> Unit) {
    var number by rememberSaveable { mutableIntStateOf(1) }
    val (load, retry) = rememberLoad(route, number) { c.repository.groupedRound(route.sportId, route.competitionId, route.categoryId, number) }
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val actions = LocalActions.current
    Scaffold(topBar = { BackBar(route.title, onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (number > 1) number-- }, enabled = number > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_round)) }
                    val date = (load as? Load.Ok)?.value?.asSequence()
                        ?.flatMap { r -> r.categories.asSequence() }?.flatMap { it.groups.asSequence() }?.flatMap { it.matches.asSequence() }
                        ?.firstNotNullOfOrNull { it.roundDate ?: it.day }?.let(Schedule::weekendOf)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.round_n, number), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (date != null) Text(fmt.day(date), style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
                    }
                    IconButton(onClick = { number++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_round)) }
                }
                Hairline()
            }
            when (load) {
                is Load.Ok -> {
                    val list = load.value
                    if (list.isEmpty()) item { Text(stringResource(R.string.no_matches_round), Modifier.padding(16.dp), color = JdcmColors.extra.muted) }
                    // One lazy item per group: a sport's whole round is ~170 matches, composed only as they scroll in.
                    list.forEach { comp ->
                        comp.categories.forEach { cat ->
                            item(key = "h-${comp.competitionId}-${cat.categoryId}") {
                                SectionHeader(comp.competition.lowercase().replaceFirstChar { it.titlecase() }, cat.name.takeIf { it != comp.competition })
                            }
                            items(cat.groups, key = { "g-${it.groupId}" }) { g ->
                                val gref = GroupRef(g.groupId, route.sportId, comp.competitionId, cat.categoryId)
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .clickable { onGroup(GroupRoute(gref.groupId, gref.sportId, gref.competitionId, gref.categoryId, g.name)) }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(g.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = JdcmColors.extra.link)
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.link)
                                    }
                                    FlatCard {
                                        g.matches.sortedWith(MatchOrder).forEachIndexed { i, m ->
                                            if (i > 0) Hairline()
                                            MatchCard(m, onClick = { actions.openMatch(MatchArgs(m, gref, title = g.name)) }, compact = true)
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                }
                else -> item { LoadBox(load, retry) { _: List<GroupedRound> -> } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
