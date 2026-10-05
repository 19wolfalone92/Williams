import os
import time
import uuid
from datetime import datetime, timezone
from portfolio_controller import PortfolioController
from db import Database


class MultiPositionTrader:
    """Execution/recovery layer for several independent symbols.

    Safety invariants:
    - total reserved stop risk <= MAX_TOTAL_RISK_PCT (hard capped at 1%);
    - each position risk <= MAX_RISK_PER_TRADE_PCT (hard capped at 0.5%);
    - each symbol has an independent DB position state;
    - every entry gets its own native OCO;
    - DRY_RUN never sends an order.
    """

    def __init__(self, client, db=None, symbols=None):
        self.client = client
        self.db = db or Database()
        self.symbols = symbols or [
            x.strip().upper() for x in os.getenv(
                "AUTO_SCAN_SYMBOLS",
                "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,ADAUSDT,DOGEUSDT,AVAXUSDT,LINKUSDT,DOTUSDT",
            ).split(",") if x.strip()
        ]
        self.max_open_positions = max(1, int(os.getenv("MAX_OPEN_POSITIONS", "3")))
        self.max_total_risk_pct = min(0.01, max(0.0, float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01"))))
        self.max_risk_per_trade_pct = min(0.005, max(0.0, float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005"))))
        self.dry_run = os.getenv("DRY_RUN", "true").lower() == "true"
        self.poll_seconds = max(5, int(os.getenv("POLL_SECONDS", "20")))
        self._locks = set()

    def _state_key(self, symbol):
        return f"position_state:{symbol.upper()}"

    def state(self, symbol):
        return self.db.state_get(self._state_key(symbol), "FLAT")

    def set_state(self, symbol, state):
        if state not in {"FLAT", "ENTRY_PENDING", "OPEN", "EXIT_PENDING", "RECONCILE_REQUIRED"}:
            raise ValueError(f"invalid position state: {state}")
        self.db.state_set(self._state_key(symbol), state)

    def open_trades(self):
        rows = self.db.conn.execute(
            "SELECT * FROM trades WHERE exit_time IS NULL ORDER BY id ASC"
        ).fetchall()
        return [dict(r) for r in rows]

    def reserved_risk_quote(self):
        total = 0.0
        for trade in self.open_trades():
            value = trade.get("entry_price", 0.0) * trade.get("quantity", 0.0)
            stop_pct = float(os.getenv("STOP_LOSS_PCT", "0.02"))
            total += value * stop_pct
        return total

    def _balance(self):
        account = self.client.account()
        balances = account.get("balances", [])
        return sum(float(b.get("free", 0) or 0) + float(b.get("locked", 0) or 0)
                   for b in balances if b.get("asset") == "USDT")

    def _quote_free(self):
        account = self.client.account()
        for b in account.get("balances", []):
            if b.get("asset") == "USDT":
                return float(b.get("free", 0) or 0)
        return 0.0

    def _filters(self, symbol):
        info = self.client.exchange_info(symbol)
        s = info.get("symbols", [])[0]
        return {f["filterType"]: f for f in s.get("filters", [])}

    def _normalize_qty(self, symbol, qty):
        f = self._filters(symbol).get("LOT_SIZE") or self._filters(symbol).get("MARKET_LOT_SIZE")
        step = f["stepSize"] if f else "0.000001"
        min_qty = float(f.get("minQty", 0)) if f else 0.0
        q = self.client.decimal_floor(qty, step)
        return float(q) if float(q) >= min_qty else 0.0

    def _normalize_price(self, symbol, price):
        f = self._filters(symbol).get("PRICE_FILTER")
        tick = f["tickSize"] if f else "0.01"
        return float(self.client.decimal_floor(price, tick))

    def _min_notional(self, symbol):
        f = self._filters(symbol)
        n = f.get("NOTIONAL") or f.get("MIN_NOTIONAL") or {}
        return float(n.get("minNotional", 0) or 0)

    def _allocation_quote(self, balance, risk_pct, stop_pct):
        risk_quote = balance * risk_pct
        return min(balance * float(os.getenv("POSITION_FRACTION", "0.25")),
                   risk_quote / max(stop_pct, 1e-9))

    def _create_oco(self, symbol, qty, entry, trade_id):
        filters = self._filters(symbol)
        qty = self._normalize_qty(symbol, qty)
        if qty <= 0:
            raise RuntimeError(f"{symbol}: quantity below LOT_SIZE minimum")

        stop_pct = float(os.getenv("STOP_LOSS_PCT", "0.02"))
        target_pct = float(os.getenv("TAKE_PROFIT_PCT", "0.04"))
        tp = self._normalize_price(symbol, entry * (1 + target_pct))
        sl = self._normalize_price(symbol, entry * (1 - stop_pct))
        tick = float((filters.get("PRICE_FILTER") or {}).get("tickSize", "0.01"))
        sl_limit = self._normalize_price(symbol, max(sl - tick * 2, tick))

        if not (tp > entry > sl > sl_limit):
            raise RuntimeError(f"{symbol}: invalid rounded TP/SL")

        cid = f"WILLV4_OCO_{uuid.uuid4().hex[:20]}"
        self.set_state(symbol, "EXIT_PENDING")
        result = self.client.create_oco_sell(
            symbol,
            self.client.decimal_format(qty),
            self.client.decimal_format(tp),
            self.client.decimal_format(sl),
            self.client.decimal_format(sl_limit),
            cid,
        )
        for leg in result.get("orderReports", []):
            self.db.save_order(leg)
        if result.get("orderListId") is not None:
            self.db.update_trade_oco(trade_id, result["orderListId"])
        self.set_state(symbol, "OPEN")
        return result

    def recover(self):
        """Reconcile every open DB trade independently and restore missing OCO protection."""
        for trade in self.open_trades():
            symbol = trade["symbol"].upper()
            try:
                self.set_state(symbol, "OPEN")
                open_lists = self.client.open_order_lists()
                active_lists = open_lists.get("orderList", open_lists if isinstance(open_lists, list) else [])
                if any(
                    str(x.get("symbol", symbol)).upper() == symbol
                    and str(x.get("listOrderStatus", x.get("listStatusType", ""))).upper()
                    in {"EXEC_STARTED", "EXECUTING", "NEW"}
                    for x in active_lists
                ):
                    continue

                # If a bot-owned exit already filled, close the DB trade instead
                # of creating a new OCO against a position that no longer exists.
                orders = self.client.all_orders(symbol, limit=1000)
                entry_id = str(trade.get("entry_order_id") or "")
                entry_time = int(next(
                    (o.get("time", o.get("transactTime", 0)) for o in orders
                     if str(o.get("orderId")) == entry_id), 0
                ) or 0)
                sells = [
                    o for o in orders
                    if o.get("side") == "SELL"
                    and str(o.get("status", "")).upper() == "FILLED"
                    and int(o.get("time", o.get("transactTime", 0)) or 0) >= entry_time
                    and str(o.get("clientOrderId", "")).startswith(("WILLV4_OCO_", "tp-", "sl-"))
                ]
                if sells:
                    sell = max(sells, key=lambda x: int(x.get("time", x.get("transactTime", 0)) or 0))
                    qty = float(sell.get("executedQty", 0) or 0)
                    proceeds = float(sell.get("cummulativeQuoteQty", 0) or 0)
                    exit_price = proceeds / qty if qty else float(sell.get("price", 0) or 0)
                    entry = float(trade["entry_price"])
                    pnl = (exit_price - entry) * min(qty, float(trade["quantity"]))
                    pct = (exit_price / entry - 1) if entry else 0
                    self.db.close_trade(
                        trade["id"],
                        datetime.fromtimestamp(
                            int(sell.get("transactTime", sell.get("time", 0))) / 1000,
                            tz=timezone.utc,
                        ).isoformat(),
                        exit_price,
                        pnl,
                        pct,
                        "TAKE_PROFIT/STOP_LOSS (multi recovery)",
                        sell.get("orderListId"),
                    )
                    self.set_state(symbol, "FLAT")
                    continue

                # No active OCO and position still exists: restore protection.
                info = self.client.exchange_info(symbol)
                asset = info["symbols"][0]["baseAsset"]
                account = self.client.account()
                qty = next(
                    (float(b.get("free", 0) or 0) + float(b.get("locked", 0) or 0)
                     for b in account.get("balances", []) if b.get("asset") == asset),
                    0.0,
                )
                if qty <= 0:
                    self.set_state(symbol, "RECONCILE_REQUIRED")
                    self.db.log_event(
                        "ERROR", "multi_position_recovery_required",
                        "Open DB trade has no exchange position and no filled bot exit",
                        {"symbol": symbol, "trade_id": trade["id"]},
                    )
                    continue

                self._create_oco(symbol, min(qty, float(trade["quantity"])),
                                 float(trade["entry_price"]), trade["id"])
                self.db.log_event(
                    "INFO", "multi_position_oco_restored",
                    "Missing OCO protection restored during recovery",
                    {"symbol": symbol, "trade_id": trade["id"]},
                )
            except Exception as exc:
                self.set_state(symbol, "RECONCILE_REQUIRED")
                self.db.log_event(
                    "ERROR", "multi_position_recovery_error", str(exc),
                    {"symbol": symbol, "trade_id": trade["id"]},
                )

    def execute(self, selections):
        if self.dry_run:
            return [{"symbol": s.candidate.symbol, "risk_pct": s.risk.risk_pct, "dry_run": True}
                    for s in selections]

        results = []
        for selection in selections:
            if len(self.open_trades()) >= self.max_open_positions:
                break
            symbol = selection.candidate.symbol.upper()
            if symbol in self._locks or self.db.open_trade(symbol):
                continue
            if self.state(symbol) == "RECONCILE_REQUIRED":
                continue

            balance = self._balance()
            open_risk = self.reserved_risk_quote()
            total_risk_quote = balance * self.max_total_risk_pct
            remaining = max(0.0, total_risk_quote - open_risk)
            requested = min(balance * selection.risk.risk_pct / 100.0, remaining)
            stop_pct = float(os.getenv("STOP_LOSS_PCT", "0.02"))
            quote = self._allocation_quote(balance, requested / max(balance, 1e-9), stop_pct)
            quote = min(quote, self._quote_free())
            if quote < self._min_notional(symbol):
                continue

            self._locks.add(symbol)
            cid = f"WILLV4_ENTRY_{uuid.uuid4().hex[:20]}"
            self.set_state(symbol, "ENTRY_PENDING")
            self.db.state_set(f"entry_client_order_id:{symbol}", cid)
            try:
                order = self.client.order(
                    symbol, "BUY", "MARKET",
                    quote_order_qty=self.client.decimal_format(quote),
                    new_client_order_id=cid,
                )
                self.db.save_order(order)
                qty = float(order.get("executedQty", 0) or 0)
                spent = float(order.get("cummulativeQuoteQty", 0) or 0)
                entry = spent / qty if spent and qty else float(order.get("price", 0) or 0)
                if qty <= 0 or entry <= 0:
                    raise RuntimeError(f"{symbol}: BUY returned invalid fill")
                trade_id = self.db.save_trade(
                    entry_time=datetime.now(timezone.utc).isoformat(),
                    symbol=symbol, side="LONG", entry_price=entry, quantity=qty,
                    entry_order_id=str(order.get("orderId")), fees=0,
                )
                self.db.state_delete(f"entry_client_order_id:{symbol}")
                self.set_state(symbol, "OPEN")
                self._create_oco(symbol, qty, entry, trade_id)
                results.append({"symbol": symbol, "trade_id": trade_id, "risk_pct": selection.risk.risk_pct})
            except Exception as exc:
                self.set_state(symbol, "RECONCILE_REQUIRED")
                self.db.log_event("ERROR", "multi_position_entry_error", str(exc),
                                  {"symbol": symbol, "clientOrderId": cid})
                results.append({"symbol": symbol, "error": str(exc), "reconcile_required": True})
            finally:
                self._locks.discard(symbol)
        return results

    def scan_and_execute(self):
        balance = self._balance()
        controller = PortfolioController(
            self.client, balance_quote=balance, symbols=self.symbols
        )
        trades = self.open_trades()
        selections = controller.select_portfolio(
            open_risk_quote=self.reserved_risk_quote(),
            open_positions=len(trades),
        )
        return self.execute(selections)
