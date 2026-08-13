package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mesh-Generierung aus einer Punktwolke.
 *
 * Vereinfachte, ressourcenschonende Variante für den CT45P (statt voller
 * Poisson-Rekonstruktion mit Multigrid): Aufbau eines regulären Voxel-Gitters
 * mit einem vorzeichenbehafteten Distanzfeld (SDF) und Extraktion der
 * Oberfläche als Quad-Faces an besetzten/leeren Zellgrenzen.
 *
 * Für höhere Qualität kann dieser Baustein gegen eine vollständige
 * Marching-Cubes-/Poisson-Implementierung ausgetauscht werden.
 */
class PoissonReconstruction {

    data class Mesh(
        val vertices: List<FloatArray>,   // [x, y, z]
        val faces: List<IntArray>,        // Quads [i0, i1, i2, i3]
    )

    /**
     * Rekonstruiert ein Mesh aus Punkten (flach: [x1,y1,z1, ...]).
     * @param resolution Anzahl der Zellen je Achse (z.B. 32)
     */
    fun reconstruct(points: List<Float>, resolution: Int = 32, iso: Float = 0.5f): Mesh {
        val pts = mutableListOf<FloatArray>()
        var i = 0
        while (i + 2 < points.size) {
            pts.add(floatArrayOf(points[i], points[i + 1], points[i + 2]))
            i += 3
        }
        return reconstruct(pts, resolution, iso)
    }

    fun reconstruct(points: List<FloatArray>, resolution: Int = 32, iso: Float = 0.5f): Mesh {
        if (points.isEmpty()) return Mesh(emptyList(), emptyList())

        val lo = FloatArray(3) { Float.MAX_VALUE }
        val hi = FloatArray(3) { -Float.MAX_VALUE }
        for (p in points) {
            for (d in 0..2) {
                lo[d] = min(lo[d], p[d]); hi[d] = max(hi[d], p[d])
            }
        }
        // Sicherheitspuffer, damit die Oberfläche nicht am Rand klebt
        val pad = 0.1f * max(1f, max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2])))
        for (d in 0..2) { lo[d] -= pad; hi[d] += pad }

        val cell = FloatArray(3) {
            if (hi[it] - lo[it] < 1e-6f) 0.1f else (hi[it] - lo[it]) / resolution
        }

        // Besetztheits-Grid (Distanz zu nächstem Punkt < iso * Zellgröße)
        val occ = Array(resolution + 1) { Array(resolution + 1) { BooleanArray(resolution + 1) } }
        val isoDist = iso * cell[0]

        // Grobe Nächste-Nachbar-Suche über Zellindizes
        fun nearestDist(x: Float, y: Float, z: Float): Float {
            var best = Float.MAX_VALUE
            for (p in points) {
                val dx = p[0] - x; val dy = p[1] - y; val dz = p[2] - z
                val d = dx * dx + dy * dy + dz * dz
                if (d < best) best = d
            }
            return sqrt(best)
        }

        for (ix in 0..resolution) for (iy in 0..resolution) for (iz in 0..resolution) {
            val x = lo[0] + ix * cell[0]
            val y = lo[1] + iy * cell[1]
            val z = lo[2] + iz * cell[2]
            occ[ix][iy][iz] = nearestDist(x, y, z) <= isoDist
        }

        // Oberflächen-Quads an besetzten/leeren Grenzen extrahieren
        val vertices = mutableListOf<FloatArray>()
        val faces = mutableListOf<IntArray>()
        val keyToIndex = HashMap<String, Int>()

        fun vertexIndex(x: Float, y: Float, z: Float): Int {
            val key = "${x.round()},${y.round()},${z.round()}"
            return keyToIndex.getOrPut(key) {
                vertices.add(floatArrayOf(x, y, z))
                vertices.size - 1
            }
        }

        fun emitQuad(v1: FloatArray, v2: FloatArray, v3: FloatArray, v4: FloatArray) {
            val a = vertexIndex(v1[0], v1[1], v1[2])
            val b = vertexIndex(v2[0], v2[1], v2[2])
            val c = vertexIndex(v3[0], v3[1], v3[2])
            val d = vertexIndex(v4[0], v4[1], v4[2])
            faces.add(intArrayOf(a, b, c, d))
        }

        for (ix in 0 until resolution) for (iy in 0 until resolution) for (iz in 0 until resolution) {
            val x0 = lo[0] + ix * cell[0]; val x1 = lo[0] + (ix + 1) * cell[0]
            val y0 = lo[1] + iy * cell[1]; val y1 = lo[1] + (iy + 1) * cell[1]
            val z0 = lo[2] + iz * cell[2]; val z1 = lo[2] + (iz + 1) * cell[2]

            val c000 = occ[ix][iy][iz]
            val c100 = occ[ix + 1][iy][iz]
            val c010 = occ[ix][iy + 1][iz]
            val c110 = occ[ix + 1][iy + 1][iz]
            val c001 = occ[ix][iy][iz + 1]
            val c101 = occ[ix + 1][iy][iz + 1]
            val c011 = occ[ix][iy + 1][iz + 1]
            val c111 = occ[ix + 1][iy + 1][iz + 1]

            // X-Richtung
            if (c000 != c100) emitQuad(floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1))
            // Y-Richtung
            if (c000 != c010) emitQuad(floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
            // Z-Richtung
            if (c000 != c001) emitQuad(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
        }

        return Mesh(vertices, faces)
    }

    private fun Float.round(): Int = (this * 1000).toInt()
}
