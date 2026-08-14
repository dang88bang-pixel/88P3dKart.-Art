import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';

// ─── Szene ──────────────────────────────────────────────────────
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x111122);

const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 500);
camera.position.set(15, 10, 20);

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
document.body.appendChild(renderer.domElement);

// ─── Adaptive Renderqualität (docs/RESOURCE_OPT.md §6) ─────────
// FPS-basiertes PixelRatio-Management: bei < 24 FPS wird die Auflösung in
// 0,25-Schritten gesenkt (min. 0,75), bei > 55 FPS wieder angehoben —
// korrekte Umsetzung des v11-Renderbudgets (die Spec-Variante nutzte
// doppeltes renderer.setAnimationLoop, was wirkungslos ist).
const MAX_PIXEL_RATIO = Math.min(window.devicePixelRatio, 2);
const MIN_PIXEL_RATIO = 0.75;
let currentPixelRatio = MAX_PIXEL_RATIO;
let fpsWindowStart = 0;
let fpsFrameCount = 0;

function adaptRenderQuality(timeMs) {
    fpsFrameCount++;
    if (timeMs - fpsWindowStart < 2000) return;
    const fps = (fpsFrameCount * 1000) / (timeMs - fpsWindowStart);
    if (fps < 24 && currentPixelRatio > MIN_PIXEL_RATIO) {
        currentPixelRatio = Math.max(MIN_PIXEL_RATIO, currentPixelRatio - 0.25);
        renderer.setPixelRatio(currentPixelRatio);
        renderer.setSize(window.innerWidth, window.innerHeight);
    } else if (fps > 55 && currentPixelRatio < MAX_PIXEL_RATIO) {
        currentPixelRatio = Math.min(MAX_PIXEL_RATIO, currentPixelRatio + 0.25);
        renderer.setPixelRatio(currentPixelRatio);
        renderer.setSize(window.innerWidth, window.innerHeight);
    }
    fpsWindowStart = timeMs;
    fpsFrameCount = 0;
}

const labelRenderer = new CSS2DRenderer();
labelRenderer.setSize(window.innerWidth, window.innerHeight);
labelRenderer.domElement.style.position = 'absolute';
labelRenderer.domElement.style.top = '0px';
labelRenderer.domElement.style.left = '0px';
labelRenderer.domElement.style.pointerEvents = 'none';
document.body.appendChild(labelRenderer.domElement);

const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(0, 0, 0);
controls.enableDamping = true;

// ─── Licht & Hilfslinien ────────────────────────────────────────
scene.add(new THREE.AmbientLight(0x404060));
const dirLight = new THREE.DirectionalLight(0xffffff, 1);
dirLight.position.set(10, 20, 5);
scene.add(dirLight);
scene.add(new THREE.HemisphereLight(0x445566, 0x221133, 0.7));
scene.add(new THREE.GridHelper(40, 20, 0x88aaff, 0x335577));

// ─── Punktwolke ────────────────────────────────────────────────
const MAX_POINTS = 150000;
const geometry = new THREE.BufferGeometry();
const positions = new Float32Array(MAX_POINTS * 3);
geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
geometry.setDrawRange(0, 0);

const pointCloud = new THREE.Points(geometry, new THREE.PointsMaterial({
    color: 0x00ff88, size: 0.15, sizeAttenuation: true,
    transparent: true, opacity: 0.9,
}));
pointCloud.frustumCulled = true;
scene.add(pointCloud);

// ─── Avatare ────────────────────────────────────────────────────
const avatars = [];
const AVATAR_COLORS = [0xff3333, 0x33ff33, 0x33aaff, 0xffaa33, 0xcc33ff];

function createAvatar(color, label = 'Person') {
    const group = new THREE.Group();
    const body = new THREE.Mesh(
        new THREE.CylinderGeometry(0.3, 0.4, 0.8, 8),
        new THREE.MeshStandardMaterial({ color })
    );
    body.position.y = 0.4;
    group.add(body);

    const head = new THREE.Mesh(
        new THREE.SphereGeometry(0.2, 8, 8),
        new THREE.MeshStandardMaterial({ color: 0xffccaa })
    );
    head.position.y = 1.0;
    group.add(head);

    const div = document.createElement('div');
    div.textContent = label;
    div.style.cssText = 'color:white;font-size:12px;font-weight:bold;text-shadow:1px 1px 3px black;background:rgba(0,0,0,0.5);padding:2px 6px;border-radius:4px;';
    const labelObj = new CSS2DObject(div);
    labelObj.position.y = 1.4;
    group.add(labelObj);

    group.scale.set(0.5, 0.5, 0.5);
    scene.add(group);
    avatars.push(group);
    return group;
}

for (let i = 0; i < 5; i++) {
    const angle = (i / 5) * Math.PI * 2;
    const radius = 3 + Math.random() * 2;
    const avatar = createAvatar(AVATAR_COLORS[i % AVATAR_COLORS.length], `Agent ${i + 1}`);
    avatar.position.set(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    avatar.userData = { speed: 0.5 + Math.random() * 0.5, phase: Math.random() * Math.PI * 2 };
}

// ─── Aura RF-Feld (RTI-Voxel & Heatmap) ────────────────────────
// docs/AURA.md §2/§4: halbtransparente Voxel (Dämpfung) und extrudierte
// Zellen (Signalstärke) — InstancedMesh für tausende Instanzen.
const rfGroup = new THREE.Group();
rfGroup.visible = false;
scene.add(rfGroup);

const MAX_RF_INSTANCES = 6000;
const DUMMY = new THREE.Object3D();
const COLOR_TMP = new THREE.Color();

function colorFor(value, minV, maxV) {
    const t = maxV > minV ? Math.min(1, Math.max(0, (value - minV) / (maxV - minV))) : 0.5;
    return COLOR_TMP.setHSL(0.66 - 0.66 * t, 0.9, 0.5); // blau → rot
}

// RTI-Voxel-Layer
const voxelGeo = new THREE.BoxGeometry(1, 1, 1);
const voxelMat = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.45 });
const voxelMesh = new THREE.InstancedMesh(voxelGeo, voxelMat, MAX_RF_INSTANCES);
voxelMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
voxelMesh.count = 0;
voxelMesh.frustumCulled = false;
rfGroup.add(voxelMesh);

// Heatmap-Layer (extrudierte Zellen)
const cellGeo = new THREE.BoxGeometry(1, 1, 1);
const cellMat = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.35 });
const cellMesh = new THREE.InstancedMesh(cellGeo, cellMat, MAX_RF_INSTANCES);
cellMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
cellMesh.count = 0;
cellMesh.frustumCulled = false;
rfGroup.add(cellMesh);

function applyAuraVoxels(voxels) {
    const sorted = [...voxels].sort((a, b) => b.attenuation - a.attenuation);
    const use = sorted.slice(0, MAX_RF_INSTANCES);
    const maxAtt = use.length ? use[0].attenuation : 1;
    const minAtt = use.length ? use[use.length - 1].attenuation : 0;

    use.forEach((v, i) => {
        const scale = 0.4; // Voxel-Kantenlänge
        DUMMY.position.set(v.x, v.y, v.z);
        DUMMY.scale.setScalar(scale);
        DUMMY.updateMatrix();
        voxelMesh.setMatrixAt(i, DUMMY.matrix);
        voxelMesh.setColorAt(i, colorFor(v.attenuation, minAtt, maxAtt));
    });
    voxelMesh.count = use.length;
    voxelMesh.instanceMatrix.needsUpdate = true;
    if (voxelMesh.instanceColor) voxelMesh.instanceColor.needsUpdate = true;
    updateAuraStatus(`📡 RF-Feld: ${use.length} RTI-Voxel (${maxAtt.toFixed(1)} dB max)`);
    rfGroup.visible = rfVisible;
}

function applyAuraHeatmap(cells) {
    const use = cells.slice(0, MAX_RF_INSTANCES);
    const dbms = use.map(c => c.dbm);
    const maxDbm = dbms.length ? Math.max(...dbms) : -30;
    const minDbm = dbms.length ? Math.min(...dbms) : -90;

    use.forEach((c, i) => {
        const h = Math.max(0.05, c.height || 0.5);
        DUMMY.position.set(c.x, c.y, (c.z || 0) + h / 2);
        DUMMY.scale.set(c.size || 1, h, c.size || 1);
        DUMMY.updateMatrix();
        cellMesh.setMatrixAt(i, DUMMY.matrix);
        cellMesh.setColorAt(i, colorFor(c.dbm, minDbm, maxDbm));
    });
    cellMesh.count = use.length;
    cellMesh.instanceMatrix.needsUpdate = true;
    if (cellMesh.instanceColor) cellMesh.instanceColor.needsUpdate = true;
    updateAuraStatus(`📡 RF-Feld: ${use.length} Heatmap-Zellen (${minDbm.toFixed(0)}…${maxDbm.toFixed(0)} dBm)`);
    rfGroup.visible = rfVisible;
}

function updateAuraStatus(text) {
    const el = document.getElementById('aura-status');
    if (el) el.textContent = text;
}

let rfVisible = false;
const btnAura = document.getElementById('btn-aura');
if (btnAura) {
    btnAura.addEventListener('click', () => {
        rfVisible = !rfVisible;
        rfGroup.visible = rfVisible;
        btnAura.classList.toggle('active', rfVisible);
        if (!rfVisible) updateAuraStatus('📡 RF-Feld: ausgeblendet');
    });
}

// ─── Triangulation (Wi-Fi RTT / BLE-Anker) ───────────────────────
// docs/TRIANGULATION.md §8: Anker-Ringe + Geräteposition im Weltraum.
// Konvention: Schätzungen (x, y_horizontal, z_Höhe) → Three.js (x, y=Höhe, z=horizontal).
const triGroup = new THREE.Group();
scene.add(triGroup);

const anchorMeshes = [];
const deviceMarker = new THREE.Mesh(
    new THREE.ConeGeometry(0.35, 0.9, 8),
    new THREE.MeshBasicMaterial({ color: 0xffdd00 })
);
deviceMarker.position.set(0, 0.45, 0);
triGroup.add(deviceMarker);

const deviceLabelDiv = document.createElement('div');
deviceLabelDiv.textContent = 'CT45P';
deviceLabelDiv.style.cssText = 'color:white;font-size:11px;font-weight:bold;text-shadow:1px 1px 3px black;background:rgba(255,200,0,0.35);padding:1px 6px;border-radius:4px;';
const deviceLabel = new CSS2DObject(deviceLabelDiv);
deviceLabel.position.set(0, 1.4, 0);
triGroup.add(deviceLabel);

function applyTriangulationAnchors(anchors) {
    while (anchorMeshes.length) {
        const m = anchorMeshes.pop();
        triGroup.remove(m);
    }
    anchors.forEach(a => {
        const ring = new THREE.Mesh(
            new THREE.TorusGeometry(0.5, 0.06, 8, 32),
            new THREE.MeshBasicMaterial({ color: a.type === 'wifi' ? 0x00c8a0 : 0x4488ff })
        );
        ring.rotation.x = Math.PI / 2;
        ring.position.set(a.x, (a.z || 0) + 0.1, a.y);
        triGroup.add(ring);
        anchorMeshes.push(ring);

        const div = document.createElement('div');
        div.textContent = a.id;
        div.style.cssText = 'color:white;font-size:10px;text-shadow:1px 1px 2px black;background:rgba(0,0,0,0.5);padding:1px 5px;border-radius:3px;';
        const label = new CSS2DObject(div);
        label.position.set(a.x, (a.z || 0) + 1.2, a.y);
        triGroup.add(label);
        anchorMeshes.push(label);
    });
}

function applyPositionUpdate(p) {
    deviceMarker.position.set(p.x, p.z || 0, p.y);
    deviceLabel.position.set(p.x, (p.z || 0) + 1.4, p.y);
    updateTriStatus(
        `📶 ${p.source}: (${p.x.toFixed(1)}, ${p.y.toFixed(1)}) ±${p.accuracy_m.toFixed(1)} m`
    );
}

function updateTriStatus(text) {
    const el = document.getElementById('tri-status');
    if (el) el.textContent = text;
}

let triVisible = true;
const btnTri = document.getElementById('btn-triangulation');
if (btnTri) {
    btnTri.addEventListener('click', () => {
        triVisible = !triVisible;
        triGroup.visible = triVisible;
        btnTri.classList.toggle('active', triVisible);
    });
}

// ─── Network3D: Topologie-Layer (docs/NETWORK3D.md) ─────────────
// 3d-force-graph-Muster in nativem Three.js: Nodes (Typ-/Statusfarben),
// Spline-Edges (Auslastungsfarben), Flow-Partikel entlang der Kanten,
// kritische Nodes pulsieren (Spatial Alert).
const netGroup = new THREE.Group();
scene.add(netGroup);

const netNodes = new Map();   // id → mesh
const netEdges = new Map();   // "src|dst" → { curve, line, particles: [...] }
const netHeatBars = new Map(); // nodeId → Heatmap-Säule
const NODE_COLORS = {
    router: 0x4488ff, switch: 0x44aaff, firewall: 0xff8800,
    vm: 0xaa44ff, container: 0xff44aa, cloud: 0xffffff,
    server: 0x88cc44, sensor: 0x44ff88,
};
const MAX_NET_NODES = 300;

// Schwellen identisch zu Kotlin/Python (NetworkTraffic)
const BW_HIGH = 100, BW_MEDIUM = 50, BW_LOW = 20, BW_MIN = 10;
const LAT_HIGH = 100, LAT_MEDIUM = 40;
const MAX_PARTICLES_PER_EDGE = 5;

const netParticleGeo = new THREE.SphereGeometry(0.06, 6, 6);
const netParticleMat = new THREE.MeshBasicMaterial({ color: 0x00ffcc });
const netNodeGeo = new THREE.SphereGeometry(0.25, 12, 12);

function nodeColor(type, status) {
    if (status === 'critical') return 0xff3333;
    if (status === 'warning') return 0xffcc00;
    if (status === 'down') return 0x555555;
    return NODE_COLORS[type] || 0x888888;
}

/** Zentrale Bandbreiten-/Latenz-Farbe (identisch zu NetworkTraffic.kt/py). */
function trafficColorHex(bandwidthMbps, latencyMs) {
    if (latencyMs > LAT_HIGH) return 0xff3333;
    if (bandwidthMbps > BW_HIGH) return 0xff3333;
    if (bandwidthMbps > BW_MEDIUM || latencyMs > LAT_MEDIUM) return 0xff8800;
    if (bandwidthMbps > BW_LOW) return 0xffff00;
    if (bandwidthMbps > BW_MIN) return 0x44ff88;
    return 0x4488ff;
}

function particleCountFor(bandwidthMbps) {
    if (bandwidthMbps < 1) return 1;
    return Math.min(MAX_PARTICLES_PER_EDGE, Math.max(1, Math.floor(bandwidthMbps / 10)));
}

function applyNetworkTopology(topo) {
    // Aufräumen
    netNodes.forEach(mesh => netGroup.remove(mesh));
    netNodes.clear();
    netEdges.forEach(edge => edge.particles.forEach(p => netGroup.remove(p)));
    netEdges.forEach(edge => netGroup.remove(edge.line));
    netEdges.clear();
    netHeatBars.forEach(bar => netGroup.remove(bar));
    netHeatBars.clear();

    const nodes = (topo.nodes || []).slice(0, MAX_NET_NODES);
    const nodeById = new Map(nodes.map(n => [n.id, n]));

    nodes.forEach(n => {
        const mesh = new THREE.Mesh(
            netNodeGeo,
            new THREE.MeshBasicMaterial({ color: nodeColor(n.type, n.status) })
        );
        mesh.position.set(n.x, n.z || 0, n.y);
        mesh.userData = { id: n.id, status: n.status, phase: Math.random() * Math.PI * 2, trafficActive: false };
        netGroup.add(mesh);
        netNodes.set(n.id, mesh);
    });

    (topo.edges || []).forEach(e => {
        const a = nodeById.get(e.source);
        const b = nodeById.get(e.target);
        if (!a || !b) return;
        const pA = new THREE.Vector3(a.x, a.z || 0, a.y);
        const pB = new THREE.Vector3(b.x, b.z || 0, b.y);
        const mid = new THREE.Vector3().addVectors(pA, pB).multiplyScalar(0.5);
        mid.y += pA.distanceTo(pB) * 0.15;
        const curve = new THREE.QuadraticBezierCurve3(pA, mid, pB);
        const line = new THREE.Line(
            new THREE.BufferGeometry().setFromPoints(curve.getPoints(16)),
            new THREE.LineBasicMaterial({
                color: e.utilization > 0.8 ? 0xff3333 : e.utilization > 0.5 ? 0xffcc00 : 0x44ff88,
                transparent: true, opacity: 0.6,
            })
        );
        netGroup.add(line);
        const particles = Array.from({ length: MAX_PARTICLES_PER_EDGE }, (_, i) => {
            const p = new THREE.Mesh(netParticleGeo, netParticleMat);
            p.userData = { t: i / MAX_PARTICLES_PER_EDGE, speed: 0.005 + (e.utilization || 0) * 0.02, active: i < 3 };
            p.visible = i < 3;
            netGroup.add(p);
            return p;
        });
        const key = `${e.source}|${e.target}`;
        netEdges.set(key, { curve, line, particles, sourceId: e.source, targetId: e.target });
    });

    updateNetStatus(`🌐 ${nodes.length} Nodes · ${(topo.edges || []).length} Links`);
}

/**
 * Live-Traffic-Update (v14.1.0): Flüsse steuern Linienfarbe, Partikel-
 * anzahl/-geschwindigkeit/-farbe, Knoten-Aktivität und Heatmap-Säulen.
 */
function applyNetworkTraffic(payload) {
    const flows = payload.flows || [];
    const activity = payload.activity || {};
    const heatmap = payload.heatmap || {};

    // 1) Flüsse auf die Kanten abbilden (bidirektional)
    const edgeTraffic = new Map();
    flows.forEach(flow => {
        const bw = flow.bandwidth_mbps || 0;
        const lat = flow.latency_ms || 0;
        for (const key of [`${flow.source}|${flow.target}`, `${flow.target}|${flow.source}`]) {
            const prev = edgeTraffic.get(key);
            if (!prev || bw > prev.bandwidth_mbps) {
                edgeTraffic.set(key, { bandwidth_mbps: bw, latency_ms: lat });
            }
        }
    });

    netEdges.forEach((edge, key) => {
        const traffic = edgeTraffic.get(key);
        const bw = traffic ? traffic.bandwidth_mbps : 0;
        const lat = traffic ? traffic.latency_ms : 0;
        const color = trafficColorHex(bw, lat);
        edge.line.material.color.setHex(color);
        edge.line.material.opacity = traffic ? Math.min(0.9, 0.35 + bw / 200) : 0.25;
        const count = particleCountFor(bw);
        edge.particles.forEach((p, i) => {
            if (i < count && traffic) {
                p.userData.active = true;
                p.userData.speed = 0.005 + bw / 1000; // ∝ Bandbreite (Spec-Formel, szenenskaliert)
                p.visible = true;
                p.material.color.setHex(color);
            } else {
                p.userData.active = false;
                p.visible = false;
            }
        });
    });

    // 2) Knoten-Aktivität + Latenz-Alarm
    netNodes.forEach(mesh => {
        const nodeActivity = activity[mesh.userData.id];
        mesh.userData.trafficActive = !!nodeActivity && nodeActivity.active;
        if (nodeActivity && nodeActivity.max_latency_ms > LAT_HIGH) {
            mesh.material.color.setHex(0xff3333);
        }
    });

    // 3) Bandbreiten-Heatmap: Säulen unter den Knoten
    netHeatBars.forEach(bar => netGroup.remove(bar));
    netHeatBars.clear();
    for (const [nodeId, value] of Object.entries(heatmap)) {
        const mesh = netNodes.get(nodeId);
        if (!mesh || value <= 0) continue;
        const nodeActivity = activity[nodeId];
        const color = nodeActivity
            ? trafficColorHex(nodeActivity.total_mbps, nodeActivity.max_latency_ms)
            : 0x4488ff;
        const bar = new THREE.Mesh(
            new THREE.BoxGeometry(0.12, 1, 0.12),
            new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.7 })
        );
        const height = Math.max(0.1, value * 3); // max. 3 Welteinheiten
        bar.scale.set(1, height, 1);
        bar.position.copy(mesh.position);
        bar.position.y -= 0.4 + height / 2; // Säule unterhalb des Knotens
        netGroup.add(bar);
        netHeatBars.set(nodeId, bar);
    }

    const totalMbps = flows.reduce((sum, f) => sum + (f.bandwidth_mbps || 0), 0);
    updateNetStatus(`🌐 Live: ${flows.length} Flüsse · Σ ${totalMbps.toFixed(0)} Mbit/s`);
}

function updateNetStatus(text) {
    const el = document.getElementById('net-status');
    if (el) el.textContent = text;
}

function animateNetwork(time) {
    const tSec = time * 0.001;
    netNodes.forEach(mesh => {
        if (mesh.userData.status === 'critical') {
            const pulse = 1 + 0.35 * Math.sin(tSec * 5 + mesh.userData.phase);
            mesh.scale.setScalar(pulse);
        } else if (mesh.userData.trafficActive) {
            // Aktiver Datenfluss → sanfter Aktivitätspuls (Spec: ActivityIndicator)
            const pulse = 1 + 0.15 * Math.sin(tSec * 6 + mesh.userData.phase);
            mesh.scale.setScalar(pulse);
        }
    });
    netEdges.forEach(edge => {
        edge.particles.forEach(p => {
            if (!p.userData.active) return;
            const d = p.userData;
            d.t = (d.t + d.speed) % 1;
            p.position.copy(edge.curve.getPoint(d.t));
        });
    });
}

let netVisible = true;
const btnNet = document.getElementById('btn-network');
if (btnNet) {
    btnNet.addEventListener('click', () => {
        netVisible = !netVisible;
        netGroup.visible = netVisible;
        btnNet.classList.toggle('active', netVisible);
    });
}

// ─── Grundriss-Integration (docs/FLOORPLAN.md) ─────────────────
// Gebäudeumrisse (GeoJSON vom Edge-Agent/Overpass) werden lokal
// (Zentroid der ersten Feature) zentriert, zu Metern konvertiert und
// mit Etagenhöhe extrudiert; Kanten + Namens-Labels ergänzen das Modell.
const floorGroup = new THREE.Group();
scene.add(floorGroup);
const MAX_FLOOR_FEATURES = 300;
const MAX_FLOOR_LABELS = 40;

const METERS_PER_DEG_LAT = 110_540.0;

function applyFloorPlanBuildings(fc) {
    while (floorGroup.children.length) {
        const child = floorGroup.children.pop();
        floorGroup.remove(child);
        if (child.geometry) child.geometry.dispose();
        if (child.material) child.material.dispose();
    }
    const features = (fc.features || []).slice(0, MAX_FLOOR_FEATURES);
    if (features.length === 0) {
        updateFloorStatus('🌆 Grundriss: keine Gebäude gefunden');
        return;
    }

    // Ursprung: Zentroid des ersten Gebäuderinges
    const firstRing = features[0]?.geometry?.coordinates?.[0];
    let cLon = 0, cLat = 0;
    if (firstRing) {
        for (const [lon, lat] of firstRing) { cLon += lon; cLat += lat; }
        cLon /= firstRing.length; cLat /= firstRing.length;
    }
    const lonScale = METERS_PER_DEG_LAT * Math.cos((cLat * Math.PI) / 180);
    let labelCount = 0;
    let maxLevels = 0;

    for (const feature of features) {
        const ring = feature.geometry?.coordinates?.[0];
        if (!ring || ring.length < 4) continue;
        const shape = new THREE.Shape();
        ring.forEach(([lon, lat], i) => {
            const x = (lon - cLon) * lonScale;
            const z = -(lat - cLat) * METERS_PER_DEG_LAT;
            if (i === 0) shape.moveTo(x, z); else shape.lineTo(x, z);
        });
        shape.closePath();
        const props = feature.properties || {};
        const height = Math.max(1, props.height || (props.levels || 1) * 3.2);
        maxLevels = Math.max(maxLevels, props.levels || 1);

        const geometry = new THREE.ExtrudeGeometry(shape, {
            steps: 1, depth: height, bevelEnabled: false,
        });
        const mesh = new THREE.Mesh(
            geometry,
            new THREE.MeshBasicMaterial({
                color: 0x4488ff, transparent: true, opacity: 0.35, side: THREE.DoubleSide,
            })
        );
        floorGroup.add(mesh);

        const edges = new THREE.LineSegments(
            new THREE.EdgesGeometry(geometry),
            new THREE.LineBasicMaterial({ color: 0x00ffcc, transparent: true, opacity: 0.6 })
        );
        floorGroup.add(edges);

        if (props.name && labelCount < MAX_FLOOR_LABELS) {
            const div = document.createElement('div');
            div.textContent = props.name;
            div.style.cssText = 'color:white;font-size:11px;text-shadow:1px 1px 2px black;background:rgba(0,0,0,0.55);padding:1px 6px;border-radius:3px;';
            const label = new CSS2DObject(div);
            label.position.set(0, height + 1, 0);
            mesh.add(label);
            labelCount++;
        }
    }
    updateFloorStatus(`🌆 Grundriss: ${features.length} Gebäude (max. ${maxLevels} Etagen)`);
    floorGroup.visible = floorVisible;
}

function updateFloorStatus(text) {
    const el = document.getElementById('floorplan-status');
    if (el) el.textContent = text;
}

let floorVisible = true;
const btnFloor = document.getElementById('btn-floorplan');
if (btnFloor) {
    btnFloor.addEventListener('click', () => {
        floorVisible = !floorVisible;
        floorGroup.visible = floorVisible;
        btnFloor.classList.toggle('active', floorVisible);
    });
}

// ─── Geräteinteraktion (docs/DEVICE_INTERACTION.md) ────────────
// Geräte-Registry des Edge-Agents: Marker mit Typ-/Statusfarben, Labels,
// Raycast-Auswahl (Selektionsring pulsiert), Kontextmenü mit
// capability-geprüften Aktionen, Layer-Sichtbarkeit je Kategorie.
const deviceGroup = new THREE.Group();
scene.add(deviceGroup);

const deviceMarkers = new Map();      // id → { group, mesh }
const devicePickMeshes = [];
const DEVICE_CATEGORY_COLORS = {
    SENSOR: 0x44ff88, NETWORK: 0x4488ff, ACTUATOR: 0xff8800,
    VEHICLE: 0xff44ff, OTHER: 0x888888,
};
const DEVICE_ICONS = {
    BLE_TOKEN: '🔵', UWB_SENSOR: '📡', MMWAVE_RADAR: '📡', LIDAR: '🔴',
    WIFI_AP: '📶', WIFI_CLIENT: '📱', BLE_DEVICE: '🔵', ZIGBEE_NODE: '🔶',
    LORA_GATEWAY: '📻', SMART_LIGHT: '💡', SMART_LOCK: '🔒',
    EBIKE: '🚲', ESCOOTER: '🛴', EROLLER: '🛵', EV: '🚗',
};
const MAX_DEVICE_MARKERS = 250;

const deviceSelectionRing = new THREE.Mesh(
    new THREE.SphereGeometry(0.35, 16, 16),
    new THREE.MeshBasicMaterial({ color: 0x44ff88, wireframe: true, transparent: true, opacity: 0.8 })
);
deviceSelectionRing.visible = false;
deviceGroup.add(deviceSelectionRing);
let selectedDeviceId = null;
let deviceLayerVisibility = {}; // category → boolean

function applyDevices(payload) {
    const devices = (payload.devices || []).slice(0, MAX_DEVICE_MARKERS);
    const layers = payload.layers || {};
    deviceLayerVisibility = {};
    for (const layer of Object.values(layers)) {
        deviceLayerVisibility[layer.category] = layer.is_visible !== false;
    }
    const seen = new Set();
    devices.forEach(device => {
        seen.add(device.id);
        upsertDeviceMarker(device);
    });
    for (const id of [...deviceMarkers.keys()]) {
        if (!seen.has(id)) {
            const entry = deviceMarkers.get(id);
            deviceGroup.remove(entry.group);
            disposeMarker(entry);
            deviceMarkers.delete(id);
        }
    }
    updateDeviceVisibility();
    const byCat = {};
    devices.forEach(d => { byCat[d.category] = (byCat[d.category] || 0) + 1; });
    const parts = Object.entries(byCat).map(([c, n]) => `${n} ${c.toLowerCase()}`).join(' · ');
    updateDeviceStatus(`🛰️ Geräte: ${devices.length}${parts ? ` (${parts})` : ''}`);
}

function upsertDeviceMarker(device) {
    let entry = deviceMarkers.get(device.id);
    if (!entry) {
        const group = new THREE.Group();
        const color = DEVICE_CATEGORY_COLORS[device.category] || 0x888888;
        const mesh = new THREE.Mesh(
            new THREE.SphereGeometry(0.22, 12, 12),
            new THREE.MeshBasicMaterial({ color })
        );
        mesh.userData.deviceId = device.id;
        group.add(mesh);

        const statusDot = new THREE.Mesh(
            new THREE.SphereGeometry(0.08, 8, 8),
            new THREE.MeshBasicMaterial({ color: statusColor(device.status) })
        );
        statusDot.position.set(0.32, 0.32, 0);
        statusDot.userData.isStatusDot = true;
        group.add(statusDot);

        const div = document.createElement('div');
        div.textContent = device.name || device.id;
        div.style.cssText = 'color:white;font-size:11px;font-weight:bold;text-shadow:1px 1px 2px black;background:rgba(0,0,0,0.55);padding:1px 6px;border-radius:3px;';
        const label = new CSS2DObject(div);
        label.position.set(0, 0.55, 0);
        group.add(label);

        deviceGroup.add(group);
        deviceMarkers.set(device.id, { group, mesh, label });
        devicePickMeshes.push(mesh);
    }
    const marker = deviceMarkers.get(device.id);
    const pos = device.position || [0, 0, 0];
    // Welt-Konvention [x, y_horizontal, z_Höhe] → Three (x, Höhe, z)
    marker.group.position.set(pos[0] || 0, pos[2] || 0, pos[1] || 0);
    marker.mesh.material.color.setHex(DEVICE_CATEGORY_COLORS[device.category] || 0x888888);
    const dot = marker.group.children.find(c => c.userData.isStatusDot);
    if (dot) dot.material.color.setHex(statusColor(device.status));
    marker.group.userData = { device, category: device.category, visible: device.is_visible !== false };
    updateDeviceVisibility();
}

function statusColor(status) {
    if (status === 'ONLINE') return 0x44ff88;
    if (status === 'OFFLINE') return 0xff3333;
    if (status === 'UPDATING') return 0xffcc00;
    if (status === 'CONNECTING') return 0x00ffcc;
    return 0x888888;
}

function updateDeviceVisibility() {
    for (const [id, entry] of deviceMarkers) {
        const category = entry.group.userData.category;
        const categoryVisible = deviceLayerVisibility[category] !== false;
        entry.group.visible = categoryVisible && entry.group.userData.visible;
    }
}

function disposeMarker(entry) {
    entry.group.children.forEach(child => {
        if (child.geometry) child.geometry.dispose();
        if (child.material) child.material.dispose();
    });
    const idx = devicePickMeshes.indexOf(entry.mesh);
    if (idx >= 0) devicePickMeshes.splice(idx, 1);
}

function updateDeviceStatus(text) {
    const el = document.getElementById('device-status');
    if (el) el.textContent = text;
}

// ─── Auswahl (Raycast-Picking) + Kontextmenü ───────────────────
let devicePointerDown = null;

renderer.domElement.addEventListener('pointerdown', (e) => {
    devicePointerDown = { x: e.clientX, y: e.clientY, button: e.button };
});

renderer.domElement.addEventListener('pointerup', (e) => {
    if (!devicePointerDown) return;
    const moved = Math.hypot(e.clientX - devicePointerDown.x, e.clientY - devicePointerDown.y) > 5;
    const button = devicePointerDown.button;
    devicePointerDown = null;
    if (moved) return;

    const rect = renderer.domElement.getBoundingClientRect();
    const mouse = new THREE.Vector2(
        ((e.clientX - rect.left) / rect.width) * 2 - 1,
        -((e.clientY - rect.top) / rect.height) * 2 + 1,
    );
    const raycaster = new THREE.Raycaster();
    raycaster.setFromCamera(mouse, camera);
    const hits = raycaster.intersectObjects(devicePickMeshes, false);
    if (hits.length > 0) {
        const id = hits[0].object.userData.deviceId;
        selectDevice(id);
        if (button === 2) showDeviceContextMenu(id, e.clientX, e.clientY);
    } else {
        if (button === 2) {
            hideDeviceContextMenu();
        } else {
            deselectDevice();
        }
    }
});

renderer.domElement.addEventListener('contextmenu', (e) => e.preventDefault());

function selectDevice(id) {
    selectedDeviceId = id;
    const entry = deviceMarkers.get(id);
    if (entry) {
        deviceSelectionRing.position.copy(entry.group.position);
        deviceSelectionRing.visible = true;
        updateDeviceStatus(`🛰️ Ausgewählt: ${entry.group.userData.device.name || id}`);
    }
}

function deselectDevice() {
    selectedDeviceId = null;
    deviceSelectionRing.visible = false;
}

function animateDeviceLayer(time) {
    if (deviceSelectionRing.visible) {
        const pulse = 1 + 0.2 * Math.sin(time * 0.004);
        deviceSelectionRing.scale.setScalar(pulse);
    }
}

// ─── Kontextmenü (capability-geprüfte Aktionen) ────────────────
const deviceContextMenu = document.createElement('div');
deviceContextMenu.style.cssText = 'display:none;position:fixed;background:rgba(20,20,40,0.95);color:white;padding:8px 0;border-radius:8px;border:1px solid rgba(255,255,255,0.1);min-width:220px;z-index:1000;backdrop-filter:blur(10px);box-shadow:0 8px 32px rgba(0,0,0,0.5);';
document.body.appendChild(deviceContextMenu);

document.addEventListener('click', (e) => {
    if (!deviceContextMenu.contains(e.target)) hideDeviceContextMenu();
});

function hideDeviceContextMenu() {
    deviceContextMenu.style.display = 'none';
}

function showDeviceContextMenu(deviceId, clientX, clientY) {
    const entry = deviceMarkers.get(deviceId);
    if (!entry) return;
    const device = entry.group.userData.device;
    const capabilities = device.capabilities || [];
    const menu = deviceContextMenu;
    menu.innerHTML = '';

    const title = document.createElement('div');
    title.textContent = device.name || device.id;
    title.style.cssText = 'padding:8px 16px;font-weight:bold;border-bottom:1px solid rgba(255,255,255,0.1);';
    menu.appendChild(title);

    const capTypes = new Set(capabilities.map(c => c.type));
    const items = [];
    if (capTypes.has('READ_DATA')) {
        items.push({ label: '📊 Status abfragen', action: 'read_status', params: {} });
        items.push({ label: '📍 Position', action: 'locate', params: {} });
    }
    if (capTypes.has('EXECUTE_COMMAND')) {
        items.push({ label: '💡 LED umschalten', action: 'toggle_led', params: { state: 'true' } });
    }
    items.push({ label: '🔇 Ausblenden', action: 'set_visibility', params: { visible: 'false' }, danger: true });

    items.forEach(item => {
        const row = document.createElement('div');
        row.textContent = item.label;
        row.style.cssText = `padding:8px 16px;cursor:pointer;${item.danger ? 'color:#FF6666;' : ''}`;
        row.onmouseover = () => { row.style.backgroundColor = 'rgba(255,255,255,0.1)'; };
        row.onmouseout = () => { row.style.backgroundColor = 'transparent'; };
        row.onclick = () => {
            send('device_action', { device_id: deviceId, action: item.action, params: item.params });
            hideDeviceContextMenu();
        };
        menu.appendChild(row);
    });

    menu.style.display = 'block';
    menu.style.left = Math.min(clientX, window.innerWidth - 240) + 'px';
    menu.style.top = Math.min(clientY, window.innerHeight - 200) + 'px';
}

let devicesVisible = true;
const btnDevices = document.getElementById('btn-devices');
if (btnDevices) {
    btnDevices.addEventListener('click', () => {
        devicesVisible = !devicesVisible;
        deviceGroup.visible = devicesVisible;
        btnDevices.classList.toggle('active', devicesVisible);
    });
}

// ─── WebSocket ──────────────────────────────────────────────────
const WS_PROTO = window.location.protocol === 'https:' ? 'wss' : 'ws';
const WS_URL = `${WS_PROTO}://${window.location.host}/ws`;
let socket;

function connect() {
    socket = new WebSocket(WS_URL);
    socket.binaryType = 'arraybuffer';

    socket.onopen = () => {
        console.log('WebSocket verbunden');
        document.getElementById('status').textContent = '🟢 Verbunden';
    };

    socket.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            const view = new DataView(event.data);
            const numPoints = view.getUint32(0, true);
            if (numPoints === 0) return;
            const floatData = new Float32Array(event.data, 4, numPoints * 3);
            const count = Math.min(numPoints, MAX_POINTS);
            positions.set(floatData.slice(0, count * 3));
            geometry.setDrawRange(0, count);
            geometry.attributes.position.needsUpdate = true;
        } else {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'avatar_update') updateAvatars(msg.avatars);
                else if (msg.type === 'scenario_status') {
                    document.getElementById('scenario-status').textContent = msg.status;
                } else if (msg.type === 'aura_voxels' && msg.payload && msg.payload.voxels) {
                    applyAuraVoxels(msg.payload.voxels);
                } else if (msg.type === 'aura_heatmap' && msg.payload && msg.payload.cells) {
                    applyAuraHeatmap(msg.payload.cells);
                } else if (msg.type === 'triangulation_anchors' && msg.payload && msg.payload.anchors) {
                    applyTriangulationAnchors(msg.payload.anchors);
                } else if (msg.type === 'position_update' && msg.payload) {
                    applyPositionUpdate(msg.payload);
                } else if (msg.type === 'network_topology' && msg.payload) {
                    applyNetworkTopology(msg.payload);
                } else if (msg.type === 'topology_simulation' && msg.payload) {
                    updateNetStatus(
                        `🧪 What-If: ${msg.payload.failing_node} → ` +
                        `${msg.payload.affected_flows} betroffen, ` +
                        `${msg.payload.rerouted_flows} reroutet, ` +
                        `${msg.payload.unreachable_flows} unerreichbar`
                    );
                } else if (msg.type === 'network_traffic_update' && msg.payload) {
                    applyNetworkTraffic(msg.payload);
                } else if (msg.type === 'floorplan_buildings' && msg.payload) {
                    applyFloorPlanBuildings(msg.payload);
                } else if (msg.type === 'devices_update' && msg.payload) {
                    applyDevices(msg.payload);
                } else if (msg.type === 'device_action_result' && msg.payload) {
                    updateDeviceStatus(
                        `🛰️ Aktion ${msg.payload.action}: ${msg.payload.success ? '✅' : '❌'} ${msg.payload.message}`
                    );
                }
            } catch (_) { /* ignore */ }
        }
    };

    socket.onclose = () => {
        document.getElementById('status').textContent = '🔴 Getrennt';
        setTimeout(connect, 3000);
    };
}
connect();

function updateAvatars(avatarData) {
    avatarData.forEach((data, i) => {
        if (i < avatars.length) {
            avatars[i].position.set(data.x, 0, data.z);
        }
    });
}

// ─── LOD & Animation ───────────────────────────────────────────
function updateLOD() {
    const dist = camera.position.length();
    pointCloud.material.size = dist < 10 ? 0.12 : dist < 30 ? 0.25 : 0.5;
}

function animateAvatars(time) {
    avatars.forEach((avatar, i) => {
        const d = avatar.userData;
        const t = time * 0.001 * d.speed + d.phase;
        const radius = 2 + Math.sin(t * 0.5 + i) * 1.5;
        const angle = t + i * 1.2;
        const tx = Math.cos(angle) * radius;
        const tz = Math.sin(angle) * radius;
        avatar.position.x += (tx - avatar.position.x) * 0.02;
        avatar.position.z += (tz - avatar.position.z) * 0.02;
        avatar.rotation.y = Math.atan2(tx - avatar.position.x, tz - avatar.position.z);
    });
}

function send(type, payload) {
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type, payload }));
    }
}
document.getElementById('btn-evacuation').addEventListener('click', () =>
    send('scenario_start', { scenario: 'evacuation', persons: 50, smoke: 0.7 }));
document.getElementById('btn-tactical').addEventListener('click', () =>
    send('scenario_start', { scenario: 'tactical', units: 6 }));
document.getElementById('btn-stop').addEventListener('click', () => send('scenario_stop'));

// ─── Geräte-DB-Panel (docs/DEVICE_DATABASE.md §Erweiterte Kategorien) ───
const dbPanel = document.getElementById('devicedb-panel');
const dbSummary = document.getElementById('devicedb-summary');
const dbResults = document.getElementById('devicedb-results');
const dbCompanyInput = document.getElementById('devicedb-company');
const dbCompanyResult = document.getElementById('devicedb-company-result');
const dbSearchInput = document.getElementById('devicedb-search');
const dbTechSelect = document.getElementById('devicedb-tech');
const dbCatSelect = document.getElementById('devicedb-cat');
let dbOpen = false;

async function apiGet(path) {
    const response = await fetch(path);
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error((body && body.detail) || `HTTP ${response.status}`);
    }
    return response.json();
}

function dbEscape(text) {
    const div = document.createElement('div');
    div.textContent = String(text ?? '');
    return div.innerHTML;
}

async function loadDbMeta() {
    try {
        const status = await apiGet('/api/v1/devicedb/status');
        const cats = Object.entries(status.categories || {}).sort((a, b) => a[0].localeCompare(b[0]));
        const techs = Object.entries(status.technologies || {}).sort((a, b) => a[0].localeCompare(b[0]));
        dbTechSelect.innerHTML = '<option value="">Technologie: alle</option>' +
            techs.map(([t, n]) => `<option value="${dbEscape(t)}">${dbEscape(t)} (${n})</option>`).join('');
        dbCatSelect.innerHTML = '<option value="">Kategorie: alle</option>' +
            cats.map(([c, n]) => `<option value="${dbEscape(c)}">${dbEscape(c)} (${n})</option>`).join('');
        dbSummary.textContent =
            `📦 ${status.records} Geräte (${status.source}) · ${status.company_ids} Company-IDs · ` +
            `${status.oui_entries} OUI-Präfixe · ${status.gatt_services} GATT-Services`;
        runDbSearch();
    } catch (err) {
        dbSummary.textContent = `⚠️ Geräte-DB nicht erreichbar: ${err.message}`;
    }
}

async function runDbSearch() {
    const params = new URLSearchParams();
    const q = dbSearchInput.value.trim();
    if (q) params.set('q', q);
    if (dbTechSelect.value) params.set('technology', dbTechSelect.value);
    if (dbCatSelect.value) params.set('category', dbCatSelect.value);
    params.set('limit', '60');
    try {
        const data = await apiGet(`/api/v1/devicedb/search?${params.toString()}`);
        if (!data.results.length) {
            dbResults.innerHTML = '<div class="db-empty">Keine Treffer.</div>';
            return;
        }
        dbResults.innerHTML = data.results.map((r) => `
            <div class="db-item">
                <div class="db-name">${dbEscape(r.name)}</div>
                <div class="db-sub">${dbEscape(r.vendor)}${r.model ? ' · ' + dbEscape(r.model) : ''} · ${dbEscape(r.id)}</div>
                <div class="db-tags">
                    <span class="db-tag">${dbEscape(r.category)}</span>
                    ${r.technologies.map((t) => `<span class="db-tag">${dbEscape(t)}</span>`).join('')}
                    ${(r.frequency_bands || []).map((b) => `<span class="db-tag band">${dbEscape(b)}</span>`).join('')}
                    ${r.verified ? '' : '<span class="db-tag warn">⚠ unverifiziert</span>'}
                </div>
            </div>
        `).join('');
    } catch (err) {
        dbResults.innerHTML = `<div class="db-empty">Fehler: ${dbEscape(err.message)}</div>`;
    }
}

async function lookupCompanyId() {
    const value = dbCompanyInput.value.trim();
    dbCompanyResult.className = '';
    if (!value) { dbCompanyResult.textContent = ''; return; }
    try {
        const data = await apiGet(`/api/v1/devicedb/lookup/company/${encodeURIComponent(value)}`);
        dbCompanyResult.textContent = `✅ ${data.company_id} → ${data.name}`;
        dbCompanyResult.className = 'ok';
    } catch (err) {
        dbCompanyResult.textContent = `❌ ${err.message}`;
        dbCompanyResult.className = 'err';
    }
}

document.getElementById('btn-devicedb').addEventListener('click', () => {
    dbOpen = !dbOpen;
    dbPanel.classList.toggle('hidden', !dbOpen);
    document.getElementById('btn-devicedb').classList.toggle('active', dbOpen);
    if (dbOpen) loadDbMeta();
});
document.getElementById('devicedb-close').addEventListener('click', () => {
    dbOpen = false;
    dbPanel.classList.add('hidden');
    document.getElementById('btn-devicedb').classList.remove('active');
});
document.getElementById('devicedb-company-btn').addEventListener('click', lookupCompanyId);
dbCompanyInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') lookupCompanyId(); });
dbSearchInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') runDbSearch(); });
dbTechSelect.addEventListener('change', runDbSearch);
dbCatSelect.addEventListener('change', runDbSearch);

// ─── Renderloop ────────────────────────────────────────────────
function animate(time) {
    requestAnimationFrame(animate);
    animateAvatars(time);
    animateNetwork(time);
    animateDeviceLayer(time);
    updateLOD();
    adaptRenderQuality(time);
    controls.update();
    renderer.render(scene, camera);
    labelRenderer.render(scene, camera);
}
animate(0);

window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
    labelRenderer.setSize(window.innerWidth, window.innerHeight);
});
