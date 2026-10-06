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

load_dotenv()
API_TOKEN = os.getenv('MOBILE_API_TOKEN', '').strip()
VERSION = '4.17.0'

app = FastAPI(title='Williams Binance Bot API', version=VERSION)
hub = WebSocketHub()


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
            f"williams_reconciliation_required {1 if state.ensure_multi().unresolved_symbols() else 0}",
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
        self.api_key = ''
        self.api_secret = ''
        self.testnet = True
        self.credentials = CredentialStore()
        stored = self.credentials.load()
        if stored:
            self.api_key = stored['api_key']
            self.api_secret = stored['api_secret']
            self.testnet = stored['testnet']

    def configure(self, key, secret, testnet=True):
        with self.lock:
            key = key.strip()
            secret = secret.strip()
            testnet = bool(testnet)
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

    def ensure_trader(self):
        with self.lock:
            if self.trader is None:
                self.trader = Trader(
                    api_key=self.api_key or None,
                    api_secret=self.api_secret or None,
                    testnet=self.testnet,
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
                )
            return t._multi_position_trader

    def loop(self):
        t = self.ensure_trader()
        try:
            t.setup()
            with self.lock:
                self.running = True
                self.last_error = None
            while self.running:
                if not self.paused:
                    try:
                        t.process()
                    except Exception as e:
                        self.last_error = (
                            f'{type(e).__name__}: {e}'
                        )
                        t.db.log_event(
                            'ERROR',
                            'api_loop_error',
                            self.last_error,
                        )
                time.sleep(t.poll_seconds)
        except Exception as e:
            self.last_error = f'{type(e).__name__}: {e}'
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
        # Close only Williams-managed positions/orders; never cancel
        # unrelated account orders via DELETE /openOrders.
        with self.lock:
            self.running = False
            self.paused = True
            thread = self.thread

        t = self.ensure_trader()
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
        return {
            "cached": bool(scanner_cache["data"]),
            "duration_ms": int(scanner_cache["duration_ms"]),
            "symbols_scanned": int(scanner_cache["symbols_scanned"]),
            "fresh": fresh,
            "cache_ttl_seconds": SCANNER_CACHE_SECONDS,
            "scanning": bool(scanner_cache["scanning"]),
            "age_seconds": age,
            "last_error": scanner_cache["last_error"],
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


def _start_scanner_background():
    with scanner_lock:
        if scanner_cache["scanning"]:
            return False
        scanner_cache["scanning"] = True
        thread = threading.Thread(
            target=_scanner_worker,
            daemon=True,
            name="williams-scanner",
        )
        thread.start()
        return True


@app.on_event('startup')
def startup():
    hub.configure_credentials(
        state.api_key,
        state.api_secret,
        state.testnet,
    )
    hub.start()
    if (
        os.getenv('AUTO_START', 'true').lower() == 'true'
        and state.api_key
        and state.api_secret
    ):
        state.start()


@app.on_event('shutdown')
def shutdown():
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


@app.get('/api/v1/health')
def health():
    t = state.ensure_trader()
    multi = state.ensure_multi()
    unresolved = bool(multi.unresolved_symbols() or multi._pending_entries())
    execution_state = (
        'RECONCILE_REQUIRED' if unresolved
        else ('OPEN' if multi.open_positions() else 'READY_FLAT')
    )
    return {
        'ok': True,
        'service': 'williams-binance-bot',
        'version': VERSION,
        'execution_state_contract': {
            'version': 1,
            'state': execution_state,
            'execution_enabled': not unresolved,
            'reconciliation_required': unresolved,
            'kill_switch_latched': False,
        },
        'websocket': True,
        'auth_configured': len(API_TOKEN) >= 32,
        'max_open_positions': multi.max_open_positions,
        'max_total_risk_pct': multi.max_total_risk_pct,
        'max_risk_per_trade_pct': multi.max_risk_per_trade_pct,
        'open_positions': len(multi.open_positions()),
        'execution_enabled': not bool(
            multi.unresolved_symbols()
            or multi._pending_entries()
        ),
        'testnet': t.client.testnet,
    }


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
    state.stop()
    with state.lock:
        state.api_key = ''
        state.api_secret = ''
        state.trader = None
        state.last_error = None
        state.paused = False
    state.credentials.clear()
    hub.configure_credentials('', '', True)
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
        'state': (
            'RECONCILE_REQUIRED'
            if multi.unresolved_symbols()
            else (
                'OPEN'
                if positions
                else 'FLAT'
            )
        ),
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
        'reserved_risk_quote': multi.reserved_risk_quote(),
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
    if refresh and not _scanner_cache_fresh():
        started = _start_scanner_background()
    result = _scanner_snapshot()
    result["version"] = VERSION
    if refresh:
        result["started"] = started
    return result


@app.get(
    '/api/v1/market/klines',
    dependencies=[Depends(auth)],
)
def market_klines(limit: int = 120):
    t = state.ensure_trader()
    df = fetch_klines(
        t.client,
        t.symbol,
        t.interval,
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
        'symbol': t.symbol,
        'interval': t.interval,
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
    if t.max_open_positions > 1:
        result = state.ensure_multi().recover()
        return {
            'recovered': bool(result['ok']),
            'state': (
                'RECONCILE_REQUIRED'
                if not result['ok']
                else (
                    'OPEN'
                    if result['open_positions']
                    else 'FLAT'
                )
            ),
            'details': result,
        }
    t.recover_state()
    return {
        'recovered': t.recovered,
        'state': t.state(),
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
