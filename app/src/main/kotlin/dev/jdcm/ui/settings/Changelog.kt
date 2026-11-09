package dev.jdcm.ui.settings

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One version's «Novedades», as written in `release-notes/<versionName>.json`. */
data class ReleaseNote(val version: String, val es: String, val en: String) {
    fun text(language: String): String = (if (language == "es") es else en).ifBlank { es.ifBlank { en } }
    fun bullets(language: String): List<String> = text(language).lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotEmpty() }
}

/**
 * The bundled changelog. Notes are short by rule — at most [MAX_BULLETS] bullets of at most
 * [MAX_BULLET_CHARS] characters per language, enforced by `ChangelogTest` — because the owner
 * wants «Novedades» read in seconds, never a wall of text.
 */
object Changelog {
    const val MAX_BULLETS = 3
    const val MAX_BULLET_CHARS = 140

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ReleaseNote> {
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return emptyList()
        return root.mapNotNull { (version, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val es = (o["es"] as? JsonPrimitive)?.content.orEmpty()
            val en = (o["en"] as? JsonPrimitive)?.content.orEmpty()
            if (es.isBlank() && en.isBlank()) null else ReleaseNote(version, es, en)
        }.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    /** "0.10.0-alpha" > "0.9.3-beta": numeric parts compared as numbers, the suffix ignored. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun load(context: Context): List<ReleaseNote> =
        runCatching { context.assets.open("changelog.json").bufferedReader().use { it.readText() } }.map(::parse).getOrDefault(emptyList())
}
