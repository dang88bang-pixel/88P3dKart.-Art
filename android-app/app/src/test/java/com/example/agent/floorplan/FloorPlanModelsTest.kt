package com.example.agent.floorplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanModelsTest {

    private val fixture = """
        {
          "elements": [
            {"type":"node","id":1,"lat":48.1370,"lon":11.5752},
            {"type":"node","id":2,"lat":48.1372,"lon":11.5756},
            {"type":"node","id":3,"lat":48.1369,"lon":11.5758},
            {"type":"node","id":4,"lat":48.1367,"lon":11.5754},
            {"type":"way","id":100,"nodes":[1,2,3,4,1],
             "tags":{"building":"public","building:levels":"5","height":"20","name":"Rathaus"}},
            {"type":"way","id":101,"nodes":[1,2,3,4],"tags":{"building":"yes"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `quellen-katalog spiegelt den verifizierten status`() {
        val names = FloorPlanModels.SOURCES.map { it.name }
        assertTrue(FloorPlanModels.SOURCES.any { "Nominatim" in it.name && it.available })
        assertTrue(FloorPlanModels.SOURCES.any { "Photon" in it.name && it.available })
        assertTrue(FloorPlanModels.SOURCES.any { "Overpass" in it.name && it.available })
        assertFalse(FloorPlanModels.SOURCES.any { it.available && "HOWOGE" in it.name })
        assertFalse(FloorPlanModels.SOURCES.any { it.available && "BIM Deutschland" in it.name })
        assertFalse(FloorPlanModels.SOURCES.any { it.name.contains("Mapzen") })
    }

    @Test
    fun `overpass-query enthaelt gebaeude-wege und radius`() {
        val query = OverpassQueryBuilder.buildingsAround(48.137, 11.575, radius = 75.0)
        assertTrue(query.contains("way[\"building\"](around:75.0,48.137,11.575)"))
        assertTrue(query.contains("relation[\"building\"](around:75.0,48.137,11.575)"))
        assertTrue(query.contains("[out:json]"))
    }

    @Test
    fun `overpass-parser extrahiert ringe etagen und hoehen`() {
        val buildings = BuildingParser.parseOverpass(fixture)
        assertEquals(1, buildings.size) // ungeschlossener Weg wird verworfen
        val rathaus = buildings.first()
        assertEquals("way/100", rathaus.osmId)
        assertEquals(5, rathaus.levels)
        assertEquals(20.0, rathaus.height, 1e-9)
        assertEquals("Rathaus", rathaus.name)
        // GeoJSON-Konvention: (lon, lat)
        assertEquals(11.5752 to 48.1370, rathaus.ring.first())
        assertEquals(rathaus.ring.first(), rathaus.ring.last())
    }

    @Test
    fun `geojson-parser akzeptiert die agent-antwort`() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "geometry": {"type": "Polygon",
                  "coordinates": [[[11.5752,48.1370],[11.5756,48.1372],[11.5758,48.1369],[11.5754,48.1367],[11.5752,48.1370]]]},
                "properties": {"osm_id":"way/100","building":"apartments","levels":3,"height":10.5,"name":"Haus"}
              }]
            }
        """.trimIndent()
        val buildings = BuildingParser.parseGeoJson(geoJson)
        assertEquals(1, buildings.size)
        val building = buildings.first()
        assertEquals(3, building.levels)
        assertEquals(10.5, building.height, 1e-9)
        assertTrue(building.centroid.first > 11.5)
    }

    @Test
    fun `parser ignoriert ungueltige eingaben`() {
        assertEquals(0, BuildingParser.parseOverpass("{}").size)
        assertEquals(0, BuildingParser.parseOverpass("kein json").size)
        assertEquals(0, BuildingParser.parseGeoJson("""{"features":[]}""").size)
    }
}
