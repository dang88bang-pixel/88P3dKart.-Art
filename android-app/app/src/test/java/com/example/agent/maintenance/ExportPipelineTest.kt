package com.example.agent.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPipelineTest {

    private val annotations = listOf(
        ExportPipeline.ExportAnnotation(
            id = "a1",
            title = "Person <A> & Co.",
            description = "Bewegung \"hinter\" der Wand",
            lon = 13.405,
            lat = 52.52,
            z = 1.2,
        ),
        ExportPipeline.ExportAnnotation(
            id = "a2",
            title = "Sender 433 MHz",
            description = "",
            lon = 13.406,
            lat = 52.521,
            z = 0.5,
        ),
    )

    @Test
    fun `geojson enthaelt feature-collection mit allen punkten`() {
        val geo = ExportPipeline.toGeoJson(annotations)
        assertTrue(geo.contains("\"type\": \"FeatureCollection\""))
        assertTrue(geo.contains("\"coordinates\": [13.405, 52.52, 1.2]"))
        assertTrue(geo.contains("\"title\": \"Person <A> & Co.\""))
    }

    @Test
    fun `kml escapet xml-sonderzeichen`() {
        val kml = ExportPipeline.toKml(annotations)
        assertTrue(kml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        // & und < müssen escaped sein — sonst ist das KML ungültig
        assertTrue(kml.contains("Person &lt;A&gt; &amp; Co."))
        assertTrue(!kml.contains("Person <A> & Co."))
        assertTrue(kml.contains("<coordinates>13.405,52.52,1.2</coordinates>"))
    }

    @Test
    fun `json-export ist syntaktisch strukturiert`() {
        val json = ExportPipeline.toJson(annotations)
        assertTrue(json.contains("\"annotations\": ["))
        assertTrue(json.contains("\"id\": \"a1\""))
    }

    @Test
    fun `leere annotationen ergeben leere strukturen`() {
        assertEquals(
            "{\n  \"type\": \"FeatureCollection\",\n  \"features\": [\n  ]\n}",
            ExportPipeline.toGeoJson(emptyList()),
        )
        assertTrue(ExportPipeline.toKml(emptyList()).contains("<Document>\n"))
        assertTrue(ExportPipeline.toJson(emptyList()).contains("\"annotations\": [\n"))
    }

    @Test
    fun `retention behaelt nur junge eintraege`() {
        val now = 1_700_000_000_000L
        val items = listOf(
            ExportPipeline.ExportItem("old1", now - 40L * 24 * 3600 * 1000, "json"),
            ExportPipeline.ExportItem("new1", now - 5L * 24 * 3600 * 1000, "kml"),
            ExportPipeline.ExportItem("new2", now - 29L * 24 * 3600 * 1000, "geojson"),
        )
        val kept = ExportPipeline.applyRetention(items, retentionDays = 30, nowMs = now)
        assertEquals(listOf("new1", "new2"), kept.map { it.id })
    }
}
