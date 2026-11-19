package dev.jdcm.oem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The phone makers' power managers (OemPages): who is who, and every page reachable from the manifest. */
class OemTest {
    @Test
    fun `makers are told apart by manufacturer or brand`() {
        assertEquals(Oem.XIAOMI, Oem.of("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, Oem.of("Xiaomi", "POCO"))
        assertEquals(Oem.XIAOMI, Oem.of("XIAOMI", "xiaomi"))
        assertEquals(Oem.SAMSUNG, Oem.of("samsung", "samsung"))
        assertEquals(Oem.HONOR, Oem.of("HUAWEI", "HONOR"), "an old Honor")
        assertEquals(Oem.HONOR, Oem.of("HONOR", "HONOR"))
        assertEquals(Oem.HUAWEI, Oem.of("HUAWEI", "HUAWEI"))
        assertEquals(Oem.ONEPLUS, Oem.of("OnePlus", "OnePlus"))
        assertEquals(Oem.REALME, Oem.of("realme", "realme"))
        assertEquals(Oem.OPPO, Oem.of("OPPO", "OPPO"))
        assertEquals(Oem.VIVO, Oem.of("vivo", "iQOO"))
        assertEquals(Oem.OTHER, Oem.of("Google", "google"))
        assertEquals(Oem.OTHER, Oem.of("motorola", "motorola"))
        assertEquals(Oem.OTHER, Oem.of(null, null))
    }

    @Test
    fun `every maker but the rest has a page, Xiaomi both kinds`() {
        for (oem in Oem.entries.filter { it != Oem.OTHER }) assertTrue(OemPages.of(oem).isNotEmpty(), "$oem has no page")
        val kinds = OemPages.of(Oem.XIAOMI).map { it.kind }.toSet()
        assertEquals(setOf(OemPageKind.AUTOSTART, OemPageKind.BATTERY), kinds)
        assertTrue(OemPages.of(Oem.OTHER).isEmpty())
        assertTrue(OemPages.of(Oem.entries.first()).all { it.cls != null || it.action != null })
    }

    @Test
    fun `the manifest's queries list every maker package (Android 11+ hides the others)`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<package\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = OemPages.packages - declared
        assertTrue(missing.isEmpty(), "not in <queries>: $missing")
    }
}
