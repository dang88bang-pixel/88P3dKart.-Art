package com.example.agent.triangulation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Trilateration — Positionsbestimmung aus Distanzmessungen zu Ankern mit
 * bekannter Position (docs/TRIANGULATION.md §5).
 *
 * Verfahren:
 * 1. **Lineare Startlösung** (Referenz-Anker subtrahieren → lineares
 *    Kleinste-Quadrate-System, siehe [lmSolve]),
 * 2. **Levenberg-Marquardt-Verfeinerung** (nichtlineares
 *    Kleinste-Quadrate-Problem min Σ wᵢ·(|p−aᵢ| − dᵢ)² mit analytischer
 *    Jacobi-Matrix, Dämpfung λ adaptiv),
 * 3. **Reject-and-Resolve (LTS-1):** jede Leave-one-out-Lösung wird über die
 *    Trimmed-Kosten (Summe der m−1 kleinsten quadratischen Residuen)
 *    bewertet; liegt die beste ≥ 40 % unter der Volllösung, wird der
 *    betreffende Anker als Ausreißer verworfen (robust gegen Masking —
 *    spiegelbildlich zu `edge-agent/trilateration.py`).
 *
 * Ausgabe inkl. Residuum-RMS, Positions-Sigma ((JᵀWJ)⁻¹-Spur) und
 * Konfidenzwert — Grundlage für die Sensorfusion (Mahalanobis-Gate in
 * [EstimateGate]) und den EKF-Messupdate.
 */
object TrilaterationEngine {

    /** Anker (Access Point, Beacon) mit bekannter Position. */
    data class Anchor(
        val id: String,
        val x: Double,
        val y: Double,
        val z: Double = 0.0,
    )

    /** Ergebnis einer Trilateration. */
    data class Estimate(
        val x: Double,
        val y: Double,
        val z: Double,
        /** sqrt(mean(rᵢ²)) der Distanzresiduen in Metern. */
        val residualRmsM: Double,
        /** Positions-Unsicherheit in Metern: sqrt(trace((JᵀWJ)⁻¹)). */
        val positionSigmaM: Double,
        /** Konfidenz 0..1 (Residuum + Konvergenz). */
        val confidence: Float,
        val converged: Boolean,
        val iterations: Int,
        val anchorCount: Int,
        /** Anzahl der verworfenen Ausreißer-Anker. */
        val rejectedAnchors: Int = 0,
    )

    private const val MAX_ITERATIONS = 40
    private const val CONVERGENCE_DELTA = 1e-12
    private const val DEFAULT_UNCERTAINTY_M = 1.0

    /** Interne Lösung eines LM-Durchlaufs. */
    private data class LmResult(
        val p: DoubleArray,
        val converged: Boolean,
        val iterations: Int,
    )

    private data class Entry(val anchor: Anchor, val distance: Double, val uncertainty: Double)

    /**
     * Löst die Trilateration.
     * @param anchors Anker mit bekannter Position
     * @param distances Distanzen in Metern, Schlüssel = [Anchor.id]
     * @param uncertainties Messunsicherheiten σ in Metern (Standard 1,0 m);
     *        inverse Varianz = Gewichtung
     * @param useZ 3D-Lösung (≥ 4 Anker) oder 2D-Lösung (≥ 3 Anker)
     * @param robustIterations Reject-and-Resolve-Durchgänge (LTS-1, Standard 2)
     * @return [Estimate] oder null bei zu wenigen/gültigen Messungen
     */
    fun solve(
        anchors: List<Anchor>,
        distances: Map<String, Double>,
        uncertainties: Map<String, Double> = emptyMap(),
        useZ: Boolean = true,
        robustIterations: Int = 2,
    ): Estimate? {
        var entries = anchors.mapNotNull { a ->
            val d = distances[a.id]
            if (d == null || !d.isFinite() || d < 0.0) return@mapNotNull null
            var u = uncertainties[a.id] ?: DEFAULT_UNCERTAINTY_M
            if (!u.isFinite() || u <= 0.0) u = DEFAULT_UNCERTAINTY_M
            Entry(a, d, u)
        }.toMutableList()

        val minAnchors = if (useZ) 4 else 3
        if (entries.size < minAnchors) return null
        val dim = if (useZ) 3 else 2

        // 1) Volllösung
        var result = lmSolve(entries, dim) ?: return null

        // 2) Reject-and-Resolve (LTS-1)
        var rejected = 0
        var iter = 0
        while (iter < robustIterations.coerceAtLeast(0) && entries.size > minAnchors) {
            val costFull = trimmedCost(result.p, entries, dim)
            var bestIdx = -1
            var bestCost = Double.POSITIVE_INFINITY
            var bestResult: LmResult? = null
            for (i in entries.indices) {
                val sub = entries.filterIndexed { idx, _ -> idx != i }
                val candidate = lmSolve(sub, dim) ?: continue
                val c = trimmedCost(candidate.p, entries, dim)
                if (c < bestCost) {
                    bestCost = c
                    bestIdx = i
                    bestResult = candidate
                }
            }
            if (bestIdx >= 0 && bestResult != null && bestCost < 0.6 * costFull) {
                entries.removeAt(bestIdx)
                result = bestResult
                rejected++
                iter++
            } else {
                break
            }
        }

        // 3) Abschlussbewertung
        val weights = DoubleArray(entries.size) { 1.0 / (entries[it].uncertainty * entries[it].uncertainty) }
        var rmsSum = 0.0
        val jacobian = Array(entries.size) { DoubleArray(dim) }
        for (i in entries.indices) {
            val dx = result.p[0] - entries[i].anchor.x
            val dy = result.p[1] - entries[i].anchor.y
            val dz = if (dim == 3) result.p[2] - entries[i].anchor.z else 0.0
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            val r = dist - entries[i].distance
            rmsSum += r * r
            val safe = if (dist < 1e-6) 1e-6 else dist
            jacobian[i][0] = dx / safe
            jacobian[i][1] = dy / safe
            if (dim == 3) jacobian[i][2] = dz / safe
        }
        val rms = sqrt(rmsSum / entries.size)

        val jtwj = DoubleArray(dim * dim)
        for (i in entries.indices) {
            val w = weights[i]
            for (row in 0 until dim) {
                for (col in 0 until dim) {
                    jtwj[row * dim + col] += jacobian[i][row] * w * jacobian[i][col]
                }
            }
        }
        val sigma = sqrt(traceInverse(jtwj, dim))

        var confidence = (1.0 - rms / 3.0).coerceIn(0.0, 1.0).toFloat()
        if (!result.converged) confidence *= 0.6f
        if (sigma > 50.0) confidence = minOf(confidence, 0.3f)

        return Estimate(
            x = result.p[0],
            y = result.p[1],
            z = if (dim == 3) result.p[2] else 0.0,
            residualRmsM = rms,
            positionSigmaM = sigma,
            confidence = confidence,
            converged = result.converged,
            iterations = result.iterations,
            anchorCount = entries.size,
            rejectedAnchors = rejected,
        )
    }

    // ── LM-Durchlauf ─────────────────────────────────────────────────

    /**
     * Levenberg-Marquardt auf den gegebenen Einträgen:
     * Start über das lineare LSQ-System (Referenz-Anker-Subtraktion).
     */
    private fun lmSolve(entries: List<Entry>, dim: Int): LmResult? {
        val weights = DoubleArray(entries.size) { 1.0 / (entries[it].uncertainty * entries[it].uncertainty) }

        var p = linearInit(entries, dim) ?: centroidInit(entries, dim)

        var lambda = 1e-3
        var cost = Double.POSITIVE_INFINITY
        var converged = false
        var iterations = 0

        for (iter in 0 until MAX_ITERATIONS) {
            iterations = iter
            val residuals = DoubleArray(entries.size)
            val jacobian = Array(entries.size) { DoubleArray(dim) }
            var newCost = 0.0
            for (i in entries.indices) {
                val a = entries[i].anchor
                val dx = p[0] - a.x
                val dy = p[1] - a.y
                val dz = if (dim == 3) p[2] - a.z else 0.0
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val safe = if (dist < 1e-6) 1e-6 else dist
                val r = dist - entries[i].distance
                residuals[i] = r
                newCost += weights[i] * r * r
                jacobian[i][0] = dx / safe
                jacobian[i][1] = dy / safe
                if (dim == 3) jacobian[i][2] = dz / safe
            }

            if (newCost >= cost && cost.isFinite()) {
                lambda *= 10.0
                if (lambda > 1e9) break
            } else {
                lambda = max(lambda * 0.3, 1e-9)
                cost = newCost
            }

            // Normalengleichungen: (JᵀWJ + λI) Δ = −JᵀWr
            val jtwj = DoubleArray(dim * dim)
            val jtwr = DoubleArray(dim)
            for (i in entries.indices) {
                val w = weights[i]
                for (row in 0 until dim) {
                    jtwr[row] += jacobian[i][row] * w * residuals[i]
                    for (col in 0 until dim) {
                        jtwj[row * dim + col] += jacobian[i][row] * w * jacobian[i][col]
                    }
                }
            }
            for (k in 0 until dim) jtwj[k * dim + k] += lambda

            val delta = solveLinear(jtwj, jtwr, dim) ?: break
            for (k in 0 until dim) p[k] -= delta[k]

            var deltaSq = 0.0
            for (k in 0 until dim) deltaSq += delta[k] * delta[k]
            if (deltaSq < CONVERGENCE_DELTA) {
                converged = true
                break
            }
        }

        return LmResult(p, converged, iterations)
    }

    /** Trimmed-Kosten (LTS-1): Summe der m−1 kleinsten quadratischen Residuen. */
    private fun trimmedCost(p: DoubleArray, entries: List<Entry>, dim: Int): Double {
        val squared = entries.map { e ->
            val dx = p[0] - e.anchor.x
            val dy = p[1] - e.anchor.y
            val dz = if (dim == 3) p[2] - e.anchor.z else 0.0
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            val r = dist - e.distance
            r * r
        }.sorted()
        return squared.take(max(0, entries.size - 1)).sum()
    }

    // ── Startlösungen ───────────────────────────────────────────────

    /**
     * Lineares Kleinste-Quadrate-System durch Subtraktion des Referenz-Ankers:
     * 2·(aᵢ−a₀)·p = d₀² − dᵢ² + ‖aᵢ‖² − ‖a₀‖².
     */
    private fun linearInit(entries: List<Entry>, dim: Int): DoubleArray? {
        val a0 = entries[0].anchor
        val d0 = entries[0].distance
        val rows = entries.size - 1
        val aMat = DoubleArray(rows * dim)
        val bVec = DoubleArray(rows)
        for (i in 1 until entries.size) {
            val ai = entries[i].anchor
            val di = entries[i].distance
            aMat[(i - 1) * dim] = 2.0 * (ai.x - a0.x)
            aMat[(i - 1) * dim + 1] = 2.0 * (ai.y - a0.y)
            if (dim == 3) aMat[(i - 1) * dim + 2] = 2.0 * (ai.z - a0.z)
            var norm0 = a0.x * a0.x + a0.y * a0.y
            var normI = ai.x * ai.x + ai.y * ai.y
            if (dim == 3) {
                norm0 += a0.z * a0.z
                normI += ai.z * ai.z
            }
            bVec[i - 1] = d0 * d0 - di * di + normI - norm0
        }

        val ata = DoubleArray(dim * dim)
        val atb = DoubleArray(dim)
        for (r in 0 until rows) {
            for (i in 0 until dim) {
                val av = aMat[r * dim + i]
                atb[i] += av * bVec[r]
                for (j in 0 until dim) {
                    ata[i * dim + j] += av * aMat[r * dim + j]
                }
            }
        }
        return solveLinear(ata, atb, dim)
    }

    /** Fallback: Anker-Schwerpunkt. */
    private fun centroidInit(entries: List<Entry>, dim: Int): DoubleArray {
        val c = DoubleArray(dim)
        for (e in entries) {
            c[0] += e.anchor.x
            c[1] += e.anchor.y
            if (dim == 3) c[2] += e.anchor.z
        }
        val n = entries.size.toDouble()
        c[0] /= n
        c[1] /= n
        if (dim == 3) c[2] /= n
        return c
    }

    // ── Lineare Algebra (2×2 / 3×3) ─────────────────────────────────

    /** Löst A·x = b für dim ∈ {2, 3}; null bei singulärer Matrix. */
    private fun solveLinear(a: DoubleArray, b: DoubleArray, dim: Int): DoubleArray? {
        if (dim == 2) {
            val det = a[0] * a[3] - a[1] * a[2]
            if (abs(det) < 1e-12) return null
            return doubleArrayOf(
                (b[0] * a[3] - a[1] * b[1]) / det,
                (a[0] * b[1] - b[0] * a[2]) / det,
            )
        }
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = DoubleArray(9)
        inv[0] = (a[4] * a[8] - a[5] * a[7]) / det
        inv[1] = (a[2] * a[7] - a[1] * a[8]) / det
        inv[2] = (a[1] * a[5] - a[2] * a[4]) / det
        inv[3] = (a[5] * a[6] - a[3] * a[8]) / det
        inv[4] = (a[0] * a[8] - a[2] * a[6]) / det
        inv[5] = (a[2] * a[3] - a[0] * a[5]) / det
        inv[6] = (a[3] * a[7] - a[4] * a[6]) / det
        inv[7] = (a[1] * a[6] - a[0] * a[7]) / det
        inv[8] = (a[0] * a[4] - a[1] * a[3]) / det
        return doubleArrayOf(
            inv[0] * b[0] + inv[1] * b[1] + inv[2] * b[2],
            inv[3] * b[0] + inv[4] * b[1] + inv[5] * b[2],
            inv[6] * b[0] + inv[7] * b[1] + inv[8] * b[2],
        )
    }

    /** Spur der Inversen einer (dim×dim)-Matrix — Positions-Sigma. */
    private fun traceInverse(a: DoubleArray, dim: Int): Double {
        if (dim == 2) {
            val det = a[0] * a[3] - a[1] * a[2]
            if (abs(det) < 1e-12) return 1e6
            return (a[3] + a[0]) / det
        }
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return 1e6
        val inv00 = (a[4] * a[8] - a[5] * a[7]) / det
        val inv11 = (a[0] * a[8] - a[2] * a[6]) / det
        val inv22 = (a[0] * a[4] - a[1] * a[3]) / det
        return inv00 + inv11 + inv22
    }
}
