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

    @Test
    fun `median-filter unterdrueckt rssi-spikes`() {
        val median = RssiMedianFilter(window = 5)
        var value = 0.0
        for (rssi in listOf(-60, -61, -59, -62, -60)) {
            value = median.smooth("AA:BB", rssi)
        }
        assertEquals(-60.0, value, 1e-9)
        // Spike wird bei Fenster 5 ignoriert
        assertEquals(-61.0, median.smooth("AA:BB", -200), 1e-9)
        median.clear("AA:BB")
        assertNull(median.value("AA:BB"))
    }

    @Test
    fun `kalman-filter konvergiert und daempft sprünge`() {
        val kalman = RssiKalmanFilter(q = 4.0, r = 16.0)
        var value = 0.0
        repeat(30) { value = kalman.smooth("AA:BB", -62) }
        assertEquals(-62.0, value, 1.0)
        // Einzelner Sprung wird gedämpft (Gain < 1)
        val prev = value
        val jumped = kalman.smooth("AA:BB", -80)
        assertTrue("Sprung nicht gedämpft: $prev → $jumped", kotlin.math.abs(jumped - prev) < 18.0)
        kalman.clear("AA:BB")
        assertNull(kalman.value("AA:BB"))
    }
}
