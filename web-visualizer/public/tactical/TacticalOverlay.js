import * as THREE from 'three';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';

/**
 * Taktisches Overlay für 3D-Ansicht
 * - Farbcodierte Avatare basierend auf Stress-Level
 * - Echtzeit-Vitaldaten als schwebende Labels
 * - Alarme als pulsierende Marker
 * - Einsatzstatus-Übersicht
 */
export class TacticalOverlay {
    constructor(scene, camera, renderer) {
        this.scene = scene;
        this.camera = camera;
        this.renderer = renderer;
        this.personnelMarkers = new Map();
        this.alertMarkers = [];
        this.statsOverlay = null;
        this.onPersonnelClick = null;
        
        // Farben für Stress-Level
        this.stressColors = {
            'LOW': 0x44FF88,      // Grün – ruhig
            'MEDIUM': 0xFFCC00,   // Gelb – erhöhte Aufmerksamkeit
            'HIGH': 0xFF8800,     // Orange – stark belastet
            'CRITICAL': 0xFF3333  // Rot – kritisch
        };
        
        // Rollen-Symbole
        this.roleSymbols = {
            'COMMANDER': '⭐',
            'TEAM_LEADER': '👤',
            'ASSAULT': '🔫',
            'SUPPORT': '🛡️',
            'MEDIC': '❤️',
            'RECON': '👁️',
            'SNIPER': '🎯',
            'DEMO': '💥'
        };
        
        this.setupOverlay();
        this.startAnimation();
    }

    setupOverlay() {
        // CSS2D-Renderer für Labels
        this.labelRenderer = new CSS2DRenderer();
        this.labelRenderer.setSize(
            this.renderer.domElement.width || window.innerWidth,
            this.renderer.domElement.height || window.innerHeight
        );
        this.labelRenderer.domElement.style.position = 'absolute';
        this.labelRenderer.domElement.style.top = '0';
        this.labelRenderer.domElement.style.left = '0';
        this.labelRenderer.domElement.style.pointerEvents = 'none';
        document.body.appendChild(this.labelRenderer.domElement);

        // Stats-Overlay (oben links)
        this.statsOverlay = document.createElement('div');
        this.statsOverlay.id = 'tactical-stats-overlay';
        this.statsOverlay.style.cssText = `
            position: absolute;
            top: 20px;
            left: 20px;
            background: rgba(0,0,0,0.7);
            color: white;
            padding: 12px 16px;
            border-radius: 8px;
            font-family: 'Segoe UI', monospace;
            font-size: 12px;
            border: 1px solid rgba(255,255,255,0.1);
            backdrop-filter: blur(10px);
            z-index: 100;
            min-width: 200px;
        `;
        this.statsOverlay.innerHTML = `
            <div style="font-weight:bold;margin-bottom:8px;">🎯 Einsatz-Status</div>
            <div id="tactical-stats">
                <div style="display:flex;justify-content:space-between;">
                    <span>👤 Personal:</span>
                    <span id="total-count">0</span>
                </div>
                <div style="display:flex;justify-content:space-between;color:#44FF88;">
                    <span>✅ Einsatzbereit:</span>
                    <span id="operational-count">0</span>
                </div>
                <div style="display:flex;justify-content:space-between;color:#FFCC00;">
                    <span>⚠️ Eingeschränkt:</span>
                    <span id="degraded-count">0</span>
                </div>
                <div style="display:flex;justify-content:space-between;color:#FF8800;">
                    <span>❌ Nicht einsatzfähig:</span>
                    <span id="unfit-count">0</span>
                </div>
                <div style="display:flex;justify-content:space-between;color:#FF3333;">
                    <span>🚑 Verletzt:</span>
                    <span id="casualty-count">0</span>
                </div>
            </div>
        `;
        document.body.appendChild(this.statsOverlay);
    }

    updatePersonnel(personnelData) {
        // 1. Neue Personen hinzufügen
        for (const person of personnelData) {
            if (!this.personnelMarkers.has(person.id)) {
                this.createPersonnelMarker(person);
            } else {
                this.updatePersonnelMarker(person);
            }
        }

        // 2. Entfernte Personen löschen
        const currentIds = new Set(personnelData.map(p => p.id));
        for (const [id, marker] of this.personnelMarkers) {
            if (!currentIds.has(id)) {
                this.removePersonnelMarker(id);
            }
        }

        // 3. Stats aktualisieren
        this.updateStats(personnelData);
    }

    createPersonnelMarker(person) {
        const group = new THREE.Group();
        const pos = new THREE.Vector3(
            person.position?.x || 0,
            person.position?.y || 1.2,
            person.position?.z || 0
        );

        // ─── Körper (Zylinder) ────────────────────────────────────
        const bodyGeo = new THREE.CylinderGeometry(0.25, 0.35, 0.7, 8);
        const color = this.stressColors[person.stressLevel] || 0x888888;
        const bodyMat = new THREE.MeshStandardMaterial({
            color: color,
            emissive: color,
            emissiveIntensity: 0.2,
            transparent: true,
            opacity: 0.9
        });
        const body = new THREE.Mesh(bodyGeo, bodyMat);
        body.position.y = 0.35;
        group.add(body);

        // ─── Kopf (Kugel) ──────────────────────────────────────────
        const headGeo = new THREE.SphereGeometry(0.18, 8, 8);
        const headMat = new THREE.MeshStandardMaterial({
            color: 0xFFDDBB,
            emissive: color,
            emissiveIntensity: 0.1
        });
        const head = new THREE.Mesh(headGeo, headMat);
        head.position.y = 0.9;
        group.add(head);

        // ─── Status-Ring (pulsierend) ────────────────────────────
        const ringGeo = new THREE.RingGeometry(0.3, 0.4, 16);
        const ringMat = new THREE.MeshBasicMaterial({
            color: color,
            transparent: true,
            opacity: 0.4,
            side: THREE.DoubleSide
        });
        const ring = new THREE.Mesh(ringGeo, ringMat);
        ring.position.y = 0.1;
        ring.rotation.x = -Math.PI / 2;
        ring.name = 'pulse_ring';
        group.add(ring);

        // ─── CSS2D-Label ──────────────────────────────────────────
        const div = document.createElement('div');
        div.style.cssText = `
            color: white;
            font-size: 11px;
            font-family: 'Segoe UI', sans-serif;
            text-align: center;
            background: rgba(0,0,0,0.6);
            padding: 4px 8px;
            border-radius: 4px;
            border: 1px solid ${this.stressColors[person.stressLevel] ? 
                '#' + this.stressColors[person.stressLevel].toString(16).padStart(6, '0') : 
                '#888888'};
            backdrop-filter: blur(4px);
            pointer-events: auto;
            cursor: pointer;
        `;
        div.innerHTML = `
            <div style="font-weight:bold;">${person.callSign || person.name}</div>
            <div style="font-size:9px;opacity:0.7;">${person.role}</div>
            <div style="font-size:10px;">
                ❤️ ${person.heartRate} bpm | ⚡ ${(person.combatReadiness * 100).toFixed(0)}%
            </div>
            <div style="font-size:8px;margin-top:2px;color:${this.stressColors[person.stressLevel] ? 
                '#' + this.stressColors[person.stressLevel].toString(16).padStart(6, '0') : 
                '#888888'}">
                ${person.stressLevel} ${this.roleSymbols[person.role] || ''}
            </div>
        `;
        div.addEventListener('click', () => {
            if (this.onPersonnelClick) {
                this.onPersonnelClick(person);
            }
        });
        const label = new CSS2DObject(div);
        label.position.set(0, 1.4, 0);
        group.add(label);

        // ─── Position ──────────────────────────────────────────────
        group.position.copy(pos);
        group.userData.personnelId = person.id;
        group.userData.person = person;

        this.scene.add(group);
        this.personnelMarkers.set(person.id, group);
    }

    updatePersonnelMarker(person) {
        const group = this.personnelMarkers.get(person.id);
        if (!group) return;

        // Position aktualisieren
        if (person.position) {
            group.position.set(person.position.x, person.position.y + 1.2, person.position.z);
        }

        // Farbe aktualisieren
        const color = this.stressColors[person.stressLevel] || 0x888888;
        const body = group.children[0];
        if (body) {
            body.material.color.setHex(color);
            body.material.emissive.setHex(color);
        }

        // Ring aktualisieren
        const ring = group.getObjectByName('pulse_ring');
        if (ring) {
            ring.material.color.setHex(color);
        }

        // Label aktualisieren
        const label = group.children.find(c => c.isCSS2DObject);
        if (label) {
            const borderColor = this.stressColors[person.stressLevel] ?
                '#' + this.stressColors[person.stressLevel].toString(16).padStart(6, '0') :
                '#888888';
            label.element.style.borderColor = borderColor;
            label.element.innerHTML = `
                <div style="font-weight:bold;">${person.callSign || person.name}</div>
                <div style="font-size:9px;opacity:0.7;">${person.role}</div>
                <div style="font-size:10px;">
                    ❤️ ${person.heartRate} bpm | ⚡ ${(person.combatReadiness * 100).toFixed(0)}%
                </div>
                <div style="font-size:8px;margin-top:2px;color:${borderColor}">
                    ${person.stressLevel} ${this.roleSymbols[person.role] || ''}
                </div>
            `;
        }

        group.userData.person = person;
    }

    removePersonnelMarker(personnelId) {
        const group = this.personnelMarkers.get(personnelId);
        if (group) {
            this.scene.remove(group);
            this.personnelMarkers.delete(personnelId);
        }
    }

    updateStats(personnel) {
        const total = personnel.length;
        const operational = personnel.filter(p => p.status === 'OPERATIONAL').length;
        const degraded = personnel.filter(p => p.status === 'DEGRADED').length;
        const unfit = personnel.filter(p => p.status === 'UNFIT').length;
        const casualty = personnel.filter(p => p.status === 'CASUALTY' || p.status === 'KIA').length;

        const setText = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.textContent = val;
        };

        setText('total-count', total);
        setText('operational-count', operational);
        setText('degraded-count', degraded);
        setText('unfit-count', unfit);
        setText('casualty-count', casualty);
    }

    startAnimation() {
        const animate = () => {
            requestAnimationFrame(animate);
            
            const time = Date.now() / 1000;
            for (const [id, group] of this.personnelMarkers) {
                // Ring pulsieren lassen
                const ring = group.getObjectByName('pulse_ring');
                if (ring) {
                    const pulse = 0.4 + 0.6 * Math.sin(time * 1.2 + id.hashCode ? id.hashCode() : 0);
                    ring.material.opacity = pulse * 0.6;
                    const scale = 1 + 0.3 * Math.sin(time * 0.8 + (id.hashCode ? id.hashCode() : 0));
                    ring.scale.set(scale, scale, scale);
                }
                
                // Kopf leichten wiegen
                const head = group.children[1];
                if (head) {
                    head.rotation.z = 0.1 * Math.sin(time * 0.5 + (id.hashCode ? id.hashCode() : 0));
                }
            }
        };
        animate();
    }

    // ─── Resize ──────────────────────────────────────────────────

    resize(width, height) {
        if (this.labelRenderer) {
            this.labelRenderer.setSize(width, height);
        }
    }

    // ─── Dispose ─────────────────────────────────────────────────

    dispose() {
        if (this.labelRenderer) {
            this.labelRenderer.domElement.remove();
            this.labelRenderer.dispose();
        }
        if (this.statsOverlay) {
            this.statsOverlay.remove();
        }
        for (const [id, group] of this.personnelMarkers) {
            this.scene.remove(group);
        }
        this.personnelMarkers.clear();
    }
}