package dev.jdcm.pump

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The four ids the site wants to serve a group's calendar (the app's `GroupRef`). */
@Serializable
data class GroupRef(val groupId: Int, val sportId: Int, val competitionId: Int, val categoryId: Int)

/** One entry of `pwa/groups.json`: a group the pump keeps up to date. [label] is for humans only. */
@Serializable
data class FollowedGroup(
    val groupId: Int,
    val sportId: Int,
    val competitionId: Int,
    val categoryId: Int,
    val label: String = "",
) {
    val ref: GroupRef get() = GroupRef(groupId, sportId, competitionId, categoryId)
}

@Serializable
data class PumpConfig(val groups: List<FollowedGroup> = emptyList()) {
    companion object {
        /** More than this and one run could no longer refresh every group's matchday within its budget. */
        const val MAX_GROUPS = 40

        fun read(file: File): PumpConfig {
            val config = PumpJson.decodeFromString(serializer(), file.readText())
            val bad = config.groups.filter { it.groupId <= 0 || it.sportId <= 0 || it.competitionId <= 0 || it.categoryId <= 0 }
            require(bad.isEmpty()) { "groups.json: every id must be positive (${bad.map { it.groupId }})" }
            val dupes = config.groups.groupBy { it.groupId }.filterValues { it.size > 1 }.keys
            require(dupes.isEmpty()) { "groups.json: group listed twice $dupes" }
            require(config.groups.size <= MAX_GROUPS) { "groups.json: ${config.groups.size} groups, at most $MAX_GROUPS" }
            return config
        }
    }
}

/** Compact output (it is served to phones), tolerant input (a field added later must not break a read). */
val PumpJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
