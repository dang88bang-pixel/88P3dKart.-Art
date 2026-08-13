package com.example.agent.offline

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ICP-Map-Merging (Kabsch-Umeyama) in Kotlin.
 * Führt zwei Punktwolken durch optimale Rotation + Translation zusammen.
 */
object ICPMerger {

    data class Transform(val rotation: Array<FloatArray>, val translation: FloatArray)

    private val IDENTITY = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
    )

    /**
     * ICP: passt `source` an `target` an.
     * Rückgabe: (transformierte Punktwolke, kumulierte Transformation).
     */
    fun icp(
        source: Array<FloatArray>,
        target: Array<FloatArray>,
        maxIterations: Int = 50,
        tolerance: Float = 1e-6f,
        maxCorrespondenceDist: Float = 5f,
    ): Pair<Array<FloatArray>, Transform> {
        if (source.isEmpty() || target.isEmpty()) {
            return source to Transform(IDENTITY, floatArrayOf(0f, 0f, 0f))
        }

        var src = source.map { it.copyOf() }.toTypedArray()
        var prevError = Float.MAX_VALUE
        var rTotal = IDENTITY
        var tTotal = floatArrayOf(0f, 0f, 0f)

        for (iter in 0 until maxIterations) {
            // Nearest-Neighbor (brute force)
            val correspondences = mutableListOf<Pair<FloatArray, FloatArray>>()
            for (p in src) {
                var minDist = Float.MAX_VALUE
                var best = target[0]
                for (q in target) {
                    val dx = p[0] - q[0]; val dy = p[1] - q[1]; val dz = p[2] - q[2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < minDist) { minDist = d; best = q }
                }
                if (sqrt(minDist) < maxCorrespondenceDist) correspondences.add(p to best)
            }
            if (correspondences.size < 3) break

            val (r, t) = kabschUmeyama(correspondences)

            for (i in src.indices) src[i] = matVec3(r, src[i], t)

            rTotal = matMul3(r, rTotal)
            tTotal = addVec(matVec3(r, tTotal, zeroVec()), t)

            var error = 0f
            for ((a, b) in correspondences) {
                val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
                error += dx * dx + dy * dy + dz * dz
            }
            error = sqrt(error / correspondences.size)
            if (abs(prevError - error) < tolerance) break
            prevError = error
        }

        return src to Transform(rTotal, tTotal)
    }

    /** Kabsch-Umeyama: optimale Rotation R und Translation t für Korrespondenzen. */
    fun kabschUmeyama(correspondences: List<Pair<FloatArray, FloatArray>>): Pair<Array<FloatArray>, FloatArray> {
        val n = correspondences.size.toFloat()
        val muA = FloatArray(3)
        val muB = FloatArray(3)
        for ((a, b) in correspondences) {
            muA[0] += a[0]; muA[1] += a[1]; muA[2] += a[2]
            muB[0] += b[0]; muB[1] += b[1]; muB[2] += b[2]
        }
        muA[0] /= n; muA[1] /= n; muA[2] /= n
        muB[0] /= n; muB[1] /= n; muB[2] /= n

        // Korrelationsmatrix H = A'^T B'
        val H = Array(3) { DoubleArray(3) }
        for ((a, b) in correspondences) {
            val ax = (a[0] - muA[0]).toDouble(); val ay = (a[1] - muA[1]).toDouble(); val az = (a[2] - muA[2]).toDouble()
            val bx = (b[0] - muB[0]).toDouble(); val by = (b[1] - muB[1]).toDouble(); val bz = (b[2] - muB[2]).toDouble()
            H[0][0] += ax * bx; H[0][1] += ax * by; H[0][2] += ax * bz
            H[1][0] += ay * bx; H[1][1] += ay * by; H[1][2] += ay * bz
            H[2][0] += az * bx; H[2][1] += az * by; H[2][2] += az * bz
        }

        val (U, _, Vt) = svd3(H)
        var R = matMul3(Vt, transpose3(U))

        if (det3(R) < 0) {
            // Reflexion vermeiden
            val VtFix = Vt.map { it.copyOf() }.toTypedArray()
            for (i in 0..2) VtFix[i][2] = -VtFix[i][2]
            R = matMul3(VtFix, transpose3(U))
        }

        val rMuA = matVec3(R, muA, zeroVec())
        val t = floatArrayOf(muB[0] - rMuA[0], muB[1] - rMuA[1], muB[2] - rMuA[2])
        return R to t
    }

    // ─── 3x3 SVD (Jacobi auf A^T A) ─────────────────────────────
    private fun svd3(A: Array<DoubleArray>): Triple<Array<DoubleArray>, DoubleArray, Array<DoubleArray>> {
        // A^T A (symmetrisch 3x3)
        val AtA = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += A[k][i] * A[k][j]
            AtA[i][j] = s
        }

        val (V, eig) = jacobiEigen(AtA)
        val order = listOf(0, 1, 2).sortedByDescending { eig[it] }
        val s = DoubleArray(3) { sqrt(max(0.0, eig[order[it]])) }

        // Vt = V^T, Zeilen = Eigenvektoren (absteigend sortiert)
        val Vt = Array(3) { DoubleArray(3) }
        for (col in 0..2) for (row in 0..2) Vt[col][row] = V[row][order[col]]

        // U = A V S^-1
        val U = Array(3) { DoubleArray(3) }
        for (col in 0..2) {
            val inv = if (s[col] > 1e-12) 1.0 / s[col] else 0.0
            for (row in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += A[row][k] * V[k][order[col]]
                U[row][col] = sum * inv
            }
        }
        // Null-Singulärwerte: U-Spalten orthogonal vervollständigen
        for (col in 0..2) {
            if (s[col] > 1e-12) continue
            val a = U[(col + 1) % 3]
            val b = U[(col + 2) % 3]
            U[0][col] = a[1] * b[2] - a[2] * b[1]
            U[1][col] = a[2] * b[0] - a[0] * b[2]
            U[2][col] = a[0] * b[1] - a[1] * b[0]
        }
        return Triple(U, s, Vt)
    }

    private fun jacobiEigen(sym: Array<DoubleArray>): Pair<Array<DoubleArray>, DoubleArray> {
        val a = Array(3) { i -> DoubleArray(3) { j -> sym[i][j] } }
        val V = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        repeat(30) {
            var p = 0; var q = 1
            var mx = abs(a[0][1])
            for (i in 0..2) for (j in i + 1..2) {
                if (abs(a[i][j]) > mx) { mx = abs(a[i][j]); p = i; q = j }
            }
            if (mx < 1e-14) return@repeat

            val app = a[p][p]; val aqq = a[q][q]; val apq = a[p][q]
            val phi = 0.5 * atan2(2 * apq, aqq - app)
            val c = cos(phi); val s = sin(phi)

            for (k in 0..2) {
                if (k != p && k != q) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - s * akq; a[p][k] = a[k][p]
                    a[k][q] = s * akp + c * akq; a[q][k] = a[k][q]
                }
            }
            a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq
            a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq
            a[p][q] = 0.0; a[q][p] = 0.0

            for (k in 0..2) {
                val vkp = V[k][p]; val vkq = V[k][q]
                V[k][p] = c * vkp - s * vkq
                V[k][q] = s * vkp + c * vkq
            }
        }
        return V to doubleArrayOf(a[0][0], a[1][1], a[2][2])
    }

    // ─── Helfer ────────────────────────────────────────────────
    private fun zeroVec() = floatArrayOf(0f, 0f, 0f)
    private fun addVec(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    private fun matVec3(r: Array<FloatArray>, v: FloatArray, t: FloatArray): FloatArray = floatArrayOf(
        r[0][0] * v[0] + r[0][1] * v[1] + r[0][2] * v[2] + t[0],
        r[1][0] * v[0] + r[1][1] * v[1] + r[1][2] * v[2] + t[1],
        r[2][0] * v[0] + r[2][1] * v[1] + r[2][2] * v[2] + t[2],
    )

    private fun matMul3(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) {
            r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]
        }
        return r
    }

    private fun matMul3(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) {
            r[i][j] = (a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]).toFloat()
        }
        return r
    }

    private fun transpose3(a: Array<DoubleArray>): Array<DoubleArray> = arrayOf(
        doubleArrayOf(a[0][0], a[1][0], a[2][0]),
        doubleArrayOf(a[0][1], a[1][1], a[2][1]),
        doubleArrayOf(a[0][2], a[1][2], a[2][2]),
    )

    private fun det3(a: Array<FloatArray>): Float =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
        a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
        a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
}
