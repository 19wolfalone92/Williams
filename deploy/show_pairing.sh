#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CONN="$ROOT/credentials/mobile_connection.txt"
if [[ ! -f "$CONN" ]]; then echo "Сначала запустите install_no_domain.sh"; exit 2; fi
URL="$(awk -F': ' '/^Backend URL:/{print $2}' "$CONN")"
TOKEN="$(awk -F': ' '/^Mobile API token:/{print $2}' "$CONN")"
if [[ -z "$URL" || -z "$TOKEN" || "$URL" == UNKNOWN* ]]; then echo "Не удалось определить URL/token"; exit 3; fi
PAIRING="williams://connect?url=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1],safe="") )' "$URL")&token=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1],safe="") )' "$TOKEN")"
printf '%s\n' "$PAIRING" > "$ROOT/credentials/pairing_uri.txt"
echo "QR-подключение сохранено: $ROOT/credentials/pairing_uri.txt"
if command -v qrencode >/dev/null 2>&1; then qrencode -t UTF8 "$PAIRING"; else echo "Установите qrencode для QR в терминале: sudo apt-get install -y qrencode"; fi
