package dev.jdcm.ui

import dev.jdcm.BuildConfig
import dev.jdcm.ui.settings.Changelog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * «Novedades» must be readable in seconds (the owner's rule): every note is at most three
 * bullets of at most 140 characters, in both languages, and the build being released has one.
 */
class ChangelogTest {
    private val dir = File("../release-notes")

    private fun all() = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().toList()

    @Test
    fun `every release note is short, in Spanish and English`() {
        assertTrue(all().isNotEmpty(), "no release notes found in ${dir.absolutePath}")
        for (f in all()) {
            val note = Changelog.parse("{\"${f.name.removeSuffix(".json")}\": ${f.readText()}}").single()
            for (lang in listOf("es", "en")) {
                val bullets = note.bullets(lang)
                assertTrue(bullets.isNotEmpty(), "${f.name} has no $lang text")
                assertTrue(bullets.size <= Changelog.MAX_BULLETS, "${f.name} [$lang] has ${bullets.size} bullets: summarise to ${Changelog.MAX_BULLETS}")
                bullets.forEach { b ->
                    assertTrue(b.length <= Changelog.MAX_BULLET_CHARS, "${f.name} [$lang] bullet too long (${b.length}): $b")
                }
            }
            assertEquals(note.bullets("es").size, note.bullets("en").size, "${f.name}: es and en say different amounts")
        }
    }

    @Test
    fun `the build being released has its note`() {
        val name = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        assertTrue(File(dir, "$name.json").exists(), "write release-notes/$name.json (short: ${Changelog.MAX_BULLETS} bullets max)")
    }

    @Test
    fun `versions sort numerically, newest first`() {
        val notes = Changelog.parse("""{"0.9.0-alpha":{"es":"a","en":"a"},"0.10.0-alpha":{"es":"b","en":"b"},"0.2.1-beta":{"es":"c","en":"c"}}""")
        assertEquals(listOf("0.10.0-alpha", "0.9.0-alpha", "0.2.1-beta"), notes.map { it.version })
    }

    @Test
    fun `a broken changelog is an empty list, not a crash`() {
        assertTrue(Changelog.parse("not json").isEmpty())
        assertEquals(1, Changelog.parse("""{"0.1.0-alpha":{"es":"x"},"0.2.0-alpha":"oops"}""").size)
    }
}
