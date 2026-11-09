package dev.jdcm.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A torn or corrupt file must never read as "no children": the previous version comes back. */
class JsonFileTest {
    @TempDir lateinit var dir: File

    private fun store() = JsonFile(File(dir, "kids.json"), ListSerializer(String.serializer()))

    @Test
    fun `a corrupt file falls back to the previous version and is set aside`() = runBlocking {
        val s = store()
        s.write(listOf("Lucía"))
        s.write(listOf("Lucía", "Pablo"))
        File(dir, "kids.json").writeText("{\"trunca")
        assertEquals(listOf("Lucía"), store().read())
        assertTrue(File(dir, "kids.json.bad").exists())
    }

    @Test
    fun `a missing main file reads the backup, and nothing at all reads as null`() = runBlocking {
        assertNull(store().read())
        val s = store()
        s.write(listOf("a"))
        s.write(listOf("b"))
        File(dir, "kids.json").delete()
        assertEquals(listOf("a"), store().read())
    }

    @Test
    fun `a normal write leaves no temp file`() = runBlocking {
        store().write(listOf("x"))
        assertEquals(listOf("x"), store().read())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
