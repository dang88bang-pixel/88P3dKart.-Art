"""IQ-SDR-UDP-Tunnel — Python-Port der Kotlin-Module
(`com.example.agent.aura.IqDatagram` + `IqTunnelReceiver`,
docs/AURA.md §3.3/§3.4).

Enthält:
- `IqDatagram`-Konstanten und `encode_datagram`/`decode_datagram` —
  identisches Drahtformat zum Kotlin-Gegenstück (12-Byte-Header
  Big-Endian, 704 IQ-Paare/Paket bei MTU 1420),
- `GapTracker` — UInt32-überlaufsichere Lücken-/Verluststatistik,
- `IqTunnelReceiver` — asynchroner UDP-Empfänger mit Ringpuffer
  (`DROP_OLDEST`): bei Lastspitzen gewinnen stets die **aktuellsten**
  Funkdaten, die Signalverarbeitung bleibt flüssig (entspricht
  `Channel(capacity, BufferOverflow.DROP_OLDEST)` in Kotlin).

Datenmodell und Numerik sind identisch zur Kotlin-Implementierung,
sodass CT45P-App (Sender/Scanner) und Edge-Agent (Empfänger/
Verarbeiter) austauschbar miteinander kommunizieren.
"""

from __future__ import annotations

import asyncio
import logging
import socket
import struct
from dataclasses import dataclass
from typing import Callable, Optional, Tuple

log = logging.getLogger("iq_tunnel")

# ─── Drahtformat (identisch zu IqDatagram.kt) ────────────────────────────

HEADER_SIZE = 12
TUNNEL_MTU = 1420

# Maximale Payload-Größe: 1420 − 12 = 1408 Byte.
MAX_PAYLOAD_SIZE = TUNNEL_MTU - HEADER_SIZE

# IQ-Paare pro Paket bei 8-Bit-Samples: 1408 / 2 = 704.
IQ_PAIRS_PER_DATAGRAM = MAX_PAYLOAD_SIZE // 2

MAX_DATAGRAM_SIZE = TUNNEL_MTU

DEFAULT_PORT = 50000

# Empfangspuffer großzügig über der MTU (1420) — entspricht 1500 im Blueprint.
RECEIVE_BUFFER_SIZE = 1500

# OS-Empfangspuffer gegen Bursts (1 MiB, wie `receiveBufferSize` in Kotlin).
SOCKET_RECEIVE_BUFFER = 1 << 20


def bits_per_second(sample_rate_msps: float, bits_per_sample: int = 8) -> float:
    """Bitrate des SDR-Datenstroms (I+Q).

    Referenz: 2,4 MS/s × 8 Bit × 2 (I+Q) ≈ 38,4 Mbit/s.
    """
    return sample_rate_msps * 1e6 * bits_per_sample * 2.0


@dataclass(frozen=True)
class Datagram:
    """Ein dekodiertes Aura-Datagramm."""

    sequence: int
    timestamp_micros: int
    # Interleaved 8-Bit-IQ: [I0, Q0, I1, Q1, ...]. Länge immer gerade.
    iq: bytes

    @property
    def pair_count(self) -> int:
        return len(self.iq) // 2


def encode_datagram(sequence: int, timestamp_micros: int, iq: bytes) -> bytes:
    """Kodiert ein Datagramm inkl. 12-Byte-Header (Big-Endian).

    Raises:
        ValueError: Payload größer als `MAX_PAYLOAD_SIZE` oder ungeradzahlig.
    """
    if len(iq) > MAX_PAYLOAD_SIZE:
        raise ValueError(
            f"Payload zu groß: {len(iq)} > {MAX_PAYLOAD_SIZE} Byte (MTU {TUNNEL_MTU})"
        )
    if len(iq) % 2 != 0:
        raise ValueError("IQ-Payload muss geradzahlig sein (I+Q-Paare)")
    return (
        struct.pack(">I", sequence & 0xFFFFFFFF)
        + struct.pack(">Q", timestamp_micros & 0xFFFFFFFFFFFFFFFF)
        + bytes(iq)
    )


def decode_datagram(data: bytes, length: Optional[int] = None) -> Optional[Datagram]:
    """Dekodiert ein empfangenes UDP-Paket.

    Returns:
        `Datagram` oder None bei unvollständigem Header.
    """
    if length is None:
        length = len(data)
    if length < HEADER_SIZE:
        return None
    sequence, timestamp = struct.unpack(">IQ", data[:HEADER_SIZE])
    return Datagram(sequence=sequence, timestamp_micros=timestamp, iq=bytes(data[HEADER_SIZE:length]))


class GapTracker:
    """Lückenstatistik für die Paketverlust-Erkennung auf der Empfängerseite.

    Thread-/Task-sicher: der Zustand wird nur aus dem Parse-Loop verändert,
    `track()` ist idempotent gegenüber Duplikaten und wickelt
    Sequenznummer-Überläufe (UInt32) korrekt ab — wie in Kotlin rechnet die
    Differenz mod 2³², ohne Vorzeichenerweiterung.
    """

    def __init__(self) -> None:
        self._initialized = False
        self._last_sequence = 0
        self._lost_packets = 0
        self._reordered_packets = 0
        self.received_packets = 0

    @property
    def lost_packets(self) -> int:
        """Als Lücke erkannte (verlorene) Pakete."""
        return self._lost_packets

    @property
    def reordered_packets(self) -> int:
        """Pakete, die außerhalb der Reihenfolge ankamen (Jitter/Routing)."""
        return self._reordered_packets

    @property
    def loss_rate(self) -> float:
        """Aufgelaufene Verlustrate 0..1."""
        total = self.received_packets + self._lost_packets
        return 0.0 if total == 0 else self._lost_packets / total

    def track(self, sequence: int) -> int:
        """Wertet eine Sequenznummer aus; liefert die Lückengröße (0 = lückenlos)."""
        self.received_packets += 1
        if not self._initialized:
            self._initialized = True
            self._last_sequence = sequence
            return 0
        # UInt32-Differenz — wickelt Überläufe korrekt ab (mod 2³²).
        diff = (sequence - self._last_sequence) & 0xFFFFFFFF
        if diff == 0:
            return 0  # Duplikat
        if diff > 0x80000000:
            # Paket kam in falscher Reihenfolge an (negative Differenz)
            self._reordered_packets += 1
            return 0
        gap = diff - 1
        if gap > 0:
            self._lost_packets += gap
        self._last_sequence = sequence
        return gap

    def reset(self) -> None:
        self._initialized = False
        self._last_sequence = 0
        self._lost_packets = 0
        self._reordered_packets = 0
        self.received_packets = 0


@dataclass
class IqChunk:
    """Dekodierter IQ-Chunk inkl. Verlust-Metadaten (→ Signalverarbeitung)."""

    sequence: int
    timestamp_micros: int
    iq: bytes
    # Seit dem letzten Chunk verlorene Pakete (0 = lückenlos).
    lost_packets: int
    # Seit Verbindungsaufbau aufgelaufene Verlustrate 0..1.
    loss_rate: float

    @property
    def pair_count(self) -> int:
        return len(self.iq) // 2


@dataclass
class Stats:
    """Empfänger-Statistik."""

    received_packets: int
    lost_packets: int
    reordered_packets: int
    loss_rate: float


class IqTunnelReceiver:
    """Hochperformanter IQ-Datagramm-Empfänger (UDP) für den Aura-Tunnel-Link.

    Architektur (docs/AURA.md §3.4) — portiert von `IqTunnelReceiver.kt`:
    - Empfangs-Loop über `asyncio`-Datagram-Endpoint (blockierungsfrei),
    - Ringpuffer mit **DROP_OLDEST**: eine volle Warteschlange verwirft das
      älteste Paket — die aktuellsten Funkdaten gewinnen immer,
    - Paketverlust-/Jitter-Analyse über `GapTracker`,
    - dekodierte Chunks als `asyncio.Queue` bzw. Callback für die
      Signalverarbeitung (FFT, RTI).

    Beispiel:
        ```python
        rx = IqTunnelReceiver(port=50000, on_chunk=print)
        await rx.start()
        ...
        await rx.stop()
        ```
    """

    def __init__(
        self,
        port: int = DEFAULT_PORT,
        channel_capacity: int = 64,
        on_chunk: Optional[Callable[[IqChunk], None]] = None,
    ) -> None:
        self.port = port
        self.channel_capacity = channel_capacity
        self.on_chunk = on_chunk
        self._gap_tracker = GapTracker()
        # asyncio.Queue(maxsize) + DROP_OLDEST-Semantik: bei vollem Kanal wird
        # per get_nowait() das älteste Paket verworfen, dann eingefügt.
        self._raw: asyncio.Queue[bytes] = asyncio.Queue(maxsize=channel_capacity)
        self._chunks: asyncio.Queue[IqChunk] = asyncio.Queue(maxsize=channel_capacity * 2)
        self._transport: Optional[asyncio.DatagramTransport] = None
        self._parse_task: Optional[asyncio.Task] = None
        self._dropped_oldest = 0

    @property
    def running(self) -> bool:
        return self._transport is not None

    async def start(self) -> None:
        """Öffnet den UDP-Socket und startet Empfangs- sowie Parse-Task."""
        if self.running:
            return
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: _UdpProtocol(self),
            local_addr=("0.0.0.0", self.port),
        )
        self._transport = transport
        # 1 MiB OS-Puffer gegen Bursts (wie Kotlin `receiveBufferSize`).
        sock = transport.get_extra_info("socket")
        if sock is not None:
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_RECEIVE_BUFFER)
            except OSError:  # pragma: no cover — Plattform-Limit
                log.warning("SO_RCVBUF konnte nicht gesetzt werden")
        self._parse_task = asyncio.create_task(self._parse_loop())
        log.info(
            "IQ-Empfänger aktiv auf UDP:%s (Channel=%s, DROP_OLDEST)",
            self.port,
            self.channel_capacity,
        )

    async def stop(self) -> None:
        """Schließt den Socket und beendet den Parse-Task."""
        if self._parse_task is not None:
            self._parse_task.cancel()
            try:
                await self._parse_task
            except asyncio.CancelledError:
                pass
            self._parse_task = None
        if self._transport is not None:
            self._transport.close()
            self._transport = None
        log.info("IQ-Empfänger gestoppt (verworfene Alt-Pakete: %s)", self._dropped_oldest)

    def stats(self) -> Stats:
        g = self._gap_tracker
        return Stats(
            received_packets=g.received_packets,
            lost_packets=g.lost_packets,
            reordered_packets=g.reordered_packets,
            loss_rate=g.loss_rate,
        )

    # ─── Intern ─────────────────────────────────────────────────────────

    def _on_packet(self, data: bytes) -> None:
        """UDP-Datagramm empfangen — nicht blockierend in den Ringpuffer.

        DROP_OLDEST: bei vollem Kanal wird das älteste Paket verworfen
        (aktuellste Daten gewinnen), wie `Channel(trySend, DROP_OLDEST)`.
        """
        try:
            self._raw.put_nowait(data)
        except asyncio.QueueFull:
            try:
                self._raw.get_nowait()  # ältestes Paket verwerfen
                self._dropped_oldest += 1
            except asyncio.QueueEmpty:  # pragma: no cover — Race-Schutz
                pass
            try:
                self._raw.put_nowait(data)
            except asyncio.QueueFull:  # pragma: no cover — nicht erreichbar
                log.warning("Ringpuffer voll — Paket verworfen (DROP_OLDEST)")

    async def _parse_loop(self) -> None:
        """Parse-Loop — Header-Extraktion, Lückenstatistik, Emission."""
        while True:
            raw = await self._raw.get()
            datagram = decode_datagram(raw)
            if datagram is None:
                log.warning("Ungültiges Datagramm verworfen (Header < %s Byte)", HEADER_SIZE)
                continue
            lost = self._gap_tracker.track(datagram.sequence)
            if lost > 0:
                log.warning(
                    "Paketlücke: %s verloren (Rate=%.3f)", lost, self._gap_tracker.loss_rate
                )
            chunk = IqChunk(
                sequence=datagram.sequence,
                timestamp_micros=datagram.timestamp_micros,
                iq=datagram.iq,
                lost_packets=lost,
                loss_rate=self._gap_tracker.loss_rate,
            )
            # Kotlin-Äquivalent: `_chunks.tryEmit(...)` — wirft nie; bei vollem
            # Puffer wird das älteste Element verworfen (DROP_OLDEST).
            try:
                self._chunks.put_nowait(chunk)
            except asyncio.QueueFull:
                try:
                    self._chunks.get_nowait()
                except asyncio.QueueEmpty:  # pragma: no cover — Race-Schutz
                    pass
                try:
                    self._chunks.put_nowait(chunk)
                except asyncio.QueueFull:  # pragma: no cover — nicht erreichbar
                    log.warning("Chunk-Warteschlange voll — Chunk verworfen (DROP_OLDEST)")
            if self.on_chunk is not None:
                try:
                    self.on_chunk(chunk)
                except Exception:  # pragma: no cover — Abnehmer-Fehler isolieren
                    log.exception("on_chunk-Callback fehlgeschlagen")

    async def chunks(self) -> asyncio.Queue[IqChunk]:
        """Warteschlange dekodierter Chunks für asynchrone Abnehmer."""
        return self._chunks


class _UdpProtocol(asyncio.DatagramProtocol):
    """Brücke Datagram-Transport → `IqTunnelReceiver._on_packet`."""

    def __init__(self, receiver: IqTunnelReceiver) -> None:
        self._receiver = receiver

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        self._receiver._on_packet(data)

    def error_received(self, exc: Exception) -> None:  # pragma: no cover
        log.warning("UDP-Fehler: %s", exc)


# ─── Selbsttest / Standalone-Betrieb ─────────────────────────────────────

async def _run_receiver(port: int, duration_s: float) -> Stats:
    """Empfängt `duration_s` Sekunden lang IQ-Datagramme und protokolliert Stats."""

    def _log_chunk(chunk: IqChunk) -> None:
        log.info(
            "Chunk seq=%s t=%s µs iq=%s Byte pairs=%s lost=%s",
            chunk.sequence,
            chunk.timestamp_micros,
            len(chunk.iq),
            chunk.pair_count,
            chunk.lost_packets,
        )

    receiver = IqTunnelReceiver(port=port, on_chunk=_log_chunk)
    await receiver.start()
    try:
        await asyncio.sleep(duration_s)
    finally:
        await receiver.stop()
    return receiver.stats()


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(
        description="Aura-IQ-UDP-Empfänger (DROP_OLDEST-Ringpuffer, docs/AURA.md §3)"
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"UDP-Port (Default {DEFAULT_PORT})")
    parser.add_argument("--duration", type=float, default=60.0, help="Empfangsdauer in Sekunden (Default 60)")
    parser.add_argument("--verbose", action="store_true", help="Chunk-Details loggen")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    stats = asyncio.run(_run_receiver(args.port, args.duration))
    print(
        f"Stats: received={stats.received_packets} lost={stats.lost_packets} "
        f"reordered={stats.reordered_packets} loss_rate={stats.loss_rate:.4f}"
    )


if __name__ == "__main__":
    main()
