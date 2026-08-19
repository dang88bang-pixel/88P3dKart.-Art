package com.example.agent.aura

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Smart-Tag-Tracking und Live-Geschwindigkeitsmessung (docs/AURA.md §6).
 *
 * AirTags/Samsung Tags/Tile liefern über BLE und UWB nur Punktmessungen; Aura
 * berechnet aus den Positionsänderungen die Live-Geschwindigkeit und trägt sie
 * als 3D-Vektorgrafik in das Scanner-Modul ein (RC-Leistungsanalyse). Die
 * Positionen stammen aus der bestehenden BLE-RSSI-Triangulation
 * ([com.example.agent.sensors.BleTokenManager]) bzw. UWB-Ranging.
 *
 * Glättung über exponentiell gleitenden Mittelwert (EMA) — robust gegen
 * Ranging-Jitter, reaktionsschnell bei Richtungswechseln.
 */
class TagVelocityTracker(private val alpha: Float = 0.6f) {

    /** Ein verfolgter Tag mit Position, Geschwindigkeitsvektor und Tempo. */
    data class TrackedTag(
        val mac: String,
        val position: FloatArray,   // [x, y, z]
        val velocity: FloatArray,   // [vx, vy, vz] m/s
        val speedMs: Float,         // |v| in m/s
        val lastSeenMs: Long,
    ) {
        fun copy() = TrackedTag(mac, position.clone(), velocity.clone(), speedMs, lastSeenMs)
    }

    data class State(
        var lastPosition: FloatArray?,
        var lastTimeMs: Long,
        var velocity: FloatArray,
    )

    private val tags = ConcurrentHashMap<String, State>()

    /**
     * Aktualisiert die Position eines Tags und berechnet den
     * Geschwindigkeitsvektor.
     * @return aktualisierter [TrackedTag] oder null bei nicht auswertbarem Δt.
     */
    fun updatePosition(
        mac: String,
        x: Float,
        y: Float,
        z: Float,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackedTag? {
        val state = tags.getOrPut(mac) {
            State(null, nowMs, FloatArray(3))
        }
        val last = state.lastPosition
        if (last == null) {
            state.lastPosition = floatArrayOf(x, y, z)
            state.lastTimeMs = nowMs
            return null
        }

        val dtSec = (nowMs - state.lastTimeMs) / 1000f
        if (dtSec <= 0f) return null
        // Positionsreset bei langen Lücken (z. B. Token wieder in Reichweite)
        if (dtSec > 2f) {
            state.lastPosition = floatArrayOf(x, y, z)
            state.lastTimeMs = nowMs
            state.velocity = FloatArray(3)
            return null
        }

        val instantVx = (x - last[0]) / dtSec
        val instantVy = (y - last[1]) / dtSec
        val instantVz = (z - last[2]) / dtSec

        // EMA-Glättung: v = α·v_inst + (1−α)·v_alt
        state.velocity[0] = alpha * instantVx + (1f - alpha) * state.velocity[0]
        state.velocity[1] = alpha * instantVy + (1f - alpha) * state.velocity[1]
        state.velocity[2] = alpha * instantVz + (1f - alpha) * state.velocity[2]

        state.lastPosition = floatArrayOf(x, y, z)
        state.lastTimeMs = nowMs

        val v = state.velocity
        return TrackedTag(
            mac = mac,
            position = floatArrayOf(x, y, z),
            velocity = v.clone(),
            speedMs = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]),
            lastSeenMs = nowMs,
        )
    }

    /** Snapshot aller verfolgten Tags (defensive Kopien). */
    fun snapshots(): List<TrackedTag> = tags.mapNotNull { (mac, state) ->
        val p = state.lastPosition ?: return@mapNotNull null
        TrackedTag(
            mac = mac,
            position = p.clone(),
            velocity = state.velocity.clone(),
            speedMs = sqrt(
                state.velocity[0] * state.velocity[0] +
                    state.velocity[1] * state.velocity[1] +
                    state.velocity[2] * state.velocity[2]
            ),
            lastSeenMs = state.lastTimeMs,
        )
    }

    fun reset(mac: String) = tags.remove(mac)
    fun resetAll() = tags.clear()
}
