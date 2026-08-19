package com.example.agent.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Wand-/Dynamik-Klassifikation (3-Stufen-Pipeline, rein geometrisch) —
 * Spiegel der Python-Testsuite (edge-agent/tests/test_wall_person.py).
 */
class WallPersonClassifierTest {

    private val classifier = WallPersonClassifier()

    private fun wallPoints(n: Int = 600, seed: Int = 1): List<Point3D> {
        val rng = Random(seed)
        return List(n) {
            Point3D(
                rng.nextFloat() * 6f - 3f,
                2.0f + rng.nextFloat() * 0.04f - 0.02f,
                0.6f + rng.nextFloat() * 1.8f,
            )
        }
    }

    private fun blobPoints(centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 1.2f, n: Int = 400, seed: Int = 2): List<Point3D> {
        val rng = Random(seed)
        return List(n) {
            val x = centerX + gaussian(rng) * 0.12f
            val y = centerY + gaussian(rng) * 0.12f
            val z = (centerZ + gaussian(rng) * 0.12f).coerceIn(0.6f, 2.4f)
            Point3D(x, y, z)
        }
    }

    // Box-Muller
    private fun gaussian(rng: Random): Float {
        val u1 = rng.nextFloat().coerceAtLeast(1e-9f)
        val u2 = rng.nextFloat()
        return kotlin.math.sqrt(-2f * kotlin.math.ln(u1)) * kotlin.math.cos(2f * Math.PI.toFloat() * u2)
    }

    @Test
    fun voxelFilterReducesPointCount() {
        val rng = Random(9)
        val pts = List(6000) {
            Point3D(
                rng.nextFloat() * 2f - 1f,
                2.0f + rng.nextFloat() * 0.01f - 0.005f,
                0.8f + rng.nextFloat() * 1.2f,
            )
        }
        val down = classifier.voxelFilter(pts)
        assertTrue("Voxel-Filter muss > 50 % reduzieren (${down.size}/${pts.size})", down.size < pts.size * 0.5)
    }

    @Test
    fun heightFilterKeepsOnlyBand() {
        val pts = listOf(
            Point3D(0f, 0f, 0f), Point3D(0f, 0f, 0.4f),
            Point3D(0f, 0f, 1.5f), Point3D(0f, 0f, 2.4f), Point3D(0f, 0f, 3f),
        )
        val out = classifier.heightFilter(pts)
        assertEquals(2, out.size)
        assertTrue(out.all { it.z in 0.5f..2.5f })
    }

    @Test
    fun clusteringSeparatesBlobs() {
        val a = blobPoints(centerX = -1.5f)
        val b = blobPoints(centerX = 1.5f, seed = 3)
        assertEquals(2, classifier.euclideanClustering(a + b).size)
    }

    @Test
    fun planarityWallHighBlobLow() {
        assertTrue(classifier.calculatePlanarityScore(wallPoints()) > 0.6f)
        assertTrue(classifier.calculatePlanarityScore(blobPoints()) < 0.6f)
    }

    @Test
    fun wallIsPersistable() {
        val (reports, summary) = classifier.classify(wallPoints())
        assertTrue(summary["walls"]!! >= 1)
        assertTrue(reports.filter { it.label == WallPersonClassifier.ObjectType.WALL }.all { it.persistable })
    }

    @Test
    fun dynamicIsLiveOnly() {
        val (reports, summary) = classifier.classify(blobPoints())
        assertTrue(summary["dynamic"]!! >= 1)
        val dynamic = reports.filter { it.label == WallPersonClassifier.ObjectType.DYNAMIC }
        assertTrue(dynamic.isNotEmpty())
        assertTrue(dynamic.all { !it.persistable })
    }

    @Test
    fun mixedSceneSeparatesWallAndDynamic() {
        val scene = wallPoints() + blobPoints(centerX = 0.5f, centerY = -1f)
        val (reports, summary) = classifier.classify(scene)
        assertTrue(summary["walls"]!! >= 1)
        assertTrue(summary["dynamic"]!! >= 1)
    }

    @Test
    fun persistablePointsExcludeDynamic() {
        val scene = wallPoints() + blobPoints(centerX = 0.5f, centerY = -1f)
        val kept = classifier.persistablePoints(scene)
        assertTrue(kept.size < scene.size)
        // Dynamik-Blob liegt um y≈−1 → persistierte Punkte liegen über y>0.5
        assertTrue(kept.all { it.y > 0.5f })
    }

    @Test
    fun emptyInputIsSafe() {
        val (reports, summary) = classifier.classify(emptyList())
        assertTrue(reports.isEmpty())
        assertEquals(0, summary["total_points"])
    }
}
