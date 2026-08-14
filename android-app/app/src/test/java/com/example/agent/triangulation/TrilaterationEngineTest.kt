package com.example.agent.triangulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TrilaterationEngineTest {

    private fun anchor(id: String, x: Double, y: Double, z: Double = 0.0) =
        TrilaterationEngine.Anchor(id, x, y, z)

    private fun dist(a: TrilaterationEngine.Anchor, x: Double, y: Double, z: Double = 0.0): Double {
        val dx = a.x - x
        val dy = a.y - y
        val dz = a.z - z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun `2D loesung mit vier quadratischen ankern ist exakt`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 10.0, 0.0),
            anchor("C", 10.0, 10.0),
            anchor("D", 0.0, 10.0),
        )
        val trueX = 3.5
        val trueY = 2.5
        val distances = anchors.associate { it.id to dist(it, trueX, trueY) }

        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = false)
        assertNotNull(estimate)
        assertEquals(trueX, estimate!!.x, 1e-6)
        assertEquals(trueY, estimate.y, 1e-6)
        assertTrue(estimate.converged)
        assertTrue("Konfidenz zu niedrig: ${estimate.confidence}", estimate.confidence > 0.9f)
        assertTrue("Residuum zu groß: ${estimate.residualRmsM}", estimate.residualRmsM < 1e-6)
    }

    @Test
    fun `2D loesung bleibt unter rauschen innerhalb eines meters`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 12.0, 0.0),
            anchor("C", 12.0, 12.0),
            anchor("D", 0.0, 12.0),
        )
        val trueX = 4.0
        val trueY = 6.0
        val rng = Random(42L)
        val distances = anchors.associate { id ->
            id.id to (dist(id, trueX, trueY) + rng.nextGaussian() * 0.4)
        }

        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = false)
        assertNotNull(estimate)
        val err = kotlin.math.hypot(estimate!!.x - trueX, estimate.y - trueY)
        assertTrue("Fehler zu groß: ${err}m", err < 1.0)
        assertTrue(estimate.confidence > 0.3f)
    }

    @Test
    fun `3D loesung mit vier anker-wuerfeln ist exakt`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0, 0.0),
            anchor("B", 10.0, 0.0, 0.0),
            anchor("C", 0.0, 10.0, 0.0),
            anchor("D", 0.0, 0.0, 10.0),
        )
        val truePos = doubleArrayOf(2.0, 3.0, 4.0)
        val distances = anchors.associate {
            it.id to dist(it, truePos[0], truePos[1], truePos[2])
        }

        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = true)
        assertNotNull(estimate)
        assertEquals(truePos[0], estimate!!.x, 1e-6)
        assertEquals(truePos[1], estimate.y, 1e-6)
        assertEquals(truePos[2], estimate.z, 1e-6)
    }

    @Test
    fun `weniger anker als noetig ergibt null`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 10.0, 0.0),
        )
        val distances = anchors.associate { it.id to dist(it, 2.0, 2.0) }
        assertNull(TrilaterationEngine.solve(anchors, distances, useZ = false))
        assertNull(TrilaterationEngine.solve(anchors, distances, useZ = true))
    }

    @Test
    fun `ungueltige distanzen werden ignoriert`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 10.0, 0.0),
            anchor("C", 10.0, 10.0),
            anchor("D", 0.0, 10.0),
        )
        val distances = mapOf(
            "A" to 2.0,
            "B" to -5.0,          // ungültig → ignoriert
            "C" to Double.NaN,    // ungültig → ignoriert
            "D" to 3.0,
        )
        // Nur 2 gültige → null
        assertNull(TrilaterationEngine.solve(anchors, distances, useZ = false))
    }

    @Test
    fun `kollineare anker fuehren nicht zu einem absturz`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 5.0, 0.0),
            anchor("C", 10.0, 0.0),
        )
        val distances = anchors.associate { it.id to dist(it, 5.0, 2.0) }
        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = false)
        // Entweder null oder endliche Werte — nie NaN/Inf
        if (estimate != null) {
            assertTrue(estimate.x.isFinite())
            assertTrue(estimate.y.isFinite())
            assertTrue(estimate.residualRmsM.isFinite())
        }
    }

    @Test
    fun `unsicherheiten gewichten die loesung`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 10.0, 0.0),
            anchor("C", 0.0, 10.0),
            anchor("D", 10.0, 10.0),
        )
        val truePos = doubleArrayOf(4.0, 4.0)
        val distances = anchors.associate { it.id to dist(it, truePos[0], truePos[1]) }
        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = false)
        assertNotNull(estimate)
        // Position-Sigma muss endlich sein; zentrale Position mit 4
        // symmetrischen Ankern ergibt analytisch σ ≈ 1,0 m
        assertTrue(estimate!!.positionSigmaM.isFinite())
        assertTrue(estimate.positionSigmaM <= 1.01)
    }

    @Test
    fun `robuste loesung verwirft einen ausreisser-anker`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 12.0, 0.0),
            anchor("C", 12.0, 12.0),
            anchor("D", 0.0, 12.0),
            anchor("E", 6.0, 24.0),
        )
        val distances = anchors.associate { it.id to dist(it, 4.0, 6.0) }.toMutableMap()
        distances["D"] = distances["D"]!! + 8.0 // Multipath-Ausreißer

        val plain = TrilaterationEngine.solve(anchors, distances, useZ = false, robustIterations = 0)
        val robust = TrilaterationEngine.solve(anchors, distances, useZ = false, robustIterations = 2)

        assertNotNull(plain)
        assertNotNull(robust)
        val errPlain = kotlin.math.hypot(plain!!.x - 4.0, plain.y - 6.0)
        val errRobust = kotlin.math.hypot(robust!!.x - 4.0, robust.y - 6.0)
        assertTrue(
            "robust ($errRobust m) nicht besser als plain ($errPlain m)",
            errRobust < errPlain,
        )
        assertTrue("robuste Lösung zu ungenau: ${errRobust}m", errRobust < 1.0)
        assertTrue("kein Anker verworfen", robust.rejectedAnchors >= 1)
    }

    @Test
    fun `robuste loesung behaelt die mindest-ankerzahl`() {
        val anchors = listOf(
            anchor("A", 0.0, 0.0),
            anchor("B", 10.0, 0.0),
            anchor("C", 0.0, 10.0),
        )
        val distances = anchors.associate { it.id to dist(it, 3.0, 3.0) }
        val estimate = TrilaterationEngine.solve(anchors, distances, useZ = false, robustIterations = 3)
        assertNotNull(estimate)
        assertEquals(3, estimate!!.anchorCount)
        assertEquals(3.0, estimate.x, 1e-6)
        assertEquals(3.0, estimate.y, 1e-6)
    }
}
