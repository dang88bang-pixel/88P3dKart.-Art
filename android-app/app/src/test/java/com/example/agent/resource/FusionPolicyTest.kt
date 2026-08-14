package com.example.agent.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionPolicyTest {

    private fun voxel(
        x: Float,
        y: Float,
        z: Float,
        confidence: Float,
        semantic: String = "unknown",
        motion: Float = 0f,
        lastUpdate: Long = System.currentTimeMillis(),
    ) = FusionPolicy.FusionVoxel(x, y, z, confidence, semantic, motion, lastUpdate)

    @Test
    fun `adaptation steigert voxelgroesse und lod unter last`() {
        val idle = FusionPolicy.adapt(0.1f, 0.2f, 80)
        val busy = FusionPolicy.adapt(0.9f, 0.9f, 80)
        assertEquals(0, idle.lodLevel)
        assertEquals(2, busy.lodLevel)
        assertTrue("Voxelgröße wächst nicht unter Last", busy.voxelSize > idle.voxelSize)
        assertTrue("Konfidenzschwelle steigt nicht unter Last",
            busy.confidenceThreshold > idle.confidenceThreshold)
    }

    @Test
    fun `lod-snapping rundet auf das raster`() {
        val snapped = FusionPolicy.snapLod(voxel(0.13f, -0.07f, 0.9f, 0.8f), lodLevel = 1)
        assertEquals(0f, snapped.x, 1e-3f)
        assertEquals(0f, snapped.y, 1e-3f)
        assertTrue(snapped.z in listOf(0f, 1f, 2f))
        // LOD 0 ändert nichts
        val unchanged = FusionPolicy.snapLod(voxel(0.13f, -0.07f, 0.9f, 0.8f), 0)
        assertEquals(0.13f, unchanged.x, 1e-6f)
    }

    @Test
    fun `gewichtete verschmelzung wertet alte voxel ab`() {
        val old = voxel(0f, 0f, 0f, 0.8f, lastUpdate = 0L)
        val new = voxel(1f, 0f, 0f, 0.9f)
        val merged = FusionPolicy.mergeWeighted(old, new, nowMs = 60_000L)
        assertTrue("Position rückt nicht Richtung neuem Voxel: ${merged.x}",
            merged.x > 0.5f && merged.x <= 1f)
        assertTrue("Konfidenz steigt nicht: ${merged.confidence}", merged.confidence > 0.8f)
    }

    @Test
    fun `semantik folgt der hoeheren konfidenz`() {
        val old = voxel(0f, 0f, 0f, 0.4f, semantic = "wall")
        val new = voxel(0.1f, 0f, 0f, 0.9f, semantic = "person")
        val merged = FusionPolicy.mergeWeighted(old, new, nowMs = System.currentTimeMillis())
        assertEquals("person", merged.semanticType)
    }

    @Test
    fun `batch-fusion filtert mergt und begrenzt`() {
        val config = FusionPolicy.adapt(0.1f, 0.2f, 80)
        val fused = FusionPolicy.fuseBatch(
            listOf(
                voxel(0.01f, 0.01f, 0f, 0.9f),
                voxel(0.02f, 0.01f, 0f, 0.8f),   // gleiche Zelle → Merge
                voxel(1f, 1f, 0f, 0.2f),          // unter Schwelle → weg
                voxel(2f, 2f, 0f, 0.7f),
            ),
            config,
        )
        assertEquals(2, fused.size)
        val cell = fused.first { it.confidence > 0.85f }
        assertTrue(cell.confidence > 0.85f)
    }

    @Test
    fun `kompressionsstatistik zaehlt eindeutige zellen`() {
        val config = FusionPolicy.adapt(0.1f, 0.2f, 80)
        val voxels = listOf(
            voxel(0.01f, 0.01f, 0f, 0.9f),
            voxel(0.02f, 0.02f, 0f, 0.9f),  // gleiche Zelle
            voxel(1f, 1f, 0f, 0.9f),
        )
        val stats = FusionPolicy.compressionStats(voxels, config)
        assertEquals(3, stats["total_voxels"])
        assertEquals(2, stats["unique_positions"])
        assertEquals(1.5f, stats["compression_ratio"] as Float, 1e-6f)
    }
}
