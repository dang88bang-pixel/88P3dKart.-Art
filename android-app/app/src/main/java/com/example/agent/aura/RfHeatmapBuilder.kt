package com.example.agent.aura

import kotlin.math.floor

/**
 * Volumetrische RF-Heatmap (docs/AURA.md §2): extrudierte Polygone, deren Höhe
 * direkt proportional zur gemessenen Signalstärke ist. Die Extrusion macht
 * Hindernisse (Wände, Reflexionsflächen) im städtischen Raum sichtbar.
 *
 * Die erzeugten [ExtrudedCell]-Datenmodelle sind renderer-agnostisch: sie
 * speisen den Three.js-Layer des Web-Visualizers und lassen sich 1:1 in
 * extrudierte Polygone des Google Maps 3D SDK überführen (Höhe = [heightM],
 * Fußabdruck = [cellSizeM] × [cellSizeM]).
 */
object RfHeatmapBuilder {

    /** Eine Messung: Leistung + Position + Frequenz. */
    data class RfSample(
        val timestampMs: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val dbm: Float,
        val frequencyHz: Double,
        val sampleCount: Int = 1,
    )

    /** Eine extrudierte Heatmap-Zelle (Fußabdruck quadratisch, zentriert). */
    data class ExtrudedCell(
        val centerX: Float,
        val centerY: Float,
        /** Boden-Höhe der Zelle. */
        val baseZ: Float,
        /** Extrusionshöhe ∝ Signalstärke. */
        val heightM: Float,
        /** Mittlere Leistung in dBm. */
        val dbm: Float,
        /** Kantenlänge des Fußabdrucks in Metern. */
        val cellSizeM: Float,
        /** Anzahl aggregierter Samples. */
        val sampleCount: Int,
    )

    /** Standard-Maximalhöhe der Extrusion in Metern (normierte Skala). */
    const val DEFAULT_MAX_HEIGHT_M = 12f

    /**
     * Aggregiert Samples zu einem Bodenraster (Mittelwert je Zelle) und
     * normalisiert die Extrusionshöhe auf [minDbm]..[maxDbm].
     */
    fun build(
        samples: List<RfSample>,
        cellSizeM: Float,
        minDbm: Float = -90f,
        maxDbm: Float = -30f,
        maxHeightM: Float = DEFAULT_MAX_HEIGHT_M,
    ): List<ExtrudedCell> {
        if (samples.isEmpty()) return emptyList()
        require(cellSizeM > 0f) { "cellSizeM muss > 0 sein" }

        data class CellKey(val cx: Int, val cy: Int)

        val accumulators = LinkedHashMap<CellKey, MutableList<RfSample>>()
        for (s in samples) {
            val key = CellKey(
                floor(s.x / cellSizeM).toInt(),
                floor(s.y / cellSizeM).toInt(),
            )
            accumulators.getOrPut(key) { mutableListOf() }.add(s)
        }

        return accumulators.map { (key, cellSamples) ->
            val dbm = cellSamples.map { it.dbm }.average().toFloat()
            val clamped = dbm.coerceIn(minDbm, maxDbm)
            val normalized = (clamped - minDbm) / (maxDbm - minDbm)
            ExtrudedCell(
                centerX = (key.cx + 0.5f) * cellSizeM,
                centerY = (key.cy + 0.5f) * cellSizeM,
                baseZ = cellSamples.minOf { it.z },
                heightM = normalized * maxHeightM,
                dbm = dbm,
                cellSizeM = cellSizeM,
                sampleCount = cellSamples.sumOf { it.sampleCount },
            )
        }
    }

    /**
     * Schätzt die Empfangsleistung in dBm aus einem 8-Bit-IQ-Block
     * (RTL-SDR-Skala): dBm = 10·log10(mean|IQ|²) + Kalibrierungs-Offset.
     * @param calibrationOffsetDbm Geräteabhängige Kalibrierung (Empfangsverstärkung,
     *        Antenne); Standardwert orientiert am RTL-SDR-Tuner-Gain 0 dB.
     */
    fun estimateDbm(iq: ByteArray, calibrationOffsetDbm: Float = -49.6f): Float {
        if (iq.isEmpty()) return -120f
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < iq.size) {
            val vI = iq[i].toFloat()
            val vQ = iq[i + 1].toFloat()
            sum += vI * vI + vQ * vQ
            n++
            i += 2
        }
        if (n == 0) return -120f
        val meanPower = sum / n
        if (meanPower <= 0f) return -120f
        return (10f * kotlin.math.log10(meanPower.toFloat())) + calibrationOffsetDbm
    }
}
