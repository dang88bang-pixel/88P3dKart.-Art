# 88P3dKart.-Art — Security-Header & CORS (Ist-Stand)

Alle Header werden von **nginx** gesetzt (`nginx/nginx.conf`); der Edge-Agent erzwingt zusätzlich TLS-Pflicht und Origin-Prüfung auf App-Ebene.

## HTTP Security Headers (nginx, Port 443)

| Header | Wert | Zweck |
|---|---|---|
| `Strict-Transport-Security` | `max-age=31536000` | HSTS |
| `X-Content-Type-Options` | `nosniff` | MIME-Sniffing verhindern |
| `Referrer-Policy` | `no-referrer` | Kein Referrer-Leck |
| `Cache-Control` | `no-store` | Kein Caching sensibler Antworten |
| `server_tokens` | `off` | Versions-Fingerprinting vermeiden |

Zusätzlich: Port 80 leitet per `308` dauerhaft auf HTTPS um; TLS 1.2/1.3 mit deaktivierten Session-Tickets.

## App-Ebene (Edge-Agent)

- **TLS-Pflicht:** `AGENT_REQUIRE_TLS=true` (Default) → alle Requests außer `/api/v1/health` erhalten HTTP 426.
- **TrustedHost:** `AGENT_TRUSTED_HOSTS` erlaubt nur konfigurierte Hostnamen.
- **CORS:** `AGENT_CORS_ORIGINS` ist eine explizite Allowlist — unbekannte Origins erhalten **keinen** CORS-Grant (getestet in `test_agent.py::test_unlisted_browser_origin_gets_no_cors_grant`). Kein `Access-Control-Allow-Credentials`.
- **WebSocket-Origin:** Unbekannte Origins werden mit Close-Code 4403 abgelehnt.
- **Body-Limits:** `AGENT_MAX_HTTP_BODY_BYTES` (4 MB) — Content-Length wird serverseitig nachgemessen (Trick mit falscher Länge getestet).

## Container-Härtung (docker-compose.yml)

| Maßnahme | Umsetzung |
|---|---|
| Read-only Root-Dateisystem | `read_only: true` + `tmpfs` |
| Capabilities | `cap_drop: ALL` (nginx behält nur `NET_BIND_SERVICE`) |
| Secrets | Docker-Secrets statt ENV (`agent_signing_secret`, `agent_admin_token`) |
| Nicht-root | Edge-Agent läuft als UID 10001 |
| MQTT | Fail-closed: `allow_anonymous false`, `require_certificate true`, ACL (Profil `mqtt`) |

## JWT & Verschlüsselung

| Bereich | Verfahren |
|---|---|
| Session-Token | HMAC-SHA256, signiert mit `agent_signing_secret` (TTL konfigurierbar, Default 900 s) |
| Transport | TLS 1.2/1.3 (nginx-Terminierung, interne Komponenten im Docker-Netz) |
| Passwort-Hashes | Salted Hash (Geräte-Secrets), Sliding-Window-Brute-Force-Schutz |

> Hinweis: Die im früheren Entwurf genannten Kategorien „Personensuche-/Recovery-Daten" existieren in diesem Stack nicht — es gibt keine entsprechenden Datenspeicher.
