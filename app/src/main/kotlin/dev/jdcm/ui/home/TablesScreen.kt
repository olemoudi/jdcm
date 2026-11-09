package dev.jdcm.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.ui.components.EmptyState
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.FreshnessLine
import dev.jdcm.ui.components.KidPills
import dev.jdcm.ui.components.SectionHeader
import dev.jdcm.ui.components.StandingsTable
import dev.jdcm.ui.components.sportIcon
import dev.jdcm.ui.nav.TeamRoute
import dev.jdcm.ui.theme.kidColor

/** The second thing the friend asked for: each child's table, one tap from anywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(c: AppContainer, onSettings: () -> Unit, onAddKid: () -> Unit, onTeam: (TeamRoute) -> Unit) {
    val kidsOrNull by c.kids.kids.collectAsState()
    val snaps by c.repository.snapshots.collectAsState()
    val status by c.repository.status.collectAsState()
    var selectedKid by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { c.kids.load() }
    LaunchedEffect(kidsOrNull) { kidsOrNull?.forEach { c.repository.load(it.groupId) } }
    val kids = kidsOrNull.orEmpty()
    val visible = if (selectedKid == null) kids else kids.filter { it.id == selectedKid }.ifEmpty { kids }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tab_tables), fontWeight = FontWeight.Bold) },
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
                EmptyState(
                    Icons.Filled.FormatListNumbered,
                    stringResource(R.string.tables_empty_title),
                    stringResource(R.string.tables_empty_text),
                    primary = stringResource(R.string.add_kid) to onAddKid,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item(key = "pills") { KidPills(kids, selectedKid, { selectedKid = it }) }
                items(visible, key = { it.id }) { kid ->
                    val snap = snaps[kid.groupId]
                    val kind = SportKind.of(kid.sportId, kid.sportName)
                    val rule = PointsRule.forSport(kind)
                    val rows = remember(snap) { snap?.let { Standings.compute(it.matches, rule) }.orEmpty() }
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(
                            title = kid.name,
                            subtitle = "${kid.competitionName.lowercase().replaceFirstChar { it.titlecase() }} · ${kid.groupName}",
                            icon = sportIcon(kind),
                            accent = kidColor(kid.color),
                        )
                        FlatCard {
                            if (rows.isEmpty()) {
                                FreshnessLine(status[kid.groupId] ?: dev.jdcm.data.SyncStatus(running = true), 0)
                            } else {
                                StandingsTable(rows, rule, kid.teamId, kidColor(kid.color), onTeam = { teamId ->
                                    onTeam(TeamRoute(kid.groupId, kid.sportId, kid.competitionId, kid.categoryId, teamId))
                                })
                            }
                        }
                        FreshnessLine(status[kid.groupId], snap?.newestFetch ?: 0)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
