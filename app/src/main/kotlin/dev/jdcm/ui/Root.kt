package dev.jdcm.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.jdcm.AppContainer
import dev.jdcm.BuildConfig
import dev.jdcm.R
import dev.jdcm.core.model.Match
import dev.jdcm.data.GroupRef
import dev.jdcm.diag.DiagLog
import dev.jdcm.diag.collectDiagnostics
import dev.jdcm.diag.render
import dev.jdcm.ui.explore.ExploreCompetitionScreen
import dev.jdcm.ui.explore.ExploreScreen
import dev.jdcm.ui.explore.ExploreSportScreen
import dev.jdcm.ui.explore.GroupedRoundScreen
import dev.jdcm.ui.group.GroupScreen
import dev.jdcm.ui.group.TeamScreen
import dev.jdcm.ui.home.HomeScreen
import dev.jdcm.ui.home.TablesScreen
import dev.jdcm.ui.kid.KidEditorScreen
import dev.jdcm.ui.match.MatchSheet
import dev.jdcm.ui.onboarding.OnboardingScreen
import dev.jdcm.ui.nav.ChangelogRoute
import dev.jdcm.ui.nav.DiagnosticsRoute
import dev.jdcm.ui.nav.ExploreCompetitionRoute
import dev.jdcm.ui.nav.ExploreRoute
import dev.jdcm.ui.nav.ExploreSportRoute
import dev.jdcm.ui.nav.GroupRoute
import dev.jdcm.ui.nav.GroupedRoundRoute
import dev.jdcm.ui.nav.HomeRoute
import dev.jdcm.ui.nav.KidEditorRoute
import dev.jdcm.ui.nav.SettingsRoute
import dev.jdcm.ui.nav.TablesRoute
import dev.jdcm.ui.nav.TeamRoute
import dev.jdcm.ui.settings.ChangelogScreen
import dev.jdcm.ui.settings.DiagnosticsScreen
import dev.jdcm.ui.settings.SettingsScreen
import dev.jdcm.ui.settings.WhatsNewSheet
import dev.jdcm.ui.settings.appDiagSections
import dev.jdcm.ui.theme.JdcmTheme
import dev.jdcm.work.SyncWorker
import kotlinx.coroutines.flow.first

/** What any screen can ask of the shell: open a match's sheet, get directions to a field. */
data class MatchArgs(val match: Match, val ref: GroupRef?, val highlightTeamId: Int = 0, val accent: Color? = null, val title: String = "")

class AppActions(
    val openMatch: (MatchArgs) -> Unit,
    val directions: (Match) -> Unit,
    val openUrl: (String) -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

fun openDirections(ctx: Context, m: Match) {
    val v = m.venue ?: return
    val uri = if (v.hasPosition) Uri.parse("geo:${v.lat},${v.lng}?q=${v.lat},${v.lng}(${Uri.encode(v.name)})")
    else Uri.parse("geo:0,0?q=" + Uri.encode(listOf(v.address, v.postcode, v.town).filter { it.isNotBlank() }.joinToString(" ")))
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // No maps app: the browser's Google Maps (the site's own link) does it.
        if (v.hasPosition) openUrl(ctx, "https://www.google.com/maps/search/?api=1&query=${v.lat},${v.lng}")
    }
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { DiagLog.w("UI", "no app for url: ${it.javaClass.simpleName}") }
}

@Composable
fun JdcmRoot(c: AppContainer, pendingOpen: MutableState<OpenRequest?>) {
    val settings by c.settings.state.collectAsState()
    val ctx = LocalContext.current
    JdcmTheme(settings.theme) {
        val nav = rememberNavController()
        var sheet by remember { mutableStateOf<MatchArgs?>(null) }
        val actions = remember {
            AppActions(
                openMatch = { sheet = it },
                directions = { openDirections(ctx, it) },
                openUrl = { openUrl(ctx, it) },
            )
        }

        // A notification's match: open its sheet over Home once the group is loaded.
        val open = pendingOpen.value
        LaunchedEffect(open) {
            if (open == null) return@LaunchedEffect
            val snap = c.repository.load(open.groupId)
            val kid = c.kids.current().firstOrNull { it.id == open.kidId }
            val m = snap?.matches?.firstOrNull { it.id == open.matchId }
            if (m != null && snap != null) {
                sheet = MatchArgs(
                    m, GroupRef(snap.group.id, snap.sportId, snap.competitionId, snap.categoryId),
                    kid?.teamId ?: 0, kid?.let { dev.jdcm.ui.theme.kidColor(it.color) },
                )
            }
            pendingOpen.value = null
        }

        CompositionLocalProvider(LocalActions provides actions, LocalContainer provides c) {
            var onboarding by remember { mutableStateOf(!c.settings.onboardingDone) }
            // Navigation waits for the NavHost below to exist: navigating from the guide itself
            // crashed 0.2.0's first build ("setGraph() before getGraph()").
            var addKidAfterOnboarding by remember { mutableStateOf(false) }
            if (onboarding) {
                val kids by c.kids.kids.collectAsState()
                LaunchedEffect(Unit) { c.kids.load() }
                OnboardingScreen(c, hasKids = !kids.isNullOrEmpty()) { addKid ->
                    c.settings.onboardingDone = true
                    // The guide is this version's news: no «Novedades» sheet right after it.
                    c.settings.seenVersionCode = BuildConfig.VERSION_CODE
                    onboarding = false
                    addKidAfterOnboarding = addKid
                }
                return@CompositionLocalProvider
            }
            val entry by nav.currentBackStackEntryAsState()
            val dest = entry?.destination
            val topLevel = dest == null || dest.hasRoute(HomeRoute::class) || dest.hasRoute(TablesRoute::class) || dest.hasRoute(ExploreRoute::class)
            Scaffold(
                bottomBar = {
                    if (topLevel) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            val tabs = listOf(
                                Triple(HomeRoute, R.string.tab_matches, Icons.Filled.CalendarMonth to Icons.Outlined.CalendarMonth),
                                Triple(TablesRoute, R.string.tab_tables, Icons.Filled.FormatListNumbered to Icons.Outlined.FormatListNumbered),
                                Triple(ExploreRoute, R.string.tab_explore, Icons.Filled.Explore to Icons.Outlined.Explore),
                            )
                            tabs.forEach { (route, label, icons) ->
                                val selected = dest?.hasRoute(route::class) == true || (dest == null && route == HomeRoute)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(if (selected) icons.first else icons.second, null) },
                                    label = { Text(stringResource(label)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { pad ->
                NavHost(
                    nav,
                    startDestination = HomeRoute,
                    modifier = Modifier.padding(bottom = pad.calculateBottomPadding()),
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            c,
                            onSettings = { nav.navigate(SettingsRoute) },
                            onAddKid = { nav.navigate(KidEditorRoute()) },
                            onExplore = { nav.navigate(ExploreRoute) { launchSingleTop = true } },
                            onGroup = { nav.navigate(it) },
                            onTeam = { nav.navigate(it) },
                        )
                    }
                    composable<TablesRoute> {
                        TablesScreen(c, onSettings = { nav.navigate(SettingsRoute) }, onAddKid = { nav.navigate(KidEditorRoute()) }, onTeam = { nav.navigate(it) })
                    }
                    composable<ExploreRoute> {
                        ExploreScreen(onSport = { nav.navigate(ExploreSportRoute(it)) }, onSettings = { nav.navigate(SettingsRoute) })
                    }
                    composable<ExploreSportRoute> { e ->
                        val r = e.toRoute<ExploreSportRoute>()
                        ExploreSportScreen(c, r.sportId, onBack = { nav.popBackStack() }, onCompetition = { nav.navigate(it) }, onGrouped = { nav.navigate(it) })
                    }
                    composable<ExploreCompetitionRoute> { e ->
                        val r = e.toRoute<ExploreCompetitionRoute>()
                        ExploreCompetitionScreen(c, r, onBack = { nav.popBackStack() }, onGroup = { nav.navigate(it) }, onGrouped = { nav.navigate(it) })
                    }
                    composable<GroupedRoundRoute> { e ->
                        GroupedRoundScreen(c, e.toRoute(), onBack = { nav.popBackStack() }, onGroup = { nav.navigate(it) })
                    }
                    composable<GroupRoute> { e ->
                        GroupScreen(c, e.toRoute(), onBack = { nav.popBackStack() }, onTeam = { nav.navigate(it) })
                    }
                    composable<TeamRoute> { e ->
                        TeamScreen(c, e.toRoute(), onBack = { nav.popBackStack() }, onGroup = { nav.navigate(it) }, onFollow = { nav.navigate(it) })
                    }
                    composable<KidEditorRoute> { e ->
                        KidEditorScreen(c, e.toRoute(), onDone = {
                            nav.navigate(HomeRoute) { popUpTo(HomeRoute) { inclusive = true } }
                        }, onBack = { nav.popBackStack() })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            c,
                            onBack = { nav.popBackStack() },
                            onAddKid = { nav.navigate(KidEditorRoute()) },
                            onEditKid = { nav.navigate(KidEditorRoute(kidId = it)) },
                            onChangelog = { nav.navigate(ChangelogRoute) },
                            onDiagnostics = { nav.navigate(DiagnosticsRoute) },
                        )
                    }
                    composable<ChangelogRoute> { ChangelogScreen(onBack = { nav.popBackStack() }) }
                    composable<DiagnosticsRoute> {
                        val app = ctx.applicationContext as android.app.Application
                        DiagnosticsScreen(
                            buildReport = { collectDiagnostics(app, listOf(SyncWorker.NAME)) { appDiagSections(c) }.render() },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }

            LaunchedEffect(addKidAfterOnboarding) {
                if (addKidAfterOnboarding) {
                    androidx.compose.runtime.snapshotFlow { nav.currentBackStackEntry }.first { it != null }
                    addKidAfterOnboarding = false
                    nav.navigate(KidEditorRoute())
                }
            }

            sheet?.let { args ->
                MatchSheet(
                    args,
                    onDismiss = { sheet = null },
                    onTeam = { ref, teamId ->
                        sheet = null
                        nav.navigate(TeamRoute(ref.groupId, ref.sportId, ref.competitionId, ref.categoryId, teamId))
                    },
                )
            }
            WhatsNewSheet(c, onAll = { nav.navigate(ChangelogRoute) })
        }
    }
}

/** True on the build that should show Novedades (debug builds share the code). */
val isDebugBuild: Boolean get() = BuildConfig.DEBUG
