package com.example.agent.tactical

import java.util.UUID

/**
 * Annotation-Templates & -Modelle (docs/TACTICAL.md) — Portierung der
 * v9.1/9.2-Kernlogik: 20+ vordefinierte Icons über 7 Layer, Typen und
 * Farben; Erzeugung fertiger Annotationen aus Templates.
 *
 * Rendering/Overlay: Web-Visualizer (`AnnotationRenderer`-Muster aus der
 * Spec) bzw. Maps-3D-Integration laut UI_UX_PLAN (Roadmap).
 */
object AnnotationTemplates {

    enum class Layer { TACTICAL, LOGISTICS, MEDICAL, HAZARD, COMMUNICATION, INTELLIGENCE, OVERVIEW }

    enum class Type {
        WAYPOINT, NOTE, HINT, DANGER, ASSEMBLY, ENTRY_POINT, EXIT_POINT,
        COMMAND_POST, MEDICAL_POINT, RESUPPLY, VEHICLE, OBSERVATION,
        CHECKPOINT, HAZARD, COMMUNICATION, ROADBLOCK, SHELTER,
        WATER_SOURCE, FUEL_POINT, AMMUNITION, CASUALTY, CUSTOM,
    }

    data class Template(
        val id: String,
        val name: String,
        val iconId: String,
        val layer: Layer,
        val type: Type,
        val description: String = "",
        val color: Int = 0xFFCC00.toInt(),
    )

    val ALL: List<Template> = listOf(
        // Taktik
        Template("tactical_entry", "Eingang", "entry", Layer.TACTICAL, Type.ENTRY_POINT, "Taktischer Eingangspunkt", 0x00FF88),
        Template("tactical_exit", "Ausgang", "exit", Layer.TACTICAL, Type.EXIT_POINT, "Taktischer Ausgangspunkt", 0xFF4444),
        Template("tactical_command", "Führungsstelle", "command", Layer.TACTICAL, Type.COMMAND_POST, "Taktische Führungsstelle", 0x4488FF),
        Template("tactical_observation", "Beobachtungsposten", "observation", Layer.TACTICAL, Type.OBSERVATION, "Beobachtungsposten", 0x44FF44),
        Template("waypoint", "Wegpunkt", "waypoint", Layer.TACTICAL, Type.WAYPOINT, "Wegpunkt", 0xFFFF00),
        Template("checkpoint", "Checkpoint", "checkpoint", Layer.TACTICAL, Type.CHECKPOINT, "Kontrollpunkt", 0xFF8800),
        // Logistik
        Template("logistics_resupply", "Nachschub", "resupply", Layer.LOGISTICS, Type.RESUPPLY, "Nachschubpunkt", 0xFFAA00),
        Template("logistics_fuel", "Tankstelle", "fuel", Layer.LOGISTICS, Type.FUEL_POINT, "Tankstelle", 0xFF6600),
        Template("logistics_vehicle", "Fahrzeug", "vehicle", Layer.LOGISTICS, Type.VEHICLE, "Fahrzeug", 0x0088FF),
        // Medizin
        Template("medical_point", "Sanitätsstelle", "medical", Layer.MEDICAL, Type.MEDICAL_POINT, "Sanitätsstelle", 0xFF4444),
        Template("casualty", "Verletzter", "casualty", Layer.MEDICAL, Type.CASUALTY, "Verletzten-Sammelpunkt", 0xFF2222),
        Template("shelter", "Schutzraum", "shelter", Layer.MEDICAL, Type.SHELTER, "Schutzraum", 0x44AAFF),
        // Gefahren
        Template("hazard_danger", "Gefahrenzone", "danger", Layer.HAZARD, Type.DANGER, "Gefahrenzone", 0xFF0000),
        Template("hazard_roadblock", "Sperrung", "roadblock", Layer.HAZARD, Type.ROADBLOCK, "Straßensperrung", 0xAA4444),
        Template("hazard_chemical", "Gefahrstoff", "hazard", Layer.HAZARD, Type.HAZARD, "Gefahrstoff", 0xFF8800),
        // Kommunikation
        Template("comm_network", "Netzwerkknoten", "network", Layer.COMMUNICATION, Type.COMMUNICATION, "Netzwerkknoten", 0x00FFCC),
        Template("comm_radio", "Funkstelle", "communication", Layer.COMMUNICATION, Type.COMMUNICATION, "Funkstelle", 0x44FF44),
        // Gebäude / Übersicht
        Template("building", "Gebäude", "building", Layer.OVERVIEW, Type.CUSTOM, "Gebäude", 0x8888FF),
        Template("stairs", "Treppe", "stairs", Layer.OVERVIEW, Type.CUSTOM, "Treppenhaus", 0xAAAAAA),
        Template("door", "Tür", "door", Layer.OVERVIEW, Type.CUSTOM, "Tür", 0xCCCC88),
        Template("window", "Fenster", "window", Layer.OVERVIEW, Type.CUSTOM, "Fenster", 0x88CCFF),
        Template("custom", "Benutzerdefiniert", "custom", Layer.OVERVIEW, Type.CUSTOM, "Benutzerdefiniert", 0xCCCCCC),
    )

    fun byId(id: String): Template =
        ALL.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Template $id nicht gefunden")

    /** Erzeugt eine fertige Annotation aus einem Template. */
    fun create(
        templateId: String,
        mapId: String,
        x: Float,
        y: Float,
        z: Float,
        title: String? = null,
    ): MapAnnotation {
        val template = byId(templateId)
        return MapAnnotation(
            id = UUID.randomUUID().toString(),
            mapId = mapId,
            type = template.type,
            iconId = template.iconId,
            title = title ?: template.name,
            description = template.description,
            color = template.color,
            layer = template.layer,
            x = x,
            y = y,
            z = z,
            createdAt = System.currentTimeMillis(),
            createdBy = "system",
        )
    }
}

/** Annotation-Datenmodell (v9.1, ohne Room-Annotationen — pure Kernlogik). */
data class MapAnnotation(
    val id: String,
    val mapId: String,
    val type: AnnotationTemplates.Type,
    val iconId: String,
    val title: String,
    val description: String,
    val color: Int,
    val layer: AnnotationTemplates.Layer,
    val x: Float,
    val y: Float,
    val z: Float,
    val createdAt: Long,
    val createdBy: String,
)
