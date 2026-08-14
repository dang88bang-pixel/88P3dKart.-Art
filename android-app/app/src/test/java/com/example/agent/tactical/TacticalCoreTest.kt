package com.example.agent.tactical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalCoreTest {

    // ── ScenarioComposer ─────────────────────────────────────────

    private fun composer() = ScenarioComposer(
        listOf(
            ScenarioComposer.ScenarioModule("urban_terrain", "Urbane Umgebung", "terrain", mapOf("density" to 0.8)),
            ScenarioComposer.ScenarioModule(
                "civilian_population", "Zivilbevölkerung", "personnel",
                mapOf("density" to 0.3), dependencies = listOf("urban_terrain"),
            ),
            ScenarioComposer.ScenarioModule(
                "hostile_forces", "Feindliche Kräfte", "personnel",
                mapOf("count" to 6), dependencies = listOf("urban_terrain"),
            ),
        )
    )

    @Test
    fun `abhaengigkeiten werden automatisch mit aufgeloest und vorsortiert`() {
        val result = composer().build(listOf("hostile_forces"))
        assertEquals(listOf("urban_terrain", "hostile_forces"), result.modules)
        assertEquals(0.8, result.config["terrain"]!!["density"])
        assertEquals(6, result.config["personnel"]!!["count"])
    }

    @Test
    fun `unbekannte module werden abgelehnt`() {
        assertThrows(IllegalArgumentException::class.java) {
            composer().build(listOf("gibt_es_nicht"))
        }
    }

    @Test
    fun `zyklen werden erkannt`() {
        val cyclic = ScenarioComposer(
            listOf(
                ScenarioComposer.ScenarioModule("a", "A", "terrain", dependencies = listOf("b")),
                ScenarioComposer.ScenarioModule("b", "B", "terrain", dependencies = listOf("a")),
            )
        )
        val error = assertThrows(IllegalArgumentException::class.java) { cyclic.build(listOf("a")) }
        assertTrue(error.message!!.contains("Zyklische"))
    }

    @Test
    fun `doppelte auswahl wird abgelehnt`() {
        assertThrows(IllegalArgumentException::class.java) {
            composer().build(listOf("urban_terrain", "urban_terrain"))
        }
    }

    // ── MapVersioning ───────────────────────────────────────────

    @Test
    fun `delta-kette rekonstruiert jede version`() {
        val versioning = MapVersioning()
        versioning.create(
            mapOf("v1" to floatArrayOf(0f, 0f, 0f), "v2" to floatArrayOf(1f, 0f, 0f))
        )
        versioning.commit(
            mapOf("v1" to floatArrayOf(0f, 0f, 0f), "v2" to floatArrayOf(2f, 0f, 0f), "v3" to floatArrayOf(3f, 0f, 0f))
        )
        versioning.commit(mapOf("v3" to floatArrayOf(3f, 0f, 0f)))

        assertEquals(3, versioning.latestVersion)
        val v1 = versioning.reconstruct(1)
        assertTrue(v1["v2"]!!.contentEquals(floatArrayOf(1f, 0f, 0f)))
        val v2 = versioning.reconstruct(2)
        assertTrue(v2["v2"]!!.contentEquals(floatArrayOf(2f, 0f, 0f)))
        assertTrue("v3" in v2)
        val latest = versioning.reconstruct()
        assertEquals(setOf("v3"), latest.keys)
    }

    @Test
    fun `rekonstruktion mutiert die basis nicht`() {
        val versioning = MapVersioning()
        versioning.create(mapOf("a" to floatArrayOf(1f, 0f, 0f)))
        versioning.commit(mapOf("a" to floatArrayOf(2f, 0f, 0f)))
        val v1 = versioning.reconstruct(1)
        v1["a"]!![0] = 99f // Manipulation der Rekonstruktion
        // Basis bleibt unverändert (defensive Kopien)
        assertTrue(versioning.reconstruct(1)["a"]!!.contentEquals(floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `unbekannte version wird abgelehnt`() {
        val versioning = MapVersioning()
        versioning.create(emptyMap())
        assertThrows(IllegalArgumentException::class.java) { versioning.reconstruct(42) }
    }

    // ── ScenarioCompressor ──────────────────────────────────────

    @Test
    fun `kompression ist verlustfrei und verkleinert wiederholende daten`() {
        val text = "{\"name\":\"Evakuierung\",\"modules\":[\"a\",\"b\"],\"persons\":50}".repeat(50)
        val compressed = ScenarioCompressor.compress(text)
        assertTrue("Keine Verkleinerung: ${compressed.size} >= ${text.toByteArray().size}",
            compressed.size < text.toByteArray().size)
        assertEquals(text, ScenarioCompressor.decompress(compressed))
    }

    // ── AnnotationTemplates ─────────────────────────────────────

    @Test
    fun `templates decken 20+ icons ab und sind eindeutig`() {
        assertTrue(AnnotationTemplates.ALL.size >= 20)
        assertEquals(AnnotationTemplates.ALL.size, AnnotationTemplates.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `annotation aus template entsteht korrekt`() {
        val annotation = AnnotationTemplates.create("medical_point", "map-1", 1.5f, 2.5f, 0f)
        assertEquals("map-1", annotation.mapId)
        assertEquals("medical", annotation.iconId)
        assertEquals(AnnotationTemplates.Layer.MEDICAL, annotation.layer)
        assertEquals(1.5f, annotation.x, 1e-6f)
        assertEquals("Sanitätsstelle", annotation.title)
        assertEquals("system", annotation.createdBy)
    }

    @Test
    fun `unbekanntes template wird abgelehnt`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationTemplates.create("gibt_es_nicht", "map-1", 0f, 0f, 0f)
        }
    }
}
