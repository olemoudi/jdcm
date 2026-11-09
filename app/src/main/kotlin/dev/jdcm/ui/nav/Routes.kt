package dev.jdcm.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe destinations. The three tabs are top-level; everything else is pushed on top. */
@Serializable data object HomeRoute
@Serializable data object TablesRoute
@Serializable data object ExploreRoute

@Serializable data class ExploreSportRoute(val sportId: Int)
@Serializable data class ExploreCompetitionRoute(val sportId: Int, val competitionId: Int, val competitionName: String)
/** Every group of a category (competitionId -1: every category of the sport) for one round number. */
@Serializable data class GroupedRoundRoute(val sportId: Int, val competitionId: Int, val categoryId: Int, val title: String)

@Serializable
data class GroupRoute(
    val groupId: Int,
    val sportId: Int,
    val competitionId: Int,
    val categoryId: Int,
    val title: String = "",
    /** 0 rounds, 1 standings, 2 teams. */
    val tab: Int = 0,
    /** The followed team to highlight, if any. */
    val teamId: Int = 0,
    val kidColor: Int = -1,
    val kidName: String = "",
)

@Serializable
data class TeamRoute(val groupId: Int, val sportId: Int, val competitionId: Int, val categoryId: Int, val teamId: Int)

/** Add (kidId null) or edit a child. */
@Serializable data class KidEditorRoute(val kidId: String? = null, val presetTeamId: Int = 0, val presetGroupId: Int = 0, val presetSportId: Int = 0, val presetCompetitionId: Int = 0, val presetCategoryId: Int = 0)

@Serializable data object SettingsRoute
@Serializable data object ChangelogRoute
@Serializable data object DiagnosticsRoute
