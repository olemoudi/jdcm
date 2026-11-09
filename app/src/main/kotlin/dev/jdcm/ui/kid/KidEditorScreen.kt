package dev.jdcm.ui.kid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.R
import dev.jdcm.core.model.Category
import dev.jdcm.core.model.Competition
import dev.jdcm.core.model.SportKind
import dev.jdcm.data.Kid
import dev.jdcm.data.SyncMode
import dev.jdcm.data.TeamInGroup
import dev.jdcm.diag.DiagLog
import dev.jdcm.notify.Notifier
import dev.jdcm.notify.Reminders
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.sportIcon
import dev.jdcm.ui.components.sportName
import dev.jdcm.ui.explore.LoadBox
import dev.jdcm.ui.explore.ageHint
import dev.jdcm.ui.explore.rememberLoad
import dev.jdcm.ui.nav.KidEditorRoute
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.kidColor
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.UUID

private enum class Step { NAME, SPORT, CATEGORY, TEAM }

/**
 * Adding a child takes four short steps and no knowledge of the site: a name and a colour, the
 * sport, the age category, and the school's team found by typing its name (the group number,
 * which families rarely know, is worked out from it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KidEditorScreen(c: AppContainer, route: KidEditorRoute, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var existing by remember { mutableStateOf<Kid?>(null) }
    var loaded by remember { mutableStateOf(route.kidId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(-1) }
    var step by rememberSaveable { mutableStateOf(Step.NAME) }
    var sportId by rememberSaveable { mutableIntStateOf(route.presetSportId) }
    var competition by remember { mutableStateOf<Competition?>(null) }
    var category by remember { mutableStateOf<Category?>(null) }
    var changingTeam by rememberSaveable { mutableStateOf(route.kidId == null && route.presetTeamId == 0) }

    LaunchedEffect(route.kidId) {
        val kids = c.kids.current()
        if (color < 0) color = (0 until Notifier.KID_COLORS.size).firstOrNull { i -> kids.none { it.color == i } } ?: kids.size
        route.kidId?.let { id ->
            existing = kids.firstOrNull { it.id == id }
            existing?.let { if (name.isEmpty()) name = it.name; color = it.color; sportId = it.sportId }
            loaded = true
        }
    }

    fun back() {
        when {
            step == Step.NAME -> onBack()
            step == Step.SPORT -> step = Step.NAME
            step == Step.CATEGORY -> step = Step.SPORT
            else -> step = Step.CATEGORY
        }
    }
    BackHandler(enabled = step != Step.NAME) { back() }

    fun save(kid: Kid) {
        scope.launch {
            c.kids.save(kid)
            DiagLog.i("Kids", "saved #${kid.id.take(8)} group=#${kid.groupId} team=#${kid.teamId} new=${existing == null}")
            c.scope.launch {
                c.repository.sync(kid.ref, SyncMode.IF_STALE, "kid saved")
                Reminders.rearm(ctx)
            }
            onDone()
        }
    }

    fun saveWithTeam(t: TeamInGroup, comp: Competition?, cat: Category?) {
        val kind = SportKind.of(sportId)
        save(
            Kid(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                color = color.coerceAtLeast(0),
                sportId = sportId,
                sportName = t.group.sport.ifBlank { kind.name },
                competitionId = comp?.id ?: route.presetCompetitionId,
                competitionName = comp?.name ?: t.group.competition,
                categoryId = cat?.id ?: route.presetCategoryId,
                categoryName = cat?.name ?: t.group.category,
                groupId = t.group.id,
                groupName = t.group.name,
                teamId = t.team.id,
                teamName = t.team.name,
                crestPath = t.team.crestPath,
            ),
        )
    }

    val title = stringResource(if (route.kidId == null) R.string.add_kid else R.string.edit_kid)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (changingTeam) {
                            Text(
                                stringResource(R.string.step_of, step.ordinal + 1, Step.entries.size),
                                style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        AnimatedContent(step, modifier = Modifier.fillMaxSize().padding(pad).imePadding(), label = "step") { s ->
            when (s) {
                Step.NAME -> NameStep(
                    name = name, onName = { name = it }, color = color, onColor = { color = it },
                    isEdit = existing != null,
                    hasPresetTeam = route.presetTeamId != 0,
                    onNext = {
                        when {
                            existing != null && !changingTeam -> save(existing!!.copy(name = name.trim(), color = color))
                            route.presetTeamId != 0 -> scope.launch {
                                val snap = c.repository.load(route.presetGroupId)
                                val team = snap?.team(route.presetTeamId)
                                if (snap != null && team != null) saveWithTeam(TeamInGroup(team, snap.group), null, null)
                            }
                            else -> step = Step.SPORT
                        }
                    },
                    onChangeTeam = { changingTeam = true; step = Step.SPORT },
                )
                Step.SPORT -> SportStep(sportId) { sportId = it; competition = null; category = null; step = Step.CATEGORY }
                Step.CATEGORY -> CategoryStep(c, sportId) { comp, cat -> competition = comp; category = cat; step = Step.TEAM }
                Step.TEAM -> {
                    val comp = competition
                    val cat = category
                    if (comp == null || cat == null) {
                        LaunchedEffect(Unit) { step = Step.CATEGORY }
                    } else {
                        TeamStep(c, sportId, comp, cat, preselected = existing?.teamId ?: 0) { t -> saveWithTeam(t, comp, cat) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameStep(
    name: String,
    onName: (String) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
    isEdit: Boolean,
    hasPresetTeam: Boolean,
    onNext: () -> Unit,
    onChangeTeam: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.kid_name_question), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.kid_name_hint), style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { onName(it.take(24)) },
            label = { Text(stringResource(R.string.kid_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.kid_color), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Notifier.KID_COLORS.indices.forEach { i ->
                val sel = i == color
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(kidColor(i))
                        .border(if (sel) 3.dp else 0.dp, if (sel) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .clickable(role = Role.RadioButton) { onColor(i) }
                        .semantics { selected = sel; contentDescription = "color ${i + 1}" },
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.Black.copy(alpha = 0.7f)) }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            Text(stringResource(if (isEdit || hasPresetTeam) R.string.save else R.string.next))
        }
        if (isEdit) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onChangeTeam, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(stringResource(R.string.change_team))
            }
        }
    }
}

@Composable
private fun SportStep(selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.kid_sport_question), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        listOf(SportKind.FUTSAL, SportKind.BASKETBALL, SportKind.VOLLEYBALL).forEach { k ->
            FlatCard(Modifier.padding(vertical = 6.dp), borderColor = if (k.siteId == selected) MaterialTheme.colorScheme.primary else null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 84.dp).clickable { onPick(k.siteId) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(sportIcon(k), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(sportName(k), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.muted)
                }
            }
        }
    }
}

@Composable
private fun CategoryStep(c: AppContainer, sportId: Int, onPick: (Competition, Category) -> Unit) {
    val scope = rememberCoroutineScope()
    val (load, retry) = rememberLoad(sportId) { c.repository.competitions(sportId, includeClosed = false) }
    var picking by remember { mutableStateOf<Competition?>(null) }
    var choices by remember { mutableStateOf<List<Category>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(stringResource(R.string.kid_category_question), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp))
            Text(stringResource(R.string.kid_category_hint), style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
        }
        item {
            LoadBox(load, retry) { list: List<Competition> ->
                FlatCard {
                    list.forEachIndexed { i, comp ->
                        if (i > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = picking == null) {
                                picking = comp
                                failed = false
                                scope.launch {
                                    try {
                                        val cats = c.repository.categories(comp.id)
                                        if (cats.size == 1) onPick(comp, cats.single()) else choices = cats
                                        if (cats.isEmpty()) failed = true
                                    } catch (e: Exception) {
                                        failed = true
                                    } finally {
                                        if (choices.isEmpty()) picking = null
                                    }
                                }
                            }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(comp.name.lowercase().replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyLarge)
                                ageHint(comp.name)?.let { Text(stringResource(R.string.age_hint, it), style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted) }
                            }
                            if (picking == comp && choices.isEmpty()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.muted)
                        }
                        if (picking == comp && choices.size > 1) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                choices.forEach { cat -> FilterChip(selected = false, onClick = { onPick(comp, cat) }, label = { Text(cat.name) }) }
                            }
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.sync_failed), Modifier.padding(16.dp), color = JdcmColors.extra.danger)
            }
        }
    }
}

@Composable
private fun TeamStep(c: AppContainer, sportId: Int, comp: Competition, cat: Category, preselected: Int, onPick: (TeamInGroup) -> Unit) {
    var progress by remember { mutableStateOf(0 to 0) }
    val (load, retry) = rememberLoad(comp.id, cat.id) {
        c.repository.teamsOfCategory(sportId, comp.id, cat.id) { d, t -> progress = d to t }
            .sortedWith(compareBy({ fold(it.team.name) }, { it.group.name }))
    }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.kid_team_question), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp))
        Text(
            comp.name.lowercase().replaceFirstChar { it.titlecase() } + " · " + sportName(SportKind.of(sportId)),
            style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.search_school)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (load is dev.jdcm.ui.explore.Load.Loading) {
            val (d, t) = progress
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    if (t > 0) stringResource(R.string.finding_teams_progress, d, t) else stringResource(R.string.finding_teams),
                    style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted,
                )
                Spacer(Modifier.height(8.dp))
                if (t > 0) LinearProgressIndicator(progress = { d.toFloat() / t }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (load !is dev.jdcm.ui.explore.Load.Loading) LoadBox(load, retry) { all: List<TeamInGroup> ->
            val q = fold(query)
            val shown = if (q.isBlank()) all else all.filter { fold(it.team.name).contains(q) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) item { Text(stringResource(R.string.no_team_found), Modifier.padding(20.dp), color = JdcmColors.extra.muted) }
                items(shown, key = { "${it.group.id}-${it.team.id}" }) { t ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onPick(t) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Crest(t.team, 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.team.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(t.group.name, style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
                        }
                        if (t.team.id == preselected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Hairline(Modifier.padding(start = 66.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Accent- and case-insensitive, quotes dropped: «Nuestra Señora» finds «NUESTRA SEÑORA DE LA CONSOLACIÓN "A"». */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("\"", "").lowercase().trim()
