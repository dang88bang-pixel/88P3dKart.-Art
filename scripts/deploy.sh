#!/bin/bash
# ─────────────────────────────────────────────────────────────
# 88P3dKart.-Art — Deployment (Produktion / Staging / Status / Backup)
#
# Nutzung:  ./scripts/deploy.sh {production|staging|status|backup|logs}
#
# Voraussetzungen (einmalig):
#   1) ./scripts/generate-secrets.sh   (Secrets + TLS)
#   2) .env.example → .env             (Host, Gateway-ID, Geräte-GID)
# ─────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }

MODE="${1:-status}"
COMPOSE="docker-compose.yml"
BACKUP_DIR="./backups/$(date +%Y%m%d_%H%M%S)"

compose() {
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        docker compose -f "$COMPOSE" "$@"
    elif command -v docker-compose >/dev/null 2>&1; then
        docker-compose -f "$COMPOSE" "$@"
    else
        log_error "Weder 'docker compose' noch 'docker-compose' gefunden."
        exit 1
    fi
}

check_prerequisites() {
    log_info "Prüfe Voraussetzungen…"
    local missing=0
    for cmd in docker git; do
        command -v "$cmd" >/dev/null 2>&1 || { log_error "$cmd fehlt"; missing=1; }
    done
    [ -f .env ] || { log_error ".env fehlt — 'cp .env.example .env' und Werte setzen"; missing=1; }
    [ -s secrets/agent-signing-secret ] || { log_error "secrets/agent-signing-secret fehlt — generate-secrets.sh ausführen"; missing=1; }
    [ -s secrets/agent-admin-token ] || { log_error "secrets/agent-admin-token fehlt — generate-secrets.sh ausführen"; missing=1; }
    [ -s ssl/server.crt ] || { log_error "ssl/server.crt fehlt — generate-secrets.sh ausführen"; missing=1; }
    [ "$missing" -eq 0 ] || exit 1
    log_success "Voraussetzungen OK"
}

wait_healthy() {
    local service="$1" attempts="${2:-30}"
    for _ in $(seq 1 "$attempts"); do
        state=$(compose ps --format json 2>/dev/null | python3 -c "
import json,sys
for line in sys.stdin:
    try:
        d=json.loads(line)
        if d.get('Service')=='$service' and d.get('Health')=='healthy':
            print('healthy'); break
    except Exception: pass" 2>/dev/null || true)
        [ "$state" = "healthy" ] && return 0
        sleep 2
    done
    return 1
}

backup_current() {
    log_info "Sichere aktuellen Stand nach $BACKUP_DIR …"
    mkdir -p "$BACKUP_DIR"
    [ -f "$COMPOSE" ] && cp "$COMPOSE" "$BACKUP_DIR/"
    [ -f .env ] && cp .env "$BACKUP_DIR/"
    docker volume ls -q 2>/dev/null | while read -r vol; do
        case "$vol" in
            *88p3dkart*|*agent*)
                log_info "  Volume $vol …"
                docker run --rm -v "$vol:/data" -v "$BACKUP_DIR:/backup" alpine \
                    tar czf "/backup/$(echo "$vol" | tr '/' '_').tar.gz" -C /data . 2>/dev/null || true
                ;;
        esac
    done
    log_success "Backup: $BACKUP_DIR"
}

verify_deployment() {
    log_info "Verifiziere Services …"
    local ok=true
    for probe in "https://localhost/api/v1/health:Edge-Agent"; do
        url="${probe%%:*}"; name="${probe##*:}"
        code=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 "$url" 2>/dev/null || echo 000)
        if [ "$code" = "200" ]; then
            log_success "$name: healthy ($code)"
        else
            log_warn "$name: HTTP $code"
            ok=false
        fi
    done
    [ "$ok" = true ] || log_warn "Nicht alle Services gesund — 'scripts/deploy.sh logs' für Details."
}

case "$MODE" in
    production)
        check_prerequisites
        backup_current
        log_info "Baue Images…"
        compose build --pull
        log_info "Starte Produktion…"
        compose up -d --remove-orphans
        log_info "Warte auf Edge-Agent…"
        wait_healthy edge-agent 30 || log_warn "Edge-Agent noch nicht healthy — Logs prüfen"
        verify_deployment
        ;;
    staging)
        COMPOSE="docker-compose.dev.yml"
        check_prerequisites
        log_info "Starte Staging (docker-compose.dev.yml)…"
        compose up -d --remove-orphans
        wait_healthy edge-agent 30 || log_warn "Edge-Agent noch nicht healthy"
        ;;
    status)
        compose ps 2>/dev/null || log_warn "Kein laufendes Deployment"
        ;;
    backup)
        backup_current
        ;;
    logs)
        compose logs --tail=100 -f edge-agent nginx 2>/dev/null || true
        ;;
    *)
        echo "Usage: $0 {production|staging|status|backup|logs}"
        exit 1
        ;;
esac
