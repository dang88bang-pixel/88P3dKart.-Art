package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.exp

/**
 * Adaptiver Octree mit variabler Auflösung (LOD).
 * - Tiefe: bis zu 6 Ebenen (Kantenlänge 8 m → ~0.125 m auf tiefster Ebene)
 * - Speichert nur Zellen mit mindestens einem Voxel
 * - Ermöglicht effiziente Kugel-Suche und Verschmelzung
 */
class AdaptiveOctree(private val maxDepth: Int = 6, private val minCellSize: Float = 0.2f) {

    private val root = OctreeNode(0f, 0f, 0f, 8f, 0)

    private inner class OctreeNode(
        val cx: Float, val cy: Float, val cz: Float,
        val size: Float, val depth: Int,
    ) {
        var voxel: VoxelNode? = null
        val children = arrayOfNulls<OctreeNode>(8)
        var isLeaf = true

        fun getChildIndex(x: Float, y: Float, z: Float): Int =
            (if (x >= cx) 1 else 0) or
            (if (y >= cy) 2 else 0) or
            (if (z >= cz) 4 else 0)

        fun insert(v: VoxelNode, maxDepth: Int): VoxelNode? {
            val half = size / 2f
            if (abs(v.x - cx) > half || abs(v.y - cy) > half || abs(v.z - cz) > half) {
                return null // Punkt liegt außerhalb dieser Zelle
            }

            // Blatt oder maximale Auflösung erreicht → speichern/verschmelzen
            if (depth >= maxDepth || size <= minCellSize) {
                return mergeVoxel(v)
            }

            // Sonst in Kinder einfügen
            if (isLeaf) {
                if (voxel == null) {
                    voxel = v
                    return v
                }
                split()
            }

            val idx = getChildIndex(v.x, v.y, v.z)
            if (children[idx] == null) {
                val halfSize = size / 2f
                val ccx = cx + (if (idx and 1 != 0) halfSize else -halfSize)
                val ccy = cy + (if (idx and 2 != 0) halfSize else -halfSize)
                val ccz = cz + (if (idx and 4 != 0) halfSize else -halfSize)
                children[idx] = OctreeNode(ccx, ccy, ccz, halfSize, depth + 1)
            }
            return children[idx]!!.insert(v, maxDepth)
        }

        private fun split() {
            val v = voxel ?: return
            if (!isLeaf) return
            // Bugfix: Kindzentren liegen bei ± size/4 (Kind-Kantenlänge = size/2,
            // Kind-Halbextent = size/4) — vorher ± size/2, wodurch eingefügte
            // Punkte aus dem Baum fielen. Der alte Voxel wird strikt dem EINEN
            // Oktanten zugewiesen, der ihn enthält (gleiche Indexlogik wie insert,
            // Grenzwerte zählen zum ≥-Kind) — vorher konnte eine Bereichsprüfung
            // denselben Voxel mehreren Kindern zuordnen (Doppelzählung).
            val halfSize = size / 2f
            val quarter = size / 4f
            for (i in 0..7) {
                val ccx = cx + (if (i and 1 != 0) quarter else -quarter)
                val ccy = cy + (if (i and 2 != 0) quarter else -quarter)
                val ccz = cz + (if (i and 4 != 0) quarter else -quarter)
                val child = OctreeNode(ccx, ccy, ccz, halfSize, depth + 1)
                if (getChildIndex(v.x, v.y, v.z) == i) {
                    child.voxel = v
                }
                children[i] = child
            }
            isLeaf = false
            this.voxel = null
        }

        private fun mergeVoxel(newVoxel: VoxelNode): VoxelNode {
            val old = voxel
            if (old == null) {
                voxel = newVoxel
                return newVoxel
            }
            // Zeitlicher Gewichtungsabfall: ältere Messungen verlieren an Einfluss
            val ageFactor = exp(-((System.currentTimeMillis() - old.lastUpdate) / 60000f).toDouble()).toFloat()
            val wOld = old.confidence * (0.5f + 0.5f * ageFactor)
            val wNew = newVoxel.confidence
            val merged = old.mergeWith(newVoxel, wOld, wNew)
            voxel = merged
            return merged
        }

        fun collectVoxels(list: MutableList<VoxelNode>) {
            if (isLeaf) {
                voxel?.let { list.add(it) }
            } else {
                for (c in children) c?.collectVoxels(list)
            }
        }

        fun searchSphere(x: Float, y: Float, z: Float, radius: Float, out: MutableList<VoxelNode>) {
            val half = size / 2f
            if (abs(x - cx) > half + radius ||
                abs(y - cy) > half + radius ||
                abs(z - cz) > half + radius
            ) return

            if (isLeaf) {
                voxel?.let { v ->
                    if (v.distanceTo(VoxelNode(x, y, z)) <= radius) out.add(v)
                }
            } else {
                for (c in children) c?.searchSphere(x, y, z, radius, out)
            }
        }
    }

    fun insert(voxel: VoxelNode): VoxelNode? = root.insert(voxel, maxDepth)

    fun getAllVoxels(): List<VoxelNode> {
        val list = mutableListOf<VoxelNode>()
        root.collectVoxels(list)
        return list
    }

    fun searchSphere(x: Float, y: Float, z: Float, radius: Float): List<VoxelNode> {
        val out = mutableListOf<VoxelNode>()
        root.searchSphere(x, y, z, radius, out)
        return out
    }

    fun size(): Int = getAllVoxels().size
}
