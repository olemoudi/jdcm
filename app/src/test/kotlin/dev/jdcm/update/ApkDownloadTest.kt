package dev.jdcm.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ApkDownloadTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val manifest = ChannelManifest(versionCode = 2, versionName = "1.0.1-beta", assetId = 1, size = bytes.size.toLong(), sha256 = sha)

    @TempDir lateinit var dir: File

    @BeforeEach fun start() { server = MockWebServer().apply { start() } }
    @AfterEach fun stop() = server.shutdown()

    private fun req() = Request.Builder().url(server.url("/myapp.apk")).build()
    private fun body(from: Int = 0, to: Int = bytes.size) = Buffer().write(bytes, from, to - from)
    private fun parts() = dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().toList()

    @Test
    fun `a whole download is verified and staged`() {
        server.enqueue(MockResponse().setBody(body()))
        val f = downloadVerified(client, req(), manifest, dir, maxBytes = 1_000_000)
        assertEquals(APK_FILE, f.name)
        assertTrue(f.readBytes().contentEquals(bytes))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `a cut connection keeps the part and the next attempt resumes with Range`() {
        server.enqueue(MockResponse().setBody(body(0, 80_000)).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        assertThrows(IOException::class.java) { downloadVerified(client, req(), manifest.copy(), dir, 1_000_000) }
        assertEquals(80_000L, parts().single().length())

        server.takeRequest()
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 80000-${bytes.size - 1}/${bytes.size}")
                .setBody(body(80_000)),
        )
        val f = downloadVerified(client, req(), manifest, dir, 1_000_000)
        assertEquals("bytes=80000-", server.takeRequest().getHeader("Range"))
        assertTrue(f.readBytes().contentEquals(bytes))
    }

    @Test
    fun `a server that ignores Range starts over`() {
        File(dir, "update-${sha.take(16)}.part").writeBytes(bytes.copyOf(50_000))
        server.enqueue(MockResponse().setBody(body()))
        val f = downloadVerified(client, req(), manifest, dir, 1_000_000)
        assertTrue(f.readBytes().contentEquals(bytes))
    }

    @Test
    fun `wrong bytes are deleted, never staged`() {
        val evil = bytes.copyOf().also { it[10] = 99 }
        server.enqueue(MockResponse().setBody(Buffer().write(evil)))
        assertThrows(BadDownloadException::class.java) { downloadVerified(client, req(), manifest, dir, 1_000_000) }
        assertTrue(parts().isEmpty())
        assertFalse(File(dir, APK_FILE).exists())
    }

    @Test
    fun `more bytes than the manifest says is refused`() {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes + ByteArray(10))))
        assertThrows(BadDownloadException::class.java) { downloadVerified(client, req(), manifest, dir, 1_000_000) }
        assertTrue(parts().isEmpty())
    }

    @Test
    fun `a manifest without a digest downloads nothing`() {
        assertThrows(BadDownloadException::class.java) { downloadVerified(client, req(), manifest.copy(sha256 = ""), dir, 1_000_000) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an HTTP error keeps nothing and says so`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows(IOException::class.java) { downloadVerified(client, req(), manifest, dir, 1_000_000) }
        assertTrue(e.message!!.contains("404"))
    }
}
