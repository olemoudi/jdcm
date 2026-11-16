package dev.jdcm.pump

import java.io.File
import kotlin.system.exitProcess

/**
 * `pump --config pwa/groups.json --prev <data branch checkout> --out <dir> [--force] [--status-file f]`
 *
 * Exit 0 when the run did its job (even if the site was down: that is not ours to fix, and the data
 * already published stays); non-zero only for our own failures (bad config, a bug), so the workflow
 * mails the owner only when there is something to act on. `--status-file` gets `publish=true|false`
 * and the counts, in the format of `$GITHUB_OUTPUT`.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val config = PumpConfig.read(File(opts["config"] ?: usage("--config is required")))
    val out = File(opts["out"] ?: usage("--out is required"))
    if (out.exists() && (out.listFiles()?.isNotEmpty() == true)) usage("--out must be empty: ${out.path}")
    val prev = opts["prev"]?.let(::File)?.takeIf { it.isDirectory }
    val limits = Limits().let { l -> opts["max-requests"]?.toIntOrNull()?.let { l.copy(maxRequests = it) } ?: l }
    Log.i("start", "groups=${config.groups.size} prev=${prev != null} force=${"force" in opts} maxRequests=${limits.maxRequests}")
    val result = Pump(HttpSource(), config, prev, out, limits).run(force = "force" in opts)
    opts["status-file"]?.let { f ->
        File(f).appendText(
            "publish=${result.publish}\nrequests=${result.requests}\nfetched=${result.fetched}\n" +
                "failed=${result.failed}\nstopped=${result.stopped.orEmpty()}\n",
        )
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) usage("unexpected argument $a")
        val key = a.removePrefix("--")
        if (key == "force") out[key] = "true" else {
            out[key] = args.getOrNull(i + 1) ?: usage("$a needs a value")
            i++
        }
        i++
    }
    return out
}

private fun usage(msg: String): Nothing {
    System.err.println("pump: $msg")
    System.err.println("usage: pump --config <groups.json> --out <empty dir> [--prev <dir>] [--force] [--status-file <file>] [--max-requests n]")
    exitProcess(2)
}
