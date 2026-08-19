package com.example.agent.classification

/**
 * Semantische Klassifikation & Farbkodierung (Spezifikation §2.2/§3.1).
 *
 * Verbindliches Farbschema — identisch zur Visualizer-Palette
 * (`web-visualizer/public/colorcoding.js`) und zur Server-Erzwingung
 * (`edge-agent/privacy.py`):
 *
 *   WALL #AAAAAA · FLOOR #666666 · CEILING #CCCCCC · FURNITURE #777777
 *   DEVICE #4488FF (anonymisiert gespeichert)
 *   PERSON/ANIMAL #44FF88 — NUR Live-View, NIE gespeichert
 *   EXIT #FF3333 · MARKER #FFCC00 · UNKNOWN #555555
 *
 * Die Geometrie-Klassifikation (Wand vs. dynamischer Kandidat) delegiert an
 * den WallPersonClassifier. Geräte-/Ausgangs-Erkennung läuft über optionale
 * Hinweise (Positionen bekannter eigener Geräte / Öffnungsmarker) — keine
 * biometrische Erkennung.
 */
class SemanticClassifier(
    private val wallPersonClassifier: WallPersonClassifier = WallPersonClassifier(),
) {

    enum class SemanticType {
        WALL, FLOOR, CEILING, FURNITURE, DEVICE, PERSON, ANIMAL, EXIT, MARKER, UNKNOWN,
    }

    data class ClassifiedPoint(
        val point: Point3D,
        val type: SemanticType,
        val confidence: Float,
        val color: Int,
        val liveOnly: Boolean,
    )

    data class DeviceHint(val x: Float, val y: Float, val z: Float, val radius: Float = 0.3f)
    data class ExitHint(val x: Float, val y: Float, val z: Float, val radius: Float = 0.4f)

    companion object {
        val COLOR_WALL = 0xFFAAAAAA.toInt()
        val COLOR_FLOOR = 0xFF666666.toInt()
        val COLOR_CEILING = 0xFFCCCCCC.toInt()
        val COLOR_FURNITURE = 0xFF777777.toInt()
        val COLOR_DEVICE = 0xFF4488FF.toInt()
        val COLOR_PERSON = 0xFF44FF88.toInt()
        val COLOR_EXIT = 0xFFFF3333.toInt()
        val COLOR_MARKER = 0xFFFFCC00.toInt()
        val COLOR_UNKNOWN = 0xFF555555.toInt()

        /** Live-Only-Typen — synchron zu edge-agent/privacy.py. */
        val LIVE_ONLY_TYPES = setOf("person", "animal", "moving_person", "dynamic")

        fun isLiveOnly(type: SemanticType): Boolean = when (type) {
            SemanticType.PERSON, SemanticType.ANIMAL -> true
            else -> false
        }
    }

    /**
     * Klassifiziert eine Punktwolke und weist Farben zu.
     * @param deviceHints Positionen bekannter eigener Geräte (anonym)
     * @param exitHints manuell verifizierte Öffnungsmarker (optional)
     */
    fun classify(
        points: List<Point3D>,
        deviceHints: List<DeviceHint> = emptyList(),
        exitHints: List<ExitHint> = emptyList(),
    ): List<ClassifiedPoint> {
        if (points.isEmpty()) return emptyList()
        val (reports, _) = wallPersonClassifier.classify(points)

        // Dynamik-Cluster-Mengen für die Punktzuordnung
        val dynamicCenters = reports.filter { !it.persistable }.map { it.centroid }
        val wallCenters = reports.filter { it.persistable }.map { it.centroid }

        val zMin = points.minOf { it.z }
        val zMax = points.maxOf { it.z }

        return points.map { p ->
            val type: SemanticType
            val confidence: Float
            when {
                exitHints.any { near(it.x, it.y, it.z, it.radius, p) } -> {
                    type = SemanticType.EXIT; confidence = 0.75f
                }
                deviceHints.any { near(it.x, it.y, it.z, it.radius, p) } -> {
                    type = SemanticType.DEVICE; confidence = 0.70f
                }
                dynamicCenters.any { near(it.x, it.y, it.z, 0.45f, p) } -> {
                    type = SemanticType.PERSON; confidence = 0.75f
                }
                wallCenters.any { near(it.x, it.y, it.z, 0.45f, p) } && p.z in 0.5f..2.5f -> {
                    type = SemanticType.WALL; confidence = 0.85f
                }
                p.z <= zMin + 0.15f * (zMax - zMin) -> {
                    type = SemanticType.FLOOR; confidence = 0.90f
                }
                p.z >= zMax - 0.15f * (zMax - zMin) -> {
                    type = SemanticType.CEILING; confidence = 0.80f
                }
                else -> { type = SemanticType.UNKNOWN; confidence = 0.50f }
            }
            ClassifiedPoint(
                point = p,
                type = type,
                confidence = confidence,
                color = colorFor(type),
                liveOnly = isLiveOnly(type),
            )
        }
    }

    fun colorFor(type: SemanticType): Int = when (type) {
        SemanticType.WALL -> COLOR_WALL
        SemanticType.FLOOR -> COLOR_FLOOR
        SemanticType.CEILING -> COLOR_CEILING
        SemanticType.FURNITURE -> COLOR_FURNITURE
        SemanticType.DEVICE -> COLOR_DEVICE
        SemanticType.PERSON, SemanticType.ANIMAL -> COLOR_PERSON
        SemanticType.EXIT -> COLOR_EXIT
        SemanticType.MARKER -> COLOR_MARKER
        SemanticType.UNKNOWN -> COLOR_UNKNOWN
    }

    private fun near(cx: Float, cy: Float, cz: Float, radius: Float, p: Point3D): Boolean {
        val dx = p.x - cx
        val dy = p.y - cy
        val dz = p.z - cz
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
