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
FINAL_ARCHIVE="backups/williams-data-$STAMP.tgz"
TMP_ARCHIVE="backups/.williams-data-$STAMP.tgz.tmp"
cleanup() {
  docker compose "${COMPOSE[@]}" exec -T williams rm -f "$TMP_DB" >/dev/null 2>&1 || true
  rm -f "$TMP_ARCHIVE"
}
trap cleanup EXIT

docker compose "${COMPOSE[@]}" exec -T williams python -c \
  "import sqlite3; src=sqlite3.connect('file:/app/data/trader.sqlite3?mode=ro', uri=True); dst=sqlite3.connect('$TMP_DB'); src.backup(dst); dst.close(); src.close()"
# Write to a hidden temporary archive; publish the final name only after the
# stream is complete and the gzip/tar container validates successfully.
docker compose "${COMPOSE[@]}" exec -T williams tar czf - -C /tmp "williams-data-$STAMP.sqlite3" > "$TMP_ARCHIVE"
test -s "$TMP_ARCHIVE"
tar -tzf "$TMP_ARCHIVE" >/dev/null
chmod 600 "$TMP_ARCHIVE"
mv -f "$TMP_ARCHIVE" "$FINAL_ARCHIVE"

# Backups intentionally exclude Binance credentials and the credential encryption key.
# If the VPS is lost, reconnect Binance from the Android app.
# Keep the last 14 daily backups.
ls -1t backups/williams-data-*.tgz 2>/dev/null | tail -n +15 | xargs -r rm -f
echo "Backup: backups/williams-data-$STAMP.tgz"
