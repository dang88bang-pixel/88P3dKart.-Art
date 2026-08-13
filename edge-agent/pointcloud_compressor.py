"""Effizientes Punktwolken-Streaming als Binary-Blob (uint32 N + N*3 float32)."""
import struct

import numpy as np


class PointCloudCompressor:
    """Komprimiert (N, 3)-Punktwolken für die WebSocket-Übertragung."""

    @staticmethod
    def compress(points: np.ndarray) -> bytes:
        """(N,3) ndarray → bytes: [N (uint32, LE), x1,y1,z1, x2,y2,z2, ...] float32."""
        points = np.asarray(points, dtype=np.float32)
        if points.ndim != 2 or points.shape[1] != 3:
            points = points.reshape(-1, 3)
        if points.size == 0:
            return struct.pack("<I", 0)
        n = points.shape[0]
        flat = points.flatten()
        return struct.pack("<I", n) + flat.tobytes()

    @staticmethod
    def decompress(data: bytes) -> np.ndarray:
        """bytes → (N, 3) float32 ndarray."""
        if not data or len(data) < 4:
            return np.empty((0, 3), dtype=np.float32)
        n = struct.unpack("<I", data[:4])[0]
        if n == 0:
            return np.empty((0, 3), dtype=np.float32)
        flat = np.frombuffer(data[4:4 + n * 3 * 4], dtype=np.float32)
        return flat.reshape((n, 3))
