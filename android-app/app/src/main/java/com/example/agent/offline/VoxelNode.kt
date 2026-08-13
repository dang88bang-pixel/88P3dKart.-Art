package com.example.agent.offline

import kotlin.math.sqrt

/**
 * Ein einzelner Voxel im adaptiven Octree.
 * Speichert Position, Normalen, semantische Information, Konfidenz und Bewegung.
 */
data class VoxelNode(
    val x: Float,
    val y: Float,
    val z: Float,
    var normalX: Float = 0f,
    var normalY: Float = 0f,
    var normalZ: Float = 1f,
    var semanticType: String = "unknown", // "person", "furniture", "wall", "floor", "unknown"
    var confidence: Float = 0.5f,         // 0..1
    var lastUpdate: Long = System.currentTimeMillis(),
    var motionScore: Float = 0f,          // 0 = statisch, >0.5 = bewegt
) {
    /**
     * Verschmilzt zwei Voxel. Gewichtung basiert auf Konfidenz und Aktualität.
     */
    fun mergeWith(other: VoxelNode, weightThis: Float = 1f, weightOther: Float = 1f): VoxelNode {
        val total = weightThis + weightOther
        if (total <= 0f) return this

        val nx = (x * weightThis + other.x * weightOther) / total
        val ny = (y * weightThis + other.y * weightOther) / total
        val nz = (z * weightThis + other.z * weightOther) / total

        val nnx = (normalX * weightThis + other.normalX * weightOther) / total
        val nny = (normalY * weightThis + other.normalY * weightOther) / total
        val nnz = (normalZ * weightThis + other.normalZ * weightOther) / total

        // Semantik: höhere Konfidenz gewinnt
        val semantic = if (other.confidence > confidence) other.semanticType else semanticType
        val conf = (confidence * weightThis + other.confidence * weightOther) / total
        val motion = (motionScore * weightThis + other.motionScore * weightOther) / total

        return VoxelNode(
            x = nx, y = ny, z = nz,
            normalX = nnx, normalY = nny, normalZ = nnz,
            semanticType = semantic,
            confidence = conf,
            lastUpdate = System.currentTimeMillis(),
            motionScore = motion,
        )
    }

    /** Euklidische Distanz zu einem anderen Voxel. */
    fun distanceTo(other: VoxelNode): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
