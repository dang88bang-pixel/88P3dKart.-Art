package com.example.agent.aura

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Radio-Tomographische Bildgebung (RTI) — 3D-Rekonstruktion des
 * Raumverlustfelds φ (docs/AURA.md §4.1).
 *
 * Jede Messung liefert die Dämpfung y eines Links zwischen Sender und Empfänger:
 *
 *     y_i,j = ∫ φ(x,y,z) ds + n
 *
 * Diskretisiert (Voxel + Gewichtungsmodell) ergibt sich das lineare System
 *
 *     y = A·φ,  gelöst per Tikhonov-Regularisierung: min ‖Aφ − y‖² + λ‖φ‖²
 *
 * mit matrixfreiem Conjugate-Gradient (Sparse-Rows), alternativ per
 * Backprojection (gewichteter Durchschnitt) für Echtzeit-Vorschauen.
 */
class RtiSolver(
    private val boundsMin: FloatArray,
    private val boundsMax: FloatArray,
    private val voxelSize: Float,
    /** Ellipsenbreite λ_w in Metern (Gewichtungsmodell). */
    private val ellipseWidth: Float = 0.05f,
    /** Tikhonov-Regularisierungsparameter λ. */
    private val regularization: Float = 0.1f,
    /**
     * Glättungs-Regularisierung γ: diskreter Graph-Laplacian über die
     * 6-Nachbarschaft des Voxelgitters — (WᵀW + λI + γL)·φ = Wᵀy
     * (Differenzoperator-Ansatz, vgl. SPIE 8753 „Regularization in radio
     * tomographic imaging" und `edge-agent/rti_solver.py`).
     */
    private val smoothing: Float = 0f,
) {

    companion object {
        /** Obergrenze Voxelzahl — Schutz vor Speicherexplosion auf Mobilgeräten. */
        private const val MAX_VOXELS = 500_000
        private const val MAX_CG_ITERATIONS = 500
        private const val CG_TOLERANCE = 1e-6

        fun distance3(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            val dz = a[2] - b[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    /** Eine Messlinie zwischen Sender und Empfänger inkl. gemessener Dämpfung. */
    data class Link(
        val tx: FloatArray,        // [x, y, z] Sender
        val rx: FloatArray,        // [x, y, z] Empfänger
        val attenuationDb: Float,  // Dämpfung y_i,j in dB
    ) {
        val length: Float get() = distance3(tx, rx)
    }

    /** Rekonstruiertes Voxel des Dämpfungsfelds. */
    data class Voxel(
        val index: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        /** Rekonstruierte Dämpfung (Tikhonov/Backprojection), dB-Einheiten der Messung. */
        val attenuation: Float,
        /** Normierte Gewichtung 0..1 (relative Empfindlichkeit). */
        val weight: Float = 1f,
    )

    private val links = mutableListOf<Link>()

    private val nx: Int
    private val ny: Int
    private val nz: Int

    init {
        require(voxelSize > 0f) { "voxelSize muss > 0 sein" }
        nx = max(1, ceil((boundsMax[0] - boundsMin[0]) / voxelSize).toInt())
        ny = max(1, ceil((boundsMax[1] - boundsMin[1]) / voxelSize).toInt())
        nz = max(1, ceil((boundsMax[2] - boundsMin[2]) / voxelSize).toInt())
        require(nx.toLong() * ny * nz <= MAX_VOXELS) {
            "Voxelgitter zu groß: $nx×$ny×$nz (Limit $MAX_VOXELS)"
        }
    }

    val voxelCount: Int get() = nx * ny * nz
    val linkCount: Int get() = links.size

    fun addLink(link: Link) = links.add(link)
    fun addLink(tx: FloatArray, rx: FloatArray, attenuationDb: Float) =
        links.add(Link(tx, rx, attenuationDb))

    fun clearLinks() = links.clear()

    /** Voxel-Mittelpunkt für einen linearen Index. */
    fun voxelCenter(index: Int): FloatArray {
        val iz = index / (nx * ny)
        val rem = index % (nx * ny)
        val iy = rem / nx
        val ix = rem % nx
        return floatArrayOf(
            boundsMin[0] + (ix + 0.5f) * voxelSize,
            boundsMin[1] + (iy + 0.5f) * voxelSize,
            boundsMin[2] + (iz + 0.5f) * voxelSize,
        )
    }

    // ── Gewichtungsmodell ─────────────────────────────────────────────

    /**
     * Normalisiertes Ellipsen-Gewichtungsmodell:
     *
     *     w_i,j(v) = 1 / √(d_tx(v) + d_rx(v))   falls d_tx + d_rx < d_link + λ_w
     *     w_i,j(v) = 0                          sonst
     *
     * Anschließend zeilennormiert (Σ_v w = 1).
     * Rückgabe: (Voxel-Index, Gewicht)-Paare je Link.
     */
    fun buildWeights(): List<List<Pair<Int, Float>>> {
        return links.map { link ->
            val dLink = link.length
            val weights = ArrayList<Pair<Int, Float>>(voxelCount)
            var sum = 0.0
            for (i in 0 until voxelCount) {
                val c = voxelCenter(i)
                val dTx = distance3(c, link.tx)
                val dRx = distance3(c, link.rx)
                if (dTx + dRx < dLink + ellipseWidth) {
                    val w = 1.0 / sqrt((dTx + dRx).toDouble()).toFloat()
                    weights.add(i to w)
                    sum += w
                }
            }
            if (sum > 0.0) {
                for (k in weights.indices) {
                    weights[k] = weights[k].first to (weights[k].second / sum).toFloat()
                }
            }
            weights
        }
    }

    // ── Löser ────────────────────────────────────────────────────────

    /**
     * Tikhonov-Lösung über matrixfreies Conjugate-Gradient:
     * (AᵀA + λI) φ = Aᵀy.
     */
    fun solve(): List<Voxel> {
        if (links.isEmpty()) return emptyList()
        val rows = buildWeights()
        val n = voxelCount
        val m = links.size

        // b = Aᵀy
        val b = DoubleArray(n)
        for (i in 0 until m) {
            val y = links[i].attenuationDb.toDouble()
            for ((v, w) in rows[i]) b[v] += w * y
        }

        // Matrixfreie Anwendung von (AᵀA + λI + γL):
        fun applyM(x: DoubleArray): DoubleArray {
            val out = DoubleArray(n)
            for (i in 0 until m) {
                var s = 0.0
                val row = rows[i]
                for ((v, w) in row) s += w * x[v]
                for ((v, w) in row) out[v] += w * s
            }
            for (v in 0 until n) out[v] += regularization * x[v]
            if (smoothing > 0f) {
                val lap = applyLaplacian(x)
                for (v in 0 until n) out[v] += smoothing * lap[v]
            }
            return out
        }

        fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until n) s += a[i] * b[i]
            return s
        }

        // Conjugate Gradient
        val x = DoubleArray(n)
        var r = b.clone()
        var p = r.clone()
        var rsOld = dot(r, r)
        val tolSq = CG_TOLERANCE * CG_TOLERANCE * max(1.0, rsOld)

        var converged = false
        for (iter in 0 until MAX_CG_ITERATIONS) {
            val ap = applyM(p)
            val alpha = rsOld / dot(p, ap)
            for (i in 0 until n) x[i] += alpha * p[i]
            for (i in 0 until n) r[i] -= alpha * ap[i]
            val rsNew = dot(r, r)
            if (rsNew <= tolSq) {
                converged = true
                break
            }
            val beta = rsNew / rsOld
            for (i in 0 until n) p[i] = r[i] + beta * p[i]
            rsOld = rsNew
        }

        // Ergebnis: auch ohne Konvergenz (Iterationslimit) eine gültige Näherung.
        return buildField(x)
    }

    /**
     * Backprojection (gewichteter Durchschnitt) — O(m·n), für
     * Echtzeit-Vorschauen und als Initialschätzung:
     * φ_v = Σ_i w_i,v · y_i / Σ_i w_i,v.
     */
    fun solveBackprojection(): List<Voxel> {
        if (links.isEmpty()) return emptyList()
        val rows = buildWeights()
        val n = voxelCount
        val field = DoubleArray(n)
        val weightSum = DoubleArray(n)

        for (i in links.indices) {
            val y = links[i].attenuationDb.toDouble()
            for ((v, w) in rows[i]) {
                field[v] += w * y
                weightSum[v] += w
            }
        }
        for (v in 0 until n) {
            if (weightSum[v] > 0.0) field[v] /= weightSum[v]
        }
        return buildField(field)
    }

    private fun buildField(field: DoubleArray): List<Voxel> {
        return List(voxelCount) { i ->
            val c = voxelCenter(i)
            Voxel(i, c[0], c[1], c[2], field[i].toFloat())
        }
    }

    /**
     * Matrixfreie Anwendung des diskreten Graph-Laplacian (6-Nachbarschaft):
     * (Lx)[v] = deg(v)·x[v] − Σ_{n∈N(v)} x[n]  —  O(6n).
     */
    private fun applyLaplacian(x: DoubleArray): DoubleArray {
        val out = DoubleArray(voxelCount)
        for (v in 0 until voxelCount) {
            val ix = v % nx
            val iy = (v / nx) % ny
            val iz = v / (nx * ny)
            var degree = 0
            var sum = 0.0
            if (ix > 0) { degree++; sum += x[v - 1] }
            if (ix < nx - 1) { degree++; sum += x[v + 1] }
            if (iy > 0) { degree++; sum += x[v - nx] }
            if (iy < ny - 1) { degree++; sum += x[v + nx] }
            if (iz > 0) { degree++; sum += x[v - nx * ny] }
            if (iz < nz - 1) { degree++; sum += x[v + nx * ny] }
            out[v] = degree * x[v] - sum
        }
        return out
    }

    /**
     * Lokale Maxima des Dämpfungsfelds — Kandidaten für Objekte/Personen.
     * @param topK maximale Anzahl zurückgegebener Peaks
     * @param minSeparationVoxels Mindestabstand zwischen Peaks (Voxel, Chebyshev)
     */
    fun locatePeaks(
        field: List<Voxel>,
        topK: Int = 8,
        minSeparationVoxels: Int = 2,
    ): List<Voxel> {
        if (field.isEmpty()) return emptyList()
        val threshold = field.maxOf { it.attenuation } * 0.3f

        val candidates = field.asSequence()
            .filter { it.attenuation >= threshold }
            .sortedByDescending { it.attenuation }
            .toMutableList()

        val peaks = mutableListOf<Voxel>()
        val sep = minSeparationVoxels
        while (candidates.isNotEmpty() && peaks.size < topK) {
            val candidate = candidates.removeAt(0)
            val c = voxelCenter(candidate.index)
            val tooClose = peaks.any { p ->
                val pc = voxelCenter(p.index)
                maxOf(
                    kotlin.math.abs(c[0] - pc[0]),
                    kotlin.math.abs(c[1] - pc[1]),
                    kotlin.math.abs(c[2] - pc[2]),
                ) < sep * voxelSize
            }
            if (!tooClose) peaks.add(candidate)
        }
        return peaks
    }
}
