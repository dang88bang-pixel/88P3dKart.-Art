// 3dxAgent Web-Visualizer: Express + WebSocket-Proxy zum Edge-Agent
const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const path = require('path');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });

// Statische Dateien ausliefern
app.use(express.static(path.join(__dirname, 'public')));

app.get('/health', (_req, res) => res.json({ status: 'ok' }));

// ─── REST-Proxy: /api/* → Edge-Agent (docs/API.md, docs/DEVICE_DATABASE.md) ──
const AGENT_REST_URL = process.env.AGENT_REST_URL || 'http://localhost:8080';

app.use('/api', (req, res) => {
    const target = new URL(req.originalUrl, AGENT_REST_URL);
    const proxyReq = http.request(
        target,
        { method: req.method, headers: { ...req.headers, host: target.host } },
        (proxyRes) => {
            res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
            proxyRes.pipe(res);
        },
    );
    proxyReq.on('error', (err) => {
        if (!res.headersSent) {
            res.status(502).json({ detail: `Edge-Agent nicht erreichbar: ${err.message}` });
        }
    });
    req.pipe(proxyReq);
});

// ─── WebSocket-Proxy zum Edge-Agent ──────────────────────────
const AGENT_WS_URL = process.env.AGENT_WS_URL || 'ws://localhost:8080/ws/agent/events';
const RECONNECT_MS = parseInt(process.env.AGENT_RECONNECT_MS || '5000', 10);

function connectAgent(clientWs) {
  let agentWs;
  let closed = false;

  const open = () => {
    if (closed) return;
    agentWs = new WebSocket(AGENT_WS_URL);

    agentWs.on('open', () => {
      console.log(`[Web-Viz] Verbunden mit Edge-Agent (${AGENT_WS_URL})`);
    });

    // Agent → Client (Binary = Punktwolke, Text = JSON)
    agentWs.on('message', (data, isBinary) => {
      if (closed) return;
      if (clientWs.readyState !== WebSocket.OPEN) return;
      clientWs.send(data, { binary: isBinary });
    });

    // Client → Agent (z.B. Szenario-Befehle)
    clientWs.on('message', (data, isBinary) => {
      if (closed) return;
      if (agentWs && agentWs.readyState === WebSocket.OPEN) {
        agentWs.send(data, { binary: isBinary });
      }
    });

    agentWs.on('close', () => {
      if (closed) return;
      console.log(`[Web-Viz] Edge-Agent getrennt — Reconnect in ${RECONNECT_MS}ms`);
      setTimeout(open, RECONNECT_MS);
    });

    agentWs.on('error', (err) => {
      if (!closed) console.error('[Web-Viz] Agent-Fehler:', err.message);
    });
  };

  clientWs.on('close', () => {
    closed = true;
    if (agentWs) { try { agentWs.close(); } catch (_) {} }
  });

  open();
}

wss.on('connection', (clientWs) => {
  console.log('[Web-Viz] Client verbunden');
  connectAgent(clientWs);
});

const PORT = parseInt(process.env.PORT || '3000', 10);
server.listen(PORT, '0.0.0.0', () => {
  console.log(`🌐 Web-Visualizer läuft auf http://localhost:${PORT}`);
});
