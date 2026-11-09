package dev.jdcm.diag

import android.app.Application
import android.util.Log
import dev.jdcm.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Always-on diagnostic log: one short line per event or decision worth having when something goes wrong,
 * every warning and error, crashes, and how previous processes ended. Callers never wait; nothing here may
 * ever throw into the app. [DiagReport] turns it into the text the user copies into a chat or an issue.
 *
 * Policy: log ids, counts, lengths, enums, durations and rounded coordinates — never user-entered text,
 * names, contacts, precise locations, tokens or URLs with query strings. [Redactor] is the safety net.
 *
 * From the oleskills `android-diagnostic-log` skill: znhr's logger (two rotating files, flush per line,
 * levels with separate rate budgets, crash handler, exit reasons), rwilco's repeat folding, walcott's
 * "the crash line must be written before the process dies".
 */
object DiagLog {
    const val FILE_MAX_BYTES = 256 * 1024L
    private const val CUR = "diag.log"
    private const val OLD = "diag.1.log"
    private const val LINE_MAX = 1_000
    private const val STACK_LINES = 40
    private const val STACK_MAX = 5_000
    /** Per wall-clock minute: events, and warnings/errors on their own budget (a flood never hides a failure). */
    private const val EVENTS_PER_MINUTE = 120
    private const val PROBLEMS_PER_MINUTE = 60
    private const val MEMORY_LINES = 300
    /** The same line again within this window is folded into a count instead of written. */
    private const val REPEAT_WINDOW_MS = 60_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-io").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var dir: File? = null
    private var out: BufferedWriter? = null // io thread only
    private var outBytes = 0L               // io thread only

    private val memory = ArrayDeque<String>() // guarded by itself

    // Guarded by `this`.
    private var minute = 0L
    private var events = 0
    private var problems = 0
    private var dropped = 0
    private var lastKey: String? = null
    private var runStartMs = 0L
    private var repeats = 0
    private var lastOffset: ZoneOffset? = null

    private val _version = MutableStateFlow(0L)
    /** Bumps on every stored line; the diagnostics screen rebuilds on it. */
    val version: StateFlow<Long> = _version

    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** The app's own code package (not the applicationId, which may carry a `.debug` / `.alpha` suffix). */
    private val appPackage = DiagLog::class.java.name.substringBeforeLast(".diag.")

    /** FIRST thing in Application.onCreate: files, crash handler, and how earlier processes ended. */
    fun init(app: Application) {
        val d = File(app.filesDir, "diag").apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                record('F', "Crash", "uncaught on thread ${thread.name}", e)
                // The io thread lives until the previous handler kills the process: let it drain first.
                flush(1_500)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e) // never swallow: Android's own handling must still run
        }
        io.execute { runCatching { ExitReasons.logNew(app, File(d, "exit.seen")) } }
    }

    fun i(tag: String, msg: String) = record('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = record('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = record('E', tag, msg, t)

    internal fun record(level: Char, tag: String, msg: String, t: Throwable?) {
        try {
            mirrorToLogcat(level, tag, msg, t)
            val now = System.currentTimeMillis()
            val clean = Redactor.oneLine(Redactor.redact(msg)).take(LINE_MAX)
            val batch = ArrayList<String>(3)
            var keep: Boolean
            synchronized(this) {
                val m = now / 60_000
                if (m != minute) {
                    if (dropped > 0) batch += line(now, 'W', "Diag", "$dropped lines dropped in the last minute (too many)")
                    minute = m; events = 0; problems = 0; dropped = 0
                }
                val key = "$level|$tag|$clean"
                if (level != 'F' && t == null && key == lastKey && now - runStartMs < REPEAT_WINDOW_MS) {
                    repeats++
                    return
                }
                if (repeats > 0) batch += line(now, 'I', "Diag", "previous line repeated $repeats more times")
                lastKey = key; runStartMs = now; repeats = 0
                val zone = ZoneId.systemDefault()
                val offset = zone.rules.getOffset(Instant.ofEpochMilli(now))
                if (offset != lastOffset) { // first line of the process, travel, DST
                    batch += line(now, 'I', "Diag", "clock zone ${zone.id} (UTC$offset)")
                    lastOffset = offset
                }
                keep = when (level) {
                    'F' -> true
                    'I' -> ++events <= EVENTS_PER_MINUTE
                    else -> ++problems <= PROBLEMS_PER_MINUTE
                }
                if (!keep) dropped++
            }
            if (keep) {
                val detail = when {
                    t == null -> ""
                    level == 'W' -> " | " + summary(t)
                    else -> "\n" + stack(t)
                }
                batch += line(now, level, tag, clean + detail)
            }
            if (batch.isEmpty()) return
            synchronized(memory) {
                batch.forEach(memory::addLast)
                while (memory.size > MEMORY_LINES) memory.removeFirst()
            }
            if (dir != null) io.execute { batch.forEach(::write) }
            _version.update { it + 1 }
        } catch (_: Throwable) {
            // Diagnostics must never break the app.
        }
    }

    private fun mirrorToLogcat(level: Char, tag: String, msg: String, t: Throwable?) {
        if (level == 'I' && !BuildConfig.DEBUG) return
        runCatching { // android.util.Log is a throwing stub in plain JVM tests
            val text = Redactor.redact(if (t == null) msg else msg + "\n" + Log.getStackTraceString(t))
            when (level) {
                'I' -> Log.i("app/$tag", text)
                'W' -> Log.w("app/$tag", text)
                else -> Log.e("app/$tag", text)
            }
        }
    }

    private fun line(now: Long, level: Char, tag: String, msg: String) =
        "${stamp.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))} $level $tag: $msg"

    /** "IOException: timeout ← SocketTimeoutException: … @ Repo.kt:88" — redacted before it is cut. */
    private fun summary(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(4).joinToString(" ← ") {
            "${it.javaClass.simpleName}: ${Redactor.oneLine(Redactor.redact(it.message.orEmpty())).take(160)}"
        }
        val at = t.stackTrace.firstOrNull { it.className.startsWith(appPackage) }
            ?.let { " @ ${it.fileName}:${it.lineNumber}" }.orEmpty()
        return chain + at
    }

    private fun stack(t: Throwable): String =
        Redactor.redact(t.stackTraceToString()).lineSequence().take(STACK_LINES).joinToString("\n").take(STACK_MAX)

    private fun write(text: String) { // io thread only
        val d = dir ?: return
        try {
            if (out != null && outBytes > FILE_MAX_BYTES) { // rotate: two files, never a rewrite
                runCatching { out?.close() }
                out = null
                File(d, OLD).delete()
                File(d, CUR).renameTo(File(d, OLD))
            }
            val w = out ?: run {
                val f = File(d, CUR)
                outBytes = f.length()
                BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)).also { out = it }
            }
            w.write(text)
            w.newLine()
            w.flush() // per line: a kill right after must not lose the lines that explain it
            outBytes += text.toByteArray(Charsets.UTF_8).size + 1
        } catch (_: Exception) {
            runCatching { out?.close() }
            out = null // disk full or gone: drop the line, retry with the next one
        }
    }

    /** Waits (at most [timeoutMs]) until everything logged so far is on disk. */
    fun flush(timeoutMs: Long = 2_000) {
        runCatching { io.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** The newest [maxChars] of the log, whole lines, oldest first. Blocking: call off the main thread. */
    fun recent(maxChars: Int): String {
        val text = runCatching {
            io.submit(Callable {
                val d = dir ?: return@Callable null
                listOf(OLD, CUR).map { File(d, it) }.filter { it.exists() }.joinToString("") { it.readText() }
            }).get(5, TimeUnit.SECONDS)
        }.getOrNull() ?: synchronized(memory) { memory.joinToString("\n") }
        if (text.length <= maxChars) return text
        val from = text.indexOf('\n', text.length - maxChars)
        return if (from < 0) text.takeLast(maxChars) else text.substring(from + 1)
    }

    fun sizeBytes(): Long = dir?.let { d -> listOf(CUR, OLD).sumOf { File(d, it).length() } } ?: 0L

    fun clear() {
        synchronized(memory) { memory.clear() }
        io.execute {
            runCatching { out?.close() }
            out = null
            dir?.let { d -> File(d, CUR).delete(); File(d, OLD).delete() }
        }
        i("Diag", "log cleared")
    }

    /** Tests: caps and repeat folding start afresh (tests in the same minute starve each other otherwise). */
    internal fun resetForTests() = synchronized(this) {
        minute = 0; events = 0; problems = 0; dropped = 0; lastKey = null; repeats = 0; lastOffset = null
        synchronized(memory) { memory.clear() }
    }
}
