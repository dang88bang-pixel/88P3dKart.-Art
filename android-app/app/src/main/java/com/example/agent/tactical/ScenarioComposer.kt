package com.example.agent.tactical

/**
 * Modulare Szenario-Komposition (docs/TACTICAL.md) — Portierung der
 * v9.0-Kernlogik (ModularScenarioBuilder) mit Abhängigkeitsauflösung:
 * DFS über die Modulabhängigkeiten, Zyklus-Erkennung, topologische Ordnung,
 * Konfigurations-Merge je Modultyp.
 */
class ScenarioComposer(modules: List<ScenarioModule>) {

    data class ScenarioModule(
        val id: String,
        val name: String,
        val type: String, // terrain|buildings|personnel|vehicles|hazards|...
        val config: Map<String, Any> = emptyMap(),
        val dependencies: List<String> = emptyList(),
    )

    data class ComposedScenario(
        val modules: List<String>,      // topologische Ordnung (Abhängigkeiten zuerst)
        val config: Map<String, Map<String, Any>>, // Modultyp → Konfiguration
    )

    private val modulesById: Map<String, ScenarioModule> = modules.associateBy { it.id }

    fun availableIds(): Set<String> = modulesById.keys

    /** Baut das Szenario aus den gewählten Modulen (inkl. Abhängigkeits-Hülle). */
    fun build(selected: List<String>): ComposedScenario {
        require(selected.size == selected.toSet().size) { "Doppelte Modul-IDs in der Auswahl" }

        val order = mutableListOf<String>()
        val visited = HashSet<String>()
        val visiting = mutableListOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            if (id in visiting) {
                val cycle = visiting.subList(visiting.indexOf(id), visiting.size) + id
                throw IllegalArgumentException("Zyklische Abhängigkeit: ${cycle.joinToString(" -> ")}")
            }
            val module = modulesById[id]
                ?: throw IllegalArgumentException("Modul $id nicht gefunden")
            visiting.add(id)
            for (dep in module.dependencies) visit(dep)
            visiting.removeAt(visiting.size - 1)
            visited.add(id)
            order.add(id)
        }

        for (id in selected) visit(id)

        val config = LinkedHashMap<String, Map<String, Any>>()
        for (id in order) {
            val module = modulesById.getValue(id)
            config[module.type] = module.config
        }
        return ComposedScenario(modules = order, config = config)
    }
}
