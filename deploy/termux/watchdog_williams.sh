#!/data/data/com.termux/files/usr/bin/bash

set -u

PROJECT="$HOME/Williams"
DATA_DIR="$PROJECT/data"
PIDFILE="$DATA_DIR/termux-server.pid"
LOG="$DATA_DIR/termux-watchdog.log"

mkdir -p "$DATA_DIR"

cd "$PROJECT" || exit 1

set -a
[ -f "$PROJECT/.env" ] && . "$PROJECT/.env"
set +a

HOST="${MOBILE_API_HOST:-127.0.0.1}"
PORT="${MOBILE_API_PORT:-8000}"
HEALTH_URL="http://${HOST}:${PORT}/api/v1/health"

while true; do
    HEALTHY=false

    if curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
        HEALTHY=true
    fi

    if [ "$HEALTHY" = false ]; then
        echo "$(date '+%Y-%m-%d %H:%M:%S') health check failed -> starting server" >> "$LOG"

        # Только наш PID из PIDFILE.
        # Дополнительно проверяем, что это действительно run_server.py.
        if [ -f "$PIDFILE" ]; then
            PID="$(cat "$PIDFILE" 2>/dev/null || true)"

            if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
                CMD="$(ps -p "$PID" -o args= 2>/dev/null || true)"

                case "$CMD" in
                    *run_server.py*)
                        kill "$PID" 2>/dev/null || true
                        sleep 2
                        ;;
                esac
            fi
        fi

        rm -f "$PIDFILE"

        "$PROJECT/deploy/termux/start_williams.sh" >> "$LOG" 2>&1 || true
    fi

    sleep 15
done
