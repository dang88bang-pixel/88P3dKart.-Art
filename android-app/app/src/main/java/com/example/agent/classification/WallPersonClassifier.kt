package com.example.agent.classification

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dreistufige, rein geometrische Wand-/Dynamik-Klassifikation (Spezifikation
 * "Erkennung & Unterscheidung von Wänden und Menschen", §2).
 *
 * Numerische Parität zur Python-Implementierung des Edge-Agents
 * (`edge-agent/wall_person_classifier.py`) — gleiche Parameter, gleiche Labels:
 *
 *   STUFE 1  Voxel-Grid (0,05 m) → Höhenfilter (0,5–2,5 m) → Euklidisches
 *            Clustering (0,2 m, min. 10 Punkte)
 *   STUFE 2  PCA-Planarität + RANSAC-Ebenenkonsens;
 *            Schwellen distanzabhängig: > 0,60 (nah/mittel), > 0,53 (weit)
 *   STUFE 3  Zylinder-Validierung (r = 0,35 m, h = 2,5 m) + Plausibilität
 *            (Höhe 0,5–2,5 m, Breite ≤ 1,0 m, Volumen ≥ 0,1 m³, Sphärizität)
 *
 * Ergebnis: `WALL` = statisch → persistierbar; `DYNAMIC` = volumetrischer
 * Bewegt-/Lebewesen-Kandidat → NUR Live-View (wird von PersistenceFilter
 * erzwungen nie gespeichert).
 *
 * Planaritätsmaß: (λ₂ − λ₃)/λ₂ statt der Spezifikationsformel (λ₂ − λ₃)/λ₁ —
 * ein langgestrecktes Wandstück (z. B. 6 m × 1,8 m) hätte nach der
 * Originalformel λ₁ ≫ λ₂ und würde fälschlich als Linie verworfen; das
 * elongationsrobuste Maß behält die Schwellen (0,60/0,53) unverändert bei.
 *
 * Bewusst NICHT implementiert: Atemfrequenz-/Doppler-Biometrie (§3.2) —
 * die Klassifikation arbeitet ausschließlich mit der Geometrie.
 */
class WallPersonClassifier {

    enum class ObjectType { WALL, DYNAMIC, UNKNOWN }

    data class ClassificationResult(
        val type: ObjectType,
        val confidence: Float,
        val points: List<Point3D>,
        val planarityScore: Float,
        val persistable: Boolean,
    )

    data class ClusterReport(
        val label: ObjectType,
        val centroid: Point3D,
        val bboxMin: Point3D,
        val bboxMax: Point3D,
        val planarity: Float,
        val count: Int,
        val persistable: Boolean,
        val points: List<Point3D>,
    )

    companion object {
        // Parameter nach der Spezifikation (wissenschaftlich validiert).
        const val PLANARITY_THRESHOLD_NEAR = 0.60f   // Distanz < 20 m
        const val PLANARITY_THRESHOLD_FAR = 0.53f    // Distanz ≥ 20 m
        const val PLANARITY_DISTANCE_M = 20.0f
        const val VOXEL_SIZE = 0.05f
        const val HEIGHT_MIN = 0.5f
        const val HEIGHT_MAX = 2.5f
        const val CLUSTER_EPS = 0.2f
        const val CLUSTER_MIN_POINTS = 10
        const val CYLINDER_RADIUS = 0.35f
        const val CYLINDER_HEIGHT = 2.5f
        const val MAX_WIDTH = 1.0f
        const val MIN_VOLUME = 0.1f
        const val MIN_SPHERICITY = 0.3f
        const val RANSAC_MAX_PLANES = 20
        const val WALL_DILATION_RADIUS = 0.15f
    }

    /** STUFE 1.1: Voxel-Grid (0,05 m) mit Mittelpunkt je Voxel. */
    fun voxelFilter(points: List<Point3D>, resolution: Float = VOXEL_SIZE): List<Point3D> =
        VoxelFilter(resolution).filter(points)

    /** STUFE 1.2: Höhenfilter (0,5–2,5 m). */
    fun heightFilter(points: List<Point3D>): List<Point3D> =
        points.filter { it.z in HEIGHT_MIN..HEIGHT_MAX }

    /** STUFE 1.3: Euklidisches Clustering (verbundene Komponenten, 0,2 m). */
    fun euclideanClustering(
        points: List<Point3D>,
        eps: Float = CLUSTER_EPS,
        minPoints: Int = CLUSTER_MIN_POINTS,
    ): List<List<Point3D>> {
        if (points.size < minPoints) return emptyList()
        val visited = BooleanArray(points.size)
        val clusters = mutableListOf<List<Point3D>>()
        for (i in points.indices) {
            if (visited[i]) continue
            val members = mutableListOf<Point3D>()
            val stack = ArrayDeque<Int>()
            stack.addLast(i)
            visited[i] = true
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                members.add(points[node])
                for (j in points.indices) {
                    if (!visited[j] && distanceSq(points[node], points[j]) <= eps * eps) {
                        visited[j] = true
                        stack.addLast(j)
                    }
                }
            }
            if (members.size >= minPoints) clusters.add(members)
        }
        return clusters
    }

    private fun distanceSq(a: Point3D, b: Point3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }

    /** STUFE 2.1: PCA-Ebenheitsmaß (elongationsrobust, 0 = volumetrisch, 1 = planar). */
    fun calculatePlanarityScore(points: List<Point3D>): Float {
        if (points.size < 3) return 0f
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        val cz = points.map { it.z }.average().toFloat()

        var c00 = 0f; var c01 = 0f; var c02 = 0f
        var c11 = 0f; var c12 = 0f; var c22 = 0f
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            val dz = p.z - cz
            c00 += dx * dx; c01 += dx * dy; c02 += dx * dz
            c11 += dy * dy; c12 += dy * dz; c22 += dz * dz
        }
        val cov = arrayOf(
            floatArrayOf(c00, c01, c02),
            floatArrayOf(c01, c11, c12),
            floatArrayOf(c02, c12, c22),
        )
        val eigenvalues = eigenValues(cov).sortedDescending()
        val lam2 = eigenvalues[1]
        if (lam2 <= 1e-12f) return 0f
        return (eigenvalues[1] - eigenvalues[2]) / lam2
    }

    /** Jacobi-Eigenwerte einer symmetrischen 3×3-Matrix. */
    private fun eigenValues(a: Array<FloatArray>): List<Float> {
        var m = a.map { it.copyOf() }.toTypedArray()
        repeat(24) {
            // größtes Nicht-Diagonalelement
            var p = 0; var q = 1
            var maxVal = abs(m[0][1])
            if (abs(m[0][2]) > maxVal) { maxVal = abs(m[0][2]); p = 0; q = 2 }
            if (abs(m[1][2]) > maxVal) { maxVal = abs(m[1][2]); p = 1; q = 2 }
            if (maxVal < 1e-9f) return listOf(m[0][0], m[1][1], m[2][2])
            val phi = 0.5f * kotlin.math.atan2(2f * m[p][q], m[q][q] - m[p][p])
            val c = kotlin.math.cos(phi)
            val s = kotlin.math.sin(phi)
            val app = c * c * m[p][p] - 2f * s * c * m[p][q] + s * s * m[q][q]
            val aqq = s * s * m[p][p] + 2f * s * c * m[p][q] + c * c * m[q][q]
            m[p][p] = app
            m[q][q] = aqq
            m[p][q] = 0f
            m[q][p] = 0f
            for (r in 0..2) {
                if (r != p && r != q) {
                    val arp = c * m[r][p] - s * m[r][q]
                    val arq = s * m[r][p] + c * m[r][q]
                    m[r][p] = arp; m[p][r] = arp
                    m[r][q] = arq; m[q][r] = arq
                }
            }
        }
        return listOf(m[0][0], m[1][1], m[2][2])
    }

    /** STUFE 2.2 (vereinfacht): RANSAC-Ebenenkonsens der dominanten Ebene. */
    fun ransacPlaneConsensus(
        points: List<Point3D>,
        distThreshold: Float = 0.05f,
    ): Pair<FloatArray, List<Point3D>> {
        if (points.size < 3) return FloatArray(3) to emptyList()
        var bestNormal = FloatArray(3)
        var bestInliers: List<Point3D> = emptyList()
        val iterations = minOf(80, maxOf(8, points.size / 2))
        for (iter in 0 until iterations) {
            val i1 = (iter * 31 + 7) % points.size
            val i2 = (iter * 57 + 13) % points.size
            val i3 = (iter * 91 + 23) % points.size
            if (i1 == i2 || i2 == i3 || i1 == i3) continue
            val p1 = points[i1]; val p2 = points[i2]; val p3 = points[i3]
            val ux = p2.x - p1.x; val uy = p2.y - p1.y; val uz = p2.z - p1.z
            val vx = p3.x - p1.x; val vy = p3.y - p1.y; val vz = p3.z - p1.z
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val norm = sqrt(nx * nx + ny * ny + nz * nz)
            if (norm < 1e-9f) continue
            nx /= norm; ny /= norm; nz /= norm
            val d = -(nx * p1.x + ny * p1.y + nz * p1.z)
            val inliers = points.filter { abs(nx * it.x + ny * it.y + nz * it.z + d) < distThreshold }
            if (inliers.size > bestInliers.size) {
                bestInliers = inliers
                bestNormal = floatArrayOf(nx, ny, nz)
            }
        }
        return bestNormal to bestInliers
    }

    /** STUFE 3.1/3.3: Zylinder- + Plausibilitätsvalidierung (rein geometrisch). */
    fun validateDynamicCluster(points: List<Point3D>, centroid: Point3D): Boolean {
        if (points.size < CLUSTER_MIN_POINTS) return false
        val zMin = points.minOf { it.z }
        val zMax = points.maxOf { it.z }
        val height = zMax - zMin
        if (height < HEIGHT_MIN || height > HEIGHT_MAX) return false
        val width = points.maxOf { it.x } - points.minOf { it.x }
        if (width > MAX_WIDTH) return false
        // Zylinder: mind. 60 % der Punkte im Radius um das XY-Zentrum
        val inside = points.count {
            val dx = it.x - centroid.x
            val dy = it.y - centroid.y
            dx * dx + dy * dy <= CYLINDER_RADIUS * CYLINDER_RADIUS
        }
        if (inside < points.size * 0.6) return false
        val depth = points.maxOf { it.y } - points.minOf { it.y }
        val volume = width * depth * height
        if (volume < MIN_VOLUME) return false
        val dims = listOf(width, depth, height).sorted()
        val sphericity = dims.first() / (dims.last() + 1e-9f)
        return sphericity >= MIN_SPHERICITY
    }

    private fun centroid(points: List<Point3D>): Point3D =
        Point3D(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat(),
            points.map { it.z }.average().toFloat(),
        )

    private fun meanDistanceToOrigin(points: List<Point3D>): Float {
        if (points.isEmpty()) return 0f
        return points.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }.average().toFloat()
    }

    /**
     * Hauptklassifikationsmethode: liefert die Cluster-Berichte.
     * `persistable = false` ⇒ Live-Only (DYNAMIC), NIE speichern.
     */
    fun classify(points: List<Point3D>): Pair<List<ClusterReport>, Map<String, Int>> {
        if (points.isEmpty()) {
            return emptyList<ClusterReport>() to mapOf("total_points" to 0, "walls" to 0, "dynamic" to 0)
        }
        val down = voxelFilter(points)
        val filtered = heightFilter(down)
        val rawClusters = euclideanClustering(filtered).toMutableList()
        // Wände über 2,5 m als statische Struktur mitnehmen
        val high = points.filter { it.z > HEIGHT_MAX }
        if (high.size >= CLUSTER_MIN_POINTS) rawClusters.add(high)

        val reports = mutableListOf<ClusterReport>()
        var walls = 0
        var dynamic = 0
        for (cluster in rawClusters) {
            val planarity = calculatePlanarityScore(cluster)
            val dist = meanDistanceToOrigin(cluster)
            val threshold = if (dist < PLANARITY_DISTANCE_M) PLANARITY_THRESHOLD_NEAR else PLANARITY_THRESHOLD_FAR
            val c = centroid(cluster)
            val label: ObjectType
            val persistable: Boolean
            when {
                planarity > threshold -> { label = ObjectType.WALL; persistable = true; walls++ }
                validateDynamicCluster(cluster, c) -> { label = ObjectType.DYNAMIC; persistable = false; dynamic++ }
                else -> { label = ObjectType.WALL; persistable = true; walls++ } // zu flach/breit → Statik
            }
            reports.add(
                ClusterReport(
                    label = label,
                    centroid = c,
                    bboxMin = Point3D(
                        cluster.minOf { it.x }, cluster.minOf { it.y }, cluster.minOf { it.z },
                    ),
                    bboxMax = Point3D(
                        cluster.maxOf { it.x }, cluster.maxOf { it.y }, cluster.maxOf { it.z },
                    ),
                    planarity = planarity,
                    count = cluster.size,
                    persistable = persistable,
                    points = cluster,
                ),
            )
        }
        return reports to mapOf(
            "total_points" to points.size,
            "downsampled_points" to down.size,
            "height_filtered_points" to filtered.size,
            "clusters" to reports.size,
            "walls" to walls,
            "dynamic" to dynamic,
        )
    }

    /** Nur die persistierbaren Punkte (Statik) — für die Kartenspeicherung. */
    fun persistablePoints(points: List<Point3D>): List<Point3D> {
        val (reports, _) = classify(points)
        val kept = mutableListOf<Point3D>()
        for (report in reports) {
            if (report.persistable) kept.addAll(report.points)
        }
        kept.addAll(points.filter { it.z > HEIGHT_MAX }) // Decken-/Wandband
        return kept.distinctBy { listOf(it.x, it.y, it.z) }
    }
}

/** Punkt im Klassifikations-Koordinatensystem (Meter, Agent-Frame). */
data class Point3D(val x: Float, val y: Float, val z: Float)
