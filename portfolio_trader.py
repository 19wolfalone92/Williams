import os
import uuid
from decimal import Decimal
from datetime import datetime, timezone

from portfolio_controller import PortfolioController
from db import Database
from equity_breaker import EquityCircuitBreaker
from l2_slippage import L2SlippageGuard
from execution_accumulator import ExecutionSummary, accumulate_order


POSITION_STATES = {
    "FLAT",
    "ENTRY_PENDING",
    "OPEN",
    "EXIT_PENDING",
    "RECONCILE_REQUIRED",
}


class MultiPositionTrader:
    """Durable lifecycle manager for Binance Spot.
    
    The autonomous contract allows multiple managed positions while aggregate reserved risk stays within the portfolio budget.
    One SQLite trade row represents that position and remains OPEN until
    exchange evidence proves the position was fully sold.

    Lifecycle:
        ENTRY_PENDING -> OPEN/EXIT_PENDING -> OPEN -> FLAT
        -> RECONCILE_REQUIRED on ambiguity
    """

    ENTRY_PREFIX = "WILLV4_ENTRY_"
    OCO_PREFIX = "WILLV4_OCO_"
    EMERGENCY_PREFIX = "WILLV4_EMERGENCY_"
    MANUAL_PREFIX = "WILLV4_MANUAL_"

    def __init__(self, client, db=None, symbols=None):
        self.client = client
        self.db = db or Database()
        if symbols is not None:
            self.symbols = [str(x).strip().upper() for x in symbols if str(x).strip()]
        else:
            raw_symbols = os.getenv("AUTO_SCAN_SYMBOLS", "ALL").strip()
            self.symbols = (
                []
                if raw_symbols.upper() in {"ALL", "AUTO", "*"}
                else [x.strip().upper() for x in raw_symbols.split(",") if x.strip()]
            )
        self.max_open_positions = max(
            0,
            int(os.getenv("MAX_OPEN_POSITIONS", "0")),
        )
        self.max_total_risk_pct = min(
            0.01,
            max(
                0.0,
                float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01")),
            ),
        )
        self.max_risk_per_trade_pct = min(
            0.005,
            max(
                0.0,
                float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005")),
            ),
        )
        self.dry_run = (
            os.getenv("DRY_RUN", "true").lower() == "true"
        )
        self.poll_seconds = max(
            5,
            int(os.getenv("POLL_SECONDS", "20")),
        )
        self.balance_tolerance_pct = max(
            0.002,
            float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
        )
        self._locks = set()
        self.l2_guard = L2SlippageGuard(
            float(os.getenv("MAX_L2_SLIPPAGE_PCT", os.getenv("MAX_SPREAD_PCT", "0.0015")))
        )
        self.equity_breaker = EquityCircuitBreaker(float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03")))
        self.max_trades_per_day = max(0, int(os.getenv("MAX_TRADES_PER_DAY", "5")))
        self.max_consecutive_losses = max(0, int(os.getenv("MAX_CONSECUTIVE_LOSSES", "3")))
        self.cooldown_minutes = max(0, int(os.getenv("COOLDOWN_MINUTES", "30")))

    # ------------------------------------------------------------------
    # Durable state
    # ------------------------------------------------------------------

    def _state_key(self, symbol):
        return f"position_state:{symbol.upper()}"

    def _pending_key(self, symbol):
        return f"entry_client_order_id:{symbol.upper()}"

    def state(self, symbol):
        state = self.db.state_get(
            self._state_key(symbol),
            "FLAT",
        )
        return state if state in POSITION_STATES else "RECONCILE_REQUIRED"

    def set_state(self, symbol, state):
        if state not in POSITION_STATES:
            raise ValueError(f"invalid position state: {state}")
        self.db.state_set(
            self._state_key(symbol),
            state,
        )

    def _sync_legacy_state(self):
        """Keep the legacy single-symbol state as a mirror of canonical state."""
        unresolved = self.unresolved_symbols()
        pending = self._pending_entries()
        if unresolved or pending:
            canonical = "RECONCILE_REQUIRED"
        elif self.open_trades():
            canonical = "OPEN"
        else:
            canonical = "FLAT"
        self.db.state_set("position_state", canonical)
        return canonical

    def _bot_entry_remaining(self, symbol):
        """Return True when exchange history shows an unresolved Williams BUY."""
        all_orders = self.client.all_orders(symbol, limit=1000)
        min_qty = 0.0
        try:
            filters = self._filters(symbol)
            lot = filters.get("LOT_SIZE") or filters.get("MARKET_LOT_SIZE") or {}
            min_qty = float(lot.get("minQty", 0) or 0)
        except Exception:
            # A metadata failure is itself ambiguous; caller must keep the barrier.
            raise

        for buy in all_orders:
            if (
                str(buy.get("side", "")).upper() != "BUY"
                or str(buy.get("status", "")).upper() != "FILLED"
                or not str(buy.get("clientOrderId", "")).startswith(self.ENTRY_PREFIX)
            ):
                continue

            bought = float(buy.get("executedQty", 0) or 0)
            if bought <= 0:
                continue

            buy_time = int(
                buy.get("time", buy.get("transactTime", 0)) or 0
            )
            sold = 0.0
            for sell in all_orders:
                if (
                    str(sell.get("side", "")).upper() != "SELL"
                    or str(sell.get("status", "")).upper() != "FILLED"
                    or not str(sell.get("clientOrderId", "")).startswith((self.OCO_PREFIX, self.EMERGENCY_PREFIX))
                ):
                    continue
                sell_time = int(
                    sell.get("time", sell.get("transactTime", 0)) or 0
                )
                if sell_time >= buy_time:
                    sold += float(sell.get("executedQty", 0) or 0)

            if max(0.0, bought - sold) >= min_qty:
                return True

        return False

    def _repair_stale_reconcile_states(self):
        """Clear a persisted recovery barrier only after clean exchange evidence.

        The previous implementation iterated only over self.symbols.  In
        AUTO/ALL scan mode that list is intentionally empty, so a stale
        position_state:<symbol>=RECONCILE_REQUIRED left in SQLite could never
        be examined.  That made a clean Testnet restart remain blocked forever.

        Build the recovery set from configured symbols *and* persisted
        per-symbol barriers, pending entries, and active_symbol.  A barrier is
        still preserved whenever Binance shows any open order or a confirmed
        Williams BUY with remaining quantity.
        """
        repaired = []

        symbols = {
            str(symbol).upper()
            for symbol in (self.symbols or [])
            if str(symbol).strip()
        }

        active_symbol = self.db.state_get("active_symbol")
        if active_symbol:
            symbols.add(str(active_symbol).upper())

        rows = self.db.conn.execute(
            "SELECT key FROM bot_state WHERE key LIKE 'position_state:%'"
        ).fetchall()
        for row in rows:
            key = str(row["key"])
            if ":" in key:
                symbols.add(key.split(":", 1)[1].upper())

        for symbol, _ in self._pending_entries():
            symbols.add(symbol)

        for symbol in sorted(symbols):
            if self.state(symbol) != "RECONCILE_REQUIRED":
                continue
            if self.db.open_trade(symbol) is not None:
                continue
            if any(item[0] == symbol for item in self._pending_entries()):
                continue

            try:
                open_orders = self.client.open_orders(symbol)
                if open_orders:
                    # Any active exchange order means the state is still ambiguous.
                    continue
                if self._bot_entry_remaining(symbol):
                    # A filled Williams BUY without a local trade is not safe to auto-clear.
                    continue

                self.set_state(symbol, "FLAT")
                repaired.append(symbol)
                self.db.log_event(
                    "INFO",
                    "stale_reconcile_cleared",
                    "Cleared stale per-symbol reconciliation barrier after clean exchange recovery",
                    {"symbol": symbol},
                )
            except Exception as exc:
                self.db.log_event(
                    "ERROR",
                    "stale_reconcile_check_failed",
                    str(exc),
                    {"symbol": symbol},
                )

        # The legacy single-symbol barrier must not survive a clean canonical
        # recovery.  _sync_legacy_state() below will write the authoritative
        # FLAT/OPEN state after all per-symbol checks have completed.
        return repaired

    def open_trades(self):
        return self.db.open_trades()

    def open_positions(self):
        return self.open_trades()

    def unresolved_symbols(self):
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state "
            "WHERE key LIKE 'position_state:%' "
            "ORDER BY key"
        ).fetchall()
        return [
            str(row["key"]).split(":", 1)[1].upper()
            for row in rows
            if str(row["value"]).upper() == "RECONCILE_REQUIRED"
        ]

    # ------------------------------------------------------------------
    # Account / filters
    # ------------------------------------------------------------------

    def _portfolio_equity_quote(self, account=None):
        """Return bot-managed portfolio equity in USDT.

        USDT alone is not sufficient once multiple Spot positions exist:
        capital moved from USDT into BTC/ETH/etc. remains part of portfolio
        equity. Foreign assets are deliberately excluded from the risk base.
        """
        account = account or self.client.account()
        equity = sum(
            float(b.get("free", 0) or 0)
            + float(b.get("locked", 0) or 0)
            for b in account.get("balances", [])
            if str(b.get("asset", "")).upper() == "USDT"
        )
        for trade in self.open_trades():
            symbol = str(trade["symbol"]).upper()
            qty = float(trade.get("quantity", 0) or 0)
            if qty <= 0:
                continue
            try:
                mark = float(self.client.ticker_price(symbol)["price"])
            except Exception:
                # A missing mark must never create extra risk capacity.
                continue
            if mark > 0:
                equity += qty * mark
        return max(0.0, equity)

    def _balance(self):
        return self._portfolio_equity_quote()

    def _quote_free(self):
        account = self.client.account()
        return next(
            (
                float(b.get("free", 0) or 0)
                for b in account.get("balances", [])
                if b.get("asset") == "USDT"
            ),
            0.0,
        )

    def _asset_balance(self, symbol, account=None):
        if account is None:
            account = self.client.account()
        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise RuntimeError(f"{symbol}: symbol metadata unavailable")
        asset = rows[0]["baseAsset"]
        return next(
            (
                float(b.get("free", 0) or 0)
                + float(b.get("locked", 0) or 0)
                for b in account.get("balances", [])
                if b.get("asset") == asset
            ),
            0.0,
        )

    def _filters(self, symbol):
        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise RuntimeError(f"{symbol}: symbol metadata unavailable")
        return {
            f["filterType"]: f
            for f in rows[0].get("filters", [])
        }

    def _normalize_qty(self, symbol, qty):
        filters = self._filters(symbol)
        f = (
            filters.get("LOT_SIZE")
            or filters.get("MARKET_LOT_SIZE")
        )
        step = f["stepSize"] if f else "0.000001"
        min_qty = float(f.get("minQty", 0)) if f else 0.0
        value = self.client.decimal_floor(qty, step)
        value = float(value)
        return value if value >= min_qty else 0.0

    def _normalize_price(self, symbol, price):
        filters = self._filters(symbol)
        f = filters.get("PRICE_FILTER")
        tick = f["tickSize"] if f else "0.01"
        return float(self.client.decimal_floor(price, tick))

    def _min_notional(self, symbol):
        filters = self._filters(symbol)
        f = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
        return float(f.get("minNotional", 0) or 0)

    # ------------------------------------------------------------------
    # Order-list helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _list_rows(payload):
        if isinstance(payload, list):
            return payload
        if not isinstance(payload, dict):
            return []
        for key in (
            "orderList",
            "orderLists",
            "ordersLists",
            "result",
        ):
            value = payload.get(key)
            if isinstance(value, list):
                return value
        return []

    def _open_order_lists(self):
        return self._list_rows(
            self.client.open_order_lists()
        )

    def _all_order_lists(self, symbol=None):
        method = getattr(self.client, "all_order_lists", None)
        if method is None:
            return []
        return self._list_rows(
            method(symbol=symbol, limit=100)
        )

    def _bot_oco_list(self, trade, open_lists, history_lists):
        wanted_id = str(
            trade.get("exit_order_list_id") or ""
        )
        wanted_client = str(
            trade.get("exit_order_list_client_id") or ""
        )
        for row in open_lists + history_lists:
            row_symbol = str(row.get("symbol", "")).upper()
            if row_symbol != str(trade["symbol"]).upper():
                continue
            lid = str(row.get("orderListId", ""))
            cid = str(row.get("listClientOrderId", ""))
            if wanted_id and lid == wanted_id:
                return row
            if wanted_client and cid == wanted_client:
                return row
            if cid.startswith(self.OCO_PREFIX):
                return row
        return None

    @staticmethod
    def _filled_exit_metrics(rows):
        qty = 0.0
        quote = 0.0
        last = None
        for row in rows:
            if (
                str(row.get("side", "")).upper() != "SELL"
                or str(row.get("status", "")).upper() != "FILLED"
            ):
                continue
            row_qty = float(row.get("executedQty", 0) or 0)
            row_quote = float(row.get("cummulativeQuoteQty", 0) or 0)
            if row_qty <= 0:
                continue
            qty += row_qty
            if row_quote > 0:
                quote += row_quote
            last = row
        return qty, quote, last

    @staticmethod
    def _executed_exit_metrics(rows):
        """Count every executed SELL quantity, including terminal partial fills.

        Binance can report a partially-filled order later as CANCELED/EXPIRED.
        The executed quantity is still real inventory movement and must remain
        part of reconciliation.
        """
        qty = 0.0
        quote = 0.0
        last = None
        for row in rows:
            if str(row.get("side", "")).upper() != "SELL":
                continue
            row_qty = float(row.get("executedQty", 0) or 0)
            if row_qty <= 0:
                continue
            row_quote = float(row.get("cummulativeQuoteQty", 0) or 0)
            qty += row_qty
            if row_quote > 0:
                quote += row_quote
            if last is None or int(
                row.get("time", row.get("transactTime", 0)) or 0
            ) >= int(last.get("time", last.get("transactTime", 0)) or 0):
                last = row
        return qty, quote, last

    @staticmethod
    def _filled_qty(rows):
        return MultiPositionTrader._filled_exit_metrics(rows)[0]

    @staticmethod
    def _exit_price(row):
        qty = float(row.get("executedQty", 0) or 0)
        quote = float(row.get("cummulativeQuoteQty", 0) or 0)
        if qty > 0 and quote > 0:
            return quote / qty
        return float(row.get("price", 0) or 0)

    @staticmethod
    def _exit_reason(row):
        typ = str(row.get("type", "")).upper()
        if "TAKE_PROFIT" in typ:
            return "TAKE_PROFIT"
        if "STOP_LOSS" in typ:
            return "STOP_LOSS"
        return "BOT_OCO_EXIT"

    def _sell_belongs_to_bot(self, order, all_orders):
        client_id = str(order.get("clientOrderId") or "").strip()
        if client_id.startswith(
            (self.OCO_PREFIX, self.EMERGENCY_PREFIX, self.MANUAL_PREFIX)
        ):
            return True
        order_list_id = str(order.get("orderListId") or "").strip()
        if not order_list_id:
            return False
        return any(
            str(row.get("orderListId") or "") == order_list_id
            and str(row.get("clientOrderId") or "").startswith(self.OCO_PREFIX)
            for row in all_orders or []
        )

    def _bot_exit_orders(self, trade, all_orders, oco):
        entry_id = str(trade.get("entry_order_id") or "")
        entry_order = next(
            (
                o for o in all_orders
                if str(o.get("orderId")) == entry_id
            ),
            None,
        )
        entry_time = int(
            (
                entry_order.get("time", entry_order.get("transactTime", 0))
                if entry_order else 0
            ) or 0
        )

        list_id = str(
            trade.get("exit_order_list_id") or ""
        )
        if not list_id and oco:
            list_id = str(oco.get("orderListId", ""))

        result = []
        for order in all_orders:
            if str(order.get("symbol", "")).upper() != str(trade["symbol"]).upper():
                continue
            if str(order.get("side", "")).upper() != "SELL":
                continue
            order_time = int(
                order.get("time", order.get("transactTime", 0)) or 0
            )
            if order_time < entry_time:
                continue
            order_list_id = str(order.get("orderListId", ""))
            client_id = str(order.get("clientOrderId", ""))
            if (
                list_id
                and order_list_id == list_id
            ) or (
                client_id.startswith((self.OCO_PREFIX, self.EMERGENCY_PREFIX, self.MANUAL_PREFIX))
            ):
                result.append(order)

        return result

    # ------------------------------------------------------------------
    # Risk
    # ------------------------------------------------------------------

    def reserved_risk_quote(self):
        total = 0.0
        for trade in self.open_trades():
            entry = float(trade.get("entry_price") or 0.0)
            qty = float(trade.get("quantity") or 0.0)
            stop = float(trade.get("stop_price") or 0.0)
            if entry <= 0 or qty <= 0:
                continue
            if stop > 0:
                stop_fraction = max(
                    0.0,
                    (entry - stop) / entry,
                )
            else:
                stop_fraction = float(
                    os.getenv("STOP_LOSS_PCT", "0.02")
                )
            total += entry * qty * stop_fraction
        return total

    def _allocation_quote(self, balance, risk_fraction, stop_fraction):
        risk_quote = balance * risk_fraction
        return min(
            balance * float(os.getenv("POSITION_FRACTION", "0.25")),
            risk_quote / max(stop_fraction, 1e-9),
        )

    # ------------------------------------------------------------------
    # Protection
    # ------------------------------------------------------------------

    def _fee_quote_for_asset(self, asset, base_asset, trade_price=0.0):
        asset = str(asset or "").upper()
        if asset in {"USDT", "USDC", "FDUSD", "BUSD"}:
            return 1.0
        if asset.endswith("USDT"):
            return 1.0
        if trade_price > 0 and asset:
            # For the traded base asset, valuing the commission at the fill
            # price is exact enough for transaction accounting.
            return float(trade_price) if asset == str(base_asset or "").upper() else 0.0
        try:
            return float(self.client.ticker_price(asset + "USDT")["price"])
        except Exception:
            pass
        try:
            cross = float(self.client.ticker_price(asset + "BTC")["price"])
            btc = float(self.client.ticker_price("BTCUSDT")["price"])
            return cross * btc
        except Exception as exc:
            raise RuntimeError(
                f"{asset}: cannot value Binance commission in USDT"
            ) from exc

    def _authoritative_execution(self, symbol, order):
        """Accumulate Binance fills, recovering fill-level commissions when needed."""
        payload = dict(order or {})
        fills = list(payload.get("fills") or [])
        order_id = payload.get("orderId")
        if not fills and order_id is not None and hasattr(self.client, "my_trades"):
            try:
                rows = self.client.my_trades(symbol, order_id=order_id, limit=1000)
                fills = [
                    {
                        "price": row.get("price", "0"),
                        "qty": row.get("qty", row.get("executedQty", "0")),
                        "commission": row.get("commission", "0"),
                        "commissionAsset": row.get("commissionAsset", ""),
                    }
                    for row in (rows or [])
                ]
                if fills:
                    payload["fills"] = fills
            except Exception as exc:
                self.db.log_event(
                    "WARNING",
                    "fill_commission_lookup_failed",
                    f"{symbol}: myTrades lookup unavailable; using order-level fill data",
                    {"order_id": order_id, "error": str(exc)},
                )

        base_asset = symbol[:-4] if symbol.endswith("USDT") else ""
        summary = accumulate_order(payload, base_asset=base_asset)
        fills = list(payload.get("fills") or [])
        extra_fee_quote = Decimal("0")
        known_assets = {"USDT", "USDC", "FDUSD", "BUSD", base_asset}
        for fill in fills:
            commission = Decimal(str(fill.get("commission", "0") or "0"))
            asset = str(fill.get("commissionAsset", "")).upper()
            if commission <= 0 or asset in known_assets:
                continue
            price = float(fill.get("price", "0") or 0.0)
            extra_fee_quote += commission * Decimal(str(
                self._fee_quote_for_asset(asset, base_asset, trade_price=price)
            ))

        if extra_fee_quote <= 0:
            return summary
        return ExecutionSummary(
            summary.executed_qty,
            summary.quote_qty,
            summary.avg_price,
            summary.fee_quote,
            summary.commission_base,
            summary.fee_quote_equivalent + extra_fee_quote,
        )


