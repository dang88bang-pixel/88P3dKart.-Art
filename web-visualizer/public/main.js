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

// ─── Renderloop ────────────────────────────────────────────────
function animate(time) {
    requestAnimationFrame(animate);
    animateAvatars(time);
    updateLOD();
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
