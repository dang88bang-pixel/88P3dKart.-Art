package com.example.agent.classification

import com.example.agent.offline.AdaptiveOctree
import com.example.agent.offline.VoxelNode

/**
 * Deduplizierung über Mehrfach-Scans (Spezifikation §2.3):
 * Nutzt den vorhandenen adaptiven Octree; Punkte innerhalb der Toleranz
 * (0,01 m) gelten als Duplikat und werden verworfen.
 */
class Deduplicator(private val tolerance: Float = 0.01f) {

    // Terminale Zellen ~0,016 m (maxDepth 12, minCellSize 0,02) — nahe an der
    // Spezifikations-Toleranz von 0,01 m; Punkte in derselben Zelle verschmelzen.
    private val octree = AdaptiveOctree(maxDepth = 12, minCellSize = 0.02f)

    /** true, wenn der Punkt neu ist (kein Nachbar innerhalb der Toleranz). */
    fun isNewPoint(point: Point3D): Boolean =
        octree.searchSphere(point.x, point.y, point.z, tolerance).isEmpty()

    /** Fügt ein, wenn neu; liefert true bei tatsächlicher Einfügung. */
    fun insertIfNew(point: Point3D): Boolean {
        if (!isNewPoint(point)) return false
        octree.insert(VoxelNode(x = point.x, y = point.y, z = point.z))
        return true
    }

    /** Verarbeitet einen kompletten Frame: nur neue Punkte bleiben. */
    fun processFrame(points: List<Point3D>): List<Point3D> =
        points.filter { insertIfNew(it) }

    /** Gesamtzahl der gespeicherten (deduplizierten) Punkte. */
    fun size(): Int = octree.size()
}
