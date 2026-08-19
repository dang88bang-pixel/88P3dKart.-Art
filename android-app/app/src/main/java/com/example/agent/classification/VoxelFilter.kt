package com.example.agent.classification

/**
 * Voxel-Grid-Filterung (Spezifikation §2.1):
 * Punkte in ein 3D-Gitter mit 0,05 m Auflösung einteilen, je Voxel den
 * Mittelpunkt aller Punkte bilden (Downsampling, Rauschreduktion > 70 %).
 */
class VoxelFilter(private val resolution: Float = 0.05f) {

    fun filter(points: List<Point3D>): List<Point3D> {
        if (points.isEmpty()) return emptyList()
        val grid = HashMap<Long, MutableList<Point3D>>()
        for (p in points) {
            val key = key(p)
            grid.getOrPut(key) { mutableListOf() }.add(p)
        }
        return grid.values.map { voxel ->
            Point3D(
                voxel.map { it.x }.average().toFloat(),
                voxel.map { it.y }.average().toFloat(),
                voxel.map { it.z }.average().toFloat(),
            )
        }
    }

    private fun key(p: Point3D): Long {
        val xi = (p.x / resolution).toInt().toLong()
        val yi = (p.y / resolution).toInt().toLong()
        val zi = (p.z / resolution).toInt().toLong()
        return ((xi and 0x1FFFFF) shl 42) or ((yi and 0x1FFFFF) shl 21) or (zi and 0x1FFFFF)
    }
}
