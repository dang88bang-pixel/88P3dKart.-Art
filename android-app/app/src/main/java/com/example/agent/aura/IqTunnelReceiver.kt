package com.example.agent.aura

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Hochperformanter IQ-Datagramm-Empfänger (UDP) für den Aura-Tunnel-Link.
 *
 * Architektur (docs/AURA.md §3.4):
 * - Empfangs-Loop in `Dispatchers.IO` (blockierendes `DatagramSocket.receive`),
 * - Puffer-[Channel] mit [BufferOverflow.DROP_OLDEST]: bei Lastspitzen werden
 *   stets die **aktuellsten** Funkdaten verarbeitet — die Visualisierung bleibt
 *   flüssig, statt einzufrieren,
 * - Paketverlust-/Jitter-Analyse über [IqDatagram.GapTracker],
 * - dekodierte Chunks als [SharedFlow] für die Signalverarbeitung.
 */
class IqTunnelReceiver(
    private val port: Int = DEFAULT_PORT,
    private val channelCapacity: Int = 64,
) {

    companion object {
        private const val TAG = "IqTunnelReceiver"
        const val DEFAULT_PORT = 50000

        /** Empfangspuffer großzügig über der MTU (1420) — entspricht 1500 im Blueprint. */
        const val RECEIVE_BUFFER_SIZE = 1500
    }

    /** Dekodierter IQ-Chunk inkl. Verlust-Metadaten. */
    data class IqChunk(
        val sequence: Int,
        val timestampMicros: Long,
        val iq: ByteArray,
        /** Seit dem letzten Chunk verlorene Pakete (0 = lückenlos). */
        val lostPackets: Int,
        /** Seit Verbindungsaufbau aufgelaufene Verlustrate 0..1. */
        val lossRate: Float,
    )

    /** Empfänger-Statistik. */
    data class Stats(
        val receivedPackets: Long,
        val lostPackets: Long,
        val reorderedPackets: Long,
        val lossRate: Float,
    )

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gapTracker = IqDatagram.GapTracker()

    /** Roh-Pakete mit Verlustschutz: volle Warteschlange verwirft das älteste Paket. */
    private val rawChannel = Channel<ByteArray>(
        capacity = channelCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _chunks = MutableSharedFlow<IqChunk>(extraBufferCapacity = 128)
    val chunks: SharedFlow<IqChunk> = _chunks.asSharedFlow()

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var isRunning = false

    /** Öffnet den UDP-Socket und startet Empfangs- sowie Parse-Coroutine. */
    @Synchronized
    fun start() {
        if (isRunning) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.receiveBufferSize = 1 shl 20 // 1 MiB OS-Puffer gegen Bursts
            socket = s
            isRunning = true
            scope.launch { receiveLoop(s) }
            scope.launch { parseLoop() }
            Log.i(TAG, "IQ-Empfänger aktiv auf UDP:$port (Channel=$channelCapacity, DROP_OLDEST)")
        } catch (e: SocketException) {
            Log.e(TAG, "Socket-Bind fehlgeschlagen: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
        Log.i(TAG, "IQ-Empfänger gestoppt")
    }

    fun stats(): Stats = Stats(
        receivedPackets = gapTracker.receivedPackets,
        lostPackets = gapTracker.lostPackets,
        reorderedPackets = gapTracker.reorderedPackets,
        lossRate = gapTracker.lossRate,
    )

    /** Empfangs-Loop — blockierendes receive() im IO-Dispatcher. */
    private suspend fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (isActive && isRunning) {
            try {
                s.receive(packet)
                val data = packet.data.copyOf(packet.length)
                // DROP_OLDEST: trySend blockiert nie; bei vollem Kanal wird
                // das älteste Paket verworfen (aktuellste Daten gewinnen).
                if (rawChannel.trySend(data) is ChannelResult.Failure) {
                    Log.w(TAG, "Kanal voll — ältestes Paket verworfen (DROP_OLDEST)")
                }
            } catch (e: SocketException) {
                if (isRunning) Log.w(TAG, "Receive-Fehler: ${e.message}")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Receive-Ausnahme: ${e.message}")
            }
        }
    }

    /** Parse-Loop — Header-Extraktion, Lückenstatistik, Emission an Abnehmer. */
    private suspend fun parseLoop() {
        for (raw in rawChannel) {
            val datagram = IqDatagram.decode(raw) ?: continue
            val lost = gapTracker.track(datagram.sequence)
            if (lost > 0) {
                Log.w(TAG, "Paketlücke: $lost verloren (Rate=${gapTracker.lossRate})")
            }
            _chunks.tryEmit(
                IqChunk(
                    sequence = datagram.sequence,
                    timestampMicros = datagram.timestampMicros,
                    iq = datagram.iq,
                    lostPackets = lost,
                    lossRate = gapTracker.lossRate,
                )
            )
        }
    }
}
