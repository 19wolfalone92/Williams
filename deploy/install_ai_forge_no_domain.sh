#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORGE="$ROOT/ai_forge"
cd "$FORGE"

if [[ "$(id -u)" -ne 0 ]]; then SUDO=sudo; else SUDO=; fi
command -v curl >/dev/null 2>&1 || { echo "Нужен curl."; exit 2; }
command -v openssl >/dev/null 2>&1 || { echo "Нужен openssl."; exit 2; }

if ! command -v docker >/dev/null 2>&1; then
  echo "Устанавливаю Docker..."
  curl -fsSL https://get.docker.com | $SUDO sh
fi
docker compose version >/dev/null 2>&1 || { echo "Нужен Docker Compose plugin."; exit 2; }

if ! command -v tailscale >/dev/null 2>&1; then
  echo "Устанавливаю Tailscale..."
  curl -fsSL https://tailscale.com/install.sh | $SUDO sh
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  TOKEN="$(openssl rand -hex 32)"
  sed -i "s#^AI_FORGE_API_TOKEN=.*#AI_FORGE_API_TOKEN=$TOKEN#" .env
  chmod 600 .env
else
  TOKEN="$(awk -F= '/^AI_FORGE_API_TOKEN=/{print $2}' .env)"
fi

if grep -q '^AI_FORGE_MOCK=true' .env; then
  echo "ОШИБКА: production deployment нельзя запускать в MOCK-режиме."
  exit 3
fi
if [[ -z "$TOKEN" || "$TOKEN" == "REPLACE_WITH_A_LONG_RANDOM_SECRET" ]]; then
  echo "ОШИБКА: AI_FORGE_API_TOKEN не задан."
  exit 3
fi

mkdir -p credentials
printf '%s\n' "$TOKEN" > credentials/ai_forge_api_token.txt
chmod 600 credentials/ai_forge_api_token.txt

echo "Собираю и запускаю AI-Forge Core..."
docker compose up -d --build

if ! tailscale ip -4 >/dev/null 2>&1; then
  echo
  echo "Авторизация Tailscale требуется один раз."
  if [[ -n "${TS_AUTH_KEY:-}" ]]; then
    $SUDO tailscale up --auth-key="$TS_AUTH_KEY" --hostname=ai-forge-core --accept-routes=false
  else
    $SUDO tailscale up --hostname=ai-forge-core --qr
    echo "После авторизации повторно запустите этот скрипт."
    exit 10
  fi
fi

$SUDO tailscale set --hostname=ai-forge-core || true
$SUDO tailscale funnel --bg 8787

URL="$(tailscale funnel status 2>/dev/null | awk '/https:///{print $1; exit}')"
if [[ -z "$URL" ]]; then
  HOST="$(tailscale status --self --json 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("Self") or {}).get("DNSName","").rstrip("."))' 2>/dev/null || true)"
  [[ -n "$HOST" ]] && URL="https://$HOST"
fi

printf 'AI Forge Core URL: %s\nAI Forge API token: %s\n' "${URL:-UNKNOWN — run: tailscale funnel status}" "$TOKEN" > credentials/mobile_connection.txt
chmod 600 credentials/mobile_connection.txt

echo
echo "============================================================"
echo "AI-Forge Council Core: установка завершена"
echo
echo "Core URL: ${URL:-UNKNOWN — run: tailscale funnel status}"
echo "API token: $TOKEN"
echo
echo "Health: ${URL:-https://UNKNOWN}/health"
echo "Council API: ${URL:-https://UNKNOWN}/v1/council"
echo
echo "Provider API keys НЕ хранятся в GitHub и НЕ выдаются APK."
echo "Добавьте их только в ai_forge/.env на сервере."
echo "============================================================"
