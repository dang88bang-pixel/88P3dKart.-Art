package com.example.agent.aura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Synthetisches RTI-Szenario (identisch zum Python-Test `edge-agent/tests/test_rti.py`):
 * Dämpfungs-„Blob" (Person/Objekt) in einem 10 m × 10 m × 1 m Raum, 12 Messlinien
 * (6 Parallelen + 6 Diagonale), Ellipsenbreite 0,5 m. Die Messwerte werden mit
 * demselben Ellipsen-Gewichtungsmodell synthetisiert, mit dem der Solver
 * rekonstruiert (selbstkonsistentes System y = A·φ).
 *
 * Erwartung: Tikhonov lokalisiert den Blob präzise; Backprojection trägt das
 * Signal (positive Korrelation mit dem wahren Feld, Blob-Region über den
 * Ecken-Regionen) — ihr globales Argmax liegt bekanntermaßen in dünn
 * abgedeckten Randregionen (RTI-Literatur).
 */
class RtiSolverTest {

    private val boundsMin = floatArrayOf(-5f, -5f, 0f)
    private val boundsMax = floatArrayOf(5f, 5f, 1f)
    private val voxelSize = 0.5f

    // Blob-Position und -Form
    private val blob = floatArrayOf(0.75f, 0.75f, 0.5f)
    private val sigma = 1.2f
    private val amplitudeDb = 10f

    private fun trueField(v: FloatArray): Float {
        val dx = v[0] - blob[0]
        val dy = v[1] - blob[1]
        val dz = v[2] - blob[2]
        return (amplitudeDb * exp(-(dx * dx + dy * dy + dz * dz) / (2f * sigma * sigma))).toFloat()
    }

    /** 12 Links: 6 Parallelen (x/y = −2, 0, 2) + 6 Diagonale (z = 0,5 m). */
    private fun linkGeometry(): List<Pair<FloatArray, FloatArray>> {
        val z = 0.5f
        val links = mutableListOf<Pair<FloatArray, FloatArray>>()
        for (y in floatArrayOf(-2f, 0f, 2f)) {
            links.add(floatArrayOf(-5f, y, z) to floatArrayOf(5f, y, z))
        }
        for (x in floatArrayOf(-2f, 0f, 2f)) {
            links.add(floatArrayOf(x, -5f, z) to floatArrayOf(x, 5f, z))
        }
        links.addAll(
            listOf(
                floatArrayOf(-4f, -4f, z) to floatArrayOf(4f, 4f, z),
                floatArrayOf(4f, -4f, z) to floatArrayOf(-4f, 4f, z),
                floatArrayOf(-5f, -2.5f, z) to floatArrayOf(5f, 2.5f, z),
                floatArrayOf(5f, -2.5f, z) to floatArrayOf(-5f, 2.5f, z),
                floatArrayOf(-5f, 2.5f, z) to floatArrayOf(5f, -2.5f, z),
                floatArrayOf(-2.5f, -5f, z) to floatArrayOf(2.5f, 5f, z),
            )
        )
        return links
    }

    private fun createSolver(regularization: Float = 0.05f): RtiSolver {
        val solver = RtiSolver(
            boundsMin = boundsMin,
            boundsMax = boundsMax,
            voxelSize = voxelSize,
            ellipseWidth = 0.5f,
            regularization = regularization,
        )
        // 1) Links zunächst mit Platzhalter einfügen, um die Gewichte zu bauen
        for ((tx, rx) in linkGeometry()) {
            solver.addLink(tx, rx, 0f)
        }
        val weights = solver.buildWeights()
        solver.clearLinks()
        // 2) Dämpfung je Link synthetisieren: y = Σ_v w_i(v) · φ_true(v)
        var idx = 0
        for ((tx, rx) in linkGeometry()) {
            var y = 0f
            for ((v, w) in weights[idx]) {
                y += w * trueField(solver.voxelCenter(v))
            }
            solver.addLink(tx, rx, y)
            idx++
        }
        return solver
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Mittlere Feldstärke innerhalb eines Radius um [center]. */
    private fun regionMean(
        field: FloatArray,
        solver: RtiSolver,
        center: FloatArray,
        radius: Float = 2f,
    ): Float {
        var sum = 0f
        var count = 0
        for (i in field.indices) {
            if (distance(solver.voxelCenter(i), center) <= radius) {
                sum += field[i]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    @Test
    fun `tikhonov-loesung lokalisiert den Daempfungs-Blob`() {
        val solver = createSolver()
        val field = solver.solve()
        assertEquals(20 * 20 * 2, field.size)

        val argmax = field.maxByOrNull { it.attenuation }!!
        val err = distance(solver.voxelCenter(argmax.index), blob)
        assertTrue(
            "Rekonstruktion zu ungenau: Fehler ${err}m (argmax bei " +
                "${argmax.x}, ${argmax.y}, ${argmax.z})",
            err <= 1.5f * voxelSize,
        )
    }

    @Test
    fun `backprojection traegt das lokalisierungssignal`() {
        val solver = createSolver()
        val field = solver.solveBackprojection()
        val bp = FloatArray(field.size) { field[it].attenuation }
        val trueFieldValues = FloatArray(field.size) { trueField(solver.voxelCenter(it)) }

        // Positive Korrelation (Pearson) mit dem wahren Feld
        val r = pearson(bp, trueFieldValues)
        assertTrue("Korrelation zu gering: r=$r", r > 0.2f)

        // Blob-Region (r = 2 m) muss über den Ecken-Regionen liegen
        val nearBlob = regionMean(bp, solver, blob)
        val nearCorner1 = regionMean(bp, solver, floatArrayOf(4.5f, 4.5f, 0.5f))
        val nearCorner2 = regionMean(bp, solver, floatArrayOf(-4.5f, 4.5f, 0.5f))
        assertTrue("Blob-Region ($nearBlob) <= Ecke 1 ($nearCorner1)", nearBlob > nearCorner1)
        assertTrue("Blob-Region ($nearBlob) <= Ecke 2 ($nearCorner2)", nearBlob > nearCorner2)
    }

    @Test
    fun `locatePeaks liefert begrenzte Anzahl getrennter Maxima`() {
        val solver = createSolver()
        val field = solver.solve()
        val peaks = solver.locatePeaks(field, topK = 4, minSeparationVoxels = 2)
        assertTrue("Keine Peaks gefunden", peaks.isNotEmpty())
        assertTrue("Zu viele Peaks: ${peaks.size}", peaks.size <= 4)
        val err = distance(solver.voxelCenter(peaks.first().index), blob)
        assertTrue("Stärkster Peak zu weit weg: ${err}m", err <= 2.5f * voxelSize)
    }

    @Test
    fun `leerer Solver liefert leeres Feld`() {
        val solver = RtiSolver(boundsMin, boundsMax, voxelSize)
        assertEquals(0, solver.solve().size)
        assertEquals(0, solver.solveBackprojection().size)
    }

    /** Pearson-Korrelationskoeffizient. */
    private fun pearson(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        val n = a.size
        if (n == 0) return 0f
        val meanA = a.average().toFloat()
        val meanB = b.average().toFloat()
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        return if (varA > 0.0 && varB > 0.0) {
            (cov / kotlin.math.sqrt(varA * varB)).toFloat()
        } else 0f
    }
}
