package com.example.agent.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Deduplizierung, Semantik-/Farbkodierung, ViewController & PersistenceFilter
 * (Spezifikation §2.3/§3.1/§3.4/§4.1) — Spiegel der Python-Privacy-Tests.
 */
class ClassificationPipelineTest {

    private val classifier = WallPersonClassifier()

    private fun wallPoints(n: Int = 600): List<Point3D> {
        val rng = Random(1)
        return List(n) {
            Point3D(
                rng.nextFloat() * 6f - 3f,
                2.0f + rng.nextFloat() * 0.04f - 0.02f,
                0.6f + rng.nextFloat() * 1.8f,
            )
        }
    }

    private fun blobPoints(): List<Point3D> {
        val rng = Random(2)
        return List(400) {
            val x = gaussian(rng) * 0.12f
            val y = gaussian(rng) * 0.12f
            val z = (1.2f + gaussian(rng) * 0.12f).coerceIn(0.6f, 2.4f)
            Point3D(x, y, z)
        }
    }

    private fun gaussian(rng: Random): Float {
        val u1 = rng.nextFloat().coerceAtLeast(1e-9f)
        val u2 = rng.nextFloat()
        return kotlin.math.sqrt(-2f * kotlin.math.ln(u1)) * kotlin.math.cos(2f * Math.PI.toFloat() * u2)
    }

    // ─── Deduplicator ─────────────────────────────────────────

    @Test
    fun deduplicatorFiltersDuplicates() {
        val dedup = Deduplicator(tolerance = 0.01f)
        val point = Point3D(1f, 2f, 3f)
        assertTrue(dedup.insertIfNew(point))
        assertFalse(dedup.insertIfNew(Point3D(1.001f, 2f, 3f))) // Duplikat
        assertTrue(dedup.insertIfNew(Point3D(1f, 2f, 3.5f)))    // neu
        assertEquals(2, dedup.size())
        // Alle drei Frame-Punkte sind Duplikate → 0 neue Punkte
        val frameDuplicates = listOf(point, Point3D(1f, 2f, 3.5f), Point3D(1.002f, 2f, 3f))
        assertEquals(0, dedup.processFrame(frameDuplicates).size)
        // Ein echter neuer Punkt → 1 neuer Punkt
        val frameNew = listOf(point, Point3D(4f, 4f, 4f))
        assertEquals(1, dedup.processFrame(frameNew).size)
        assertEquals(3, dedup.size())
    }

    // ─── SemanticClassifier (Farbkodierung) ───────────────────

    @Test
    fun semanticColorsMatchPalette() {
        val semantic = SemanticClassifier()
        assertEquals(0xFFAAAAAA.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.WALL))
        assertEquals(0xFF666666.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.FLOOR))
        assertEquals(0xFF4488FF.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.DEVICE))
        assertEquals(0xFF44FF88.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.PERSON))
        assertEquals(0xFFFF3333.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.EXIT))
        assertEquals(0xFF555555.toInt(), semantic.colorFor(SemanticClassifier.SemanticType.UNKNOWN))
    }

    @Test
    fun semanticClassifierMarksDynamicAsLiveOnly() {
        val semantic = SemanticClassifier()
        val scene = wallPoints() + blobPoints()
        val classified = semantic.classify(scene)
        val dynamic = classified.filter { it.type == SemanticClassifier.SemanticType.PERSON }
        assertTrue("Muss Dynamik-Punkte als PERSON (Live-Only) klassifizieren", dynamic.isNotEmpty())
        assertTrue(dynamic.all { it.liveOnly })
        assertTrue(dynamic.all { it.color == 0xFF44FF88.toInt() })
    }

    @Test
    fun semanticClassifierDetectsDeviceHints() {
        val semantic = SemanticClassifier()
        val hint = SemanticClassifier.DeviceHint(0.5f, 0.5f, 1.2f, 0.3f)
        val point = Point3D(0.55f, 0.5f, 1.2f)
        val classified = semantic.classify(listOf(point), deviceHints = listOf(hint))
        assertEquals(SemanticClassifier.SemanticType.DEVICE, classified.first().type)
        assertEquals(0xFF4488FF.toInt(), classified.first().color)
    }

    // ─── ViewController ───────────────────────────────────────

    @Test
    fun viewControllerPersistedRemovesLiveOnly() {
        val controller = ViewController()
        val scene = wallPoints() + blobPoints()
        val live = controller.renderFrame(scene, ViewController.ViewMode.LIVE)
        val persisted = controller.renderFrame(scene, ViewController.ViewMode.PERSISTED)
        assertTrue(live.size > persisted.size)
        assertTrue(persisted.none { it.liveOnly })
    }

    // ─── PersistenceFilter ────────────────────────────────────

    @Test
    fun persistenceFilterRemovesLiveOnlyKinds() {
        val filter = PersistenceFilter()
        val objects = listOf(
            "wall" to Point3D(0f, 0f, 1f),
            "floor" to Point3D(1f, 1f, 0f),
            "person" to Point3D(2f, 2f, 1f),
            "animal" to Point3D(3f, 3f, 0f),
            "dynamic" to Point3D(4f, 4f, 1f),
            "device" to Point3D(5f, 5f, 1f),
        )
        val (kept, removed) = filter.filterObjects(objects)
        assertEquals(3, removed)
        assertEquals(setOf("wall", "floor", "device"), kept.map { it.first }.toSet())
        val audit = filter.audit(objects)
        assertEquals(3, audit.liveOnlyRemoved)
        assertEquals(1, audit.persistedKinds["wall"])
    }

    @Test
    fun persistenceFilterAnonymizesDevices() {
        val filter = PersistenceFilter()
        val out = filter.filterDevice(
            id = "AA:BB:CC:DD:EE:FF",
            mac = "AA:BB:CC:DD:EE:FF",
            metadata = mapOf("mac" to "x", "uuid" to "y", "user_id" to "z", "friendly" to "ok"),
        )
        assertTrue(out["id"]!!.toString().startsWith("ANON_"))
        assertTrue(out["mac"]!!.toString().startsWith("ANON_"))
        val meta = out["metadata"] as Map<*, *>
        assertFalse(meta.containsKey("mac"))
        assertFalse(meta.containsKey("uuid"))
        assertEquals("ok", meta["friendly"])
    }

    @Test
    fun anonymizationDeterministicAndGranularization() {
        val filter = PersistenceFilter()
        assertEquals(
            PersistenceFilter.anonymizeIdentifier("AA:BB"),
            PersistenceFilter.anonymizeIdentifier("AA:BB"),
        )
        assertNotEquals(
            PersistenceFilter.anonymizeIdentifier("AA:BB"),
            PersistenceFilter.anonymizeIdentifier("AA:BC"),
        )
        assertEquals(1.2f, PersistenceFilter.granularize(1.234f), 1e-6f)
        assertEquals(-0.1f, PersistenceFilter.granularize(-0.057f), 1e-6f)
    }

    // ─── Pipeline-Integration ─────────────────────────────────

    @Test
    fun dataInterpreterUsesGeometricClassifier() {
        val interpreter = com.example.agent.pipeline.DataInterpreter()
        val points = (wallPoints() + blobPoints()).map {
            com.example.agent.pipeline.DataAcquisitionService.SensorDataPoint(
                timestamp = System.currentTimeMillis(),
                source = "lidar",
                x = it.x, y = it.y, z = it.z,
                quality = 1f,
            )
        }
        val objects = interpreter.interpret(points)
        val kinds = objects.map { it.kind }.toSet()
        assertTrue("Wand + Dynamik erwartet, war: $kinds", "wall" in kinds && "dynamic" in kinds)
        assertTrue(objects.filter { it.kind == "dynamic" }.none { it.persistable })
        assertTrue(objects.filter { it.kind != "dynamic" }.all { it.persistable })
    }
}
