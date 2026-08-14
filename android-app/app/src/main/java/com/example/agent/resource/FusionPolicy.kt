package com.example.agent.resource

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Ressourcensparende Voxel-Fusion (docs/RESOURCE_OPT.md) — Portierung der
 * v11.0.0-Kernlogik (VoxelFusionOptimizer) als **reine Policy**.
 *
 * Die Spec-Variante dupliziert Teile des vorhandenen
 * `offline/AdaptiveOctree`/`VoxelNode.mergeWith`; hier wird nur der neue,
 * testbare Teil umgesetzt:
 * - ressourcenabhängige Parameter-Adaption (Voxelgröße, LOD, Konfidenzschwelle),
 * - LOD-Raster-Snapping (2^lod),
 * - konfidenz-/altersgewichtete Verschmelzung (Spec-Formel),
 * - Batch-Fusion mit Grid-Key-Merge und Voxel-Obergrenze.
 *
 * Die Persistenz übernimmt der bestehende Octree (`insert`/`getAllVoxels`).
 */
object FusionPolicy {

    const val BASE_VOXEL_SIZE = 0.05f
    const val MIN_CONFIDENCE = 0.3f
    const val MAX_VOXELS = 50_000

    data class FusionConfig(
        val voxelSize: Float,
        val confidenceThreshold: Float,
        val maxVoxels: Int,
        val lodLevel: Int,
    )

    /** Voxel-Datenmodell für die Fusion (Adapter auf offline.VoxelNode). */
    data class FusionVoxel(
        val x: Float,
        val y: Float,
        val z: Float,
        val confidence: Float,
        val semanticType: String = "unknown",
        val motionScore: Float = 0f,
        val lastUpdate: Long = System.currentTimeMillis(),
    )

    /** Parameter-Adaption an CPU/RAM/Akku (v11.0.0-Schwellwerte). */
    fun adapt(
        cpuLoad: Float,
        memoryUsage: Float,
        batteryLevel: Int,
    ): FusionConfig {
        val sizeFactor = when {
            cpuLoad > 0.7f || memoryUsage > 0.7f || batteryLevel < 20 -> 2.0f
            cpuLoad > 0.5f || memoryUsage > 0.5f || batteryLevel < 40 -> 1.5f
            else -> 1.0f
        }
        val lodLevel = when {
            cpuLoad > 0.8f || memoryUsage > 0.8f -> 2
            cpuLoad > 0.6f || memoryUsage > 0.6f -> 1
            else -> 0
        }
        val confidenceThreshold = when {
            cpuLoad > 0.7f || batteryLevel < 20 -> 0.5f
            cpuLoad > 0.5f || batteryLevel < 40 -> 0.4f
            else -> MIN_CONFIDENCE
        }
        return FusionConfig(
            voxelSize = BASE_VOXEL_SIZE * sizeFactor,
            confidenceThreshold = confidenceThreshold,
            maxVoxels = MAX_VOXELS,
            lodLevel = lodLevel,
        )
    }

    /** Raster-Snapping auf das LOD-Grid (Faktor 2^lod). */
    fun snapLod(voxel: FusionVoxel, lodLevel: Int): FusionVoxel {
        if (lodLevel <= 0) return voxel
        val factor = 2.0.pow(lodLevel).toFloat()
        return voxel.copy(
            x = kotlin.math.round(voxel.x / factor) * factor,
            y = kotlin.math.round(voxel.y / factor) * factor,
            z = kotlin.math.round(voxel.z / factor) * factor,
        )
    }

    /**
     * Verschmilzt zwei Voxel konfidenz-/altersgewichtet (v11.0.0-Formel):
     * w_existing = conf · (0,5 + 0,5·e^(−Δt/60000)), w_new = conf.
     * Semantik: höhere Konfidenz gewinnt; Bewegung: Maximum.
     */
    fun mergeWeighted(existing: FusionVoxel, new: FusionVoxel, nowMs: Long): FusionVoxel {
        val ageMs = (nowMs - existing.lastUpdate).coerceAtLeast(0)
        val ageWeight = exp(-(ageMs / 60_000f))
        val weightExisting = existing.confidence * (0.5f + 0.5f * ageWeight)
        val weightNew = new.confidence
        val total = weightExisting + weightNew
        if (total <= 0f) return new

        return FusionVoxel(
            x = (existing.x * weightExisting + new.x * weightNew) / total,
            y = (existing.y * weightExisting + new.y * weightNew) / total,
            z = (existing.z * weightExisting + new.z * weightNew) / total,
            confidence = (existing.confidence * weightExisting + new.confidence * weightNew) / total,
            semanticType = if (new.confidence > existing.confidence) new.semanticType else existing.semanticType,
            motionScore = max(existing.motionScore, new.motionScore),
            lastUpdate = nowMs,
        )
    }

    /**
     * Batch-Fusion: Konfidenzfilter → LOD-Snap → Grid-Key-Merge → Obergrenze.
     * Der Grid-Key basiert auf der adaptiven Voxelgröße.
     */
    fun fuseBatch(
        voxels: List<FusionVoxel>,
        config: FusionConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): List<FusionVoxel> {
        val snapped = voxels
            .filter { it.confidence > config.confidenceThreshold }
            .map { snapLod(it, config.lodLevel) }

        val merged = LinkedHashMap<Long, FusionVoxel>()
        for (voxel in snapped) {
            val key = gridKey(voxel, config.voxelSize)
            val existing = merged[key]
            merged[key] = if (existing == null) voxel else mergeWeighted(existing, voxel, nowMs)
            if (merged.size >= config.maxVoxels) break
        }
        return merged.values.toList()
    }

    /** Kompressionsstatistik (Voxel gesamt vs. eindeutige Grid-Zellen). */
    fun compressionStats(
        voxels: List<FusionVoxel>,
        config: FusionConfig,
    ): Map<String, Any> {
        val unique = voxels.map { gridKey(it, config.voxelSize) }.toSet().size
        return mapOf(
            "total_voxels" to voxels.size,
            "unique_positions" to unique,
            "compression_ratio" to if (unique > 0) voxels.size.toFloat() / unique else 0f,
            "lod_level" to config.lodLevel,
            "voxel_size" to config.voxelSize,
            "confidence_threshold" to config.confidenceThreshold,
        )
    }

    private fun gridKey(voxel: FusionVoxel, size: Float): Long {
        val ix = (voxel.x / size).roundToLong()
        val iy = (voxel.y / size).roundToLong()
        val iz = (voxel.z / size).roundToLong()
        // 21 Bit je Achse (|Wert| < 2^20) — für Innenraumkoordinaten ausreichend
        return ((ix and 0x1FFFFF) shl 42) or ((iy and 0x1FFFFF) shl 21) or (iz and 0x1FFFFF)
    }
}
