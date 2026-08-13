package com.example.agent.offline

import kotlin.math.max
import kotlin.math.min

/**
 * Semantische Klassifikation von Voxel-Clustern basierend auf Geometrie und Bewegung.
 * Regelbasiert (kein ML-Modell) → ressourcenschonend auf dem CT45P.
 */
class SemanticEngine {

    data class Classification(
        val type: String,          // "person", "furniture", "wall", "floor", "unknown"
        val confidence: Float,
        val bbox: List<Float>,     // [x_min, y_min, z_min, x_max, y_max, z_max]
    )

    fun classifyCluster(voxels: List<VoxelNode>, motionScore: Float): Classification {
        if (voxels.isEmpty()) return Classification("unknown", 0f, emptyList())

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (v in voxels) {
            minX = min(minX, v.x); maxX = max(maxX, v.x)
            minY = min(minY, v.y); maxY = max(maxY, v.y)
            minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
        }

        val bbox = listOf(minX, minY, minZ, maxX, maxY, maxZ)
        val height = maxZ - minZ
        val width = maxX - minX
        val depth = maxY - minY
        val volume = width * depth * height
        val aspectRatio = max(width, depth) / (height + 0.01f)

        return when {
            // Person: typische Höhe, schmal, bewegt oder stehend
            height in 1.2f..2.2f && aspectRatio < 1.5f -> {
                val conf = if (motionScore > 0.4f) 0.85f + 0.15f * motionScore else 0.70f
                Classification("person", conf, bbox)
            }
            // Wand: flach und großflächig
            height < 0.3f && (width > 2.0f || depth > 2.0f) ->
                Classification("wall", 0.80f, bbox)
            // Boden: flach und horizontal
            height < 0.1f && volume > 0.5f ->
                Classification("floor", 0.90f, bbox)
            // Möbel: kompakt, nicht hoch, statisch
            volume > 0.2f && motionScore < 0.3f ->
                Classification("furniture", 0.70f, bbox)
            else -> Classification("unknown", 0.30f, bbox)
        }
    }
}
