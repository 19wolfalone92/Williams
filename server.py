import os, threading, time, asyncio, json
from datetime import datetime, timezone
from typing import Optional
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect, Response
from pydantic import BaseModel
from db import Database
from trader import Trader
from portfolio_trader import MultiPositionTrader
from data import fetch_klines
from strategy import calculate_indicators, config_from_env
from ws_hub import WebSocketHub
from credentials_store import CredentialStore
from market_scanner import MarketScanner
from market_context import ContextCache
from mtf_context_service import MultiTimeframeContextService
from feature_store import FeatureStore
from binance_client import BinanceSpotClient
from digital_williams_core import DigitalWilliamsCore
from diagnostics import DiagnosticManager

load_dotenv()
API_TOKEN = os.getenv('MOBILE_API_TOKEN', '').strip()
VERSION = '4.25.0'

app = FastAPI(title='Williams Binance Bot API', version=VERSION)
hub = WebSocketHub()
context_cache = ContextCache()
mtf_service = MultiTimeframeContextService(context_cache)
quant_store = FeatureStore()
digital_williams = DigitalWilliamsCore()


class MetricsRegistry:
    def __init__(self):
        self.lock = threading.Lock()
        self.counters = {
            "http_requests_total": 0,
            "http_errors_total": 0,
            "ws_connections_total": 0,
            "trading_starts_total": 0,
            "kill_switch_total": 0,
        }
        self.latency = {"http_request_seconds_sum": 0.0, "http_request_seconds_count": 0}

    def inc(self, name, value=1):
        with self.lock:
            self.counters[name] = self.counters.get(name, 0) + value

    def observe_http(self, seconds):
        with self.lock:
            self.latency["http_request_seconds_sum"] += seconds
            self.latency["http_request_seconds_count"] += 1

    def render(self, state):
        with self.lock:
            counters = dict(self.counters)
            latency = dict(self.latency)
        lines = [
            "# HELP williams_http_requests_total Total HTTP requests.",
            "# TYPE williams_http_requests_total counter",
            f"williams_http_requests_total {counters['http_requests_total']}",
            "# HELP williams_http_errors_total Total HTTP 4xx/5xx responses.",
            "# TYPE williams_http_errors_total counter",
            f"williams_http_errors_total {counters['http_errors_total']}",
            "# HELP williams_http_request_seconds HTTP request latency.",
            "# TYPE williams_http_request_seconds summary",
            f"williams_http_request_seconds_sum {latency['http_request_seconds_sum']}",
            f"williams_http_request_seconds_count {latency['http_request_seconds_count']}",
            "# HELP williams_ws_connections_total WebSocket client connections.",
            "# TYPE williams_ws_connections_total counter",
            f"williams_ws_connections_total {counters['ws_connections_total']}",
            "# HELP williams_trading_starts_total Trading starts.",
            "# TYPE williams_trading_starts_total counter",
            f"williams_trading_starts_total {counters['trading_starts_total']}",
            "# HELP williams_kill_switch_total Kill switch invocations.",
            "# TYPE williams_kill_switch_total counter",
            f"williams_kill_switch_total {counters['kill_switch_total']}",
            "# HELP williams_running Trading engine running state.",
            "# TYPE williams_running gauge",
            f"williams_running {1 if state.running else 0}",
            "# HELP williams_paused Trading engine paused state.",
            "# TYPE williams_paused gauge",
            f"williams_paused {1 if state.paused else 0}",
            "# HELP williams_open_positions Open managed positions.",
            "# TYPE williams_open_positions gauge",
            f"williams_open_positions {len(state.ensure_multi().open_positions())}",
            "# HELP williams_reconciliation_required Reconciliation barrier state.",
            "# TYPE williams_reconciliation_required gauge",
            f"williams_reconciliation_required {1 if (_canonical_execution_state(state.ensure_multi())[1] or _canonical_execution_state(state.ensure_multi())[2]) else 0}",
        ]
        return "\n".join(lines) + "\n"

metrics = MetricsRegistry()

@app.middleware("http")
async def metrics_middleware(request, call_next):
    started = time.perf_counter()
    metrics.inc("http_requests_total")
    try:
        response = await call_next(request)
        if response.status_code >= 400:
            metrics.inc("http_errors_total")
        return response
    finally:
        metrics.observe_http(time.perf_counter() - started)

@app.get("/metrics")
def prometheus_metrics():
    return Response(content=metrics.render(state), media_type="text/plain; version=0.0.4")


class CredentialPayload(BaseModel):
    api_key: str
    api_secret: str
    testnet: bool = True


class ControlState:
    def __init__(self):
        self.lock = threading.RLock()
        self.trader = None
        self.thread = None
        self.running = False
        self.paused = False
        self.last_error = None
        self.desired_running = False
        self.api_key = ''
        self.api_secret = ''
        self.testnet = True
        self.credentials = CredentialStore()
        self.diagnostics = DiagnosticManager()
        stored = self.credentials.load()
        if stored:
            self.api_key = stored['api_key']
            self.api_secret = stored['api_secret']
            self.testnet = stored['testnet']

    def configure(self, key, secret, testnet=True):
        key = key.strip()
        secret = secret.strip()
        testnet = bool(testnet)
        if not key or not secret:
            raise RuntimeError('Binance API key and secret are required.')

        # Validate the exact Spot credentials before persisting them. This prevents
        # the backend from storing a broken/expired key and only discovering it
        # later inside the autonomous trading loop.
        candidate = BinanceSpotClient(key, secret, testnet=testnet)
        try:
            candidate.sync_time()
            account = candidate.account()
            if str(account.get('accountType', 'SPOT')).upper() not in {'SPOT', ''}:
                raise RuntimeError('Configured Binance account is not Spot.')
            if not testnet:
                restrictions = candidate.api_restrictions()
                if not (
                    bool(restrictions.get('enableReading', False))
                    and bool(restrictions.get('enableSpotAndMarginTrading', False))
                    and not bool(restrictions.get('enableWithdrawals', True))
                ):
                    raise RuntimeError(
                        'Live Spot API key must allow reading + Spot trading and have withdrawals disabled.'
                    )
        except Exception as exc:
            raise RuntimeError(f'Binance credential validation failed: {exc}') from exc

        with self.lock:
            if self.running and (
                key != self.api_key
                or secret != self.api_secret
                or testnet != self.testnet
            ):
                raise RuntimeError(
                    'Stop the bot before changing Binance credentials.'
                )
            if self.running and (
                key == self.api_key
                and secret == self.api_secret
                and testnet == self.testnet
            ):
                return
            self.api_key = key
            self.api_secret = secret
            self.testnet = testnet
            self.trader = None
            self.scanner = None
            self.credentials.save(
                self.api_key,
                self.api_secret,
                self.testnet,
            )
        hub.configure_credentials(
            self.api_key,
            self.api_secret,
            self.testnet,
        )
        mtf_service.configure_credentials(self.api_key, self.api_secret, self.testnet)

    def ensure_trader(self):
        with self.lock:
            if self.trader is None:
                self.trader = Trader(
                    api_key=self.api_key or None,
                    api_secret=self.api_secret or None,
                    testnet=self.testnet,
                    context_cache=context_cache,
                )
            return self.trader

    def ensure_scanner(self):
        with self.lock:
            t = self.ensure_trader()
            if self.scanner is None:
                self.scanner = MarketScanner(t.client)
            return self.scanner

    def ensure_multi(self):
        with self.lock:
            t = self.ensure_trader()
            if not hasattr(t, '_multi_position_trader'):
                t._multi_position_trader = MultiPositionTrader(
                    t.client,
                    db=t.db,
                    symbols=t.auto_scan_symbols,
                    execution_barrier=t.execution_barrier,
                )
            return t._multi_position_trader

    def loop(self):
        t = self.ensure_trader()
        try:
            t.setup()
            # A successful explicit START is the controlled re-arm after KILL.
            t.db.state_set('kill_switch_latched', 'false')
            with self.lock:
                self.running = True
                self.desired_running = True
                self.last_error = None
            while self.running:
                if not self.paused:
                    try:
                        t.process()
                    except Exception as e:
                        self.last_error = f'{type(e).__name__}: {e}'
                        incident = self.diagnostics.classify("trading_loop", e)
                        try:
                            incident = self.diagnostics.run_safe_heal(
                                incident,
                                callbacks={
                                    "RELOAD_EXCHANGE_INFO": lambda: t.client.exchange_info(t.symbol),
                                    "RELOAD_MARKET_HISTORY": lambda: fetch_klines(
                                        t.client, t.symbol, t.interval, limit=60
                                    ),
                                    "RECONNECT_MARKET_WS": lambda: hub.start(),
                                    "RECONNECT_USER_WS": lambda: hub.start(),
                                },
                            )
                            self.diagnostics.record(incident)
                        finally:
                            t.db.log_event(
                                'ERROR',
                                'api_loop_error',
                                self.last_error,
                                {"incident_id": incident.incident_id},
                            )
                time.sleep(t.poll_seconds)
        except Exception as e:
            self.last_error = f'{type(e).__name__}: {e}'
            try:
                t.db.log_event('ERROR', 'api_loop_fatal', self.last_error)
            except Exception:
                pass
        finally:
            with self.lock:
                self.running = False

    def start(self):
        with self.lock:
            if (
                self.thread is not None
                and self.thread.is_alive()
            ):
                return False
            self.paused = False
            self.thread = threading.Thread(
                target=self.loop,
                daemon=True,
                name='williams-trader',
            )
            self.thread.start()
            metrics.inc("trading_starts_total")
            return True

    def stop(self):
        with self.lock:
            self.desired_running = False
            self.running = False
            self.paused = False
            thread = self.thread
        if (
            thread is not None
            and thread.is_alive()
            and thread is not threading.current_thread()
        ):
            thread.join(timeout=5)
        return True

    def pause(self):
        with self.lock:
            self.paused = True
        return True

    def resume(self):
        with self.lock:
            self.paused = False
        return True

    def kill(self):
        metrics.inc("kill_switch_total")
        with self.lock:
            self.desired_running = False
        # Close only Williams-managed positions/orders; never cancel
        # unrelated account orders via DELETE /openOrders.
        with self.lock:
            self.running = False
            self.paused = True
            thread = self.thread

        t = self.ensure_trader()
        # Persist the emergency latch before attempting any close operation.
        t.db.state_set('kill_switch_latched', 'true')
        multi = self.ensure_multi()
        errors = []
        closed = []

        try:
            open_trades = list(multi.open_trades())
        except Exception as exc:
            open_trades = []
            errors.append("open_trades: " + str(exc))

        for trade in open_trades:
            symbol = str(trade.get("symbol", "")).upper()
            if not symbol:
                continue
            try:
                result = multi.manual_sell(symbol)
                closed.append({"symbol": symbol, "result": result})
            except Exception as exc:
                errors.append(f"{symbol}: {exc}")

        if thread is not None and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=3)

        t.db.log_event(
            "ERROR" if errors else "WARNING",
            "kill_switch",
            "Emergency kill switch executed",
            {
                "closed_positions": [x["symbol"] for x in closed],
                "errors": errors,
            },
        )
        try:
            t.notify(
                "KILL SWITCH\n"
                "Trading stopped; Williams-managed positions were closed.\n"
                + ("Errors: " + "; ".join(errors) if errors else "No errors.")
            )
        except Exception:
            pass

        return {
            "killed": True,
            "trading_stopped": True,
            "closed_positions": closed,
            "errors": errors,
            "state": "RECONCILE_REQUIRED" if errors else "FLAT",
        }


state = ControlState()

scanner_lock = threading.RLock()
scanner_cache = {
    "time": 0.0,
    "data": [],
    "scanning": False,
    "last_error": None,
    "started_at": 0.0,
    "duration_ms": 0,
    "symbols_scanned": 0,
}

SCANNER_CACHE_SECONDS = max(
    10,
    int(os.getenv("SCANNER_CACHE_SECONDS", "30")),
)
SCANNER_AUTO_REFRESH_SECONDS = max(
    30,
    int(os.getenv("SCANNER_AUTO_REFRESH_SECONDS", "60")),
)
scanner_watchdog_stop = threading.Event()
scanner_watchdog_thread = None


def _scanner_snapshot():
    with scanner_lock:
        now = time.time()
        age = (
            round(now - scanner_cache["time"], 2)
            if scanner_cache["time"]
            else None
        )
        fresh = bool(
            scanner_cache["time"]
            and age is not None
            and age < SCANNER_CACHE_SECONDS
        )
        scanning = bool(scanner_cache["scanning"])
        last_error = scanner_cache["last_error"]
        if scanning:
            state_name = "RUNNING"
        elif last_error:
            state_name = "ERROR"
        elif scanner_cache["time"]:
            state_name = "READY"
        else:
            state_name = "NOT_RUN"
        return {
            "cached": bool(scanner_cache["data"]),
            "duration_ms": int(scanner_cache["duration_ms"]),
            "symbols_scanned": int(scanner_cache["symbols_scanned"]),
            "scanner_symbols": int(scanner_cache["symbols_scanned"]),
            "fresh": fresh,
            "cache_ttl_seconds": SCANNER_CACHE_SECONDS,
            "scanning": scanning,
            "scanner_state": state_name,
            "age_seconds": age,
            "scanner_age_seconds": age,
            "last_error": last_error,
            "scanner_error": last_error,
            "started_at": scanner_cache["started_at"],
            "candidates": list(scanner_cache["data"]),
        }


def _scanner_cache_fresh():
    with scanner_lock:
        if not scanner_cache["time"]:
            return False
        return (
            time.time() - scanner_cache["time"]
        ) < SCANNER_CACHE_SECONDS


def _scanner_worker():
    try:
        with scanner_lock:
            scanner_cache["last_error"] = None

        started = time.monotonic()
        scanner = state.ensure_scanner()
        results = scanner.scan()
        data = [
            candidate.to_dict()
            for candidate in results
        ]

        with scanner_lock:
            scanner_cache["time"] = time.time()
            scanner_cache["data"] = data
            scanner_cache["duration_ms"] = int(
                (time.monotonic() - started) * 1000
            )
            scanner_cache["symbols_scanned"] = len(
                getattr(scanner, "symbols", []) or []
            )

    except Exception as exc:
        with scanner_lock:
            scanner_cache["last_error"] = (
                f"{type(exc).__name__}: {exc}"
            )

    finally:
        with scanner_lock:
            scanner_cache["scanning"] = False


def _scanner_watchdog():
    while not scanner_watchdog_stop.wait(SCANNER_AUTO_REFRESH_SECONDS):
        try:
            if not state.running or state.paused:
                continue
            if not _scanner_cache_fresh():
                _start_scanner_background()
        except Exception as exc:
            with scanner_lock:
                scanner_cache["last_error"] = f"watchdog: {type(exc).__name__}: {exc}"


def _start_scanner_background(force=False):
    with scanner_lock:
        if scanner_cache["scanning"]:
            return False
        if force:
            scanner_cache["data"] = []
            scanner_cache["time"] = 0.0
            scanner_cache["duration_ms"] = 0
        scanner_cache["last_error"] = None
        scanner_cache["started_at"] = time.time()
        scanner_cache["scanning"] = True
        thread = threading.Thread(
            target=_scanner_worker,
            daemon=True,
            name="williams-scanner",
        )
        thread.start()
        return True


def _runtime_supervisor():
    """Keep the autonomous backend alive without auto-restarting deliberate STOP/KILL."""
    while not supervisor_stop.wait(10):
        try:
            with state.lock:
                wanted = bool(state.desired_running)
                alive = bool(state.thread and state.thread.is_alive())
                paused = bool(state.paused)
            if wanted and not alive and not paused:
                # Never auto-rearm a latched emergency stop or unresolved recovery.
                try:
                    t = state.ensure_trader()
                    latched = str(t.db.state_get('kill_switch_latched', 'false')).lower() == 'true'
                    unresolved = bool(state.ensure_multi().unresolved_symbols())
                except Exception as exc:
                    state.last_error = f'supervisor probe: {type(exc).__name__}: {exc}'
                    continue
                if not latched and not unresolved:
                    state.start()
        except Exception as exc:
            state.last_error = f'supervisor: {type(exc).__name__}: {exc}'


def _heartbeat_loop():
    interval = max(300, int(os.getenv('HEARTBEAT_SECONDS', '3600')))
    while not heartbeat_stop.wait(interval):
        try:
            if not state.running or state.paused:
                continue
            t = state.ensure_trader()
            multi = state.ensure_multi()
            positions = multi.open_positions()
            account = t.client.account()
            usdt = next(
                (float(b.get('free', 0) or 0) + float(b.get('locked', 0) or 0)
                 for b in account.get('balances', []) if b.get('asset') == 'USDT'),
                0.0,
            )
            t.notify(
                'WILLIAMS HEARTBEAT\\n'
                f'state={_canonical_execution_state(multi)[0]}\\n'
                f'positions={len(positions)}\\n'
                f'USDT≈{usdt:.2f}\\n'
                f'testnet={t.client.testnet}\\n'
                f'ws={hub.snapshot().get("ws_connected")}'
            )
        except Exception as exc:
            try:
                state.ensure_trader().db.log_event(
                    'ERROR', 'heartbeat_error',
                    f'{type(exc).__name__}: {exc}'
                )
            except Exception:
                pass


@app.on_event('startup')
def startup():
    global scanner_watchdog_thread, supervisor_thread, heartbeat_thread
    scanner_watchdog_stop.clear()
    supervisor_stop.clear()
    heartbeat_stop.clear()
    scanner_watchdog_thread = threading.Thread(
        target=_scanner_watchdog,
        daemon=True,
        name="williams-scanner-watchdog",
    )
    scanner_watchdog_thread.start()
    supervisor_thread = threading.Thread(
        target=_runtime_supervisor,
        daemon=True,
        name='williams-runtime-supervisor',
    )
    supervisor_thread.start()
    heartbeat_thread = threading.Thread(
        target=_heartbeat_loop,
        daemon=True,
        name='williams-heartbeat',
    )
    heartbeat_thread.start()
    hub.configure_credentials(
        state.api_key,
        state.api_secret,
        state.testnet,
    )
    mtf_service.configure_credentials(state.api_key, state.api_secret, state.testnet)
    mtf_service.db = state.ensure_trader().db
    hub.start()
    if os.getenv('MTF_CONTEXT_ENABLED', 'true').lower() == 'true':
        mtf_service.start()
    if (
        os.getenv('AUTO_START', 'true').lower() == 'true'
        and state.api_key
        and state.api_secret
    ):
        state.start()


@app.on_event('shutdown')
def shutdown():
    scanner_watchdog_stop.set()
    supervisor_stop.set()
    heartbeat_stop.set()
    mtf_service.stop()
    hub.stop()
    state.stop()


def auth(authorization: Optional[str] = Header(None)):
    if len(API_TOKEN) < 32:
        raise HTTPException(
            503,
            'MOBILE_API_TOKEN is not configured or is too short '
            '(minimum 32 characters).',
        )
    if authorization != f'Bearer {API_TOKEN}':
        raise HTTPException(401, 'Unauthorized')


def db():
    return state.ensure_trader().db


def _canonical_execution_state(multi, positions=None):
    """Single backend execution-state contract used by health, status and recovery."""
    unresolved_symbols = list(multi.unresolved_symbols())
    pending_symbols = [symbol for symbol, _ in multi._pending_entries()]

    # Preserve legacy persisted state only for recovery/compatibility; the
    # authoritative runtime is risk-budgeted MultiPositionTrader.
    legacy_state = str(
        multi.db.state_get('position_state', 'FLAT')
    ).upper()
    active_symbol = str(
        multi.db.state_get('active_symbol')
        or (multi.symbols[0] if multi.symbols else '')
    ).upper()
    legacy_pending = str(
        multi.db.state_get('entry_client_order_id', '')
    ).strip()

    if legacy_state == 'RECONCILE_REQUIRED' and active_symbol:
        if active_symbol not in unresolved_symbols:
            unresolved_symbols.append(active_symbol)
    if legacy_pending and active_symbol:
        if active_symbol not in pending_symbols:
            pending_symbols.append(active_symbol)

    if unresolved_symbols or pending_symbols:
        return 'RECONCILE_REQUIRED', sorted(unresolved_symbols), sorted(pending_symbols)

    if positions is None:
        positions = multi.open_positions()
    return ('OPEN' if positions else 'READY_FLAT'), unresolved_symbols, pending_symbols


@app.get('/api/v1/health')
def health():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    execution_state, unresolved_symbols, pending_symbols = _canonical_execution_state(multi)
    unresolved = bool(unresolved_symbols or pending_symbols)
    p0_ready = bool(t.preflight_report and t.preflight_report.get('ready'))
    open_positions = multi.open_positions()
    kill_switch_latched = str(
        t.db.state_get('kill_switch_latched', 'false')
    ).lower() == 'true'
    execution_enabled = (
        p0_ready
        and not unresolved
        and state.running
        and not state.paused
        and not kill_switch_latched
    )
    return {
        'ok': True,
        'service': 'williams-binance-bot',
        'version': VERSION,
        'digital_williams_core': digital_williams.VERSION,
        'execution_state_contract': {
            'version': 1,
            'state': execution_state,
            'execution_enabled': execution_enabled,
            'reconciliation_required': unresolved,
            'kill_switch_latched': kill_switch_latched,
        },
        'unresolved_symbols': unresolved_symbols,
        'pending_entry_symbols': pending_symbols,
        'websocket': True,
        'auth_configured': len(API_TOKEN) >= 32,
        'max_open_positions': multi.max_open_positions,
        'max_total_risk_pct': multi.max_total_risk_pct,
        'max_risk_per_trade_pct': multi.max_risk_per_trade_pct,
        'open_positions': len(open_positions),
        'execution_enabled': execution_enabled,
        'testnet': t.client.testnet,
    }

@app.get('/api/v1/williams/core', dependencies=[Depends(auth)])
def williams_core_contract():
    """Public, credential-free description of the canonical Digital Williams core."""
    return digital_williams.contract()


@app.get('/api/v1/diagnostics/incidents', dependencies=[Depends(auth)])
def diagnostic_incidents(limit: int = 50):
    t = state.ensure_trader()
    rows = t.db.conn.execute(
        'SELECT * FROM events WHERE event_name=? ORDER BY id DESC LIMIT ?',
        ('diagnostic_incident', max(1, min(limit, 100))),
    ).fetchall()
    return [dict(row) for row in rows]



@app.post(
    '/api/v1/config/binance',
    dependencies=[Depends(auth)],
)
def configure(payload: CredentialPayload):
    if not payload.api_key or not payload.api_secret:
        raise HTTPException(
            400,
            'API key and secret are required',
        )
    try:
        state.configure(
            payload.api_key,
            payload.api_secret,
            payload.testnet,
        )
    except RuntimeError as e:
        raise HTTPException(409, str(e))
    return {
        'configured': True,
        'testnet': payload.testnet,
    }


@app.delete(
    '/api/v1/config/binance',
    dependencies=[Depends(auth)],
)
def clear_binance_config():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    try:
        managed_positions = multi.open_positions()
        exchange_orders = t.client.open_orders()
    except Exception as exc:
        raise HTTPException(
            503,
            'Cannot verify Spot account state before credential removal: ' + str(exc),
        )
    if managed_positions or exchange_orders:
        raise HTTPException(
            409,
            'Credentials cannot be removed while Spot positions or open orders exist. '
            'Close/reconcile the account first.',
        )

    state.stop()
    with state.lock:
        state.api_key = ''
        state.api_secret = ''
        state.trader = None
        state.last_error = None
        state.paused = False
    state.credentials.clear()
    hub.configure_credentials('', '', True)
    mtf_service.configure_credentials('', '', True)
    return {
        'configured': False,
        'cleared': True,
    }


@app.websocket('/api/v1/ws')
async def realtime_ws(websocket: WebSocket):
    if (
        len(API_TOKEN) < 32
        or websocket.headers.get('authorization')
        != f'Bearer {API_TOKEN}'
    ):
        await websocket.close(1008)
        return
    await websocket.accept()
    metrics.inc("ws_connections_total")
    client = type('RealtimeClient', (), {})()
    client.websocket = websocket
    client.loop = asyncio.get_running_loop()
    client.queue = asyncio.Queue()
    hub.add_client(client)
    sender = asyncio.create_task(
        _ws_sender(client)
    )
    try:
        while True:
            msg = await websocket.receive_text()
            if msg.lower() == 'ping':
                await websocket.send_text(
                    '{"type":"pong"}'
                )
            elif msg.lower() == 'snapshot':
                await websocket.send_text(
                    json.dumps(
                        {
                            'type': 'snapshot',
                            'data': hub.snapshot(),
                        },
                        separators=(',', ':'),
                    )
                )
    except WebSocketDisconnect:
        pass
    finally:
        sender.cancel()
        hub.remove_client(client)


async def _ws_sender(client):
    while True:
        await client.websocket.send_text(
            json.dumps(
                await client.queue.get(),
                separators=(',', ':'),
            )
        )


def _position_payload(t, trade):
    symbol = str(trade['symbol']).upper()
    ticker = None
    try:
        ticker = float(
            t.client.ticker_price(symbol)['price']
        )
    except Exception:
        pass

    entry = float(trade.get('entry_price') or 0.0)
    qty = float(trade.get('quantity') or 0.0)
    stop = (
        float(trade.get('stop_price') or 0.0)
        or None
    )
    take = (
        float(trade.get('take_profit_price') or 0.0)
        or None
    )
    pnl = None
    pnl_pct = None
    if ticker is not None and entry > 0:
        pnl = (ticker - entry) * qty
        pnl_pct = ticker / entry - 1.0

    return {
        'trade_id': int(trade['id']),
        'symbol': symbol,
        'side': trade.get('side', 'LONG'),
        'quantity': qty,
        'entry_price': entry,
        'current_price': ticker,
        'stop_price': stop,
        'take_profit_price': take,
        'risk_pct': float(trade.get('risk_pct') or 0.0),
        'entry_order_id': trade.get('entry_order_id'),
        'entry_client_order_id': trade.get('entry_client_order_id'),
        'exit_order_list_id': trade.get('exit_order_list_id'),
        'exit_order_list_client_id': trade.get(
            'exit_order_list_client_id'
        ),
        'opened_at': trade.get('entry_time'),
        'unrealized_pnl': pnl,
        'unrealized_pnl_pct': pnl_pct,
        'state': state.ensure_multi().state(symbol),
    }


@app.get('/api/v1/status', dependencies=[Depends(auth)])
def status():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    trades = multi.open_positions()

    positions = []
    for trade in trades:
        try:
            positions.append(
                _position_payload(t, trade)
            )
        except Exception as exc:
            state.last_error = (
                f'position-status: {exc}'
            )

    balance = None
    try:
        balance = t.available_quote()
    except Exception as exc:
        state.last_error = f'status-balance: {exc}'

    total_pnl = sum(
        float(item['unrealized_pnl'] or 0.0)
        for item in positions
    )
    reserved_risk_quote = multi.reserved_risk_quote()
    account_equity_quote = max(
        0.0,
        float(balance or 0.0),
    ) + sum(
        max(0.0, float(item.get('current_price') or 0.0))
        * max(0.0, float(item.get('quantity') or 0.0))
        for item in positions
    )
    reserved_risk_pct = (
        reserved_risk_quote / account_equity_quote
        if account_equity_quote > 0.0
        else 0.0
    )

    execution_state, unresolved_symbols, pending_symbols = _canonical_execution_state(
        multi,
        positions=positions,
    )
    p0_ready = bool(t.preflight_report and t.preflight_report.get('ready'))
    kill_switch_latched = str(
        t.db.state_get('kill_switch_latched', 'false')
    ).lower() == 'true'
    remaining_risk_quote = max(
        0.0,
        account_equity_quote * multi.max_total_risk_pct - reserved_risk_quote,
    )
    execution_enabled = (
        p0_ready
        and not bool(unresolved_symbols or pending_symbols)
        and remaining_risk_quote > 0.0
        and state.running
        and not state.paused
        and not kill_switch_latched
    )
    risk_capacity_positions = (
        int(multi.max_total_risk_pct / multi.max_risk_per_trade_pct)
        if multi.max_risk_per_trade_pct > 0
        else 0
    )

    selected_position = None
    if positions:
        selected_position = positions[0]

    return {
        'version': VERSION,
        'symbol': t.symbol,
        'interval': t.interval,
        'testnet': t.client.testnet,
        'running': state.running,
        'paused': state.paused,
        'state': execution_state,
        'recovered': t.recovered,
        'last_error': state.last_error,
        'binance_configured': bool(
            t.client.api_key and t.client.api_secret
        ),
        'price': (
            selected_position['current_price']
            if selected_position
            else None
        ),
        'quote_balance': balance,
        'position': selected_position,
        'positions': positions,
        'open_positions': len(positions),
        'max_open_positions': multi.max_open_positions,
        'reserved_risk_quote': reserved_risk_quote,
        'reserved_risk_pct': reserved_risk_pct,
        'max_total_risk_pct': multi.max_total_risk_pct,
        'max_risk_per_trade_pct': multi.max_risk_per_trade_pct,
        'unrealized_pnl_quote': total_pnl,
        'stop_loss_pct': t.stop_pct,
        'take_profit_pct': t.target_pct,
        'risk_per_trade_pct': t.risk_per_trade_pct,
        'max_daily_loss_pct': t.max_daily_loss_pct,
        'max_trades_per_day': t.max_trades_day,
        'consecutive_losses': max(
            [
                t.db.consecutive_losses(x['symbol'])
                for x in positions
            ] or [0]
        ),
        'trades_today': sum(
            t.db.trades_today(x['symbol'])
            for x in positions
        ) if positions else t.db.trades_today(t.symbol),
        'server_time': datetime.now(
            timezone.utc
        ).isoformat(),
        'market_context': mtf_service.symbol_snapshot(t.symbol),
        'market_context_status': mtf_service.snapshot_status(),
        'scanner_scanning': bool(_scanner_snapshot()['scanning']),
        'scanner_symbols': int(_scanner_snapshot()['symbols_scanned']),
        'scanner_duration_ms': int(_scanner_snapshot()['duration_ms']),
        'scanner_state': _scanner_snapshot()['scanner_state'],
        'scanner_error': _scanner_snapshot()['scanner_error'],
        'scanner_age_seconds': _scanner_snapshot()['scanner_age_seconds'],
        'market_ws_connected': bool(hub.market_connected),
        'user_ws_connected': bool(hub.user_connected),
        'user_stream_sync_required': bool(hub.user_sync_required),
        'history_ready': bool(mtf_service.snapshot_status().get('contexts', 0) >= len(t.config.symbols) * len(t.config.structural_timeframes)),
        'p0_gate_passed': bool(t.preflight_report and t.preflight_report.get('ready')),
        'p0_gate_reason': ('PASS' if t.preflight_report and t.preflight_report.get('ready') else 'NOT_READY'),
        'max_open_positions_locked': False,
        'reconcile_required': bool(unresolved_symbols or pending_symbols),
        'unresolved_symbols': unresolved_symbols,
        'pending_entry_symbols': pending_symbols,
        'execution_state_contract': {
            'version': 1,
            'state': execution_state,
            'execution_enabled': execution_enabled,
            'reconciliation_required': bool(unresolved_symbols or pending_symbols),
            'kill_switch_latched': kill_switch_latched,
        },
    }


@app.get('/api/v1/portfolio', dependencies=[Depends(auth)])
def portfolio():
    """Authoritative Binance Spot portfolio snapshot with USDT/BTC valuation."""
    t = state.ensure_trader()
    account = t.client.account()
    balances = account.get('balances', []) if isinstance(account, dict) else []
    ticker_rows = t.client.ticker_prices()
    prices = {}
    for row in ticker_rows if isinstance(ticker_rows, list) else []:
        try:
            symbol = str(row.get('symbol', '')).upper()
            price = float(row.get('price', 0) or 0)
            if symbol and price > 0:
                prices[symbol] = price
        except (TypeError, ValueError):
            continue
    btc_usdt = prices.get('BTCUSDT')
    assets = []
    for row in balances:
        if not isinstance(row, dict):
            continue
        asset = str(row.get('asset', '')).upper()
        if not asset:
            continue
        try:
            free = float(row.get('free', 0) or 0)
            locked = float(row.get('locked', 0) or 0)
        except (TypeError, ValueError):
            continue
        total = free + locked
        if total <= 1e-15:
            continue
        price_usdt = 1.0 if asset == 'USDT' else prices.get(asset + 'USDT')
        if price_usdt is None and btc_usdt:
            price_btc = prices.get(asset + 'BTC')
            if price_btc:
                price_usdt = price_btc * btc_usdt
        value_usdt = total * price_usdt if price_usdt and price_usdt > 0 else 0.0
        free_value_usdt = free * price_usdt if price_usdt and price_usdt > 0 else 0.0
        locked_value_usdt = locked * price_usdt if price_usdt and price_usdt > 0 else 0.0
        assets.append({
            'asset': asset, 'free': free, 'locked': locked, 'total': total,
            'price_usdt': price_usdt, 'value_usdt': value_usdt,
            'free_value_usdt': free_value_usdt, 'locked_value_usdt': locked_value_usdt,
        })
    total_equity_usdt = sum(x['value_usdt'] for x in assets)
    free_equity_usdt = sum(x['free_value_usdt'] for x in assets)
    locked_equity_usdt = sum(x['locked_value_usdt'] for x in assets)
    total_equity_btc = total_equity_usdt / btc_usdt if btc_usdt and btc_usdt > 0 else None
    for row in assets:
        row['allocation_pct'] = row['value_usdt'] / total_equity_usdt if total_equity_usdt > 0 else 0.0
    multi = state.ensure_multi()
    positions = []
    for trade in multi.open_positions():
        try:
            raw = _position_payload(t, trade)
            current = float(raw.get('current_price') or 0.0)
            qty = float(raw.get('quantity') or 0.0)
            position_value = current * qty if current > 0 and qty > 0 else 0.0
            positions.append({
                **raw,
                'avg_entry_price': raw.get('entry_price'),
                'stop_loss': raw.get('stop_price'),
                'take_profit': raw.get('take_profit_price'),
                'position_value_usdt': position_value,
                'allocation_pct': position_value / total_equity_usdt if total_equity_usdt > 0 else 0.0,
                'unrealized_pnl_usdt': float(raw.get('unrealized_pnl') or 0.0),
                'unrealized_pnl_pct': float(raw.get('unrealized_pnl_pct') or 0.0),
                'oco_list_id': raw.get('exit_order_list_id'),
                'oco_list_client_id': raw.get('exit_order_list_client_id'),
            })
        except Exception as exc:
            state.last_error = f'portfolio-position: {exc}'
    unrealized_pnl_usdt = sum(float(p.get('unrealized_pnl_usdt') or 0.0) for p in positions)
    realized_pnl_usdt = 0.0
    try:
        row = db().conn.execute("SELECT COALESCE(SUM(CAST(pnl AS REAL)), 0) AS pnl FROM trades WHERE pnl IS NOT NULL").fetchone()
        realized_pnl_usdt = float(row['pnl'] or 0.0) if row else 0.0
    except Exception:
        pass
    return {
        'configured': bool(t.client.api_key and t.client.api_secret),
        'testnet': bool(t.client.testnet),
        'source': 'binance_spot_account',
        'account_type': 'SPOT',
        'total_equity_usdt': total_equity_usdt,
        'total_equity_btc': total_equity_btc,
        'free_equity_usdt': free_equity_usdt,
        'locked_equity_usdt': locked_equity_usdt,
        'realized_pnl_usdt': realized_pnl_usdt,
        'unrealized_pnl_usdt': unrealized_pnl_usdt,
        'assets': assets,
        'positions': positions,
        'server_time': datetime.now(timezone.utc).isoformat(),
    }


@app.get('/api/v1/positions', dependencies=[Depends(auth)])
def positions():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    rows = multi.open_positions()
    return [
        _position_payload(t, trade)
        for trade in rows
    ]


@app.get('/api/v1/scanner', dependencies=[Depends(auth)])
def scanner(refresh: bool = False):
    started = False
    if refresh:
        # A manual SCAN must always request a fresh snapshot even if the
        # previous result is still inside the normal cache TTL.
        started = _start_scanner_background(force=True)
    result = _scanner_snapshot()
    result["version"] = VERSION
    if refresh:
        result["started"] = started
    return result


@app.get(
    '/api/v1/market/klines',
    dependencies=[Depends(auth)],
)
def market_klines(
    limit: int = 120,
    symbol: Optional[str] = None,
    interval: Optional[str] = None,
):
    t = state.ensure_trader()
    target_symbol = str(symbol or t.symbol).strip().upper()
    raw_interval = str(interval or t.interval).strip()
    target_interval = "1M" if raw_interval == "1M" else raw_interval.lower()
    allowed_intervals = {
        "1m", "3m", "5m", "15m", "30m", "1h", "2h",
        "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M",
    }
    if target_interval not in allowed_intervals:
        raise HTTPException(400, f"Unsupported Binance interval: {target_interval}")
    if not target_symbol.endswith("USDT") or not target_symbol.isalnum():
        raise HTTPException(400, "Invalid Spot symbol")
    df = fetch_klines(
        t.client,
        target_symbol,
        target_interval,
        limit=max(30, min(limit, 250)),
    )
    ind = calculate_indicators(
        df.iloc[:-1].copy(),
        config_from_env(),
    )
    rows = []
    for idx, row in ind.iterrows():
        rows.append(
            {
                'time': idx.isoformat(),
                'open': float(row.open),
                'high': float(row.high),
                'low': float(row.low),
                'close': float(row.close),
                'jaw': (
                    None
                    if row.jaw_shifted != row.jaw_shifted
                    else float(row.jaw_shifted)
                ),
                'teeth': (
                    None
                    if row.teeth_shifted != row.teeth_shifted
                    else float(row.teeth_shifted)
                ),
                'lips': (
                    None
                    if row.lips_shifted != row.lips_shifted
                    else float(row.lips_shifted)
                ),
                'ao': (
                    None
                    if row.ao != row.ao
                    else float(row.ao)
                ),
                'long_signal': bool(row.long_signal),
                'fractal_up': bool(row.fractal_up),
                'fractal_down': bool(row.fractal_down),
            }
        )
    return {
        'symbol': target_symbol,
        'interval': target_interval,
        'candles': rows,
    }


@app.get('/api/v1/market/snapshot', dependencies=[Depends(auth)])
def market_snapshot(symbol: Optional[str] = None, depth: int = 20, trades: int = 20):
    """Return one coherent read-only Binance market-data snapshot."""
    t = state.ensure_trader()
    selected = (symbol or t.symbol).upper().strip()
    depth_limit = max(5, min(int(depth), 100))
    trade_limit = max(1, min(int(trades), 100))

    ticker = t.client.ticker_price(selected)
    book = t.client.book_ticker(selected)
    day = t.client.ticker_24hr(selected)
    book_depth = t.client.depth(selected, limit=depth_limit)
    exchange = t.client.exchange_info(selected)
    recent = t.client.agg_trades(selected, limit=trade_limit)

    bids = book_depth.get('bids', []) if isinstance(book_depth, dict) else []
    asks = book_depth.get('asks', []) if isinstance(book_depth, dict) else []
    bid_qty = sum(float(row[1]) for row in bids if len(row) >= 2)
    ask_qty = sum(float(row[1]) for row in asks if len(row) >= 2)
    total_qty = bid_qty + ask_qty
    imbalance = ((bid_qty - ask_qty) / total_qty) if total_qty > 0 else 0.0

    symbol_info = next((x for x in exchange.get('symbols', []) if x.get('symbol') == selected), {})
    filters = {x.get('filterType'): x for x in symbol_info.get('filters', [])}

    return {
        'symbol': selected,
        'server_time_ms': int(time.time() * 1000),
        'price': ticker.get('price'),
        'book': {
            'bid': book.get('bidPrice'),
            'ask': book.get('askPrice'),
            'bid_qty': book.get('bidQty'),
            'ask_qty': book.get('askQty'),
        },
        'depth': {
            'last_update_id': book_depth.get('lastUpdateId') if isinstance(book_depth, dict) else None,
            'levels': depth_limit,
            'bid_qty': bid_qty,
            'ask_qty': ask_qty,
            'imbalance': imbalance,
        },
        'ticker_24h': day,
        'filters': filters,
        'recent_agg_trades': recent,
    }


@app.post('/api/v1/control/start', dependencies=[Depends(auth)])
def start():
    return {'started': state.start()}


@app.post('/api/v1/control/stop', dependencies=[Depends(auth)])
def stop():
    return {'stopped': state.stop()}


@app.post('/api/v1/control/pause', dependencies=[Depends(auth)])
def pause():
    return {'paused': state.pause()}


@app.post('/api/v1/control/resume', dependencies=[Depends(auth)])
def resume():
    return {'resumed': state.resume()}

@app.post('/api/v1/control/panic', dependencies=[Depends(auth)])
def panic():
    """PANIC STOP: block new entries without closing an existing position."""
    result = state.pause()
    try:
        state.ensure_trader().db.log_event(
            'ERROR', 'panic_stop',
            'PANIC STOP activated: new entries paused; current position left intact.',
            {'action': 'PANIC_STOP'},
        )
    except Exception:
        pass
    return {'panic_stopped': bool(result), 'trading_stopped': True, 'positions_closed': False}


@app.post('/api/v1/control/kill', dependencies=[Depends(auth)])
def kill():
    return state.kill()


@app.post('/api/v1/control/sell', dependencies=[Depends(auth)])
def manual_sell(symbol: str):
    multi = state.ensure_multi()
    result = multi.manual_sell(symbol)
    if not result.get('sold') and result.get('state') == 'RECONCILE_REQUIRED':
        raise HTTPException(409, result.get('error', 'Position requires reconciliation'))
    return result


@app.post('/api/v1/control/recover', dependencies=[Depends(auth)])
def recover():
    t = state.ensure_trader()
    multi = state.ensure_multi()

    # Recovery must use the same canonical state machine as status/health.
    # Never route the button through the legacy single-symbol recovery only.
    result = multi.recover()
    t.recovered = bool(result.get('ok'))
    execution_state, unresolved_symbols, pending_symbols = _canonical_execution_state(
        multi,
        positions=multi.open_positions(),
    )
    return {
        'recovered': bool(result.get('ok')),
        'state': execution_state,
        'unresolved_symbols': unresolved_symbols,
        'pending_entry_symbols': pending_symbols,
        'details': result,
    }


@app.get('/api/v1/trades', dependencies=[Depends(auth)])
def trades(limit: int = 50):
    return [
        dict(r)
        for r in db().conn.execute(
            'SELECT * FROM trades '
            'ORDER BY id DESC LIMIT ?',
            (max(1, min(limit, 200)),),
        ).fetchall()
    ]


@app.get('/api/v1/orders', dependencies=[Depends(auth)])
def orders(
    limit: int = 50,
    symbol: Optional[str] = None,
):
    if symbol:
        return db().recent_orders(
            symbol.upper(),
            max(1, min(limit, 200)),
        )
    return db().recent_all_orders(
        max(1, min(limit, 500))
    )


@app.get('/api/v1/logs', dependencies=[Depends(auth)])
def logs(limit: int = 100):
    return [
        dict(r)
        for r in db().conn.execute(
            'SELECT * FROM events '
            'ORDER BY id DESC LIMIT ?',
            (max(1, min(limit, 300)),),
        ).fetchall()
    ]


@app.get(
    '/api/v1/trade-journal',
    dependencies=[Depends(auth)],
)
def trade_journal_endpoint(limit: int = 100):
    return db().recent_trade_journal(limit)


@app.get('/api/v1/insights', dependencies=[Depends(auth)])
def insights():
    return db().learning_summary()


@app.get(
    '/api/v1/scanner/diagnostics',
    dependencies=[Depends(auth)],
)
def scanner_diagnostics():
    scanner = state.ensure_scanner()
    snap = _scanner_snapshot()
    snap.update(
        {
            "scan_workers": scanner.scan_workers,
            "wave_top_n": scanner.wave_top_n,
            "liquidity_preselect": getattr(
                scanner,
                "liquidity_preselect",
                0,
            ),
        }
    )
    return snap



@app.get('/api/v1/quant/health', dependencies=[Depends(auth)])
def quant_health():
    """Read-only health/status of the Williams quantitative shadow layer."""
    enabled = os.getenv("FEATURE_STORE_ENABLED", "true").lower() == "true"
    latest = quant_store.recent(limit=1)
    shadows = quant_store.recent_shadow(limit=1)
    shadow_exec = quant_store.recent_shadow_executions(limit=1)
    return {
        "enabled": enabled,
        "schema_version": 1,
        "feature_store_path": quant_store.path,
        "latest_feature_timestamp_ms": latest[0].get("timestamp_ms") if latest else None,
        "latest_shadow": shadows[0] if shadows else None,
        "latest_shadow_execution": shadow_exec[0] if shadow_exec else None,
        "production_execution": "UNCHANGED",
        "ai_order_submission": False,
    }


@app.get('/api/v1/quant/features', dependencies=[Depends(auth)])
def quant_features(symbol: Optional[str] = None, limit: int = 20):
    return quant_store.recent(
        symbol=symbol,
        limit=max(1, min(limit, 200)),
    )


@app.get('/api/v1/quant/shadow', dependencies=[Depends(auth)])
def quant_shadow(limit: int = 50):
    return quant_store.recent_shadow(max(1, min(limit, 200)))


@app.get('/api/v1/quant/shadow-execution', dependencies=[Depends(auth)])
def quant_shadow_execution(limit: int = 50):
    return quant_store.recent_shadow_executions(max(1, min(limit, 200)))

@app.get('/api/v1/settings', dependencies=[Depends(auth)])
def settings():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    return {
        'version': VERSION,
        'symbol': t.symbol,
        'interval': t.interval,
        'position_fraction': t.position_fraction,
        'stop_loss_pct': t.stop_pct,
        'take_profit_pct': t.target_pct,
        'poll_seconds': t.poll_seconds,
        'risk_per_trade_pct': t.risk_per_trade_pct,
        'max_daily_loss_pct': t.max_daily_loss_pct,
        'max_trades_per_day': t.max_trades_day,
        'max_consecutive_losses': t.max_consecutive_losses,
        'cooldown_minutes': t.cooldown_minutes,
        'min_risk_reward': t.min_risk_reward,
        'atr_period': t.atr_period,
        'max_atr_pct': t.max_atr_pct,
        'max_spread_pct': t.max_spread_pct,
        'require_htf_confirmation': t.require_htf_confirmation,
        'htf_interval': t.htf_interval,
        'testnet': t.client.testnet,
        'alligator': config_from_env(),
        'strategy_name': 'Williams Profitunity Conservative',
        'binance_configured': bool(
            t.client.api_key and t.client.api_secret
        ),
        'max_open_positions': multi.max_open_positions,
        'max_total_risk_pct': multi.max_total_risk_pct,
        'max_risk_per_trade_pct': multi.max_risk_per_trade_pct,
    }
