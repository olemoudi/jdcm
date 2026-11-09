package dev.jdcm.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Every update DECISION lives here, pure and JVM-tested: the parts that were wrong in rwilco/znhr/walcott were
// wrong by omission, and an omission is only visible in a table you can read all the rows of.

/**
 * Which stream of builds this phone follows. The tag suffix, the manifest file name and the versionName suffix
 * are the same word on purpose: three files written at three different times must agree (rwilco).
 */
@Serializable
enum class UpdateChannel(val suffix: String, val file: String) {
    /** Production: what a phone gets by being a phone. GitHub "latest" release. */
    BETA("-beta", "beta"),

    /** Testing: builds as they are written. GitHub pre-release. */
    ALPHA("-alpha", "alpha"),
}

/**
 * Asked of the DOWNLOADED APK, never of the manifest: a manifest edited wrongly must not move a phone onto a
 * lineage it never chose. A versionName without a suffix belongs to neither channel.
 */
fun belongsToChannel(versionName: String, channel: UpdateChannel): Boolean = versionName.endsWith(channel.suffix)

/** `channels/<alpha|beta>.json`, written by release.yml only, after the asset is verified uploaded. */
@Serializable
data class ChannelManifest(
    val versionCode: Int,
    val versionName: String = "",
    val tag: String = "",
    /** Public repos: tag-pinned download URL. */
    val apk: String = "",
    /** Private repos: the release asset id for the API download. */
    val assetId: Long = 0,
    val size: Long = 0,
    val sha256: String = "",
    @Serializable(with = LenientNotes::class) val notes: Map<String, String> = emptyMap(),
) {
    val sha256Hex: String get() = sha256.removePrefix("sha256:").lowercase()
    fun isNewerThan(installed: Int): Boolean = versionCode > installed && (apk.isNotBlank() || assetId > 0)
    fun notesFor(language: String): String = notes[language] ?: notes["en"] ?: notes.values.firstOrNull().orEmpty()

    companion object {
        // Unknown keys ignored: a field added to the manifest later must never brick the updater of every phone.
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ChannelManifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** A malformed `notes` loses the notes, never the manifest (malachi/znhr). */
private object LenientNotes :
    JsonTransformingSerializer<Map<String, String>>(MapSerializer(String.serializer(), String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return JsonObject(emptyMap())
        return JsonObject(o.filterValues { it is JsonPrimitive && it.isString })
    }
}

private val SHA256 = Regex("(sha256:)?[0-9a-fA-F]{64}")

/** A manifest a download can be checked against. Anything less is refused before downloading (fail closed). */
fun ChannelManifest.verifiable(maxBytes: Long): Boolean = size in 1..maxBytes && SHA256.matches(sha256)

/** Why a parsed APK must not be installed, or null when it may be (znhr `rejectionReason`). */
fun rejectionReason(
    archivePackage: String?,
    archiveVersionCode: Int?,
    archiveVersionName: String?,
    ourPackage: String,
    installedVersionCode: Int,
    channel: UpdateChannel,
    manifestVersionCode: Int?,
): String? = when {
    archivePackage == null || archiveVersionCode == null -> "not a readable APK" // captive-portal HTML, truncated body
    archivePackage != ourPackage -> "belongs to $archivePackage"
    archiveVersionCode <= installedVersionCode -> "version $archiveVersionCode is not newer than $installedVersionCode"
    manifestVersionCode != null && archiveVersionCode != manifestVersionCode ->
        "version $archiveVersionCode, manifest said $manifestVersionCode"
    archiveVersionName == null || !belongsToChannel(archiveVersionName, channel) ->
        "\"$archiveVersionName\" is not a ${channel.name.lowercase()} build"
    else -> null
}

/** A staged APK is installed only while it is exactly the build on offer (rwilco 08db37b; znhr cachedApk). */
fun stagedIsCurrent(stagedVersionCode: Int?, stagedSize: Long, stagedSha256: String, offer: ChannelManifest): Boolean =
    stagedVersionCode == offer.versionCode && stagedSize == offer.size && stagedSha256.equals(offer.sha256Hex, ignoreCase = true)

/** Public repos: only https://github.com/OWNER/REPO/…, parsed — a raw `startsWith` is escaped by `/../../`. */
fun trustedApkUrl(url: String, ownerSlashRepo: String): Boolean {
    val u = url.toHttpUrlOrNull() ?: return false
    return u.scheme == "https" && u.host == "github.com" && u.encodedPath.startsWith("/$ownerSlashRepo/releases/download/")
}

/** What choosing a channel will do — not the same in both directions (rwilco). */
sealed interface ChannelSwitch {
    /** Something newer is on the chosen channel: the next check installs it. */
    data class Immediate(val versionName: String) : ChannelSwitch

    /** The chosen channel is behind this phone; Android never downgrades: it rejoins at that channel's next release. */
    data class WaitsForNextRelease(val channelVersionName: String) : ChannelSwitch

    /** Exactly the build already running. Not a wait (painting it red would alarm every up-to-date phone). */
    data object AlreadyOnIt : ChannelSwitch

    /** Nothing read, or nothing published on that channel yet. */
    data object Unknown : ChannelSwitch
}

fun channelSwitch(installedVersionCode: Int, channelVersionCode: Int, channelVersionName: String): ChannelSwitch = when {
    channelVersionCode <= 0 -> ChannelSwitch.Unknown
    channelVersionCode > installedVersionCode -> ChannelSwitch.Immediate(channelVersionName)
    channelVersionCode == installedVersionCode -> ChannelSwitch.AlreadyOnIt
    else -> ChannelSwitch.WaitsForNextRelease(channelVersionName)
}

/** How an install that did not succeed holds that version back from automatic retries. */
enum class InstallSetback { DECLINED, SIGNATURE_CONFLICT, STORAGE, OTHER }

/**
 * Until when [versionCode] is left alone after [setback]. A declined dialog or a transient failure: 24 h (znhr;
 * re-prompting every check is hostile). Another signature fails identically forever: until a newer version.
 */
fun holdUntil(setback: InstallSetback, nowMs: Long): Long = when (setback) {
    InstallSetback.SIGNATURE_CONFLICT -> Long.MAX_VALUE
    else -> nowMs + 24 * 3_600_000L
}

/** Declined: keep the verified bytes for a one-tap "Install now". Anything else: the file or the device is the problem. */
fun keepsApkAfter(setback: InstallSetback): Boolean = setback == InstallSetback.DECLINED
