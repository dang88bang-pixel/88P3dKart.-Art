package com.example.agent.aura

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Integrationsschicht: bindet die Aura-Kernmodule an die 3dxAgent-Plattform an
 * (docs/AURA.md §8.1).
 *
 * Datenfluss:
 * ```
 * WireGuard-Tunnel → IqTunnelReceiver → AuraIntegrator
 *   ├─ FFT/Spektrum → RfBandClassifier → Gatekeeper (Alerts)
 *   ├─ dBm-Schätzung + Pose (EKF) → RfHeatmapBuilder (extrudierte Zellen)
 *   └─ CrossCorrelator → RtiSolver (Dämpfung je Link → Voxel-Feld)
 * ```
 *
 * Alle Ergebnisse liegen als [SharedFlow]s vor und werden von der
 * [com.example.agent.pipeline.LiveSensorPipeline] (SensorFrames) sowie vom
 * WebSocket-Client (Edge-Agent → Web-Visualizer) konsumiert.
 */
class AuraIntegrator(
    private val receiverPort: Int = IqTunnelReceiver.DEFAULT_PORT,
    private val heatmapCellSizeM: Float = 1.0f,
) {

    companion object {
        private const val TAG = "AuraIntegrator"

        /** FFT-Länge für die Spektrumsanalyse je Chunk (2^12). */
        private const val FFT_SIZE = 4096

        /** Heatmap-Rebuild alle N Chunks (Begrenzung der UI-Last). */
        private const val HEATMAP_REBUILD_EVERY = 16

        /** Ringpuffer-Größe für Heatmap-Samples. */
        private const val SAMPLE_RING_SIZE = 4096
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiver = IqTunnelReceiver(receiverPort)
    private val gatekeeper = Gatekeeper()
    private val rtiSolver = RtiSolver(
        boundsMin = floatArrayOf(-15f, -15f, 0f),
        boundsMax = floatArrayOf(15f, 15f, 3f),
        voxelSize = 0.5f,
    )

    private val tagTracker = TagVelocityTracker()
    private val sampleRing = ArrayDeque<RfHeatmapBuilder.RfSample>()
    private var chunksSinceRebuild = 0

    /** Liefert die aktuelle Geräteposition [x, y, z] (z. B. aus dem EKF). */
    private var poseProvider: (() -> FloatArray?)? = null

    private val _chunks = MutableSharedFlow<IqTunnelReceiver.IqChunk>(extraBufferCapacity = 128)
    val chunks: SharedFlow<IqTunnelReceiver.IqChunk> = _chunks.asSharedFlow()

    private val _heatmapCells = MutableSharedFlow<List<RfHeatmapBuilder.ExtrudedCell>>(extraBufferCapacity = 16)
    val heatmapCells: SharedFlow<List<RfHeatmapBuilder.ExtrudedCell>> = _heatmapCells.asSharedFlow()

    private val _rtiVoxels = MutableSharedFlow<List<RtiSolver.Voxel>>(extraBufferCapacity = 16)
    val rtiVoxels: SharedFlow<List<RtiSolver.Voxel>> = _rtiVoxels.asSharedFlow()

    private val _alerts = MutableSharedFlow<Gatekeeper.GatekeeperAlert>(extraBufferCapacity = 100)
    val alerts: SharedFlow<Gatekeeper.GatekeeperAlert> = _alerts.asSharedFlow()

    private val _tagVelocities =
        MutableSharedFlow<List<TagVelocityTracker.TrackedTag>>(extraBufferCapacity = 32)
    val tagVelocities: SharedFlow<List<TagVelocityTracker.TrackedTag>> = _tagVelocities.asSharedFlow()

    /** Setzt die Positionsquelle (EKF-Zustand). Ohne Pose: keine Heatmap-Zellen. */
    fun setPoseProvider(provider: () -> FloatArray?) {
        poseProvider = provider
    }

    /** Startet den UDP-Tunnel-Empfänger und die Verarbeitungskette. */
    fun start() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        receiver.start()
        scope.launch {
            for (chunk in receiver.chunks) processChunk(chunk)
        }
        Log.i(TAG, "Aura-Integrator aktiv (Port $receiverPort)")
    }

    fun stop() {
        receiver.stop()
        scope.cancel()
    }

    /** Manuelles Einspeisen eines Chunks (z. B. lokaler USB-SDR statt Tunnel). */
    fun onIqChunk(chunk: IqTunnelReceiver.IqChunk) = processChunk(chunk)

    /** Speist eine RTI-Messlinie in den Solver ein und löst das Voxel-Feld neu. */
    fun submitRtiLink(
        tx: FloatArray,
        rx: FloatArray,
        attenuationDb: Float,
    ): List<RtiSolver.Voxel> {
        rtiSolver.addLink(tx, rx, attenuationDb)
        val voxels = rtiSolver.solve()
        _rtiVoxels.tryEmit(voxels)
        return voxels
    }

    /** Tag-Position aktualisieren → Geschwindigkeitsvektor. */
    fun onTagPosition(mac: String, x: Float, y: Float, z: Float) {
        val updated = tagTracker.updatePosition(mac, x, y, z) ?: return
        _tagVelocities.tryEmit(tagTracker.snapshots().map { it.copy() })
        if (updated.speedMs > 0f) {
            Log.d(TAG, "Tag $mac: ${updated.speedMs} m/s (${updated.velocity[0]}, ${updated.velocity[1]}, ${updated.velocity[2]})")
        }
    }

    private fun processChunk(chunk: IqTunnelReceiver.IqChunk) {
        _chunks.tryEmit(chunk)

        // 1) Spektrum → Gatekeeper
        val spectrumInput = FloatArray(minOf(FFT_SIZE, chunk.iq.size / 2))
        var idx = 0
        var i = 0
        while (i + 1 < chunk.iq.size && idx < FFT_SIZE) {
            spectrumInput[idx] = chunk.iq[i].toFloat() // I-Anteil als reelles Signal
            idx++
            i += 2
        }
        if (spectrumInput.isNotEmpty()) {
            val spectrum = Fft.forward(spectrumInput)
            val alerts = gatekeeper.onSpectrum(
                powerSpectrum = spectrum.power(),
                sampleRateHz = 2.4e6f, // RTL-SDR-Nennabtastrate; ggf. per Config anpassen
                centerFrequencyHz = 433.92e6, // Nominalfrequenz des 433-MHz-Bands
            )
            alerts.forEach { _alerts.tryEmit(it) }
        }

        // 2) Leistung + Pose → Heatmap-Sample (Ringpuffer)
        val pose = poseProvider?.invoke()
        if (pose != null && pose.size >= 3) {
            val dbm = RfHeatmapBuilder.estimateDbm(chunk.iq)
            sampleRing.addLast(
                RfHeatmapBuilder.RfSample(
                    timestampMs = chunk.timestampMicros / 1000,
                    x = pose[0],
                    y = pose[1],
                    z = pose[2],
                    dbm = dbm,
                    frequencyHz = 433.92e6,
                )
            )
            while (sampleRing.size > SAMPLE_RING_SIZE) sampleRing.removeFirst()

            chunksSinceRebuild++
            if (chunksSinceRebuild >= HEATMAP_REBUILD_EVERY) {
                chunksSinceRebuild = 0
                _heatmapCells.tryEmit(
                    RfHeatmapBuilder.build(sampleRing.toList(), heatmapCellSizeM)
                )
            }
        }
    }
}
