package dev.jdcm.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Spanish and English carry the same keys with the same placeholders: no screen falls back half-translated. */
class StringsParityTest {
    private fun read(folder: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                val key = n.attributes.getNamedItem("name").nodeValue
                assertTrue(key !in out, "$folder: $key defined twice")
                out[key] = n.textContent
            }
        }
        return out
    }

    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test
    fun `both languages have the same keys and placeholders`() {
        val en = read("values")
        val es = read("values-es")
        assertTrue(en.size > 100, "suspiciously few strings: ${en.size}")
        assertEquals(en.keys.sorted(), es.keys.sorted())
        for ((k, v) in en) {
            assertEquals(placeholder.findAll(v).map { it.value }.toSet(), placeholder.findAll(es.getValue(k)).map { it.value }.toSet(), "placeholders differ in $k")
        }
    }
}
