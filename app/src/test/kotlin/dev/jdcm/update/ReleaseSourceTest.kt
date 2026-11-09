package dev.jdcm.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReleaseSourceTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val manifest = """{"versionCode":5,"versionName":"1.1.0-beta","apk":"https://github.com/OWNER/REPO/releases/download/v1.1.0-beta/myapp.apk","assetId":77,"size":10,"sha256":"${"a".repeat(64)}"}"""

    @BeforeEach fun start() { server = MockWebServer().apply { start() } }
    @AfterEach fun stop() = server.shutdown()

    private fun base() = server.url("").toString().trimEnd('/')
    private fun private(token: String? = "github_pat_test") = PrivateRepoSource(client, { token }, repo = "OWNER/REPO", apiBase = base())

    @Test
    fun `public manifest comes from the raw file of the channel`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest))
        val r = PublicRepoSource(client, repo = "OWNER/REPO", rawBase = base()).manifest(UpdateChannel.BETA)
        assertEquals(5, (r as ManifestResult.Ok).manifest.versionCode)
        assertEquals("/OWNER/REPO/main/channels/beta.json", server.takeRequest().path)
    }

    @Test
    fun `public 404 means nothing published on that channel yet`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ManifestResult.NotPublished, PublicRepoSource(client, rawBase = base()).manifest(UpdateChannel.ALPHA))
    }

    @Test
    fun `public APK only from this repo's release download`() = runBlocking {
        val src = PublicRepoSource(client, repo = "OWNER/REPO", rawBase = base())
        val ok = ChannelManifest.parse(manifest)!!
        assertEquals(ok.apk, src.apkRequest(ok)!!.url.toString())
        assertNull(src.apkRequest(ok.copy(apk = "https://evil.example/myapp.apk")))
    }

    @Test
    fun `private manifest goes through the API with the token`() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest).setHeader("github-authentication-token-expiration", "2027-01-01 00:00:00 UTC"))
        val r = private().manifest(UpdateChannel.ALPHA) as ManifestResult.Ok
        assertEquals("2027-01-01 00:00:00 UTC", r.tokenExpiry)
        val req = server.takeRequest()
        assertEquals("/repos/OWNER/REPO/contents/channels/alpha.json?ref=main", req.path)
        assertEquals("Bearer github_pat_test", req.getHeader("Authorization"))
        assertEquals("application/vnd.github.raw+json", req.getHeader("Accept"))
    }

    @Test
    fun `no token means no network`() = runBlocking {
        assertEquals(ManifestResult.NeedsToken, private(token = null).manifest(UpdateChannel.BETA))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 404 the repo itself also gives is the token, not an empty channel`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ManifestResult.TokenRejected, private().manifest(UpdateChannel.BETA))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"private":true}"""))
        assertEquals(ManifestResult.NotPublished, private().manifest(UpdateChannel.BETA))
    }

    @Test
    fun `rate limit and rejection are told apart`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("x-ratelimit-remaining", "0").setHeader("x-ratelimit-reset", "1999999999"))
        assertEquals(ManifestResult.RateLimited(1999999999), private().manifest(UpdateChannel.BETA))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ManifestResult.TokenRejected, private().manifest(UpdateChannel.BETA))
    }

    @Test
    fun `the token never follows the asset to the storage host`() = runBlocking {
        val storage = MockWebServer().apply { start() }
        try {
            storage.enqueue(MockResponse().setBody("apk"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/blob").toString()))
            val req = private().apkRequest(ChannelManifest.parse(manifest)!!)!!
            client.newCall(req).execute().use { assertEquals("apk", it.body!!.string()) }
            val first = server.takeRequest()
            assertEquals("/repos/OWNER/REPO/releases/assets/77", first.path)
            assertEquals("application/octet-stream", first.getHeader("Accept"))
            assertTrue(first.getHeader("Authorization")!!.startsWith("Bearer "))
            assertNull(storage.takeRequest().getHeader("Authorization"))
        } finally {
            storage.shutdown()
        }
    }
}
