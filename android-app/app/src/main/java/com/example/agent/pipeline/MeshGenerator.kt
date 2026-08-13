package com.example.agent.pipeline

/**
 * Stufe 3 — Mesh-Punkte-Generierung.
 *
 * Erzeugt ein Dreiecks-Mesh (Delaunay auf der XY-Ebene) aus einer Punktwolke.
 */
class MeshGenerator {

    data class Mesh(
        val vertices: List<FloatArray>,   // [x, y, z]
        val faces: List<IntArray>,        // [i0, i1, i2]
    )

    fun generate(points: List<DataAcquisitionService.SensorDataPoint>): Mesh {
        if (points.size < 3) return Mesh(points.map { floatArrayOf(it.x, it.y, it.z) }, emptyList())

        // Deduplizieren über (x, y) für eine stabile Triangulation
        val seen = LinkedHashMap<Pair<Long, Long>, FloatArray>()
        for (p in points) {
            val key = (p.x.toRawBits().toLong() shl 32) or (p.y.toRawBits().toLong() and 0xFFFFFFFFL)
            seen.putIfAbsent(key, floatArrayOf(p.x, p.y, p.z))
        }
        val verts = seen.values.toList()
        if (verts.size < 3) return Mesh(verts, emptyList())

        // Einfache 2D-Delaunay-Triangulation (Bowyer-Watson, XY-Ebene)
        val faces = delaunay2D(verts)
        return Mesh(verts, faces)
    }

    /** Bowyer-Watson-Triangulation auf (x, y). */
    private fun delaunay2D(verts: List<FloatArray>): List<IntArray> {
        val n = verts.size
        // Super-Dreieck (groß genug)
        val big = 1e6f
        val superVerts = listOf(
            floatArrayOf(-big, -big, 0f),
            floatArrayOf(big, -big, 0f),
            floatArrayOf(0f, big, 0f),
        )
        val all = verts + superVerts
        val triangles = mutableListOf(intArrayOf(n, n + 1, n + 2))

        for (i in 0 until n) {
            val p = all[i]
            val bad = mutableListOf<IntArray>()
            for (t in triangles) {
                if (circumcircleContains(all[t[0]], all[t[1]], all[t[2]], p)) bad.add(t)
            }
            val polygon = mutableSetOf<Pair<Int, Int>>()
            for (t in bad) {
                triangles.remove(t)
                addEdge(polygon, t[0], t[1]); addEdge(polygon, t[1], t[2]); addEdge(polygon, t[2], t[0])
            }
            for ((a, b) in polygon) {
                if (polygon.contains(b to a)) continue
                triangles.add(intArrayOf(a, b, i))
            }
        }

        return triangles
            .filter { t -> t.all { it < n } }   // Super-Dreieck entfernen
            .map { it }
    }

    private fun addEdge(set: MutableSet<Pair<Int, Int>>, a: Int, b: Int) {
        val e = if (a < b) a to b else b to a
        if (!set.add(e)) set.remove(e)
    }

    private fun circumcircleContains(a: FloatArray, b: FloatArray, c: FloatArray, p: FloatArray): Boolean {
        val ax = a[0].toDouble(); val ay = a[1].toDouble()
        val bx = b[0].toDouble(); val by = b[1].toDouble()
        val cx = c[0].toDouble(); val cy = c[1].toDouble()
        val px = p[0].toDouble(); val py = p[1].toDouble()

        val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (kotlin.math.abs(d) < 1e-12) return false
        val ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
        val uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
        val dx = px - ux
        val dy = py - uy
        val r = (ux - ax) * (ux - ax) + (uy - ay) * (uy - ay)
        return (dx * dx + dy * dy) <= r
    }
}
