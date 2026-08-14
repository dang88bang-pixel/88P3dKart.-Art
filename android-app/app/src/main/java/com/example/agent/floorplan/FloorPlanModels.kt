package com.example.agent.floorplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Grundriss-Integration — Modelle, Quellen-Katalog, Overpass-Parser
 * (docs/FLOORPLAN.md). Kotlin-Spiegelung der Python-Implementierung
 * (`edge-agent/floorplan.py`) — gleiche Datenmodelle, gleiche
 * Overpass-QL, gleicher verifizierter Quellenstatus.
 */
object FloorPlanModels {

    /** Quellen-Katalog mit verifiziertem Verfügbarkeitsstatus. */
    data class SourceDescriptor(
        val name: String,
        val kind: String, // geocoder | buildings | imagery | portal
        val endpoint: String?,
        val available: Boolean,
        val requiresAuth: Boolean = false,
        val priority: Int = 99,
        val notes: String = "",
    )

    val SOURCES: List<SourceDescriptor> = listOf(
        SourceDescriptor("Nominatim (OSM)", "geocoder", "https://nominatim.openstreetmap.org/search", true, priority = 1,
            notes = "Usage Policy: max 1 req/s, gültiger User-Agent, ODbL-Attribution"),
        SourceDescriptor("Photon (komoot)", "geocoder", "https://photon.komoot.io/api/", true, priority = 2,
            notes = "OSM-basiert, Apache-2.0, Demo-Server mit Limits"),
        SourceDescriptor("OSM Overpass", "buildings", "https://overpass-api.de/api/interpreter", true, priority = 1,
            notes = "Gebäudeumrisse weltweit; Spiegel: overpass.kumi.systems"),
        SourceDescriptor("OSM Buildings (osmbuildings.org)", "buildings", null, false, priority = 3,
            notes = "3D-Viewer-Bibliothek; Daten-API nicht mehr frei — wir rendern selbst"),
        SourceDescriptor("KartaView (ex OpenStreetCam)", "imagery", "https://api.kartaview.org/1.0/photo/search/", true, priority = 2,
            notes = "Öffentlicher Endpoint: 100 req/h ohne Auth, 1000 req/h mit API-Key"),
        SourceDescriptor("Mapillary", "imagery", null, false, priority = 9,
            notes = "Zu Meta verkauft; freie API praktisch eingestellt"),
        SourceDescriptor("HOWOGE (Berlin, ehem. 'hoowoge.de' der Spec)", "portal", null, false, priority = 9,
            notes = "Grundriss-/BIM-Systeme intern — keine öffentliche API"),
        SourceDescriptor("BIM Deutschland (bimdeutschland.de)", "portal", null, false, priority = 9,
            notes = "Info-Portal, kein offenes BIM-Repository; Daten via CityGML/INSPIRE"),
        SourceDescriptor("Stadt-/Landes-Geoportale (INSPIRE/WFS)", "portal", null, true, priority = 4,
            notes = "z. B. Berlin FIS-Broker, Hamburg Transparenzportal — WFS je Kommune"),
    )

    /** Gebäude mit Umriss-Ring (GeoJSON-Konvention: [lon, lat]). */
    data class BuildingModel(
        val osmId: String,
        val building: String,
        val levels: Int,
        val height: Double,
        val name: String?,
        val address: String?,
        val ring: List<Pair<Double, Double>>, // (lon, lat)
    ) {
        /** Schwerpunkt des Umrisses (für die lokale Platzierung). */
        val centroid: Pair<Double, Double>
            get() {
                var lon = 0.0
                var lat = 0.0
                for ((lng, ltt) in ring) {
                    lon += lng
                    lat += ltt
                }
                return (lon / ring.size) to (lat / ring.size)
            }
    }
}

/**
 * Overpass-QL für Gebäudeumrisse (identisch zur Python-Implementierung).
 */
object OverpassQueryBuilder {

    fun buildingsAround(lat: Double, lon: Double, radius: Double = 100.0, timeout: Int = 25): String {
        val t = maxOf(1, timeout)
        return "[out:json][timeout:$t];\n" +
            "(\n" +
            "  way[\"building\"](around:$radius,$lat,$lon);\n" +
            "  relation[\"building\"](around:$radius,$lat,$lon);\n" +
            ");\n" +
            "out body;\n" +
            ">;\n" +
            "out skel qt;"
    }
}

/**
 * Parser für Overpass-Antworten / GeoJSON-FeatureCollections
 * (kotlinx.serialization — identisches Verhalten zum Python-Port).
 */
object BuildingParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Overpass-JSON (elements: way/relation + node) → Gebäude-Modelle. */
    fun parseOverpass(rawJson: String): List<FloorPlanModels.BuildingModel> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val elements = root["elements"]?.jsonArray ?: return emptyList()

        val nodes = HashMap<Long, Pair<Double, Double>>()
        for (element in elements) {
            val obj = element.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "node") {
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                nodes[obj["id"]!!.jsonPrimitive.content.toLong()] = lon to lat
            }
        }

        val buildings = ArrayList<FloorPlanModels.BuildingModel>()
        for (element in elements) {
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: continue
            if (type != "way" && type != "relation") continue
            val tags = obj["tags"]?.jsonObject ?: continue
            if (!tags.containsKey("building")) continue

            // Knoten-Referenzen: Way direkt; Relation über äußere Member
            val refs = ArrayList<Long>()
            if (type == "way") {
                obj["nodes"]?.jsonArray?.forEach { refs.add(it.jsonPrimitive.content.toLong()) }
            } else {
                obj["members"]?.jsonArray?.forEach { member ->
                    val memberObj = member.jsonObject
                    if (memberObj["role"]?.jsonPrimitive?.content != "outer") return@forEach
                    val refId = memberObj["ref"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                    val way = elements.firstOrNull { el ->
                        val e = el.jsonObject
                        e["type"]?.jsonPrimitive?.content == "way" &&
                            e["id"]?.jsonPrimitive?.content?.toLongOrNull() == refId
                    } ?: return@forEach
                    way.jsonObject["nodes"]?.jsonArray?.forEach { refs.add(it.jsonPrimitive.content.toLong()) }
                }
            }

            val ring = refs.mapNotNull { nodes[it] }
            if (ring.size < 4 || ring.first() != ring.last()) continue // ungeschlossen

            val levelsTag = tags["building:levels"]?.jsonPrimitive?.content
                ?: tags["levels"]?.jsonPrimitive?.content
            val levels = levelsTag?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val height = tags["height"]?.jsonPrimitive?.doubleOrNull
                ?: (levels * 3.2)

            val street = tags["addr:street"]?.jsonPrimitive?.content
            val number = tags["addr:housenumber"]?.jsonPrimitive?.content
            buildings.add(
                FloorPlanModels.BuildingModel(
                    osmId = "${type}/${obj["id"]!!.jsonPrimitive.content}",
                    building = tags["building"]?.jsonPrimitive?.content ?: "yes",
                    levels = levels,
                    height = height,
                    name = tags["name"]?.jsonPrimitive?.content,
                    address = listOfNotNull(street, number).joinToString(" ").ifBlank { null },
                    ring = ring,
                )
            )
        }
        return buildings
    }

    /** GeoJSON FeatureCollection (wie vom Edge-Agent geliefert) parsen. */
    fun parseGeoJson(rawJson: String): List<FloorPlanModels.BuildingModel> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val features = root["features"]?.jsonArray ?: return emptyList()
        val buildings = ArrayList<FloorPlanModels.BuildingModel>()
        for (feature in features) {
            val obj = feature.jsonObject
            val geometry = obj["geometry"]?.jsonObject ?: continue
            if (geometry["type"]?.jsonPrimitive?.content != "Polygon") continue
            val coordinates = geometry["coordinates"]?.jsonArray ?: continue
            val ringJson = coordinates.firstOrNull()?.jsonArray ?: continue
            val ring = ringJson.mapNotNull { point ->
                val p = point.jsonArray
                val lon = p.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = p.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                lon to lat
            }
            if (ring.size < 4) continue
            val properties = obj["properties"]?.jsonObject ?: continue
            buildings.add(
                FloorPlanModels.BuildingModel(
                    osmId = properties["osm_id"]?.jsonPrimitive?.content ?: "unknown",
                    building = properties["building"]?.jsonPrimitive?.content ?: "yes",
                    levels = properties["levels"]?.jsonPrimitive?.intOrNull ?: 1,
                    height = properties["height"]?.jsonPrimitive?.doubleOrNull ?: 3.2,
                    name = properties["name"]?.jsonPrimitive?.content,
                    address = listOfNotNull(
                        properties["addr_street"]?.jsonPrimitive?.content,
                        properties["addr_housenumber"]?.jsonPrimitive?.content,
                    ).joinToString(" ").ifBlank { null },
                    ring = ring,
                )
            )
        }
        return buildings
    }
}
