#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/deploy"
cd "$DEPLOY"

if [[ "$(id -u)" -ne 0 ]]; then SUDO=sudo; else SUDO=; fi

command -v curl >/dev/null 2>&1 || { echo "Нужен curl. Установите его и повторите."; exit 2; }
command -v openssl >/dev/null 2>&1 || { echo "Нужен openssl. Установите его и повторите."; exit 2; }

if ! command -v docker >/dev/null 2>&1; then
  echo "Устанавливаю Docker..."
  curl -fsSL https://get.docker.com | $SUDO sh
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Не найден Docker Compose plugin. Установите Docker с Compose и повторите."
  exit 2
fi

if command -v apt-get >/dev/null 2>&1; then
  $SUDO apt-get update -y >/dev/null 2>&1 || true
  $SUDO apt-get install -y qrencode cron >/dev/null 2>&1 || true
fi

if ! command -v tailscale >/dev/null 2>&1; then
  echo "Устанавливаю Tailscale..."
  curl -fsSL https://tailscale.com/install.sh | $SUDO sh
fi

# Create persistent backend configuration. No custom domain is required.
if [[ ! -f .env ]]; then
  TOKEN="$(openssl rand -hex 32)"
  cat > .env <<ENV
MOBILE_API_TOKEN=$TOKEN
AUTO_START=true
TESTNET=true
ALLOW_LIVE=false
SYMBOL=BTCUSDT
INTERVAL=1h
HTF_INTERVAL=4h
REQUIRE_HTF_CONFIRMATION=true
POSITION_FRACTION=0.25
STOP_LOSS_PCT=0.02
TAKE_PROFIT_PCT=0.04
RISK_PER_TRADE_PCT=0.01
MAX_DAILY_LOSS_PCT=0.03
MAX_TRADES_PER_DAY=5
MAX_CONSECUTIVE_LOSSES=3
COOLDOWN_MINUTES=30
MIN_RISK_REWARD=1.5
ATR_PERIOD=14
MAX_ATR_PCT=0.08
MAX_SPREAD_PCT=0.0015
BALANCE_TOLERANCE_PCT=0.002
POLL_SECONDS=20
MOBILE_API_HOST=0.0.0.0
MOBILE_API_PORT=8000
DB_PATH=data/trader.sqlite3
BACKEND_BIND=127.0.0.1
ENV
  chmod 600 .env
fi

mkdir -p credentials
TOKEN="$(awk -F= '/^MOBILE_API_TOKEN=/{print $2}' .env)"
printf '%s\n' "$TOKEN" > credentials/mobile_api_token.txt
chmod 600 credentials/mobile_api_token.txt

# Daily local backup of encrypted bot state; no Binance secret is written in plaintext.
if command -v crontab >/dev/null 2>&1; then
  (crontab -l 2>/dev/null | grep -v "williams-backup" || true; echo "17 3 * * * $DEPLOY/backup_vps.sh # williams-backup") | crontab - || true
fi

echo "Запускаю backend..."
docker compose -f docker-compose.no-domain.yml up -d --build williams

# Authenticate Tailscale. Prefer a one-off/pre-approved auth key supplied through stdin/env.
if ! tailscale ip -4 >/dev/null 2>&1; then
  echo
  echo "Авторизация Tailscale требуется один раз."
  if [[ -n "${TS_AUTH_KEY:-}" ]]; then
    $SUDO tailscale up --auth-key="$TS_AUTH_KEY" --hostname=williams-bot --accept-routes=false
  else
    $SUDO tailscale up --hostname=williams-bot --qr
    echo "После подтверждения Tailscale повторно запустите этот же скрипт."
    exit 10
  fi
fi

$SUDO tailscale set --hostname=williams-bot || true

echo "Включаю HTTPS Funnel..."
$SUDO tailscale funnel --bg 8000

URL="$(tailscale funnel status 2>/dev/null | awk '/https:\/\//{print $1; exit}')"
if [[ -z "$URL" ]]; then
  HOST="$(tailscale status --self --json 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("Self") or {}).get("DNSName","").rstrip("."))' 2>/dev/null || true)"
  [[ -n "$HOST" ]] && URL="https://$HOST"
fi

printf 'Backend URL: %s\nMobile API token: %s\n' "${URL:-UNKNOWN — run: tailscale funnel status}" "$TOKEN" > credentials/mobile_connection.txt
if [[ -n "${URL:-}" && "$URL" != UNKNOWN* ]]; then "$DEPLOY/show_pairing.sh" || true; fi
chmod 600 credentials/mobile_connection.txt

echo
cat <<INFO
============================================================
Williams Bot: установка без домена завершена

Backend URL: ${URL:-UNKNOWN — run: tailscale funnel status}
Mobile API token: $TOKEN

В APK используйте «Сканировать QR» и отсканируйте pairing QR.
Если сканировать QR неудобно, URL и token можно ввести вручную в «Расширенное подключение».
Binance ключи вводятся один раз.

TESTNET=true
ALLOW_LIVE=false

Важно: Funnel даёт публичный HTTPS URL, поэтому API защищён
длинным Bearer-токеном. Не публикуйте token и файл
credentials/mobile_connection.txt.
============================================================
INFO
