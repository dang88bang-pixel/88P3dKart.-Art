/**
 * Kontextring für externe Entitäten.
 *
 * Hintergrund (docs/API_INTEGRATION_REVIEW.md, Blocker A): Die Szene hat ein
 * 40-Meter-Raster und eine Kamera-Far-Plane von 500. Ein Bus in 800 m oder ein
 * Flugzeug in 9 km lässt sich darin nicht massstäblich zeichnen — entweder man
 * verliert den Nahbereich oder die Entität liegt hinter der Far-Plane.
 *
 * Lösung: eine zweite Darstellungsebene. Alles jenseits von NEAR_FIELD_M wird
 * nicht an seiner echten Position gezeichnet, sondern als Marker auf einen
 * Ring am Rand des Nahbereichs projiziert — Peilung bleibt erhalten, Distanz
 * wird beschriftet. Das ist bewusst keine massstäbliche Karte, sondern eine
 * Richtungsanzeige, und wird auch so beschriftet.
 */
import * as THREE from 'three';
import { CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';

const NEAR_FIELD_M = 18;   // ab hier wird auf den Ring projiziert
const RING_RADIUS = 19;    // Szeneneinheiten (= Meter im Nahfeld)

// Farbcodierung nach Vertrauenswürdigkeit, nicht nach Fahrzeugtyp:
// grau = veraltet, grün = frisch und nah, blau = frisch aber projiziert.
const COLOR_STALE = 0x777777;
const COLOR_FRESH = 0x33dd88;
const COLOR_RING = 0x4499ff;

const TYPE_ICON = {
    vehicle: '🚌',
    vessel: '🚢',
    aircraft: '✈️',
    micromobility: '🛴',
    incident: '⚠️',
    unknown: '❓',
};

export class ContextRing {
    /**
     * @param {THREE.Scene} scene
     * @param {object} [options]
     */
    constructor(scene, options = {}) {
        this.scene = scene;
        this.nearFieldM = options.nearFieldM ?? NEAR_FIELD_M;
        this.ringRadius = options.ringRadius ?? RING_RADIUS;
        this.markers = new Map();   // entity_id -> THREE.Group
        this.visible = true;
        this.anchorSet = false;

        this.group = new THREE.Group();
        this.group.name = 'context-ring';
        this.scene.add(this.group);

        this._buildRing();
    }

    _buildRing() {
        // Der Ring markiert die Grenze zwischen gemessenem Nahfeld und
        // hereinprojizierten Fremddaten. Ohne diese sichtbare Grenze würde
        // die Anzeige suggerieren, die Bus-Position sei genauso vermessen
        // wie die LiDAR-Punktwolke.
        const ring = new THREE.Mesh(
            new THREE.RingGeometry(this.ringRadius - 0.08, this.ringRadius, 96),
            new THREE.MeshBasicMaterial({
                color: COLOR_RING,
                side: THREE.DoubleSide,
                transparent: true,
                opacity: 0.35,
            })
        );
        ring.rotation.x = -Math.PI / 2;
        ring.position.y = 0.02;
        this.group.add(ring);
        this.ringMesh = ring;

        // Nordmarke — ohne sie ist eine Peilungsanzeige wertlos
        const northLabel = document.createElement('div');
        northLabel.className = 'ctx-north';
        northLabel.textContent = 'N';
        const north = new CSS2DObject(northLabel);
        north.position.set(0, 0.3, -this.ringRadius - 1.2);
        this.group.add(north);
    }

    _createMarker(entity) {
        const group = new THREE.Group();

        const cone = new THREE.Mesh(
            new THREE.ConeGeometry(0.45, 1.2, 6),
            // Basic statt Standard: Die Farbe kodiert den Datenstatus
            // (grau/grün/blau). Ein lichtabhängiges Material würde sie je
            // nach Kamerawinkel abdunkeln und die Aussage verfälschen.
            new THREE.MeshBasicMaterial({
                color: COLOR_FRESH,
                transparent: true,
                opacity: 0.85,
            })
        );
        cone.rotation.x = Math.PI;   // Spitze nach unten, zeigt auf den Ort
        cone.position.y = 0.9;
        group.add(cone);
        group.userData.cone = cone;

        const div = document.createElement('div');
        div.className = 'ctx-label';
        const label = new CSS2DObject(div);
        label.position.set(0, 2.0, 0);
        group.add(label);
        group.userData.labelDiv = div;

        this.group.add(group);
        return group;
    }

    /**
     * @param {object} snapshot Antwort von GET /api/v1/external/entities
     */
    update(snapshot) {
        this.anchorSet = Boolean(snapshot?.anchor_set);
        const entities = snapshot?.entities ?? [];
        const seen = new Set();

        // Ohne GeoAnchor gibt es keine lokalen Koordinaten — dann ist die
        // einzig ehrliche Darstellung: gar keine.
        if (!this.anchorSet) {
            this.clear();
            this.group.visible = false;
            return { shown: 0, projected: 0, stale: 0, anchorSet: false };
        }
        this.group.visible = this.visible;

        let projected = 0;
        let stale = 0;

        for (const entity of entities) {
            if (!Array.isArray(entity.local)) continue;
            const id = entity.entity_id ?? entity.id;
            seen.add(id);

            let marker = this.markers.get(id);
            if (!marker) {
                marker = this._createMarker(entity);
                this.markers.set(id, marker);
            }

            const [x, , z] = entity.local;
            const planar = Math.hypot(x, z);
            const isProjected = planar > this.nearFieldM;

            if (isProjected) {
                // Peilung beibehalten, Betrag auf den Ring klemmen
                const scale = this.ringRadius / (planar || 1);
                marker.position.set(x * scale, 0, z * scale);
                projected += 1;
            } else {
                marker.position.set(x, 0, z);
            }

            if (entity.stale) stale += 1;

            const color = entity.stale
                ? COLOR_STALE
                : (isProjected ? COLOR_RING : COLOR_FRESH);
            marker.userData.cone.material.color.setHex(color);
            marker.userData.cone.material.opacity = entity.stale ? 0.4 : 0.85;

            marker.userData.labelDiv.textContent =
                this._labelText(entity, isProjected);
            marker.userData.labelDiv.classList.toggle('stale', Boolean(entity.stale));
        }

        // Verschwundene Entitäten entfernen — ein stehengebliebener Marker
        // ist gefährlicher als gar keiner.
        for (const [id, marker] of this.markers) {
            if (!seen.has(id)) {
                this.group.remove(marker);
                this.markers.delete(id);
            }
        }

        return { shown: this.markers.size, projected, stale, anchorSet: true };
    }

    _labelText(entity, isProjected) {
        const icon = TYPE_ICON[entity.entity_type] ?? TYPE_ICON.unknown;
        const name = entity.label
            || entity.metadata?.route_id
            || entity.entity_id
            || '?';

        const parts = [`${icon} ${name}`];

        if (entity.distance_m != null) {
            const d = entity.distance_m >= 1000
                ? `${(entity.distance_m / 1000).toFixed(1)} km`
                : `${Math.round(entity.distance_m)} m`;
            const bearing = entity.metadata?.bearing_from_anchor;
            parts.push(bearing != null ? `${d} · ${Math.round(bearing)}°` : d);
        }

        // Alter immer anzeigen: bei 50 km/h sind 30 s bereits 400 m Fehler.
        if (entity.age_s != null) {
            parts.push(entity.stale
                ? `⚠ ${Math.round(entity.age_s)} s alt`
                : `${Math.round(entity.age_s)} s`);
        }
        if (isProjected) parts.push('↗ projiziert');

        return parts.join('\n');
    }

    setVisible(visible) {
        this.visible = visible;
        this.group.visible = visible && this.anchorSet;
    }

    clear() {
        for (const [, marker] of this.markers) this.group.remove(marker);
        this.markers.clear();
    }
}
