package com.example.agent.maintenance

/**
 * Datenexport & Format-Konvertierung (docs/SERVICE_WORKER.md §Export Worker).
 *
 * Portierung der sinnvollen Kernlogik aus der v10.2.0-ServiceWorker-
 * Spezifikation (JSON/GeoJSON/KML-Erzeugung, Retention) — als reines
 * Kotlin-Modul. Ergänzung gegenüber der Spec:
 *
 * - **XML-Escaping** in KML (Titel mit `<`, `&` etc. würden sonst
 *   ungültiges KML erzeugen),
 * - Retention als reine, testbare Funktion.
 *
 * GLB-Erzeugung erfolgt serverseitig (Edge-Agent/Python, Draco) — auf dem
 * Gerät wird nur der Export-Auftrag formuliert (docs/AURA.md §2: Zielgröße
 * < 5 MB mit Draco).
 */
object ExportPipeline {

    /** Eine exportierbare Annotation (Position im Welt-/Geo-Koordinatensystem). */
    data class ExportAnnotation(
        val id: String,
        val title: String,
        val description: String = "",
        val lon: Double,
        val lat: Double,
        val z: Double = 0.0,
        val meta: Map<String, String> = emptyMap(),
    )

    /** Ein gespeicherter Export (für Retention). */
    data class ExportItem(
        val id: String,
        val timestampMs: Long,
        val format: String,
    )

    enum class Format(val key: String) {
        JSON("json"),
        GEOJSON("geojson"),
        KML("kml"),
        GLB("glb"),
    }

    fun toJson(annotations: List<ExportAnnotation>, pretty: Boolean = true): String {
        val sb = StringBuilder()
        val indent = if (pretty) "  " else ""
        sb.append("{\n")
        sb.append(indent).append("\"type\": \"FeatureCollection\",\n")
        sb.append(indent).append("\"annotations\": [\n")
        annotations.forEachIndexed { i, a ->
            sb.append(indent).append(indent).append("{\n")
            sb.append(indent).append(indent).append(indent)
                .append("\"id\": \"").append(escapeJson(a.id)).append("\",\n")
            sb.append(indent).append(indent).append(indent)
                .append("\"title\": \"").append(escapeJson(a.title)).append("\",\n")
            sb.append(indent).append(indent).append(indent)
                .append("\"lon\": ").append(a.lon).append(",\n")
            sb.append(indent).append(indent).append(indent)
                .append("\"lat\": ").append(a.lat).append(",\n")
            sb.append(indent).append(indent).append(indent)
                .append("\"z\": ").append(a.z).append("\n")
            sb.append(indent).append(indent).append("}")
            if (i < annotations.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(indent).append("]\n")
        sb.append("}")
        return sb.toString()
    }

    /** GeoJSON FeatureCollection (RFC 7946). */
    fun toGeoJson(annotations: List<ExportAnnotation>): String {
        val sb = StringBuilder()
        sb.append("{\n  \"type\": \"FeatureCollection\",\n  \"features\": [\n")
        annotations.forEachIndexed { i, a ->
            sb.append("    {\n")
            sb.append("      \"type\": \"Feature\",\n")
            sb.append("      \"geometry\": {\n")
            sb.append("        \"type\": \"Point\",\n")
            sb.append("        \"coordinates\": [").append(a.lon).append(", ")
                .append(a.lat).append(", ").append(a.z).append("]\n")
            sb.append("      },\n")
            sb.append("      \"properties\": {\n")
            sb.append("        \"id\": \"").append(escapeJson(a.id)).append("\",\n")
            sb.append("        \"title\": \"").append(escapeJson(a.title)).append("\",\n")
            sb.append("        \"description\": \"").append(escapeJson(a.description)).append("\"\n")
            sb.append("      }\n")
            sb.append("    }")
            if (i < annotations.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    /** KML-Dokument (OGC KML 2.2) mit XML-Escaping. */
    fun toKml(annotations: List<ExportAnnotation>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        for (a in annotations) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(escapeXml(a.title)).append("</name>\n")
            sb.append("      <description>").append(escapeXml(a.description))
                .append("</description>\n")
            sb.append("      <Point>\n")
            sb.append("        <coordinates>").append(a.lon).append(",")
                .append(a.lat).append(",").append(a.z).append("</coordinates>\n")
            sb.append("      </Point>\n")
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
        return sb.toString()
    }

    /** Retention: behält nur Einträge der letzten [retentionDays] Tage. */
    fun applyRetention(
        items: List<ExportItem>,
        retentionDays: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ExportItem> {
        require(retentionDays > 0) { "retentionDays muss > 0 sein" }
        val cutoff = nowMs - retentionDays * 24L * 3600 * 1000
        return items.filter { it.timestampMs >= cutoff }
    }

    // ── Escaping ─────────────────────────────────────────────────────

    private fun escapeXml(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun escapeJson(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
