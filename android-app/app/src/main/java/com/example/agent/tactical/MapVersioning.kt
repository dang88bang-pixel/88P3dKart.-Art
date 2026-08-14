package com.example.agent.tactical

/**
 * Map-Versionierung mit Delta-Kette (docs/TACTICAL.md) — Portierung der
 * v9.0-Kernlogik (MapVersionManager): die Basis-Version speichert den
 * vollen Snapshot, jede weitere Version nur das Delta (Upsert/Remove)
 * zur Vorgängerversion. Rekonstruktion = Basis + Delta-Kette.
 *
 * In der App wird die Kette über die bestehende Room-Datenbank
 * (AppDatabase/SpatialDao-Muster) persistiert — hier die reine,
 * JVM-testbare Logik.
 */
class MapVersioning {

    data class MapVersion(
        val version: Int,
        val parent: Int?,
        val createdAtMs: Long,
        val snapshot: Map<String, FloatArray> = emptyMap(), // nur Basis
        val delta: MapDelta? = null,
    )

    data class MapDelta(
        val upsert: Map<String, FloatArray> = emptyMap(),
        val remove: List<String> = emptyList(),
    )

    private val versions = HashMap<Int, MapVersion>()

    val latestVersion: Int
        get() = versions.keys.maxOrNull() ?: 0

    /** Legt die Basis-Version (1) an. */
    fun create(snapshot: Map<String, FloatArray>): MapVersion {
        require(versions.isEmpty()) { "Basis existiert bereits — commit() verwenden" }
        val version = MapVersion(
            version = 1,
            parent = null,
            createdAtMs = System.currentTimeMillis(),
            snapshot = snapshot.mapValues { it.value.clone() },
        )
        versions[1] = version
        return version
    }

    /** Neue Version mit Delta zur Vorgängerversion. */
    fun commit(snapshot: Map<String, FloatArray>): MapVersion {
        if (versions.isEmpty()) return create(snapshot)
        val prev = versions.getValue(latestVersion)
        val prevState = reconstruct(prev.version)
        val delta = diff(prevState, snapshot)
        val version = MapVersion(
            version = prev.version + 1,
            parent = prev.version,
            createdAtMs = System.currentTimeMillis(),
            delta = delta,
        )
        versions[version.version] = version
        return version
    }

    /** Rekonstruiert den Zustand einer Version (Basis + Delta-Kette). */
    fun reconstruct(version: Int? = null): Map<String, FloatArray> {
        require(versions.isNotEmpty()) { "Keine Versionen vorhanden" }
        val target = version ?: latestVersion
        val entry = versions[target] ?: throw IllegalArgumentException("Version $target existiert nicht")

        val chain = mutableListOf<MapVersion>()
        var current: Int? = target
        while (current != null) {
            chain.add(versions.getValue(current))
            current = versions.getValue(current).parent
        }
        chain.reverse()

        val state = HashMap<String, FloatArray>().apply {
            for ((key, value) in chain.first().snapshot) put(key, value.clone())
        }
        for (i in 1 until chain.size) {
            val delta = chain[i].delta ?: continue
            for ((key, value) in delta.upsert) state[key] = value.clone()
            for (key in delta.remove) state.remove(key)
        }
        return state
    }

    companion object {
        fun diff(old: Map<String, FloatArray>, new: Map<String, FloatArray>): MapDelta {
            val upsert = LinkedHashMap<String, FloatArray>()
            for ((key, value) in new) {
                val oldValue = old[key]
                if (oldValue == null || !oldValue.contentEquals(value)) upsert[key] = value.clone()
            }
            val remove = old.keys.filter { it !in new }
            return MapDelta(upsert = upsert, remove = remove)
        }
    }
}
