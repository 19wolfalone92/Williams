#!/data/data/com.termux/files/usr/bin/bash

set -u

PROJECT="$HOME/Williams"
DATA_DIR="$PROJECT/data"
LOG="$DATA_DIR/termux-server.log"
PIDFILE="$DATA_DIR/termux-server.pid"

mkdir -p "$DATA_DIR"
cd "$PROJECT" || exit 1

# Загружаем .env
set -a
[ -f "$PROJECT/.env" ] && . "$PROJECT/.env"
set +a

HOST="${MOBILE_API_HOST:-127.0.0.1}"
PORT="${MOBILE_API_PORT:-8000}"
HEALTH_URL="http://${HOST}:${PORT}/api/v1/health"

# Безопасный режим всегда принудительно сохраняем.
export MOBILE_API_HOST="$HOST"
export MOBILE_API_PORT="$PORT"
export TESTNET=true
export DRY_RUN=true
export ALLOW_LIVE=false

# Если сервер уже отвечает — второй экземпляр не запускаем.
if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then
    EXISTING_PID=""

    if [ -f "$PIDFILE" ]; then
        EXISTING_PID="$(cat "$PIDFILE" 2>/dev/null || true)"
    fi

    if [ -n "$EXISTING_PID" ] && kill -0 "$EXISTING_PID" 2>/dev/null; then
        exit 0
    fi

    # Сервер работает, но PID-файл устарел.
    # Находим только наш run_server.py.
    EXISTING_PID="$(ps -ef | awk '/[p]ython .*run_server\.py/ {print $2; exit}')"

    if [ -n "$EXISTING_PID" ]; then
        echo "$EXISTING_PID" > "$PIDFILE"
        exit 0
    fi

    # Health отвечает, но наш процесс не найден.
    # Не запускаем второй сервер.
    echo "Health endpoint already active; refusing duplicate start." >> "$LOG"
    exit 0
fi

# Удаляем устаревший PID.
rm -f "$PIDFILE"

echo "$(date '+%Y-%m-%d %H:%M:%S') starting Williams server" >> "$LOG"

nohup python -u run_server.py >> "$LOG" 2>&1 &
PID=$!

echo "$PID" > "$PIDFILE"

# Ждём реального успешного запуска.
for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1

    if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
        exit 0
    fi

    # Процесс умер — запуск неуспешен.
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PIDFILE"
        echo "$(date '+%Y-%m-%d %H:%M:%S') server failed to start" >> "$LOG"
        exit 1
    fi
done

# После 10 секунд health так и не появился.
rm -f "$PIDFILE"

echo "$(date '+%Y-%m-%d %H:%M:%S') server startup timeout" >> "$LOG"

kill "$PID" 2>/dev/null || true

exit 1
