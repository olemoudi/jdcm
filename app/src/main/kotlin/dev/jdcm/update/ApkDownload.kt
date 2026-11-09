package dev.jdcm.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The verified, staged APK. Only ever whole and verified: partial bytes live in `update-<sha16>.part`. */
const val APK_FILE = "update.apk"

/**
 * The APK client: long call timeout (inside WorkManager's 10-minute budget), no cache, no https→http redirect.
 * Derive it from the app's client so connection pools are shared.
 */
fun apkClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .cache(null)
    .callTimeout(8, TimeUnit.MINUTES)
    .readTimeout(60, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()

/** Bad bytes: the part file was deleted, retrying the same download is pointless until the manifest changes. */
class BadDownloadException(message: String) : Exception(message)

/**
 * Downloads what [m] names to `[dir]/update.apk`, resuming a previous partial download (Range), and renames it into
 * place only when the exact size AND the SHA-256 match the manifest (rwilco resume + walcott digest-keyed part +
 * znhr size/sha verification). Blocking: call on Dispatchers.IO.
 *
 * Throws [IOException] for network trouble (the part is KEPT: the next attempt asks for the rest — a weak link once
 * burned 300 MB on a 60 MB APK restarting from byte 0) and [BadDownloadException] for wrong bytes (part deleted).
 */
fun downloadVerified(
    client: OkHttpClient,
    request: Request,
    m: ChannelManifest,
    dir: File,
    maxBytes: Long,
    onProgress: (written: Long) -> Unit = {},
): File {
    if (!m.verifiable(maxBytes)) throw BadDownloadException("manifest without a valid size/sha256") // fail closed
    val target = File(dir, APK_FILE)
    val part = File(dir, "update-${m.sha256Hex.take(16)}.part") // keyed by the bytes it will become
    dir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".part") && f != part }?.forEach { it.delete() }
    var have = if (part.isFile) part.length() else 0L
    if (have > m.size) { part.delete(); have = 0 }
    if (have < m.size) {
        val req = if (have > 0) request.newBuilder().header("Range", "bytes=$have-").build() else request
        client.newCall(req).execute().use { resp ->
            val start: Long = when (resp.code) {
                200 -> 0L // the server ignored the range: start over
                206 -> resp.header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()
                    ?.toLongOrNull()?.takeIf { it == have } // must continue exactly where we are
                    ?: throw IOException("unexpected Content-Range ${resp.header("Content-Range")}")
                416 -> { part.delete(); throw IOException("range not satisfiable; starting over next time") }
                else -> throw IOException("download failed: HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("no body")
            FileOutputStream(part, start > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = start
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > m.size) { part.delete(); throw BadDownloadException("larger than the manifest says") }
                        out.write(buf, 0, n)
                        onProgress(written)
                    }
                    out.fd.sync()
                }
            }
        }
    }
    // Short: the connection ended early. Keep the part; the next attempt asks for the rest.
    if (part.length() != m.size) throw IOException("have ${part.length()} of ${m.size} bytes")
    if (!sha256Of(part).equals(m.sha256Hex, ignoreCase = true)) {
        part.delete()
        throw BadDownloadException("sha256 mismatch")
    }
    target.delete()
    if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
    return target
}

fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
