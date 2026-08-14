// 3dxAgent Web-Visualizer — Service Worker (Offline-App-Shell)
// docs/SERVICE_WORKER.md §Offline-Sync
//
// Caching-Strategien angelehnt an Workbox (Google) — ohne Abhängigkeiten:
//   - App-Shell (HTML/CSS/JS): Cache-First mit Hintergrund-Aktualisierung
//   - API/Health: Network-First mit Cache-Fallback (letzte Daten offline)
//   - 3D-Assets/CDN (three.js): Stale-While-Revalidate (schnell + aktuell)
//   - WebSocket (/ws): wird von Service Workern NICHT abgefangen —
//     Live-Daten laufen unverändert direkt zum Edge-Agent.
//
// Hinweis: periodische Hintergrundarbeit im Service Worker ist nicht
// zuverlässig (Terminierung durch den Browser) — die Plattform setzt dafür
// auf Android WorkManager/Coroutines (native App) bzw. den Edge-Agent.

const VERSION = '3dxagent-shell-v1';
const STATIC_CACHE = `${VERSION}-static`;
const RUNTIME_CACHE = `${VERSION}-runtime`;

// App-Shell: alle lokalen statischen Ressourcen des Visualizers
const APP_SHELL = [
  '/',
  '/index.html',
  '/main.js',
  '/styles.css',
];

// ─── Install: App-Shell vorladen ─────────────────────────────────
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting()),
  );
});

// ─── Activate: alte Cache-Versionen aufräumen ────────────────────
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((key) => key.startsWith('3dxagent-') && key !== STATIC_CACHE && key !== RUNTIME_CACHE)
          .map((key) => caches.delete(key)),
      ))
      .then(() => self.clients.claim()),
  );
});

// ─── Fetch: Routing nach Ressourcentyp ───────────────────────────
self.addEventListener('fetch', (event) => {
  const request = event.request;

  // Nur GET-Anfragen cachen; WebSocket-Verkehr erreicht den SW nicht
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  const isSameOrigin = url.origin === self.location.origin;
  const isApi = isSameOrigin && (url.pathname === '/health' || url.pathname.startsWith('/api/'));

  if (isApi) {
    // Network-First: aktuelle Daten, bei Offline-Fall letzter Cache-Stand
    event.respondWith(networkFirst(request));
    return;
  }

  if (isSameOrigin) {
    // App-Shell (inkl. navigations-Anfragen): Cache-First + Update im Hintergrund
    event.respondWith(cacheFirstWithUpdate(request));
    return;
  }

  // Fremde Origin (z. B. jsdelivr-CDN für three.js):
  // Stale-While-Revalidate — gecacht wird nur bei erfolgreichem Response
  event.respondWith(staleWhileRevalidate(request));
});

// ─── Strategien ──────────────────────────────────────────────────

/** Cache-First mit Hintergrund-Aktualisierung (Workbox: cache-first + update). */
async function cacheFirstWithUpdate(request) {
  const cache = await caches.open(STATIC_CACHE);
  const cached = await cache.match(request);
  const network = fetch(request)
    .then((response) => {
      if (response && response.ok) cache.put(request, response.clone());
      return response;
    })
    .catch(() => cached);
  return cached || network;
}

/** Network-First mit Cache-Fallback (Workbox: network-first). */
async function networkFirst(request) {
  const cache = await caches.open(RUNTIME_CACHE);
  try {
    const response = await fetch(request);
    if (response && response.ok) cache.put(request, response.clone());
    return response;
  } catch (_) {
    const cached = await cache.match(request);
    if (cached) return cached;
    return new Response(JSON.stringify({ status: 'offline' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    });
  }
}

/** Stale-While-Revalidate (Workbox: stale-while-revalidate). */
async function staleWhileRevalidate(request) {
  const cache = await caches.open(RUNTIME_CACHE);
  const cached = await cache.match(request);
  const network = fetch(request)
    .then((response) => {
      // Opaque Responses (CDN ohne CORS-Header) sind cachable, aber unlesbar —
      // nur statusfähige Responses sichern.
      if (response && (response.ok || response.type === 'opaque')) {
        cache.put(request, response.clone());
      }
      return response;
    })
    .catch(() => cached);
  return cached || network;
}
