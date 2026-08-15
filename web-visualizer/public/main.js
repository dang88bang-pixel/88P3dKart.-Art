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

// ─── Bluetooth Zubehör Visualisierung ───────────────────────────
const btAccessories = new Map(); // mac -> {mesh, data}
const btMeshes = [];

const BT_COLORS = {
    TOKEN_CLASSIC: 0x44ff44,
    TOKEN_PRO: 0x44ff88,
    SENSOR_TAG: 0x44aaff,
    WEARABLE: 0xff44aa,
    ASSET_TAG: 0xffff44,
    REMOTE_CONTROLLER: 0xff8844,
    RELAY: 0xaa44ff,
    GATEWAY_BRIDGE: 0x44ffff,
    AUDIO_BEACON: 0xff44ff,
    GENERIC_BLE: 0x888888,
};

function createAccessoryMesh(type, mac) {
    const group = new THREE.Group();
    const color = BT_COLORS[type] || 0x888888;

    // Base box
    let geom;
    if (type === 'SENSOR_TAG') geom = new THREE.BoxGeometry(0.4, 0.4, 0.4);
    else if (type === 'WEARABLE') geom = new THREE.TorusGeometry(0.3, 0.08, 8, 16);
    else if (type === 'ASSET_TAG') geom = new THREE.SphereGeometry(0.25, 8, 8);
    else if (type === 'REMOTE_CONTROLLER') geom = new THREE.BoxGeometry(0.5, 0.1, 0.3);
    else geom = new THREE.CylinderGeometry(0.15, 0.15, 0.2, 12);

    const mesh = new THREE.Mesh(geom, new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.2 }));
    group.add(mesh);

    const div = document.createElement('div');
    div.textContent = mac.substring(0, 8);
    div.className = 'bt-label';
    div.style.cssText = `color:white;font-size:10px;background:rgba(0,0,0,0.6);padding:1px 4px;border-radius:4px;border:1px solid #${color.toString(16).padStart(6,'0')}`;
    const label = new CSS2DObject(div);
    label.position.y = 0.8;
    group.add(label);

    // Distance ring
    const ring = new THREE.Mesh(
        new THREE.RingGeometry(0.4, 0.42, 16),
        new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide, transparent: true, opacity: 0.3 })
    );
    ring.rotation.x = Math.PI / 2;
    ring.position.y = 0.01;
    group.add(ring);

    group.userData = { mac, type, labelDiv: div, color };
    scene.add(group);
    btMeshes.push(group);
    return group;
}

function updateAccessoryMesh(mac, data) {
    let entry = btAccessories.get(mac);
    if (!entry) {
        const mesh = createAccessoryMesh(data.type || 'GENERIC_BLE', mac);
        entry = { mesh, data };
        btAccessories.set(mac, entry);
    }
    entry.data = data;

    const mesh = entry.mesh;
    // Position: random umkreis basierend auf distance + rssi trilateration mock
    // In realem System würde EKF Position liefern – hier simuliert Kreis um Ursprung
    const distance = data.distance_m || 3;
    const angle = (parseInt(mac.replace(/:/g, '').slice(-4), 16) % 360) * Math.PI / 180;
    const noisyAngle = angle + (Date.now() % 10000) / 10000 * 0.2 - 0.1;
    const x = Math.cos(noisyAngle) * distance;
    const z = Math.sin(noisyAngle) * distance;
    const y = 0.3 + (data.type === 'WEARABLE' ? 1.0 : 0);

    // Lerp
    mesh.position.x += (x - mesh.position.x) * 0.05;
    mesh.position.z += (z - mesh.position.z) * 0.05;
    mesh.position.y = y;

    // Battery low -> blink
    if (data.battery < 20) {
        mesh.children[0].material.emissiveIntensity = Math.sin(Date.now() / 200) * 0.5 + 0.5;
    }
    if (data.is_sos) {
        mesh.children[0].material.color.setHex(0xff0000);
        mesh.children[0].material.emissive.setHex(0xff4400);
        mesh.children[0].material.emissiveIntensity = Math.sin(Date.now() / 100) * 0.8 + 0.5;
    }

    mesh.userData.labelDiv.textContent = `${data.name || mac.slice(-5)} ${data.battery}% ${data.rssi}dBm`;
}

// ─── Bluetooth Panel UI ────────────────────────────────────────
let currentBtFilter = 'all';

function setupBtFilters() {
    const buttons = document.querySelectorAll('#bt-filters button');
    buttons.forEach(btn => {
        btn.addEventListener('click', () => {
            buttons.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentBtFilter = btn.dataset.filter;
            renderBtList();
        });
    });
}

function renderBtList() {
    const listEl = document.getElementById('bt-list');
    const items = Array.from(btAccessories.values()).map(v => v.data).sort((a, b) => b.rssi - a.rssi);
    const filtered = currentBtFilter === 'all' ? items : items.filter(i => i.type === currentBtFilter || i.type.includes(currentBtFilter));

    if (filtered.length === 0) {
        listEl.innerHTML = '<div style="opacity:0.6">Keine Geräte für Filter ' + currentBtFilter + '</div>';
        return;
    }

    listEl.innerHTML = filtered.map(acc => {
        const isSos = acc.is_sos || acc.flags & 128;
        const isCritical = acc.battery < 15 || isSos;
        const move = acc.is_moving || acc.flags & 1 ? '🚶' : '🧍';
        const batClass = acc.battery < 20 ? 'battery_low' : '';
        const typeIcon = {
            TOKEN_CLASSIC: '🔑', TOKEN_PRO: '🔑', SENSOR_TAG: '🌡️',
            WEARABLE: '⌚', ASSET_TAG: '📦', REMOTE_CONTROLLER: '🎮',
            GATEWAY_BRIDGE: '🌉', RELAY: '📱', AUDIO_BEACON: '🔊'
        }[acc.type] || '📡';

        return `<div class="bt-item ${isCritical ? 'critical' : ''} ${isSos ? 'sos' : ''}">
            <div class="bt-head"><span>${typeIcon} ${acc.name || acc.mac.slice(-5)}</span><span style="color:#88ff88">${acc.rssi} dBm</span></div>
            <div class="bt-meta">${acc.type} • ${acc.mac} • ${(acc.distance_m || 0).toFixed(1)}m ${move}</div>
            <div class="bt-data">
                <span class="bt-badge ${batClass}">🔋 ${acc.battery}%</span>
                ${acc.temperature_c ? `<span class="bt-badge">🌡️ ${acc.temperature_c}°C</span>` : ''}
                ${acc.humidity_pct ? `<span class="bt-badge">💧 ${acc.humidity_pct}%</span>` : ''}
                ${acc.heart_rate_bpm ? `<span class="bt-badge">❤️ ${acc.heart_rate_bpm} bpm</span>` : ''}
                ${acc.button_state ? `<span class="bt-badge">🎮 BTN ${acc.button_state}</span>` : ''}
                ${isSos ? `<span class="bt-badge sos">🚨 SOS</span>` : ''}
                ${acc.is_moving ? `<span class="bt-badge moving">MOVING</span>` : ''}
            </div>
        </div>`;
    }).join('');
}

function updateBtStats(stats) {
    document.getElementById('bt-stats').textContent = `📡 BT: ${stats.total} Geräte • LowBat: ${stats.low_battery} • SOS: ${stats.sos_active}`;
}

function updateBtHealth(healthData) {
    if (!healthData) return;
    let healthy = 0, degraded = 0, critical = 0, lost = 0;
    if (healthData.items) {
        healthData.items.forEach(i => {
            if (i.status === 'HEALTHY') healthy++;
            else if (i.status === 'DEGRADED') degraded++;
            else if (i.status === 'CRITICAL') critical++;
            else if (i.status === 'LOST') lost++;
        });
    } else {
        healthy = healthData.healthy || 0;
        degraded = healthData.degraded || 0;
        critical = healthData.critical || 0;
        lost = healthData.lost || 0;
    }
    document.getElementById('bt-healthy').textContent = healthy;
    document.getElementById('bt-degraded').textContent = degraded;
    document.getElementById('bt-critical').textContent = critical;
    document.getElementById('bt-lost').textContent = lost;
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
                }
                else if (msg.type === 'bluetooth_accessories_update') {
                    const payload = msg.payload;
                    const accs = payload.accessories || [];
                    accs.forEach(acc => {
                        updateAccessoryMesh(acc.mac || acc.mac_address, acc);
                    });
                    renderBtList();
                    if (payload.stats) updateBtStats(payload.stats);
                    // fetch health
                    fetch('/api/v1/bluetooth/health').then(r => r.json()).then(h => updateBtHealth(h)).catch(()=>{});
                }
                else if (msg.type === 'ble_update') {
                    // legacy
                }
                else if (msg.type === 'accessory_event') {
                    const p = msg.payload;
                    console.warn('🚨 Accessory Event', p.event_type, p.mac);
                    if (p.event_type === 'sos') {
                        const el = document.getElementById('scenario-status');
                        el.textContent = `🚨 SOS von ${p.mac} !`;
                        el.style.color = '#ff4444';
                        setTimeout(()=> { el.style.color = '#88ddff'; }, 5000);
                    }
                    if (p.accessory) {
                        updateAccessoryMesh(p.mac, p.accessory);
                        renderBtList();
                    }
                }
                else if (msg.type === 'sensor_tag_update' || msg.type === 'wearable_update') {
                    const acc = msg.payload;
                    if (acc.mac) {
                        updateAccessoryMesh(acc.mac, acc);
                        renderBtList();
                    }
                }
            } catch (e) { console.warn('WS parse', e); }
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

function animateBtMeshes(time) {
    btMeshes.forEach(m => {
        m.rotation.y += 0.005;
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

document.getElementById('btn-bt-scan')?.addEventListener('click', () => {
    fetch('/api/v1/bluetooth/accessories').then(r=>r.json()).then(data=>{
        console.log('BT Scan manuell', data);
        (data.accessories||[]).forEach(acc=>updateAccessoryMesh(acc.mac, acc));
        renderBtList();
        if (data.stats) updateBtStats(data.stats);
    });
});

// Init
setupBtFilters();

// Periodisch REST fallback wenn WS wenig liefert (für lokale Tests)
setInterval(()=>{
    fetch('/api/v1/bluetooth/accessories').then(r=>r.json()).then(data=>{
        if (data.accessories && data.accessories.length>0) {
            data.accessories.forEach(acc=>updateAccessoryMesh(acc.mac, acc));
            renderBtList();
            updateBtStats(data.stats);
        }
    }).catch(()=>{});
    fetch('/api/v1/bluetooth/health').then(r=>r.json()).then(h=>updateBtHealth(h)).catch(()=>{});
}, 5000);

// ─── Renderloop ────────────────────────────────────────────────
function animate(time) {
    requestAnimationFrame(animate);
    animateAvatars(time);
    animateBtMeshes(time);
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
