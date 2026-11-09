package dev.jdcm.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** The repository that publishes the releases and the stage-less asset name, chosen ONCE (links and QRs depend on it). */
object Distribution {
    const val REPO = "olemoudi/jdcm"
    const val APK_NAME = "jdcm.apk"
    /** The install link / QR for README: always the newest BETA (alphas are pre-releases, never "latest"). */
    const val INSTALL_URL = "https://github.com/$REPO/releases/latest/download/$APK_NAME"
}

sealed interface ManifestResult {
    data class Ok(val manifest: ChannelManifest, val tokenExpiry: String? = null) : ManifestResult
    /** 404 on the channel file: nothing published on this channel yet. Retrying in minutes will not change it. */
    data object NotPublished : ManifestResult
    /** Private repo without a stored token: no network at all. */
    data object NeedsToken : ManifestResult
    /** 401/403, or the repo itself is invisible to the token (a renewed PAT without the repo ticked). */
    data object TokenRejected : ManifestResult
    data class RateLimited(val resetEpochSec: Long) : ManifestResult
    /** Transient: WorkManager retries. */
    data class Failed(val why: String) : ManifestResult
}

/** Where manifests and APKs come from. Pick ONE implementation per app. */
interface ReleaseSource {
    suspend fun manifest(channel: UpdateChannel): ManifestResult

    /** The request for the APK [m] names, or null when it may not be fetched (untrusted URL, no token). */
    suspend fun apkRequest(m: ChannelManifest): Request?
}

/**
 * PUBLIC repository (rwilco): manifest from raw.githubusercontent.com (no token, no API rate limit; ~5 min CDN
 * cache), APK from the tag-pinned github.com URL in the manifest, which must parse as this repo's release download.
 * Never call api.github.com unauthenticated from phones: 60 requests/hour per IP.
 */
class PublicRepoSource(
    private val client: OkHttpClient,
    private val repo: String = Distribution.REPO,
    private val branch: String = "main",
    private val rawBase: String = "https://raw.githubusercontent.com",
) : ReleaseSource {
    override suspend fun manifest(channel: UpdateChannel): ManifestResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$rawBase/$repo/$branch/channels/${channel.file}.json").build()
        runCatching {
            client.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> ChannelManifest.parse(r.peekBody(MAX_MANIFEST_BYTES).string())
                        ?.let { ManifestResult.Ok(it) } ?: ManifestResult.Failed("unparseable manifest")
                    r.code == 404 -> ManifestResult.NotPublished
                    else -> ManifestResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { ManifestResult.Failed(it.javaClass.simpleName) }
    }

    override suspend fun apkRequest(m: ChannelManifest): Request? =
        if (trustedApkUrl(m.apk, repo)) Request.Builder().url(m.apk).build() else null
}

/**
 * PRIVATE repository (znhr): the user stores a fine-grained PAT (Contents: read-only, this repo only). The token
 * goes on each request — never in a network interceptor — so OkHttp drops it on the cross-host redirect of the
 * asset download to GitHub's storage host (which rejects a second credential, and must never see it).
 */
class PrivateRepoSource(
    private val client: OkHttpClient,
    private val token: suspend () -> String?,
    private val repo: String = Distribution.REPO,
    private val branch: String = "main",
    private val apiBase: String = "https://api.github.com",
) : ReleaseSource {
    private fun request(path: String, token: String, accept: String) = Request.Builder()
        .url(apiBase + path)
        .header("Authorization", "Bearer $token")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()

    override suspend fun manifest(channel: UpdateChannel): ManifestResult = withContext(Dispatchers.IO) {
        val t = token()?.takeIf { it.isNotBlank() } ?: return@withContext ManifestResult.NeedsToken
        runCatching {
            val req = request("/repos/$repo/contents/channels/${channel.file}.json?ref=$branch", t, "application/vnd.github.raw+json")
            client.newCall(req).execute().use { r ->
                val expiry = r.header("github-authentication-token-expiration") // shown in Settings (znhr)
                when {
                    r.isSuccessful -> ChannelManifest.parse(r.peekBody(MAX_MANIFEST_BYTES).string())
                        ?.let { ManifestResult.Ok(it, expiry) } ?: ManifestResult.Failed("unparseable manifest")
                    (r.code == 403 || r.code == 429) && r.header("x-ratelimit-remaining") == "0" ->
                        ManifestResult.RateLimited(r.header("x-ratelimit-reset")?.toLongOrNull() ?: 0)
                    r.code == 401 || r.code == 403 -> ManifestResult.TokenRejected
                    // A private repo answers 404 to a token that cannot see it: ask the repo itself before
                    // concluding "nothing published yet".
                    r.code == 404 -> if (repoVisible(t)) ManifestResult.NotPublished else ManifestResult.TokenRejected
                    else -> ManifestResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { ManifestResult.Failed(it.javaClass.simpleName) }
    }

    private fun repoVisible(t: String): Boolean =
        client.newCall(request("/repos/$repo", t, "application/vnd.github+json")).execute().use { it.isSuccessful }

    /** Settings → "Validate token": true when the repo is readable (and private, as expected). */
    suspend fun validate(t: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/repos/$repo", t, "application/vnd.github+json")).execute().use { r ->
                r.isSuccessful && Json.parseToJsonElement(r.body?.string().orEmpty())
                    .jsonObject["private"]?.jsonPrimitive?.booleanOrNull == true
            }
        }.getOrDefault(false)
    }

    override suspend fun apkRequest(m: ChannelManifest): Request? {
        if (m.assetId <= 0) return null
        val t = token()?.takeIf { it.isNotBlank() } ?: return null
        // The only URL ever built for a download: this repo's asset endpoint. Integrity = size + sha256 + signature.
        return request("/repos/$repo/releases/assets/${m.assetId}", t, "application/octet-stream")
    }
}

private const val MAX_MANIFEST_BYTES = 64L * 1024
