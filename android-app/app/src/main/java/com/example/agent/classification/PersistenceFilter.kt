package com.example.agent.classification

import java.security.MessageDigest
import kotlin.math.floor

/**
 * Erzwingender Speicherfilter (Spezifikation §4.1) — Kotlin-Spiegelung von
 * `edge-agent/privacy.py` (identische Regeln):
 *
 *  - Live-Only-Typen (person/animal/moving_person/dynamic) werden vor
 *    Persistenz/Export VOLLSTÄNDIG entfernt.
 *  - Geräte werden anonymisiert: ID/MAC → SHA-256-Hash, Metadaten-Strip
 *    (mac/uuid/user_id/…), Positionen auf 0,1 m granularisiert.
 *  - Audit liefert Zähler ohne Objektdaten.
 */
class PersistenceFilter {

    companion object {
        val LIVE_ONLY_TYPES = setOf("person", "animal", "moving_person", "dynamic")
        val STRIP_KEYS = setOf("mac", "uuid", "user_id", "user", "owner_name", "phone", "email")
        const val POSITION_GRANULARITY = 0.1

        fun anonymizeIdentifier(value: String, prefix: String = "ANON"): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            val hex = digest.take(8).joinToString("") { "%02x".format(it) }
            return "${prefix}_$hex"
        }

        fun granularize(value: Float, granularity: Double = POSITION_GRANULARITY): Float {
            val step = granularity.toFloat()
            return (floor((value / step).toDouble()) * granularity).toFloat()
        }

        fun stripMetadata(metadata: Map<String, Any?>): Map<String, Any?> =
            metadata.filterKeys { it.lowercase() !in STRIP_KEYS }
    }

    data class Audit(
        val totalObjects: Int,
        val liveOnlyRemoved: Int,
        val persistedKinds: Map<String, Int>,
    )

    /** Entfernt Live-Only-Objekte vollständig. */
    fun filterObjects(objects: List<Pair<String, Point3D>>): Pair<List<Pair<String, Point3D>>, Int> {
        var removed = 0
        val kept = mutableListOf<Pair<String, Point3D>>()
        for ((kind, point) in objects) {
            if (kind.lowercase() in LIVE_ONLY_TYPES) {
                removed++
                continue
            }
            kept.add(kind to point)
        }
        return kept to removed
    }

    /** Anonymisiert ein Geräte-Dict (ID/MAC-Hash + Metadaten-Strip). */
    fun filterDevice(
        id: String,
        mac: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        out["id"] = if (id.startsWith("ANON_")) id else anonymizeIdentifier(id)
        mac?.let { out["mac"] = anonymizeIdentifier(it) }
        out["metadata"] = stripMetadata(metadata)
        return out
    }

    /** Granularisiert eine Position auf 0,1 m (Datensparsamkeit). */
    fun sanitizePosition(p: Point3D): Point3D =
        Point3D(granularize(p.x), granularize(p.y), granularize(p.z))

    /** Audit-Zähler ohne Objektdaten. */
    fun audit(objects: List<Pair<String, Point3D>>): Audit {
        val kinds = objects.groupingBy { it.first.lowercase() }.eachCount()
        val removed = kinds.filterKeys { it in LIVE_ONLY_TYPES }.values.sum()
        return Audit(
            totalObjects = objects.size,
            liveOnlyRemoved = removed,
            persistedKinds = kinds.filterKeys { it !in LIVE_ONLY_TYPES },
        )
    }

    /**
     * Bequemer Zugriff für den Speicherpfad:
     * nur persistierbare Punkte (Statik) zurückgeben.
     */
    fun persistablePoints(points: List<Pair<String, Point3D>>): List<Pair<String, Point3D>> =
        filterObjects(points).first
}
