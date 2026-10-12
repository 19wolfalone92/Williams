#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
chmod 700 backups
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
COMPOSE=(-f docker-compose.no-domain.yml)
if [[ ! -f docker-compose.no-domain.yml ]]; then COMPOSE=(); fi

# Use SQLite's online backup API so committed pages in WAL are included in a
# consistent snapshot. Never archive only trader.sqlite3 while WAL may be live.
TMP_DB="/tmp/williams-data-$STAMP.sqlite3"
cleanup() {
  docker compose "${COMPOSE[@]}" exec -T williams rm -f "$TMP_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose "${COMPOSE[@]}" exec -T williams python -c \
  "import sqlite3; src=sqlite3.connect('file:/app/data/trader.sqlite3?mode=ro', uri=True); dst=sqlite3.connect('$TMP_DB'); src.backup(dst); dst.close(); src.close()"
docker compose "${COMPOSE[@]}" exec -T williams tar czf - -C /tmp "williams-data-$STAMP.sqlite3" > "backups/williams-data-$STAMP.tgz"
test -s "backups/williams-data-$STAMP.tgz"
chmod 600 "backups/williams-data-$STAMP.tgz"

# Backups intentionally exclude Binance credentials and the credential encryption key.
# If the VPS is lost, reconnect Binance from the Android app.
# Keep the last 14 daily backups.
ls -1t backups/williams-data-*.tgz 2>/dev/null | tail -n +15 | xargs -r rm -f
echo "Backup: backups/williams-data-$STAMP.tgz"
