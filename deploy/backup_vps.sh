#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
chmod 700 backups
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
COMPOSE=(-f docker-compose.no-domain.yml)
if [[ ! -f docker-compose.no-domain.yml ]]; then COMPOSE=(); fi
docker compose "${COMPOSE[@]}" exec -T williams sh -c 'tar czf - -C /app/data trader.sqlite3 2>/dev/null || true' > "backups/williams-data-$STAMP.tgz"
chmod 600 "backups/williams-data-$STAMP.tgz"
# Backups intentionally exclude Binance credentials and the credential encryption key.
# If the VPS is lost, reconnect Binance from the Android app.
# Keep the last 14 daily backups.
ls -1t backups/williams-data-*.tgz 2>/dev/null | tail -n +15 | xargs -r rm -f
echo "Backup: backups/williams-data-$STAMP.tgz"
