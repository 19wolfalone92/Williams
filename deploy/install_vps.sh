#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/deploy"
cd "$DEPLOY"

if [[ "$(id -u)" -ne 0 ]]; then
  SUDO=sudo
else
  SUDO=
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker..."
  curl -fsSL https://get.docker.com | $SUDO sh
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is required. Install Docker Engine with Compose support and run again."
  exit 2
fi

read -r -p "Your VPS domain (example: bot.example.com): " DOMAIN
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "Invalid domain"; exit 2; }

TOKEN="$(openssl rand -hex 32)"
cat > .env <<ENV
DOMAIN=$DOMAIN
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

mkdir -p "$DEPLOY/credentials"
printf '%s\n' "$TOKEN" > "$DEPLOY/credentials/mobile_api_token.txt"
printf 'Backend URL: https://%s\nMobile API token: %s\n' "$DOMAIN" "$TOKEN" > "$DEPLOY/credentials/mobile_connection.txt"
chmod 600 "$DEPLOY/credentials/mobile_api_token.txt" "$DEPLOY/credentials/mobile_connection.txt"

echo "Building and starting Williams VPS stack..."
docker compose up -d --build

echo
echo "=============================================="
echo "Williams Bot VPS is installed."
echo "Backend: https://$DOMAIN"
echo "Mobile connection details saved to: deploy/credentials/mobile_connection.txt"
echo "Open the Android app and enter this URL + token."
echo "Binance credentials are entered once in the app and are stored encrypted on the VPS."
echo "TESTNET=true; LIVE trading is disabled."
echo "=============================================="
