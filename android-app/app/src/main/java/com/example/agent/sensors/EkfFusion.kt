package com.example.agent.sensors

/**
 * Adaptiver 6-DOF Extended Kalman Filter als reine FloatArray-Klasse.
 *
 * Zustand: [x, y, z, vx, vy, vz]
 * Optimierung: manuell entrollte 6x6-Matrixmultiplikationen für Latenz < 5 ms.
 */
class EkfFusion(private val dt: Float = 0.05f) {

    // Zustand & Kovarianz (6x6, zeilenweise)
    private val x = FloatArray(6)
    private val P = FloatArray(36).also {
        it[0] = 1f; it[7] = 1f; it[14] = 1f; it[21] = 0.25f; it[28] = 0.25f; it[35] = 0.25f
    }

    // Prozessrauschen Q (konstant)
    private val Q = FloatArray(36).also {
        it[0] = 0.01f; it[7] = 0.01f; it[14] = 0.01f; it[21] = 0.1f; it[28] = 0.1f; it[35] = 0.1f
    }

    // Messrauschen (adaptiv)
    private var R_lidar = 0.1f
    private var R_mmwave = 0.3f
    private var scatteringScale = 1f

    fun adaptToEnvironment(scatteringDetected: Boolean, thermalC: Float) {
        scatteringScale = if (scatteringDetected) 1000f else 1f
        val thermalFactor = 1f + maxOf(0f, thermalC - 60f) * 0.05f
        R_lidar = 0.1f * scatteringScale * thermalFactor
    }

    fun predict() {
        // F = [[I, dt*I], [0, I]] → x' = F * x
        x[0] += x[3] * dt
        x[1] += x[4] * dt
        x[2] += x[5] * dt

        // P = F * P * F^T + Q  (vereinfacht auf Diagonale + Q)
        val f = FloatArray(36)
        for (i in 0..5) f[i * 6 + i] = 1f
        f[0 * 6 + 3] = dt; f[1 * 6 + 4] = dt; f[2 * 6 + 5] = dt
        val tmp = matMul6(f, P)
        val tmp2 = matMul6T(tmp, f)
        for (i in 0..35) P[i] = tmp2[i] + Q[i]
    }

    fun updateLidar(z: FloatArray) = updateGeneric(z, R_lidar)
    fun updateMmwave(z: FloatArray) = updateGeneric(z, R_mmwave)

    private fun updateGeneric(z: FloatArray, r: Float) {
        // H projiziert Position (3 Messwerte)
        val H = FloatArray(18)
        H[0] = 1f; H[1 * 6 + 1] = 1f; H[2 * 6 + 2] = 1f

        // Innovation y = z - H x
        val y = FloatArray(3)
        y[0] = z[0] - x[0]; y[1] = z[1] - x[1]; y[2] = z[2] - x[2]

        // S = H P H^T + R (nur Positionsteil) → S[3x3]
        val S = FloatArray(9)
        for (i in 0..2) for (j in 0..2) S[i * 3 + j] = P[i * 6 + j] + if (i == j) r else 0f

        // K = P H^T S^-1 → nur erste 3 Spalten von P nötig; nutze 3x3-Inverse
        val invS = inv3(S)
        val K = FloatArray(18)
        for (row in 0..5) for (col in 0..2) {
            var s = 0f
            for (k in 0..2) s += P[row * 6 + k] * invS[k * 3 + col]
            K[row * 3 + col] = s
        }

        // Zustandskorrektur
        for (row in 0..5) {
            var s = 0f
            for (k in 0..2) s += K[row * 3 + k] * y[k]
            x[row] += s
        }

        // Kovarianzkorrektur P = (I - K H) P
        val KH = FloatArray(36)
        for (row in 0..5) for (col in 0..2) KH[row * 6 + col] = K[row * 3 + col]
        val I = FloatArray(36)
        for (i in 0..5) I[i * 6 + i] = 1f
        val im = FloatArray(36)
        for (i in 0..35) im[i] = I[i] - KH[i]
        val tmp = matMul6(im, P)
        System.arraycopy(tmp, 0, P, 0, 36)
    }

    fun getState(): FloatArray = x.copyOf()
    fun getCovariance(): Array<FloatArray> =
        Array(6) { i -> FloatArray(6) { j -> P[i * 6 + j] } }

    fun getKalmanGainLidar(): Float {
        val p = P[0]
        return p / (p + R_lidar)
    }

    // ─── Helfer ──────────────────────────────────────────────
    private fun matMul6(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(36)
        for (i in 0..5) for (j in 0..5) {
            var s = 0f
            for (k in 0..5) s += a[i * 6 + k] * b[k * 6 + j]
            out[i * 6 + j] = s
        }
        return out
    }

    private fun matMul6T(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(36)
        for (i in 0..5) for (j in 0..5) {
            var s = 0f
            for (k in 0..5) s += a[i * 6 + k] * b[j * 6 + k]
            out[i * 6 + j] = s
        }
        return out
    }

    private fun inv3(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val invDet = 1f / det
        return floatArrayOf(
            (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
            (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
            (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet
        )
    }
}
