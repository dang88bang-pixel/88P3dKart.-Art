/* Flotten-Live-Dashboard — Echtzeit-Positionen auf OpenStreetMap + Mesh-Aktionen.
 * Datenquelle: Edge-Agent (REST /api/v1/fleet*, WS fleet_update über den
 * Visualizer-Proxy). Keine eigenen Positionsdaten — alles aus dem Agent. */
(function () {
  'use strict';

  const KIND_ICONS = {
    ebike: '🚲', escooter: '🛴', eroller: '🛵', vehicle: '🚗',
    phone: '📱', tool: '🔧', ble_token: '📡', ble_accessory: '🧷', other: '🏷️',
  };

  const state = {
    vehicles: new Map(),     // id → vehicle (letzter Stand)
    selected: null,          // id
    markers: new Map(),      // id → L.Marker
    circles: new Map(),      // id → L.Circle (Genauigkeit)
    ws: null,
    mapCenter: { lat: 52.5163, lon: 13.3777 },
  };

  const $ = (id) => document.getElementById(id);
  const map = L.map('map', { zoomControl: true }).setView([state.mapCenter.lat, state.mapCenter.lon], 14);

  // ─── OSM-Kacheln (Standard-Tiles mit korrekter Namensnennung) ───
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende',
  }).addTo(map);

  map.on('moveend', () => {
    const c = map.getCenter();
    state.mapCenter = { lat: c.lat, lon: c.lng };
  });

  function toast(msg, ok = true) {
    const el = $('toast');
    el.textContent = msg;
    el.style.display = 'block';
    el.style.borderColor = ok ? '#34d399' : '#f87171';
    clearTimeout(el._t);
    el._t = setTimeout(() => { el.style.display = 'none'; }, 4000);
  }

  function setWs(connected) {
    const dot = $('ws-dot');
    dot.classList.toggle('ok', connected);
    $('ws-label').textContent = connected ? 'Echtzeit verbunden' : 'Keine Verbindung — Polling-Fallback';
  }

  // ─── Marker-Verwaltung ─────────────────────────────────────
  function markerIcon(vehicle) {
    const icon = KIND_ICONS[vehicle.kind] || '🏷️';
    return L.divIcon({
      className: '',
      html: `<div class="fleet-icon">${icon}</div>`,
      iconSize: [30, 30],
      iconAnchor: [15, 15],
      popupAnchor: [0, -16],
    });
  }

  function popupHtml(v) {
    const pos = (v.lat !== null && v.lon !== null)
      ? `${v.lat.toFixed(6)}, ${v.lon.toFixed(6)}`
      : (v.distance_m !== null && v.distance_m !== undefined)
        ? `Distanzschätzung ${v.distance_m.toFixed(1)} m (noch keine Koordinaten)`
        : 'Position unbekannt';
    const bat = v.battery !== null && v.battery !== undefined ? `${v.battery}%` : '—';
    return `<b>${v.name}</b><br>` +
      `Typ: ${v.kind_label || v.kind}<br>` +
      `Position: ${pos}<br>` +
      `Genauigkeit: ${v.accuracy_m !== null && v.accuracy_m !== undefined ? `±${v.accuracy_m} m` : '—'}<br>` +
      `Akku: ${bat} · Status: ${v.status}<br>` +
      `Quelle: ${v.source} · zuletzt: ${new Date(v.last_seen * 1000).toLocaleTimeString()}`;
  }

  function upsertMarker(v) {
    if (v.lat === null || v.lon === null) {
      // Ohne Koordinaten: nur in der Liste (grau)
      return;
    }
    if (state.markers.has(v.id)) {
      state.markers.get(v.id).setLatLng([v.lat, v.lon]).setPopupContent(popupHtml(v));
    } else {
      const m = L.marker([v.lat, v.lon], { icon: markerIcon(v) })
        .bindPopup(popupHtml(v))
        .addTo(map)
        .on('click', () => selectVehicle(v.id));
      state.markers.set(v.id, m);
    }
    // Genauigkeitskreis
    if (v.accuracy_m && v.accuracy_m > 0) {
      if (state.circles.has(v.id)) {
        state.circles.get(v.id).setLatLng([v.lat, v.lon]).setRadius(v.accuracy_m);
      } else {
        state.circles.set(v.id, L.circle([v.lat, v.lon], {
          radius: v.accuracy_m,
          color: '#38bdf8', weight: 1, opacity: .5, fillOpacity: .08,
        }).addTo(map));
      }
    }
  }

  // ─── Liste + Auswahl ───────────────────────────────────────
  function renderList() {
    const list = $('fleet-list');
    $('count-label').textContent = `${state.vehicles.size} Geräte`;
    if (state.vehicles.size === 0) {
      list.innerHTML = '<div style="padding:12px;color:#64748b;font-size:11px">Noch keine Flotten-Geräte. Der Edge-Agent liefert sie per <code>fleet/upsert</code> oder WS <code>fleet_position</code>.</div>';
      return;
    }
    const items = [...state.vehicles.values()]
      .sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
    list.innerHTML = items.map(v => {
      const bat = v.battery !== null && v.battery !== undefined ? v.battery : null;
      const batCls = bat === null ? '' : bat < 10 ? 'empty' : bat < 25 ? 'low' : '';
      const unlocated = v.lat === null;
      return `<div class="veh ${state.selected === v.id ? 'selected' : ''}" data-id="${v.id}">
        <span class="icon">${KIND_ICONS[v.kind] || '🏷️'}</span>
        <span class="meta">
          <span class="name">${v.name}</span>
          <span class="sub">${unlocated
            ? (v.distance_m ? `Distanz ~${v.distance_m.toFixed(1)} m` : 'keine Position')
            : `${v.lat.toFixed(4)}, ${v.lon.toFixed(4)}`}</span>
        </span>
        <span class="bat ${batCls}">${bat !== null ? bat + '%' : '—'}</span>
      </div>`;
    }).join('');
    list.querySelectorAll('.veh').forEach(el =>
      el.addEventListener('click', () => selectVehicle(el.dataset.id)));
  }

  function selectVehicle(id) {
    state.selected = id;
    const v = state.vehicles.get(id);
    if (!v) return;
    if (v.lat !== null && v.lon !== null) {
      map.flyTo([v.lat, v.lon], Math.max(map.getZoom(), 16), { duration: .6 });
      const m = state.markers.get(id);
      if (m) m.openPopup();
    }
    renderActions(v);
    renderList();
  }

  // ─── Aktionsleiste ─────────────────────────────────────────
  function renderActions(v) {
    const title = $('actions-title');
    const box = $('action-buttons');
    if (!v) {
      title.textContent = 'Aktionen — nichts ausgewählt';
      box.innerHTML = '';
      return;
    }
    title.textContent = `Aktionen — ${v.name}`;
    const hasLock = ['ebike', 'escooter', 'eroller', 'vehicle'].includes(v.kind);
    const buttons = [
      ['📊 Status', 'read_status', {}],
      ['📍 Ortung', 'locate', {}],
      ['💡 LED an', 'toggle_led', { state: true }],
      ['💡 LED aus', 'toggle_led', { state: false }],
    ];
    if (hasLock) {
      buttons.push(['🔒 Sperren', 'lock', {}], ['🔓 Entsperren', 'unlock', {}]);
    }
    box.innerHTML = buttons.map(([label, action, params]) =>
      `<button data-action="${action}" data-params='${JSON.stringify(params)}'>${label}</button>`
    ).join('');
    box.querySelectorAll('button').forEach(btn => btn.addEventListener('click', async () => {
      const action = btn.dataset.action;
      const params = JSON.parse(btn.dataset.params || '{}');
      btn.disabled = true;
      try {
        const r = await fetch(`/api/v1/fleet/${encodeURIComponent(v.id)}/action`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action, params }),
        });
        const body = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(body.detail || `HTTP ${r.status}`);
        toast(body.message || `${action} ausgeführt`);
      } catch (err) {
        toast(`Aktion fehlgeschlagen: ${err.message}`, false);
      } finally {
        btn.disabled = false;
      }
    }));
  }

  // ─── Daten laden (REST, Fallback) ─────────────────────────
  async function loadFleet() {
    try {
      const r = await fetch('/api/v1/fleet');
      if (r.status === 401) {
        toast('Kein Zugriff: AGENT_TOKEN im Visualizer konfigurieren', false);
        return;
      }
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const body = await r.json();
      for (const v of body.vehicles) {
        state.vehicles.set(v.id, v);
        upsertMarker(v);
      }
      renderList();
    } catch (err) {
      // leise — WS liefert dieselben Daten
      console.warn('fleet fetch:', err.message);
    }
  }

  // ─── WebSocket (fleet_update / fleet_action_result) ───────
  function connectWs() {
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    state.ws = new WebSocket(`${proto}://${window.location.host}/ws`);
    state.ws.onopen = () => { setWs(true); loadFleet(); };
    state.ws.onclose = () => { setWs(false); setTimeout(connectWs, 3000); };
    state.ws.onerror = () => setWs(false);
    state.ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.type === 'fleet_update' && msg.payload && Array.isArray(msg.payload.vehicles)) {
        for (const v of msg.payload.vehicles) {
          state.vehicles.set(v.id, v);
          upsertMarker(v);
        }
        renderList();
        if (state.selected && state.vehicles.has(state.selected)) {
          renderActions(state.vehicles.get(state.selected));
        }
      } else if (msg.type === 'fleet_action_result' && msg.payload) {
        toast(msg.payload.message || 'Aktion bestätigt');
      }
    };
  }

  // ─── Plug & Play: Umkreissuche ─────────────────────────────
  $('nearby-btn').addEventListener('click', async () => {
    const radius = Math.max(10, Math.min(50000, parseInt($('radius').value, 10) || 2000));
    const { lat, lon } = state.mapCenter;
    const res = $('nearby-results');
    res.innerHTML = '<div style="color:#64748b">Suche…</div>';
    try {
      const r = await fetch(`/api/v1/fleet/nearby?lat=${lat.toFixed(6)}&lon=${lon.toFixed(6)}&radius_m=${radius}`);
      if (!r.ok) {
        const b = await r.json().catch(() => ({}));
        throw new Error(b.detail || `HTTP ${r.status}`);
      }
      const body = await r.json();
      if (body.count === 0) {
        res.innerHTML = '<div style="color:#64748b">Nichts im Umkreis gefunden.</div>';
        return;
      }
      res.innerHTML = body.entries.map(e =>
        `<div class="nb" data-lat="${e.lat ?? ''}" data-lon="${e.lon ?? ''}" data-name="${e.name}">
          <span>${KIND_ICONS[e.kind] || '📡'} ${e.name} <small style="color:#64748b">${e.kind_label || e.kind}</small></span>
          <span>${e.distance_m !== null && e.distance_m !== undefined ? e.distance_m + ' m' : '—'}</span>
        </div>`).join('');
      res.querySelectorAll('.nb').forEach(el => el.addEventListener('click', () => {
        if (el.dataset.lat && el.dataset.lon) {
          map.flyTo([parseFloat(el.dataset.lat), parseFloat(el.dataset.lon)], 17, { duration: .5 });
        } else {
          toast(`${el.dataset.name}: nur Distanzschätzung — Fahrzeug-ID im Agent auswählen`);
        }
      }));
    } catch (err) {
      res.innerHTML = `<div style="color:#f87171">${err.message}</div>`;
    }
  });

  // ─── Start ────────────────────────────────────────────────
  connectWs();
  loadFleet();
  setInterval(() => { if (!state.ws || state.ws.readyState !== WebSocket.OPEN) loadFleet(); }, 10000);
})();
