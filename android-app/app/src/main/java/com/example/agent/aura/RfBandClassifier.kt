package com.example.agent.aura

/**
 * RF-Bandklassifikation für den Gatekeeper (docs/AURA.md §5).
 *
 * Der Nooelec RTL-SDR v5 scannt kontinuierlich die Bänder um 433 MHz und
 * 868 MHz — dort senden viele Smart-Home-Sensoren und Alarmanlagen
 * unverschlüsselt. Aura klassifiziert die belegten Subbänder und warnt vor
 * unautorisierten Sendern (Triangulation über die Scanner-Knoten).
 */
object RfBandClassifier {

    /** ISM-Bänder (Deutschland/EU), die der Gatekeeper überwacht. */
    const val BAND_433_MIN_HZ = 433.05e6
    const val BAND_433_MAX_HZ = 434.79e6
    const val BAND_868_MIN_HZ = 863.0e6
    const val BAND_868_MAX_HZ = 870.0e6

    enum class Band {
        ISM_433,
        ISM_868,
        UNKNOWN,
    }

    /** Vermutete Modulationsart anhand der belegten Bandbreite. */
    enum class Modulation {
        /** Schmalband — typisch OOK/ASK (Fernbedienungen, Türsensoren). */
        NARROWBAND,
        /** Mittlere Bandbreite — typisch FSK/GFSK (Funkmodule, Alarme). */
        FSK,
        /** Breitband — LoRa/WMBus/DAB — nicht trivial klassifizierbar. */
        WIDEBAND,
    }

    /** Eine belegte Spektrumsregion. */
    data class OccupiedBand(
        val centerHz: Double,
        val bandwidthHz: Double,
        val powerRatio: Float,     // mittlere Leistung der Region relativ zum Gesamtspektrum
        val band: Band,
        val modulation: Modulation,
    )

    /**
     * Klassifiziert ein Leistungsspektrum.
     * @param powerSpectrum |X[k]|² (beliebige Länge)
     * @param sampleRateHz Abtastrate des SDR
     * @param centerFrequencyHz eingestellte Mittenfrequenz des SDR
     * @param thresholdSigma Schwelle: mean + thresholdSigma · std über den Bins
     */
    fun classify(
        powerSpectrum: FloatArray,
        sampleRateHz: Float,
        centerFrequencyHz: Double,
        thresholdSigma: Float = 4f,
    ): List<OccupiedBand> {
        if (powerSpectrum.isEmpty() || sampleRateHz <= 0f) return emptyList()

        val n = powerSpectrum.size
        val mean = powerSpectrum.average().toFloat()
        var variance = 0.0
        for (v in powerSpectrum) {
            val d = v - mean
            variance += d * d
        }
        val std = kotlin.math.sqrt(variance / n).toFloat()
        val threshold = mean + thresholdSigma * std

        val occupied = mutableListOf<OccupiedBand>()
        var i = 0
        while (i < n) {
            if (powerSpectrum[i] < threshold) {
                i++
                continue
            }
            val start = i
            var powerSum = 0.0
            while (i < n && powerSpectrum[i] >= threshold) {
                powerSum += powerSpectrum[i]
                i++
            }
            val end = i
            val binHz = sampleRateHz / n
            val centerHz = centerFrequencyHz +
                ((start + end) / 2.0 - n / 2.0) * binHz
            val bandwidthHz = (end - start) * binHz
            val band = when {
                centerHz >= BAND_433_MIN_HZ && centerHz <= BAND_433_MAX_HZ -> Band.ISM_433
                centerHz >= BAND_868_MIN_HZ && centerHz <= BAND_868_MAX_HZ -> Band.ISM_868
                else -> Band.UNKNOWN
            }
            val modulation = when {
                bandwidthHz < 50e3 -> Modulation.NARROWBAND
                bandwidthHz < 250e3 -> Modulation.FSK
                else -> Modulation.WIDEBAND
            }
            occupied.add(
                OccupiedBand(
                    centerHz = centerHz,
                    bandwidthHz = bandwidthHz,
                    powerRatio = (powerSum / (end - start)).toFloat(),
                    band = band,
                    modulation = modulation,
                )
            )
        }
        return occupied
    }
}
