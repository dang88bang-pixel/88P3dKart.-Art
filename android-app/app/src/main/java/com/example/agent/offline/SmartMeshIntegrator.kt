package com.example.agent.offline

import android.util.Log
import kotlin.math.abs

/**
 * Intelligenter Mesh-Integrator:
 * - Verschmilzt neue Sensorpunkte mit bestehenden Voxeln (adaptiver Octree)
 * - Klassifiziert Cluster semantisch (Person/Gegenstand/Wand/Boden)
 * - Ressourcenschonend durch gebündelte Integration (Debouncing)
 */
class SmartMeshIntegrator(
    private val maxDepth: Int = 6,
    private val mergeRadius: Float = 0.15f,
) {
    companion object {
        private const val TAG = "SmartMeshIntegrator"
        private const val INTEGRATION_INTERVAL_MS = 100L
        private const val MAX_VOXELS = 50_000
    }

    private val octree = AdaptiveOctree(maxDepth)
    private val semanticEngine = SemanticEngine()
    private val motionDetector = MotionDetector()

    private val pendingPoints = mutableListOf<VoxelNode>()
    private var lastIntegrationTime = 0L
    private val lock = Any()

    /**
     * Fügt neue Punkte aus einem Sensor-Frame hinzu.
     * @param points Flache Koordinaten [x1,y1,z1, x2,y2,z2, ...]
     */
    fun addPoints(
        points: List<Float>,
        normals: List<Float>? = null,
        semanticType: String = "unknown",
        confidence: Float = 0.7f,
        motionScore: Float = 0f,
    ) {
        if (points.size < 3) return

        val hasNormals = normals != null && normals.size >= points.size
        val newVoxels = mutableListOf<VoxelNode>()

        var i = 0
        while (i + 2 < points.size) {
            val x = points[i]; val y = points[i + 1]; val z = points[i + 2]
            if (abs(x) > 50 || abs(y) > 50 || abs(z) > 50) { i += 3; continue } // Rauschen filtern

            val nx = if (hasNormals) normals!![i] else 0f
            val ny = if (hasNormals) normals!![i + 1] else 0f
            val nz = if (hasNormals) normals!![i + 2] else 1f

            newVoxels.add(
                VoxelNode(
                    x = x, y = y, z = z,
                    normalX = nx, normalY = ny, normalZ = nz,
                    semanticType = semanticType,
                    confidence = confidence,
                    motionScore = motionScore,
                )
            )
            i += 3
        }

        synchronized(lock) { pendingPoints.addAll(newVoxels) }
        triggerIntegration()
    }

    /** Führt die Integration der gesammelten Punkte durch. */
    fun integrate() {
        val batch: List<VoxelNode> = synchronized(lock) {
            if (pendingPoints.isEmpty()) return
            val b = pendingPoints.toList()
            pendingPoints.clear()
            b
        }

        Log.d(TAG, "Integriere ${batch.size} neue Punkte")

        val clusters = clusterPoints(batch, mergeRadius)
        for (cluster in clusters) {
            val avgMotion = cluster.map { it.motionScore }.average().toFloat()
            val classification = semanticEngine.classifyCluster(cluster, avgMotion)

            val finalType = if (classification.confidence > 0.6f) {
                classification.type
            } else {
                cluster.groupBy { it.semanticType }.maxByOrNull { it.value.size }?.key ?: "unknown"
            }
            val clusterConf = cluster.map { it.confidence }.average().toFloat()

            for (voxel in cluster) {
                val enhanced = voxel.copy(
                    semanticType = finalType,
                    confidence = (voxel.confidence + clusterConf) / 2f,
                    motionScore = avgMotion,
                )
                octree.insert(enhanced)
            }
        }

        cleanUpOldVoxels()
        Log.d(TAG, "Octree-Größe: ${octree.size()} Voxel")
    }

    private fun clusterPoints(points: List<VoxelNode>, radius: Float): List<List<VoxelNode>> {
        val clusters = mutableListOf<MutableList<VoxelNode>>()
        val used = mutableSetOf<Int>()
        for (i in points.indices) {
            if (i in used) continue
            val cluster = mutableListOf(points[i])
            used.add(i)
            for (j in i + 1 until points.size) {
                if (j in used) continue
                if (points[i].distanceTo(points[j]) < radius) {
                    cluster.add(points[j])
                    used.add(j)
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun cleanUpOldVoxels() {
        if (octree.size() > MAX_VOXELS) {
            // Platzhalter: Retention-Policy (älteste/konfidenzärmste Voxel entfernen)
            Log.d(TAG, "Octree-Grenze erreicht – Voxel werden bereinigt")
        }
    }

    private fun triggerIntegration() {
        val now = System.currentTimeMillis()
        if (now - lastIntegrationTime > INTEGRATION_INTERVAL_MS) {
            integrate()
            lastIntegrationTime = now
        }
    }

    fun getAllVoxels(): List<VoxelNode> = octree.getAllVoxels()
    fun searchSphere(x: Float, y: Float, z: Float, radius: Float): List<VoxelNode> =
        octree.searchSphere(x, y, z, radius)
    fun size(): Int = octree.size()
}
