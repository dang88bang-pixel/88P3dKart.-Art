#!/bin/bash
# ─────────────────────────────────────────────────────────────
# 88P3dKart.-Art — Secrets & TLS-Bootstrap (einmalig, idempotent)
#
# Erzeugt:
#   secrets/agent-signing-secret   (≥32 Zeichen, HMAC-Schlüssel für Sessions)
#   secrets/agent-admin-token      (≥32 Zeichen, Bootstrap-Admin-Token)
#   ssl/server.{crt,key}           (selbstsigniertes nginx-Zertifikat)
#   ssl/mqtt/{ca.crt,server.crt,server.key,edge-agent.crt,edge-agent.key}
#                                  (interne PKI für mTLS-MQTT, optionales Profil)
#   secrets/mosquitto-passwords    (Passwortdatei für WebSocket-Listener 9002)
#
# Alle Ausgaben sind von Docker-Compose referenziert (siehe docker-compose.yml).
# ─────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p secrets ssl ssl/mqtt mosquitto/config mosquitto/data mosquitto/log

gen_secret() { # file minlen
    if [ -s "$1" ]; then
        echo "  ✓ $1 vorhanden (überspringe)"
        return
    fi
    head -c 32 /dev/urandom | base64 | tr -d '=+/' | head -c 48 > "$1"
    echo "  ✚ $1 erzeugt"
}

echo "── Agent-Secrets ──"
gen_secret secrets/agent-signing-secret 32
gen_secret secrets/agent-admin-token 32

echo "── nginx-TLS ──"
if [ ! -s ssl/server.crt ]; then
    openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
        -keyout ssl/server.key -out ssl/server.crt \
        -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
    echo "  ✚ ssl/server.crt + ssl/server.key erzeugt (CN=localhost, 825 Tage)"
else
    echo "  ✓ ssl/server.crt vorhanden (überspringe)"
fi

echo "── MQTT-PKI (optionales mqtt-Profil) ──"
if [ ! -s ssl/mqtt/ca.crt ]; then
    # CA
    openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
        -keyout ssl/mqtt/ca.key -out ssl/mqtt/ca.crt \
        -subj "/CN=88p3dkart-mqtt-ca" 2>/dev/null
    # Broker
    openssl req -newkey rsa:2048 -nodes -keyout ssl/mqtt/server.key -out ssl/mqtt/server.csr \
        -subj "/CN=mosquitto" 2>/dev/null
    openssl x509 -req -in ssl/mqtt/server.csr -CA ssl/mqtt/ca.crt -CAkey ssl/mqtt/ca.key \
        -CAcreateserial -out ssl/mqtt/server.crt -days 825 2>/dev/null
    # Edge-Agent-Client
    openssl req -newkey rsa:2048 -nodes -keyout ssl/mqtt/edge-agent.key -out ssl/mqtt/edge-agent.csr \
        -subj "/CN=edge-agent" 2>/dev/null
    openssl x509 -req -in ssl/mqtt/edge-agent.csr -CA ssl/mqtt/ca.crt -CAkey ssl/mqtt/ca.key \
        -CAcreateserial -out ssl/mqtt/edge-agent.crt -days 825 2>/dev/null
    rm -f ssl/mqtt/*.csr ssl/mqtt/ca.srl
    echo "  ✚ MQTT-PKI erzeugt (ca, server, edge-agent)"
else
    echo "  ✓ ssl/mqtt/ca.crt vorhanden (überspringe)"
fi

echo "── Mosquitto-Passwortdatei (WebSocket-Listener 9002) ──"
if [ ! -s secrets/mosquitto-passwords ]; then
    MOSQ_USER="${MQTT_USERNAME:-agent}"
    MOSQ_PASS="$(head -c 24 /dev/urandom | base64 | tr -d '=+/')"
    docker run --rm eclipse-mosquitto:2.0 mosquitto_passwd -b -c /pw "$MOSQ_USER" "$MOSQ_PASS" >/dev/null 2>&1 \
        && docker run --rm -v "$(pwd)/secrets:/out" eclipse-mosquitto:2.0 sh -c "mosquitto_passwd -b -c /out/mosquitto-passwords '$MOSQ_USER' '$MOSQ_PASS'" >/dev/null 2>&1 \
        || echo "  ⚠ docker nicht verfügbar — Mosquitto-Passwortdatei manuell anlegen (mosquitto_passwd)" 
    if [ -s secrets/mosquitto-passwords ]; then
        echo "  ✚ secrets/mosquitto-passwords erzeugt (User: $MOSQ_USER)"
    fi
else
    echo "  ✓ secrets/mosquitto-passwords vorhanden (überspringe)"
fi

echo ""
echo "✅ Bootstrap abgeschlossen."
echo "   .env anpassen (AGENT_PUBLIC_HOST, AGENT_GATEWAY_ID, AGENT_DEVICE_GID)"
echo "   Danach:  ./scripts/deploy.sh production"
