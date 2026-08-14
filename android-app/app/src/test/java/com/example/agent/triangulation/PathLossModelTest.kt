package com.example.agent.triangulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PathLossModelTest {

    @Test
    fun `modell liefert korrekte distanzen im freiraum`() {
        val model = PathLossModel(referenceRssiDbm = -40.0, pathLossExponent = 2.0)
        assertEquals(1.0, model.distanceFromRssi(-40.0), 1e-9)
        assertEquals(10.0, model.distanceFromRssi(-60.0), 1e-9)
        assertEquals(100.0, model.distanceFromRssi(-80.0), 1e-9)
    }

    @Test
    fun `ungueltige rssi-werte ergeben NaN`() {
        val model = PathLossModel()
        assertTrue(model.distanceFromRssi(Double.NaN).isNaN())
        assertTrue(model.distanceFromRssi(0.0).isNaN()) // > −1 dBm = unplausibel
        assertTrue(model.distanceFromRssi(-30.0).isFinite())
    }

    @Test
    fun `kalibrierung rekonstruiert modellparameter aus verrauschten messungen`() {
        val reference = -45.0
        val exponent = 2.5
        val rng = Random(7L)
        val samples = listOf(1.0, 2.0, 4.0, 8.0, 16.0).map { d ->
            val rssi = reference - 10.0 * exponent * kotlin.math.log10(d) + rng.nextGaussian() * 1.0
            d to rssi
        }
        val calibration = PathLossModel.calibrate(samples)
        assertTrue(calibration != null)
        assertEquals(exponent, calibration!!.pathLossExponent, 0.3)
        assertEquals(reference, calibration.referenceRssiDbm, 3.0)
        assertTrue("R² zu niedrig: ${calibration.rSquared}", calibration.rSquared > 0.95)
    }

    @Test
    fun `kalibrierung braucht mindestens drei messpaare`() {
        assertNull(PathLossModel.calibrate(emptyList()))
        assertNull(PathLossModel.calibrate(listOf(1.0 to -50.0)))
        assertNull(PathLossModel.calibrate(listOf(1.0 to -50.0, 2.0 to -56.0)))
    }

    @Test
    fun `rssi-smoother konvergiert gegen den stationaeren wert`() {
        val smoother = RssiSmoother(alpha = 0.5f)
        val target = -62
        var value = 0.0
        repeat(20) { value = smoother.smooth("AA:BB", target) }
        assertEquals(target.toDouble(), value, 1.0)
        assertEquals(target.toDouble(), smoother.value("AA:BB")!!, 1.0)
        smoother.clear("AA:BB")
        assertNull(smoother.value("AA:BB"))
    }
}
