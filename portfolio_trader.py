import os
import uuid
from decimal import Decimal
from datetime import datetime, timezone

from portfolio_controller import PortfolioController
from db import Database
from equity_breaker import EquityCircuitBreaker
from l2_slippage import L2SlippageGuard
from execution_accumulator import ExecutionSummary, accumulate_order
from campaign_execution import CampaignExecutionService, CampaignExecutionError
from campaign_model import SignalSpec, SignalType, SignalRole
from campaign_monitor import CampaignMonitor
from execution_barrier import OrderIntent
from williams_intraday_spec import IntradayPolicy
from decision_trace import DecisionTrace


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

    def __init__(self, client, db=None, symbols=None, execution_barrier=None, strategy_profile=None):
        self.client = client
        self.db = db or Database()
        self.intraday_policy = IntradayPolicy.from_env()
        explicit_profile = str(strategy_profile or os.getenv("WILLIAMS_STRATEGY_PROFILE", "")).strip().upper()
        self.intraday_core_enabled = explicit_profile in {"WILLIAMS_INTRADAY_CORE", "WILLIAMS_INTRADAY_CONSERVATIVE"}
        if symbols is not None:
            self.symbols = [str(x).strip().upper() for x in symbols if str(x).strip()]
        else:
            raw_symbols = os.getenv("AUTO_SCAN_SYMBOLS", "ALL").strip()
            self.symbols = (
                []
                if raw_symbols.upper() in {"ALL", "AUTO", "*"}
                else [x.strip().upper() for x in raw_symbols.split(",") if x.strip()]
            )
        if self.intraday_core_enabled and not self.symbols:
            self.symbols = list(self.intraday_policy.symbols)
        self.max_open_positions = max(
            0,
            int(os.getenv("MAX_OPEN_POSITIONS", str(self.intraday_policy.risk.max_campaigns if self.intraday_core_enabled else 5))),
        )
        self.max_total_risk_pct = min(
            0.006 if self.intraday_core_enabled else 0.01,
            max(
                0.0,
                float(os.getenv("MAX_TOTAL_RISK_PCT", str(self.intraday_policy.risk.campaign_risk_pct if self.intraday_core_enabled else 0.01))),
            ),
        )
        self.max_risk_per_trade_pct = min(
            0.0025 if self.intraday_core_enabled else 0.005,
            max(
                0.0,
                float(os.getenv("MAX_RISK_PER_TRADE_PCT", os.getenv("RISK_PER_TRADE_PCT", str(self.intraday_policy.risk.initial_risk_pct)))),
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
        self.equity_breaker = EquityCircuitBreaker(float(os.getenv("MAX_DAILY_LOSS_PCT", str(self.intraday_policy.risk.daily_loss_pct))))
        self.max_trades_per_day = max(0, int(os.getenv("MAX_TRADES_PER_DAY", "0" if self.intraday_core_enabled else "5")))
        self.max_consecutive_losses = max(0, int(os.getenv("MAX_CONSECUTIVE_LOSSES", str(self.intraday_policy.risk.max_full_stopouts if self.intraday_core_enabled else 3))))
        self.cooldown_minutes = max(0, int(os.getenv("COOLDOWN_MINUTES", "30")))
        self.execution_barrier = execution_barrier
        self.campaign_engine_enabled = (
            os.getenv("CAMPAIGN_ENGINE", "true").lower() == "true"
        )
        self.campaign_execution = CampaignExecutionService(
            self.client,
            self.db,
            execution_barrier=self.execution_barrier,
            strategy_profile=self.intraday_policy.profile if self.intraday_core_enabled else None,
        )
        self.campaign_monitor = CampaignMonitor(
            self.client,
            self.db,
            self.campaign_execution,
        )

    # ------------------------------------------------------------------
    # Durable state
    # ------------------------------------------------------------------

    def _execution_mutation(self, symbol, side, order_type, *, purpose, submit, permission_interval=None, campaign_id="", signal_id=""):
        """Route every legacy Binance mutation through the canonical door."""
        if self.execution_barrier is None:
            raise RuntimeError("ExecutionBarrier is required; direct Binance mutation is forbidden")
        interval = str(permission_interval or os.getenv("INTERVAL", "1h")).lower()
        snapshot = self.execution_barrier.context_cache.snapshot()
        required = snapshot.versions(str(symbol).upper(), [interval])
        purpose_value = str(purpose).upper()
        intent = OrderIntent.new(
            str(symbol).upper(), str(side).upper(), str(order_type).upper(),
            required_context_versions=required, purpose=purpose_value,
            permission_interval=interval if purpose_value.endswith(("_ENTRY", "_ADD_ON")) else "",
            campaign_id=str(campaign_id or ""), signal_id=str(signal_id or ""),
        )
        result = self.execution_barrier.execute(intent, submit)
        if not result.accepted:
            raise RuntimeError(f"ExecutionBarrier blocked: {result.reason}")
        return result.response
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
                    or not str(sell.get("clientOrderId", "")).startswith((self.OCO_PREFIX, self.EMERGENCY_PREFIX, self.MANUAL_PREFIX))
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

    def _normalize_qty(self, symbol, qty, *, market=False):
        """Normalize quantity using the Binance filter for the exact order type.

        MARKET orders use MARKET_LOT_SIZE; normal/OCO orders use LOT_SIZE.
        """
        filters = self._filters(symbol)
        if market:
            f = filters.get("MARKET_LOT_SIZE") or filters.get("LOT_SIZE")
        else:
            f = filters.get("LOT_SIZE") or filters.get("MARKET_LOT_SIZE")
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

    def _exit_fee_quote(self, symbol, exit_orders):
        if not hasattr(self.client, "my_trades"):
            return 0.0
        total = 0.0
        seen = set()
        base_asset = symbol[:-4] if symbol.endswith("USDT") else ""
        for order in exit_orders or []:
            order_id = str(order.get("orderId") or "").strip()
            if not order_id or order_id in seen:
                continue
            seen.add(order_id)
            try:
                rows = self.client.my_trades(symbol, order_id=order_id, limit=1000)
            except Exception:
                continue
            for row in rows or []:
                commission = float(row.get("commission", 0) or 0)
                asset = str(row.get("commissionAsset", "")).upper()
                if commission <= 0:
                    continue
                price = float(row.get("price", 0) or 0)
                total += commission * self._fee_quote_for_asset(
                    asset,
                    base_asset,
                    trade_price=price,
                )
        return total

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

    @staticmethod
    def _effective_risk_fraction(stop_fraction):
        fee_per_side = max(0.0, float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        slippage_buffer = max(0.0, float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        return max(0.0, float(stop_fraction) + (2.0 * fee_per_side) + slippage_buffer)

    def reserved_risk_quote(self):
        if self.campaign_engine_enabled:
            try:
                return max(
                    0.0,
                    float(self.campaign_execution.engine.portfolio_reserved_risk_quote()),
                )
            except Exception:
                # A campaign risk calculation failure must never open extra capacity.
                return self.max_total_risk_pct * max(self._balance(), 0.0)
        total = 0.0
        for trade in self.open_trades():
            entry = float(trade.get("entry_price") or 0.0)
            qty = float(trade.get("quantity") or 0.0)
            stop = float(trade.get("stop_price") or 0.0)
            if entry <= 0 or qty <= 0:
                continue
            if stop > 0:
                stop_fraction = max(0.0, (entry - stop) / entry)
            else:
                stop_fraction = float(os.getenv("STOP_LOSS_PCT", "0.02"))
            total += entry * qty * self._effective_risk_fraction(stop_fraction)
        return total

    def _allocation_quote(self, balance, risk_fraction, stop_fraction):
        risk_quote = balance * risk_fraction
        effective_loss_fraction = self._effective_risk_fraction(stop_fraction)
        return min(
            balance * float(os.getenv("POSITION_FRACTION", "0.25")),
            risk_quote / max(effective_loss_fraction, 1e-9),
        )
    def _fee_quote_for_asset(self, asset, base_asset, trade_price=0.0):
        asset = str(asset or "").upper()
        if asset in {"USDT", "USDC", "FDUSD", "BUSD"}:
            return 1.0
        if trade_price > 0 and asset == str(base_asset or "").upper():
            return float(trade_price)
        try:
            return float(self.client.ticker_price(asset + "USDT")["price"])
        except Exception:
            pass
        try:
            cross = float(self.client.ticker_price(asset + "BTC")["price"])
            btc = float(self.client.ticker_price("BTCUSDT")["price"])
            return cross * btc
        except Exception as exc:
            raise RuntimeError(f"{asset}: cannot value Binance commission in USDT") from exc

    def _authoritative_execution(self, symbol, order):
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
        extra_fee_quote = Decimal("0")
        known_assets = {"USDT", "USDC", "FDUSD", "BUSD", base_asset}
        for fill in payload.get("fills") or []:
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

    # ------------------------------------------------------------------
    # Protection
    # ------------------------------------------------------------------

    def _emergency_market_sell(self, symbol, qty, trade_id, reason):
        """Close a just-filled/unprotected Spot position if protection is already breached."""
        if self.dry_run:
            raise RuntimeError(
                f"{symbol}: emergency exit required ({reason}) but DRY_RUN=true"
            )

        symbol = str(symbol).upper()
        account = self.client.account()
        free_qty = self._asset_balance(symbol, account=account)
        sell_qty = self._normalize_qty(symbol, min(float(qty), free_qty), market=True)
        if sell_qty <= 0:
            raise RuntimeError(
                f"{symbol}: emergency exit quantity below Binance LOT_SIZE"
            )

        client_id = f"{self.EMERGENCY_PREFIX}{uuid.uuid4().hex[:16]}"
        sell = self._execution_mutation(
            symbol, "SELL", "MARKET", purpose="LEGACY_EMERGENCY_EXIT",
            submit=lambda: self.client.order_safe(
                symbol, "SELL", "MARKET",
                quantity=self.client.decimal_format(sell_qty),
                new_client_order_id=client_id,
            ),
        )
        self.db.save_order(sell)

        order_id = sell.get("orderId")
        confirmed = self.client.get_order(
            symbol,
            order_id=order_id,
            orig_client_order_id=client_id if order_id is None else None,
        )
        if str(confirmed.get("status", "")).upper() != "FILLED":
            raise RuntimeError(
                f"{symbol}: emergency SELL is not fully filled"
            )

        execution = self._authoritative_execution(symbol, confirmed)
        executed_qty = float(execution.executed_qty)
        exit_quote = float(execution.quote_qty)
        exit_fee_quote = float(execution.fee_quote_equivalent)
        exit_price = (
            float(execution.avg_price)
            if execution.avg_price
            else (exit_quote / executed_qty if executed_qty > 0 and exit_quote > 0 else 0.0)
        )
        if executed_qty <= 0 or exit_price <= 0:
            raise RuntimeError(f"{symbol}: emergency SELL returned invalid fill")

        trade = next(
            (
                row for row in self.open_trades()
                if int(row["id"]) == int(trade_id)
            ),
            None,
        )
        managed_qty = float(trade.get("quantity") or 0.0) if trade is not None else 0.0
        tolerance = max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            managed_qty * self.balance_tolerance_pct,
        )

        if trade is not None and executed_qty + tolerance >= managed_qty:
            entry_price = float(trade.get("entry_price") or 0.0)
            entry_fee_quote = float(trade.get("fees") or 0.0)
            pnl = (
                exit_quote
                - entry_price * managed_qty
                - entry_fee_quote
                - exit_fee_quote
            )
            pnl_pct = (exit_price / entry_price - 1.0) if entry_price > 0 else 0.0
            self.db.close_trade(
                trade_id,
                datetime.now(timezone.utc).isoformat(),
                exit_price,
                pnl,
                pnl_pct,
                reason,
                fees=entry_fee_quote + exit_fee_quote,
            )
            self.set_state(symbol, "FLAT")
            state = "FLAT"
            residual_qty = 0.0
        elif trade is not None and executed_qty > 0:
            residual_qty = max(0.0, managed_qty - executed_qty)
            self.db.update_trade_quantity(trade_id, residual_qty)
            self.set_state(symbol, "RECONCILE_REQUIRED")
            self.db.log_event(
                "ERROR",
                "emergency_market_exit_partial",
                "Emergency SELL partially filled; residual position remains unprotected",
                {
                    "symbol": symbol,
                    "trade_id": trade_id,
                    "executed_qty": executed_qty,
                    "managed_qty": managed_qty,
                    "residual_qty": residual_qty,
                    "exit_price": exit_price,
                    "reason": reason,
                },
            )
            state = "RECONCILE_REQUIRED"
        else:
            residual_qty = managed_qty
            self.set_state(symbol, "RECONCILE_REQUIRED")
            state = "RECONCILE_REQUIRED"

        self.db.log_event(
            "WARNING",
            "emergency_market_exit",
            "Unprotected managed position emergency exit submitted",
            {
                "symbol": symbol,
                "trade_id": trade_id,
                "quantity": executed_qty,
                "residual_qty": residual_qty,
                "exit_price": exit_price,
                "reason": reason,
                "state": state,
            },
        )
        return {
            "emergency_exit": True,
            "symbol": symbol,
            "trade_id": trade_id,
            "quantity": executed_qty,
            "residual_quantity": residual_qty,
            "exit_price": exit_price,
            "reason": reason,
            "state": state,
        }

    def _create_oco(
        self,
        symbol,
        qty,
        entry,
        trade_id,
        stop_fraction=None,
        target_fraction=None,
        risk_pct=None,
    ):
        filters = self._filters(symbol)
        qty = self._normalize_qty(symbol, qty)
        if qty <= 0:
            raise RuntimeError(
                f"{symbol}: quantity below LOT_SIZE minimum"
            )

        stop_fraction = (
            float(stop_fraction)
            if stop_fraction is not None
            else float(os.getenv("STOP_LOSS_PCT", "0.02"))
        )
        target_fraction = (
            float(target_fraction)
            if target_fraction is not None
            else float(os.getenv("TAKE_PROFIT_PCT", "0.04"))
        )

        if not (0 < stop_fraction < 1):
            raise RuntimeError(
                f"{symbol}: invalid stop fraction {stop_fraction}"
            )
        if not (0 < target_fraction < 10):
            raise RuntimeError(
                f"{symbol}: invalid target fraction {target_fraction}"
            )

        tp = self._normalize_price(
            symbol,
            entry * (1.0 + target_fraction),
        )
        sl = self._normalize_price(
            symbol,
            entry * (1.0 - stop_fraction),
        )
        tick = float(
            (filters.get("PRICE_FILTER") or {}).get(
                "tickSize",
                "0.01",
            )
        )
        sl_limit = self._normalize_price(
            symbol,
            max(sl - tick * 2, tick),
        )

        # Re-check executable market state after the BUY fill. If price has
        # already crossed TP/SL, do not submit an invalid/stale OCO. Close the
        # unprotected position immediately and persist the exit.
        if hasattr(self.client, "book_ticker"):
            book = self.client.book_ticker(symbol)
            bid = float(book.get("bidPrice", 0) or 0)
            if bid > 0 and bid >= tp:
                return self._emergency_market_sell(
                    symbol, qty, trade_id, "TAKE_PROFIT_REACHED_BEFORE_OCO"
                )
            if bid > 0 and bid <= sl:
                return self._emergency_market_sell(
                    symbol, qty, trade_id, "STOP_LOSS_REACHED_BEFORE_OCO"
                )

        # Binance validates SELL OCO levels against the current last-traded
        # price, so verify that price too immediately before creating the list.
        if hasattr(self.client, "ticker_price"):
            last_price = float(self.client.ticker_price(symbol).get("price", 0) or 0)
            if last_price > 0:
                if last_price >= tp:
                    return self._emergency_market_sell(
                        symbol, qty, trade_id, "TAKE_PROFIT_REACHED_BEFORE_OCO_LAST_PRICE"
                    )
                if last_price <= sl:
                    return self._emergency_market_sell(
                        symbol, qty, trade_id, "STOP_LOSS_REACHED_BEFORE_OCO_LAST_PRICE"
                    )
                if not (tp > last_price > sl):
                    raise RuntimeError(
                        f"{symbol}: current last price {last_price:.12g} violates OCO price ordering"
                    )

        if not (tp > entry > sl > sl_limit):
            raise RuntimeError(
                f"{symbol}: invalid rounded TP/SL"
            )

        client_id = f"{self.OCO_PREFIX}{uuid.uuid4().hex[:20]}"
        self.set_state(symbol, "EXIT_PENDING")

        create_oco = getattr(self.client, "create_oco_sell_safe", None) or self.client.create_oco_sell
        result = self._execution_mutation(
            symbol, "SELL", "OCO", purpose="LEGACY_EXIT_OCO",
            submit=lambda: create_oco(
                symbol,
                self.client.decimal_format(qty),
                self.client.decimal_format(tp),
                self.client.decimal_format(sl),
                self.client.decimal_format(sl_limit),
                client_id,
            ),
        )

        for leg in result.get("orderReports", []):
            self.db.save_order(leg)

        order_list_id = result.get("orderListId")
        list_client_id = (
            result.get("listClientOrderId")
            or client_id
        )

        if order_list_id is not None:
            self.db.update_trade_oco(
                trade_id,
                order_list_id,
                list_client_order_id=list_client_id,
                stop_price=sl,
                take_profit_price=tp,
                risk_pct=risk_pct,
            )
        else:
            raise RuntimeError(
                f"{symbol}: Binance OCO response has no orderListId"
            )

        self.set_state(symbol, "OPEN")
        return result

    # ------------------------------------------------------------------
    # Entry recovery
    # ------------------------------------------------------------------

    def _pending_entries(self):
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state "
            "WHERE key LIKE 'entry_client_order_id:%' "
            "ORDER BY key"
        ).fetchall()
        return [
            (
                str(row["key"]).split(":", 1)[1].upper(),
                str(row["value"]),
            )
            for row in rows
            if str(row["value"]).strip()
        ]

    def recover_pending_entries(self):
        results = []
        for symbol, client_id in self._pending_entries():
            try:
                order = self.client.get_order(
                    symbol,
                    orig_client_order_id=client_id,
                )
                self.db.save_order(order)

                status = str(
                    order.get("status", "")
                ).upper()

                execution = self._authoritative_execution(symbol, order)
                qty = float(execution.net_base_qty)
                spent = float(execution.quote_qty)

                # A terminal order may still contain a real partial fill.
                # Never clear the durable entry intent and leave the bought
                # asset untracked/unprotected.
                terminal = status in {"CANCELED", "EXPIRED", "REJECTED"}
                if terminal and qty <= 0:
                    self.db.state_delete(
                        self._pending_key(symbol)
                    )
                    self.set_state(symbol, "FLAT")
                    results.append(
                        {"symbol": symbol, "state": "FLAT"}
                    )
                    continue

                # While a BUY is still live, keep the durable intent pending.
                # Do not create OCO protection until the final entry quantity
                # is known.
                if status != "FILLED" and not terminal:
                    self.set_state(symbol, "ENTRY_PENDING")
                    continue

                if qty <= 0 or spent <= 0:
                    raise RuntimeError(
                        f"{symbol}: pending BUY has invalid fill"
                    )

                entry = spent / qty
                existing = self.db.trade_by_entry_client_order_id(
                    client_id
                )
                trade = existing or self.db.open_trade(symbol)

                if trade is None:
                    trade_id = self.db.save_trade(
                        entry_time=datetime.fromtimestamp(
                            int(
                                order.get(
                                    "transactTime",
                                    order.get("time", 0),
                                )
                            ) / 1000,
                            tz=timezone.utc,
                        ).isoformat(),
                        symbol=symbol,
                        side="LONG",
                        entry_price=entry,
                        quantity=qty,
                        entry_order_id=str(
                            order.get("orderId")
                        ),
                        entry_client_order_id=client_id,
                        fees=float(execution.fee_quote_equivalent),
                    )
                    trade = self.db.open_trade(symbol)
                    trade_id = int(trade["id"])
                else:
                    trade_id = int(trade["id"])

                self.db.state_delete(
                    self._pending_key(symbol)
                )

                # Recover the protection if it is missing.
                self._reconcile_trade(trade_id)

                results.append(
                    {
                        "symbol": symbol,
                        "trade_id": trade_id,
                        "state": self.state(symbol),
                    }
                )
            except Exception as exc:
                self.set_state(
                    symbol,
                    "RECONCILE_REQUIRED",
                )
                self.db.log_event(
                    "ERROR",
                    "multi_position_pending_entry_error",
                    str(exc),
                    {
                        "symbol": symbol,
                        "clientOrderId": client_id,
                    },
                )
                results.append(
                    {
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "error": str(exc),
                    }
                )
        return results

    # ------------------------------------------------------------------
    # Position reconciliation
    # ------------------------------------------------------------------

    def _reconcile_trade(self, trade_id):
        trade = next(
            (
                row for row in self.open_trades()
                if int(row["id"]) == int(trade_id)
            ),
            None,
        )
        if trade is None:
            return {
                "trade_id": trade_id,
                "state": "FLAT",
            }

        symbol = str(trade["symbol"]).upper()

        all_orders = self.client.all_orders(
            symbol,
            limit=1000,
        )
        for order in all_orders:
            self.db.save_order(order)

        open_lists = self._open_order_lists()
        history_lists = self._all_order_lists(symbol=symbol)
        oco = self._bot_oco_list(
            trade,
            open_lists,
            history_lists,
        )

        if oco and not trade.get("exit_order_list_id"):
            self.db.update_trade_oco(
                trade["id"],
                oco.get("orderListId"),
                list_client_order_id=oco.get("listClientOrderId"),
            )
            trade = self.db.open_trade(symbol) or trade

        entry_id = str(trade.get("entry_order_id") or "")
        entry_order = next(
            (
                o for o in all_orders
                if str(o.get("orderId")) == entry_id
            ),
            None,
        )
        persisted_qty = float(trade.get("quantity") or 0.0)
        gross_qty = (
            float(entry_order.get("executedQty", 0) or 0)
            if entry_order else 0.0
        )
        if persisted_qty <= 0 and gross_qty > 0:
            persisted_qty = gross_qty
            self.db.update_trade_quantity(trade["id"], persisted_qty)
            trade = self.db.open_trade(symbol) or trade

        if persisted_qty <= 0:
            raise RuntimeError(
                f"{symbol}: managed trade has no authoritative entry quantity"
            )

        exit_orders = self._bot_exit_orders(
            trade,
            all_orders,
            oco,
        )
        bot_sold_qty, bot_sold_quote, bot_last_exit = self._executed_exit_metrics(
            exit_orders
        )

        entry_time_ms = int(
            (
                entry_order.get("time", entry_order.get("transactTime", 0))
                if entry_order else 0
            ) or 0
        )

        external_sells = [
            o for o in all_orders
            if str(o.get("side", "")).upper() == "SELL"
            and int(o.get("time", o.get("transactTime", 0)) or 0) >= entry_time_ms
            and not self._sell_belongs_to_bot(o, all_orders)
            and float(o.get("executedQty", 0) or 0) > 0
        ]
        manual_qty, manual_quote, manual_last = self._executed_exit_metrics(
            external_sells
        )

        total_sold_qty = bot_sold_qty + manual_qty
        total_sold_quote = bot_sold_quote + manual_quote
        last_exit = bot_last_exit
        if (
            manual_last is not None
            and (
                last_exit is None
                or int(
                    manual_last.get(
                        "time",
                        manual_last.get("transactTime", 0),
                    ) or 0
                ) >= int(
                    last_exit.get(
                        "time",
                        last_exit.get("transactTime", 0),
                    ) or 0
                )
            )
        ):
            last_exit = manual_last

        account = self.client.account()
        exchange_qty = self._asset_balance(
            symbol,
            account=account,
        )
        if exchange_qty < 0:
            raise RuntimeError(f"{symbol}: invalid negative exchange balance")

        tolerance_abs = max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            persisted_qty * self.balance_tolerance_pct,
        )

        # A reduction in managed inventory is only acceptable when the
        # exchange history accounts for that reduction with real SELL fills.
        if (
            exchange_qty + tolerance_abs < persisted_qty
            and total_sold_qty + tolerance_abs < persisted_qty - exchange_qty
        ):
            raise RuntimeError(
                f"{symbol}: exchange inventory decreased without matching SELL "
                f"evidence (managed={persisted_qty:.12g}, "
                f"exchange={exchange_qty:.12g}, "
                f"evidenced_sells={total_sold_qty:.12g})"
            )

        # Current exchange inventory is the idempotent source of truth for the
        # residual managed position. Historical SELL rows are evidence, not an
        # amount to subtract again on every restart.
        if exchange_qty > persisted_qty + tolerance_abs:
            raise RuntimeError(
                f"{symbol}: managed quantity mismatch; "
                f"exchange={exchange_qty:.12g} exceeds managed={persisted_qty:.12g}"
            )

        remaining_expected = exchange_qty
        if remaining_expected <= float(os.getenv("MIN_RECOVERY_QTY", "0.000001")):
            if total_sold_qty <= 0 or last_exit is None:
                raise RuntimeError(
                    f"{symbol}: managed inventory is gone without filled SELL evidence"
                )

            entry_price = float(trade["entry_price"])
            exit_price = (
                total_sold_quote / total_sold_qty
                if total_sold_quote > 0 and total_sold_qty > 0
                else self._exit_price(last_exit)
            )
            realized_quote = (
                total_sold_quote
                if total_sold_quote > 0
                else exit_price * total_sold_qty
            )
            exit_fee_quote = self._exit_fee_quote(symbol, exit_orders)
            entry_fee_quote = float(trade.get("fees") or 0.0)
            entry_cost_qty = gross_qty if gross_qty > 0 else persisted_qty
            pnl = (
                realized_quote
                - entry_price * entry_cost_qty
                - entry_fee_quote
                - exit_fee_quote
            )
            pnl_pct = exit_price / entry_price - 1.0 if entry_price else 0.0
            self.db.close_trade(
                trade["id"],
                datetime.fromtimestamp(
                    int(last_exit.get("transactTime", last_exit.get("time", 0))) / 1000,
                    tz=timezone.utc,
                ).isoformat(),
                exit_price,
                pnl,
                pnl_pct,
                self._exit_reason(last_exit),
                last_exit.get("orderListId"),
                fees=entry_fee_quote + exit_fee_quote,
            )
            self.set_state(symbol, "FLAT")
            return {
                "trade_id": trade["id"],
                "symbol": symbol,
                "state": "FLAT",
                "closed": True,
            }

        if abs(exchange_qty - persisted_qty) > tolerance_abs:
            self.db.update_trade_quantity(trade["id"], remaining_expected)
            self.db.log_event(
                "WARNING",
                "position_inventory_reconciled",
                "Managed quantity aligned to current Binance free inventory",
                {
                    "symbol": symbol,
                    "trade_id": trade["id"],
                    "previous_quantity": persisted_qty,
                    "exchange_quantity": exchange_qty,
                    "bot_sold_qty": bot_sold_qty,
                    "external_sold_qty": manual_qty,
                },
            )
        else:
            remaining_expected = persisted_qty

        self.db.update_trade_quantity(
            trade["id"],
            remaining_expected,
        )
        trade = self.db.open_trade(symbol) or trade

        open_orders = self.client.open_orders(symbol)
        unknown_sells = [
            order for order in open_orders
            if str(order.get("side", "")).upper() == "SELL"
            and str(order.get("orderListId", "")) != str(
                trade.get("exit_order_list_id") or ""
            )
            and not str(order.get("clientOrderId", "")).startswith(
                (self.OCO_PREFIX, self.EMERGENCY_PREFIX, self.MANUAL_PREFIX)
            )
        ]
        if unknown_sells:
            raise RuntimeError(
                f"{symbol}: unrecognized open SELL order conflicts "
                "with managed position"
            )

        # An active OCO is identified either by the order-list endpoint or
        # by its still-open child SELL orders. Child quantities are alternative
        # exits for one inventory quantity, so use the minimum remaining child
        # quantity as the shared residual.
        active_list = False
        active_managed_qty = 0.0
        candidate_list_ids = set()

        # A specifically persisted/returned OCO list is authoritative even if
        # a mock or an exchange response omits child-order timestamps. The
        # timestamp requirement below is only for orphaned client-id matches.
        if oco:
            oco_id = str(oco.get("orderListId", "") or "")
            oco_status = str(
                oco.get("listOrderStatus", oco.get("listStatusType", ""))
            ).upper()
            if (
                oco_id
                and oco_id == str(trade.get("exit_order_list_id") or "")
                and oco_status in {"EXECUTING", "EXEC_STARTED", "EXECUTING"}
            ):
                active_list = True
                candidate_list_ids.add(oco_id)
        for row in open_lists:
            if str(row.get("symbol", "")).upper() != symbol:
                continue
            lid = str(row.get("orderListId", "") or "")
            cid = str(row.get("listClientOrderId", "") or "")
            if (
                lid == str(trade.get("exit_order_list_id") or "")
                or cid == str(trade.get("exit_order_list_client_id") or "")
                or cid.startswith(self.OCO_PREFIX)
            ):
                active_list = True
                if lid:
                    candidate_list_ids.add(lid)

        for order in open_orders:
            if str(order.get("side", "")).upper() != "SELL":
                continue
            order_time = int(
                order.get(
                    "time",
                    order.get("transactTime", order.get("updateTime", 0)),
                ) or 0
            )
            client_id = str(order.get("clientOrderId", "") or "")
            list_id = str(order.get("orderListId", "") or "")
            exact_managed_list = (
                list_id == str(trade.get("exit_order_list_id") or "")
                or (list_id and list_id in candidate_list_ids)
            )
            if (
                not exact_managed_list
                and (
                    not order_time
                    or (entry_time_ms and order_time < entry_time_ms)
                )
            ):
                continue
            if (
                client_id.startswith(self.OCO_PREFIX)
                or exact_managed_list
            ):
                active_list = True
                if list_id:
                    candidate_list_ids.add(list_id)

        if active_list:
            remaining_legs = []
            for order in open_orders:
                if str(order.get("side", "")).upper() != "SELL":
                    continue
                order_time = int(
                    order.get(
                        "time",
                        order.get("transactTime", order.get("updateTime", 0)),
                    ) or 0
                )
                list_id = str(order.get("orderListId", "") or "")
                client_id = str(order.get("clientOrderId", "") or "")
                exact_managed_list = (
                    list_id == str(trade.get("exit_order_list_id") or "")
                    or (list_id and list_id in candidate_list_ids)
                )
                if (
                    not exact_managed_list
                    and (
                        not order_time
                        or (entry_time_ms and order_time < entry_time_ms)
                    )
                ):
                    continue
                if client_id.startswith(self.OCO_PREFIX) or exact_managed_list:
                    remaining_legs.append(
                        max(
                            0.0,
                            float(order.get("origQty", 0) or 0)
                            - float(order.get("executedQty", 0) or 0),
                        )
                    )

            if remaining_legs:
                active_managed_qty = min(remaining_legs)
                if abs(active_managed_qty - remaining_expected) > tolerance_abs:
                    raise RuntimeError(
                        f"{symbol}: active OCO quantity mismatch; "
                        f"expected={remaining_expected:.12g}, "
                        f"oco_remaining={active_managed_qty:.12g}"
                    )
                self.set_state(symbol, "OPEN")
                return {
                    "trade_id": trade["id"],
                    "symbol": symbol,
                    "state": "OPEN",
                    "protected": True,
                    "remaining_quantity": remaining_expected,
                    "partial_exit": total_sold_qty > 0,
                }

            # An EXECUTING list with no visible child orders is ambiguous.
            # Never assume it protects the inventory.
            if open_lists:
                raise RuntimeError(
                    f"{symbol}: active OCO has no visible child SELL orders"
                )

        # No managed OCO is currently open. This is the normal recovery path
        # after an OCO child was partially filled and the list became terminal:
        # protect only the inventory that still exists.
        entry = float(trade["entry_price"])
        stop = float(trade.get("stop_price") or 0.0)
        take = float(trade.get("take_profit_price") or 0.0)

        stop_fraction = (
            (entry - stop) / entry
            if entry > 0 and stop > 0
            else float(os.getenv("STOP_LOSS_PCT", "0.02"))
        )
        target_fraction = (
            (take - entry) / entry
            if entry > 0 and take > entry
            else float(os.getenv("TAKE_PROFIT_PCT", "0.04"))
        )

        protection_qty = min(exchange_qty, remaining_expected)
        if protection_qty <= 0:
            raise RuntimeError(
                f"{symbol}: remaining managed quantity cannot be protected"
            )

        self.db.update_trade_quantity(
            trade["id"],
            protection_qty,
        )
        self._create_oco(
            symbol,
            protection_qty,
            entry,
            trade["id"],
            stop_fraction=stop_fraction,
            target_fraction=target_fraction,
            risk_pct=trade.get("risk_pct"),
        )
        self.db.log_event(
            "WARNING",
            "multi_position_oco_restored",
            "Missing managed OCO protection restored",
            {
                "symbol": symbol,
                "trade_id": trade["id"],
                "quantity": protection_qty,
                "executed_sell_qty": total_sold_qty,
            },
        )
        return {
            "trade_id": trade["id"],
            "symbol": symbol,
            "state": "OPEN",
            "protected": True,
            "oco_restored": True,
            "remaining_quantity": protection_qty,
            "partial_exit": total_sold_qty > 0,
        }

    def reconcile_open_positions(self):
        """Reconcile all managed positions; block globally on ambiguity."""
        pending = self.recover_pending_entries()

        trades = list(self.open_trades())
        results = list(pending)

        if not trades:
            return results

        for trade in list(trades):
            symbol = str(trade["symbol"]).upper()
            try:
                results.append(
                    self._reconcile_trade(
                        int(trade["id"])
                    )
                )
            except Exception as exc:
                self.set_state(
                    symbol,
                    "RECONCILE_REQUIRED",
                )
                self.db.log_event(
                    "ERROR",
                    "multi_position_reconcile_required",
                    str(exc),
                    {
                        "symbol": symbol,
                        "trade_id": trade["id"],
                    },
                )
                results.append(
                    {
                        "trade_id": trade["id"],
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "error": str(exc),
                    }
                )

        return results

    def recover(self):
        """Startup/full recovery. Campaign mode never routes through legacy OCO recovery."""
        if self.campaign_engine_enabled:
            pending = self.campaign_execution.reconcile_pending_entries()
            active = self.campaign_execution.reconcile_active_campaigns()
            repaired = self._repair_stale_reconcile_states()
            unresolved = self.unresolved_symbols()
            pending_keys = self._pending_entries()
            ok = not unresolved and not pending_keys
            canonical_state = self._sync_legacy_state()
            results = pending + active
            results.extend(
                {"symbol": symbol, "state": "FLAT", "stale_reconcile_cleared": True}
                for symbol in repaired
            )
            self.db.log_event(
                "INFO" if ok else "ERROR",
                "campaign_recovery_complete",
                "Campaign exchange/SQLite reconciliation complete",
                {
                    "open_positions": len(self.open_trades()),
                    "open_campaigns": len(self.db.open_campaigns()),
                    "unresolved_symbols": unresolved,
                    "pending_entries": [x[0] for x in pending_keys],
                },
            )
            return {
                "ok": ok,
                "results": results,
                "open_positions": len(self.open_trades()),
                "open_campaigns": len(self.db.open_campaigns()),
                "unresolved_symbols": unresolved,
                "state": canonical_state,
            }

        results = self.reconcile_open_positions()
        repaired = self._repair_stale_reconcile_states()
        unresolved = self.unresolved_symbols()
        pending = self._pending_entries()
        ok = not unresolved and not pending
        canonical_state = self._sync_legacy_state()
        results.extend(
            {"symbol": symbol, "state": "FLAT", "stale_reconcile_cleared": True}
            for symbol in repaired
        )
        self.db.log_event(
            "INFO" if ok else "ERROR",
            "multi_position_recovery_complete",
            "Multi-position exchange/SQLite reconciliation complete",
            {
                "open_positions": len(self.open_trades()),
                "unresolved_symbols": unresolved,
                "pending_entries": [x[0] for x in self._pending_entries()],
            },
        )
        return {
            "ok": ok,
            "results": results,
            "open_positions": len(self.open_trades()),
            "unresolved_symbols": unresolved,
        }

    # ------------------------------------------------------------------
    # Manual / emergency exit for one managed position
    # ------------------------------------------------------------------

    def manual_sell(self, symbol):
        symbol = str(symbol).upper().strip()
        trade = self.db.open_trade(symbol)
        if trade is None:
            self.recover()
            return {
                "sold": False,
                "symbol": symbol,
                "reason": "managed position not found; recovery checked",
            }

        try:
            # Only cancel the bot-owned OCO list. Never cancel unrelated
            # user orders on the same symbol.
            list_id = str(trade.get("exit_order_list_id") or "")
            list_client = str(
                trade.get("exit_order_list_client_id") or ""
            )
            if list_id or list_client:
                cancel = getattr(self.client, "cancel_oco", None)
                if cancel is not None:
                    self._execution_mutation(
                        symbol, "SELL", "CANCEL",
                        purpose="LEGACY_EXIT_OCO_CANCEL",
                        submit=lambda: (
                            cancel(symbol, order_list_id=list_id)
                            if list_id
                            else cancel(symbol, list_client_order_id=list_client)
                        ),
                    )

            account = self.client.account()
            free_qty = self._asset_balance(
                symbol,
                account=account,
            )
            sell_qty = self._normalize_qty(
                symbol,
                min(float(trade["quantity"]), free_qty),
                market=True,
            )
            if sell_qty <= 0:
                raise RuntimeError(
                    f"{symbol}: managed quantity is no longer available"
                )

            sell = self._execution_mutation(
                symbol, "SELL", "MARKET", purpose="LEGACY_MANUAL_EXIT",
                submit=lambda: self.client.order_safe(
                    symbol, "SELL", "MARKET",
                    quantity=self.client.decimal_format(sell_qty),
                    new_client_order_id=f"{self.MANUAL_PREFIX}{uuid.uuid4().hex[:20]}",
                ),
            )
            self.db.save_order(sell)

            execution = self._authoritative_execution(symbol, sell)
            executed_qty = float(execution.executed_qty)
            quote = float(execution.quote_qty)
            exit_fee_quote = float(execution.fee_quote_equivalent)
            if executed_qty <= 0:
                raise RuntimeError(
                    f"{symbol}: manual SELL returned no fill"
                )

            exit_price = (
                quote / executed_qty
                if quote > 0
                else float(sell.get("price", 0) or 0)
            )
            managed_qty = float(trade["quantity"])
            tolerance = max(
                float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
                managed_qty * self.balance_tolerance_pct,
            )

            if executed_qty + tolerance >= managed_qty:
                entry_price = float(trade["entry_price"])
                entry_fee_quote = float(trade.get("fees") or 0.0)
                pnl = (
                    quote
                    - entry_price * managed_qty
                    - entry_fee_quote
                    - exit_fee_quote
                )
                pnl_pct = (exit_price / entry_price - 1.0) if entry_price else 0.0
                self.db.close_trade(
                    trade["id"],
                    datetime.now(timezone.utc).isoformat(),
                    exit_price,
                    pnl,
                    pnl_pct,
                    "MANUAL_SELL",
                    fees=entry_fee_quote + exit_fee_quote,
                )
                self.set_state(symbol, "FLAT")
                self.db.log_event(
                    "INFO",
                    "manual_position_closed",
                    "Managed position manually closed",
                    {
                        "symbol": symbol,
                        "trade_id": trade["id"],
                        "quantity": executed_qty,
                        "exit_price": exit_price,
                    },
                )
                return {
                    "sold": True,
                    "symbol": symbol,
                    "trade_id": trade["id"],
                    "quantity": executed_qty,
                    "residual_quantity": 0.0,
                    "exit_price": exit_price,
                    "pnl": pnl,
                    "state": "FLAT",
                }

            residual_qty = max(0.0, managed_qty - executed_qty)
            self.db.update_trade_quantity(trade["id"], residual_qty)
            self.set_state(symbol, "EXIT_PENDING")
            try:
                self._reconcile_trade(int(trade["id"]))
            except Exception as exc:
                self.set_state(symbol, "RECONCILE_REQUIRED")
                raise RuntimeError(
                    f"{symbol}: manual SELL partially filled; residual "
                    f"{residual_qty:.12g} requires reconciliation: {exc}"
                ) from exc

            self.db.log_event(
                "WARNING",
                "manual_position_partial_sell",
                "Managed position partially closed; residual quantity reprotected",
                {
                    "symbol": symbol,
                    "trade_id": trade["id"],
                    "quantity": executed_qty,
                    "residual_quantity": residual_qty,
                    "exit_price": exit_price,
                },
            )
            return {
                "sold": True,
                "partial": True,
                "symbol": symbol,
                "trade_id": trade["id"],
                "quantity": executed_qty,
                "residual_quantity": residual_qty,
                "exit_price": exit_price,
                "state": self.state(symbol),
            }
        except Exception as exc:
            self.set_state(symbol, "RECONCILE_REQUIRED")
            self.db.log_event(
                "ERROR",
                "manual_position_sell_error",
                str(exc),
                {
                    "symbol": symbol,
                    "trade_id": trade["id"],
                },
            )
            return {
                "sold": False,
                "symbol": symbol,
                "trade_id": trade["id"],
                "state": "RECONCILE_REQUIRED",
                "error": str(exc),
            }

    # ------------------------------------------------------------------
    # Portfolio-wide daily risk gate
    # ------------------------------------------------------------------

    def _daily_entry_guard(self):
        """Block new entries on daily loss, trade count, streak or cooldown."""
        from datetime import datetime, timezone
        account = self.client.account()
        equity = self._portfolio_equity_quote(account)
        unrealized = 0.0
        for trade in self.open_trades():
            symbol = str(trade["symbol"]).upper()
            try:
                mark = float(self.client.ticker_price(symbol)["price"])
            except Exception:
                continue
            qty = float(trade.get("quantity", 0) or 0)
            unrealized += (mark - float(trade.get("entry_price", 0) or 0)) * qty

        realized = float(self.db.conn.execute(
            "SELECT COALESCE(SUM(pnl),0) FROM trades WHERE exit_time IS NOT NULL AND exit_time >= date('now')"
        ).fetchone()[0] or 0.0)
        fees = float(self.db.conn.execute(
            "SELECT COALESCE(SUM(fees),0) FROM trades WHERE exit_time IS NOT NULL AND exit_time >= date('now')"
        ).fetchone()[0] or 0.0)
        trades_today = int(self.db.conn.execute(
            "SELECT COUNT(*) FROM trades WHERE entry_time >= date('now')"
        ).fetchone()[0])
        recent = self.db.conn.execute(
            "SELECT pnl, exit_time FROM trades WHERE exit_time IS NOT NULL ORDER BY id DESC LIMIT 20"
        ).fetchall()

        if self.max_trades_per_day and trades_today >= self.max_trades_per_day:
            return False, f"MAX_TRADES_PER_DAY reached: {trades_today}"
        losses = 0
        for row in recent:
            if float(row["pnl"] or 0) < 0:
                losses += 1
            else:
                break
        if self.max_consecutive_losses and losses >= self.max_consecutive_losses:
            return False, f"MAX_CONSECUTIVE_LOSSES reached: {losses}"
        if self.cooldown_minutes and recent and recent[0]["exit_time"]:
            try:
                ts = datetime.fromisoformat(str(recent[0]["exit_time"]).replace("Z", "+00:00"))
                elapsed = (datetime.now(timezone.utc) - ts).total_seconds()
                if elapsed < self.cooldown_minutes * 60:
                    return False, f"COOLDOWN active: {self.cooldown_minutes * 60 - elapsed:.0f}s remaining"
            except ValueError:
                pass
        ok, reason = self.equity_breaker.check(self.db, equity, None, unrealized, fees)
        return ok, reason
    # ------------------------------------------------------------------
    # Williams campaign entries
    # ------------------------------------------------------------------

    @staticmethod
    def _selection_signal(selection):
        raw_specs = list(
            getattr(selection.candidate, "campaign_signal_specs", []) or []
        )
        specs = []
        for raw in raw_specs:
            try:
                specs.append(
                    SignalSpec(
                        signal_id=str(raw["signal_id"]),
                        symbol=str(raw["symbol"]),
                        side=str(raw["side"]),
                        signal_type=SignalType(str(raw["signal_type"])),
                        role=SignalRole(str(raw["role"])),
                        timeframe=str(raw["timeframe"]),
                        signal_bar_time_ms=int(raw["signal_bar_time_ms"]),
                        trigger_price=float(raw["trigger_price"]),
                        protective_reference=float(raw["protective_reference"]),
                        trigger_buffer_ticks=int(raw.get("trigger_buffer_ticks", 1) or 1),
                        invalidation_price=float(raw.get("invalidation_price", 0.0) or 0.0),
                        teeth_at_detection=float(raw.get("teeth_at_detection", 0.0) or 0.0),
                        alligator_bullish=bool(raw.get("alligator_bullish", False)),
                        alligator_awake=bool(raw.get("alligator_awake", False)),
                        angulation_score=float(raw.get("angulation_score", 0.0) or 0.0),
                        wave_confidence=float(raw.get("wave_confidence", 0.0) or 0.0),
                        wave_exhaustion_risk=float(raw.get("wave_exhaustion_risk", 0.0) or 0.0),
                        htf_confirmed=bool(raw.get("htf_confirmed", False)),
                        context_versions=dict(raw.get("context_versions", {}) or {}),
                        reason=str(raw.get("reason", "")),
                        created_at_ms=int(raw.get("created_at_ms", 0) or 0),
                        expires_at_ms=int(raw.get("expires_at_ms", 0) or 0),
                        source_candle_index=int(raw.get("source_candle_index", -1) or -1),
                        execution_timeframe=str(raw.get("execution_timeframe", self.intraday_policy.timeframes.execution_tf if self.intraday_core_enabled else "") or ""),
                        detected_time_ms=int(raw.get("detected_time_ms", 0) or 0),
                    )
                )
            except Exception:
                continue
        return CampaignEngine.choose_initial_signal(specs) if specs else None

    def _signal_specs(self, selection):
        raw_specs = list(
            getattr(selection.candidate, "campaign_signal_specs", []) or []
        )
        specs = []
        for raw in raw_specs:
            try:
                specs.append(
                    SignalSpec(
                        signal_id=str(raw["signal_id"]),
                        symbol=str(raw["symbol"]),
                        side=str(raw["side"]),
                        signal_type=SignalType(str(raw["signal_type"])),
                        role=SignalRole(str(raw["role"])),
                        timeframe=str(raw["timeframe"]),
                        signal_bar_time_ms=int(raw["signal_bar_time_ms"]),
                        trigger_price=float(raw["trigger_price"]),
                        protective_reference=float(raw["protective_reference"]),
                        trigger_buffer_ticks=int(raw.get("trigger_buffer_ticks", 1) or 1),
                        invalidation_price=float(raw.get("invalidation_price", 0.0) or 0.0),
                        teeth_at_detection=float(raw.get("teeth_at_detection", 0.0) or 0.0),
                        alligator_bullish=bool(raw.get("alligator_bullish", False)),
                        alligator_awake=bool(raw.get("alligator_awake", False)),
                        angulation_score=float(raw.get("angulation_score", 0.0) or 0.0),
                        wave_confidence=float(raw.get("wave_confidence", 0.0) or 0.0),
                        wave_exhaustion_risk=float(raw.get("wave_exhaustion_risk", 0.0) or 0.0),
                        htf_confirmed=bool(raw.get("htf_confirmed", False)),
                        context_versions=dict(raw.get("context_versions", {}) or {}),
                        reason=str(raw.get("reason", "")),
                        created_at_ms=int(raw.get("created_at_ms", 0) or 0),
                        expires_at_ms=int(raw.get("expires_at_ms", 0) or 0),
                        source_candle_index=int(raw.get("source_candle_index", -1) or -1),
                    )
                )
            except Exception:
                continue
        return specs

    def execute_campaign(self, selections):
        if not selections:
            return []

        results = []
        equity = self._balance()
        if equity <= 0:
            return []

        open_count = len(self.open_trades())
        pending = {symbol for symbol, _ in self._pending_entries()}

        session_state = self.intraday_policy.session.state(datetime.now(timezone.utc)) if self.intraday_core_enabled else "ENTRY_WINDOW"
        if self.intraday_core_enabled and session_state != "ENTRY_WINDOW":
            return [{"action": "SESSION_BLOCK", "state": session_state, "reason": "no new campaigns outside 08:00-18:00 UTC"}]

        for selection in selections:
            specs = self._signal_specs(selection)
            if not specs:
                continue
            symbol = str(selection.candidate.symbol).upper()
            trace = dict(getattr(selection.candidate, "decision_trace", {}) or {})
            if trace:
                trace["risk_feasible"] = bool(getattr(selection.risk, "allowed", False))
                trace["trade_allowed"] = False
                trace["block_reason"] = "" if trace["risk_feasible"] else str(getattr(selection.risk, "reason", "RISK_BLOCKED"))
                try:
                    self.db.save_decision_trace(trace)
                except Exception as exc:
                    self.db.log_event("WARNING", "decision_trace_persist_failed", str(exc), {"symbol": symbol})
            if symbol in pending:
                if self.intraday_core_enabled:
                    pending_campaign = self.campaign_execution._active_campaign_for_symbol(symbol)
                    trace = dict(getattr(selection.candidate, "decision_trace", {}) or {})
                    current_type = str(getattr(pending_campaign, "current_signal_type", "") or "").upper() if pending_campaign else ""
                    current_time = int(getattr(pending_campaign, "tags", {}).get("signal_bar_time_ms", 0) or 0) if pending_campaign else 0
                    newer = [
                        x for x in specs
                        if x.signal_type == SignalType.FRACTAL
                        and current_type == SignalType.FRACTAL
                        and x.signal_bar_time_ms > current_time
                    ]
                    if pending_campaign is not None and pending_campaign.position_qty <= 0 and newer:
                        try:
                            newest = max(newer, key=lambda x: x.signal_bar_time_ms)
                            self.campaign_execution.supersede_pending_fractal(pending_campaign, newest)
                            pending.discard(symbol)
                        except CampaignExecutionError as exc:
                            self.db.log_event(
                                "WARNING",
                                "fractal_supersede_blocked",
                                str(exc),
                                {"symbol": symbol, "signal_id": getattr(newer[-1], "signal_id", "")},
                            )
                            continue
                    else:
                        continue
                else:
                    continue

            campaign = self.campaign_execution._active_campaign_for_symbol(symbol)

            if (
                self.intraday_core_enabled
                and campaign is not None
                and str((getattr(selection.candidate, "decision_trace", {}) or {}).get("alligator_state", "")).upper() == "SLEEP"
            ):
                # Sleeping Alligator can host an initial WM1 watch/entry, but
                # it is not a license for aggressive trend-following additions.
                continue

            # Active campaign: later WM2/WM3 signals are add-ons. A new
            # reversal is not auto-added by default because it can represent
            # countertrend risk; this is explicitly configurable.
            if campaign is not None and campaign.position_qty > 0:
                latest_time = int(
                    campaign.tags.get("last_signal_time_ms", 0)
                    or campaign.tags.get("signal_bar_time_ms", 0)
                    or 0
                )
                allow_reversal_add = (
                    os.getenv("CAMPAIGN_ALLOW_REVERSAL_ADD", "false").lower()
                    == "true"
                )
                eligible = [
                    s for s in specs
                    if s.signal_bar_time_ms > latest_time
                    and (
                        s.signal_type in {SignalType.SUPER_AO, SignalType.FRACTAL}
                        or (
                            allow_reversal_add
                            and s.signal_type == SignalType.REVERSAL
                        )
                    )
                ]
                if not eligible:
                    continue
                signal = min(
                    eligible,
                    key=lambda s: (s.signal_bar_time_ms, s.created_at_ms),
                )
                add_signal = __import__(
                    "dataclasses"
                ).replace(signal, role=SignalRole.ADD_ON)

                try:
                    result = self.campaign_execution.arm_add_on(
                        add_signal,
                        equity_quote=equity,
                        candidate_risk_pct=min(
                            self.max_risk_per_trade_pct,
                            max(0.0, float(selection.risk.risk_pct) / 100.0),
                        ),
                    )
                    results.append(
                        {
                            "symbol": symbol,
                            "campaign": True,
                            "action": "ADD_ON_ARMED",
                            **result,
                        }
                    )
                    pending.add(symbol)
                except CampaignExecutionError as exc:
                    self.db.log_event(
                        "WARNING",
                        "campaign_add_on_not_armed",
                        str(exc),
                        {"symbol": symbol, "signal_id": signal.signal_id},
                    )
                    results.append(
                        {
                            "symbol": symbol,
                            "campaign": True,
                            "action": "WAIT_ADD_ON",
                            "error": str(exc),
                        }
                    )
                continue

            # No open campaign: this is a new initial campaign. Position
            # capacity applies only to new campaigns, not later additions.
            if self.max_open_positions > 0 and open_count >= self.max_open_positions:
                continue

            signal = self.campaign_execution.engine.choose_initial_signal(specs)
            if signal is None:
                continue

            try:
                result = self.campaign_execution.arm_initial_entry(
                    signal,
                    equity_quote=equity,
                    candidate_risk_pct=min(
                        self.max_risk_per_trade_pct,
                        max(0.0, float(selection.risk.risk_pct) / 100.0),
                    ),
                )
                if trace:
                    trace["trade_allowed"] = True
                    trace["execution_feasible"] = True
                    trace["block_reason"] = ""
                    try:
                        self.db.save_decision_trace(trace)
                    except Exception as exc:
                        self.db.log_event("WARNING", "decision_trace_persist_failed", str(exc), {"symbol": symbol})
                results.append(
                    {
                        "symbol": symbol,
                        "campaign": True,
                        "action": "ENTRY_ARMED",
                        **result,
                    }
                )
                pending.add(symbol)
                open_count += 1
                if self.intraday_core_enabled:
                    break
            except CampaignExecutionError as exc:
                if trace:
                    trace["execution_feasible"] = False
                    trace["trade_allowed"] = False
                    trace["block_reason"] = str(exc)
                    try:
                        self.db.save_decision_trace(trace)
                    except Exception:
                        pass
                self.db.log_event(
                    "WARNING",
                    "campaign_entry_not_armed",
                    str(exc),
                    {"symbol": symbol, "signal_id": signal.signal_id},
                )
                results.append(
                    {
                        "symbol": symbol,
                        "campaign": True,
                        "action": "WAIT",
                        "error": str(exc),
                    }
                )
            except Exception as exc:
                if trace:
                    trace["execution_feasible"] = False
                    trace["trade_allowed"] = False
                    trace["block_reason"] = str(exc)
                    try:
                        self.db.save_decision_trace(trace)
                    except Exception:
                        pass
                self.db.log_event(
                    "ERROR",
                    "campaign_entry_error",
                    str(exc),
                    {"symbol": symbol, "signal_id": signal.signal_id},
                )
                self.set_state(symbol, "RECONCILE_REQUIRED")
                results.append(
                    {
                        "symbol": symbol,
                        "campaign": True,
                        "action": "RECONCILE_REQUIRED",
                        "error": str(exc),
                    }
                )
        return results

    # ------------------------------------------------------------------
    # New entries
    # ------------------------------------------------------------------

    def _can_enter(self, symbol):
        if symbol in self._locks:
            return False
        if self.unresolved_symbols():
            return False
        if self.state(symbol) == "RECONCILE_REQUIRED":
            return False
        if self.db.open_trade(symbol):
            return False
        if self.max_open_positions > 0 and len(self.open_trades()) >= self.max_open_positions:
            return False
        if self._pending_entries():
            return False
        return True

    def execute(self, selections):
        if self.campaign_engine_enabled:
            if self.dry_run:
                return [
                    {
                        "symbol": selection.candidate.symbol,
                        "campaign": True,
                        "action": "SHADOW_ENTRY_ARM",
                        "risk_pct": selection.risk.risk_pct,
                        "trigger_price": getattr(selection.candidate, "entry_trigger_price", 0.0),
                        "entry_signal_type": getattr(selection.candidate, "entry_signal_type", ""),
                    }
                    for selection in selections
                ]
            return self.execute_campaign(selections)

        if self.dry_run:
            return [
                {
                    "symbol": selection.candidate.symbol,
                    "risk_pct": selection.risk.risk_pct,
                    "dry_run": True,
                }
                for selection in selections
            ]

        results = []
        for selection in selections:
            symbol = selection.candidate.symbol.upper()
            if not self._can_enter(symbol):
                continue

            balance = self._balance()
            if balance <= 0:
                break

            open_risk = self.reserved_risk_quote()
            total_risk_quote = balance * self.max_total_risk_pct
            remaining_risk_quote = max(
                0.0,
                total_risk_quote - open_risk,
            )
            requested_risk_pct = min(
                self.max_risk_per_trade_pct,
                max(
                    0.0,
                    float(selection.risk.risk_pct) / 100.0,
                ),
                remaining_risk_quote / max(balance, 1e-9),
            )

            stop_fraction = (
                float(selection.risk.stop_distance_pct) / 100.0
            )
            target_fraction = (
                float(selection.risk.take_profit_pct) / 100.0
            )

            if (
                requested_risk_pct <= 0
                or stop_fraction <= 0
                or target_fraction <= 0
            ):
                continue

            quote = self._allocation_quote(
                balance,
                requested_risk_pct,
                stop_fraction,
            )
            quote = min(
                quote,
                self._quote_free(),
            )
            if quote < self._min_notional(symbol):
                continue

            self._locks.add(symbol)
            client_id = (
                f"{self.ENTRY_PREFIX}{uuid.uuid4().hex[:20]}"
            )
            self.set_state(
                symbol,
                "ENTRY_PENDING",
            )
            # The pending-entry key is a durable cross-thread/process
            # mutex. A second execution loop must not generate a second BUY.
            claimed = False
            try:
                claim = getattr(self.db, "try_claim_state", None)
                claimed = (
                    claim(self._pending_key(symbol), client_id)
                    if claim is not None
                    else False
                )
            except Exception:
                claimed = False
            if not claimed:
                self.set_state(symbol, "ENTRY_PENDING")
                self._locks.discard(symbol)
                continue

            try:
                # Last-moment market-depth protection. A MARKET BUY is admitted
                # only when the requested quote amount can be filled within the
                # configured L2 slippage budget.
                self.l2_guard.check_buy_quote(
                    self.client,
                    symbol,
                    float(quote),
                )

                order = self._execution_mutation(
                    symbol, "BUY", "MARKET", purpose="LEGACY_ENTRY",
                    permission_interval=os.getenv("INTERVAL", "1h"),
                    submit=lambda: self.client.order_safe(
                        symbol, "BUY", "MARKET",
                        quote_order_qty=self.client.decimal_format(quote),
                        new_client_order_id=client_id,
                    ),
                )
                self.db.save_order(order)

                # Confirm the exchange state immediately after submission.
                order_id = order.get("orderId")
                confirmed = self.client.get_order(
                    symbol,
                    order_id=order_id,
                    orig_client_order_id=client_id if order_id is None else None,
                )
                if str(confirmed.get("status", "")).upper() != "FILLED":
                    raise RuntimeError(
                        f"{symbol}: BUY is not fully filled: "
                        f"{confirmed.get('status', 'UNKNOWN')}"
                    )
                if not confirmed.get("fills") and order.get("fills"):
                    confirmed = dict(confirmed)
                    confirmed["fills"] = order.get("fills")
                order = confirmed

                execution = self._authoritative_execution(symbol, order)
                qty = float(execution.net_base_qty)
                spent = float(execution.quote_qty)
                if qty <= 0 or spent <= 0:
                    raise RuntimeError(
                        f"{symbol}: BUY returned invalid authoritative fill"
                    )

                entry = float(execution.avg_price)
                trade_id = self.db.save_trade(
                    entry_time=datetime.now(timezone.utc).isoformat(),
                    symbol=symbol,
                    side="LONG",
                    entry_price=entry,
                    quantity=qty,
                    entry_order_id=str(order.get("orderId")),
                    entry_client_order_id=client_id,
                    stop_price=self._normalize_price(
                        symbol,
                        entry * (1.0 - stop_fraction),
                    ),
                    take_profit_price=self._normalize_price(
                        symbol,
                        entry * (1.0 + target_fraction),
                    ),
                    risk_pct=requested_risk_pct * 100.0,
                    fees=float(execution.fee_quote_equivalent),
                )

                self.db.state_delete(
                    self._pending_key(symbol)
                )

                oco_result = self._create_oco(
                    symbol,
                    qty,
                    entry,
                    trade_id,
                    stop_fraction=stop_fraction,
                    target_fraction=target_fraction,
                    risk_pct=requested_risk_pct * 100.0,
                )

                if isinstance(oco_result, dict) and oco_result.get("emergency_exit"):
                    results.append(
                        {
                            "symbol": symbol,
                            "trade_id": trade_id,
                            "risk_pct": requested_risk_pct * 100.0,
                            "entry_price": entry,
                            "quantity": qty,
                            **oco_result,
                        }
                    )
                    continue

                self.set_state(
                    symbol,
                    "OPEN",
                )
                results.append(
                    {
                        "symbol": symbol,
                        "trade_id": trade_id,
                        "risk_pct": requested_risk_pct * 100.0,
                        "entry_price": entry,
                        "quantity": qty,
                    }
                )
            except Exception as exc:
                # Do NOT issue an emergency SELL here. The durable entry
                # intent remains on disk and startup/next cycle recovery
                # will inspect the exact Binance clientOrderId.
                self.set_state(
                    symbol,
                    "RECONCILE_REQUIRED",
                )
                self.db.log_event(
                    "ERROR",
                    "multi_position_entry_error",
                    str(exc),
                    {
                        "symbol": symbol,
                        "clientOrderId": client_id,
                    },
                )
                results.append(
                    {
                        "symbol": symbol,
                        "error": str(exc),
                        "reconcile_required": True,
                    }
                )
            finally:
                self._locks.discard(symbol)

        return results

    def scan_and_execute(self):
        recovery = self.recover()
        if not recovery["ok"]:
            return {
                "status": "BLOCKED",
                "recovery": recovery,
                "results": [],
            }

        if self.campaign_engine_enabled:
            pending_recovery = self.campaign_execution.reconcile_pending_entries()
            active_recovery = self.campaign_execution.reconcile_active_campaigns()
            # Manage open campaigns before looking for new opportunities. If a
            # protection mutation becomes ambiguous, the whole execution path
            # stays fail-closed.
            session_actions = self.campaign_monitor.enforce_session()
            campaign_monitor = self.campaign_monitor.monitor_all()
            session_state = self.intraday_policy.session.state(datetime.now(timezone.utc)) if self.intraday_core_enabled else "ENTRY_WINDOW"
            if self.intraday_core_enabled and session_state != "ENTRY_WINDOW":
                return {
                    "status": "SESSION_MANAGED",
                    "recovery": recovery,
                    "session_actions": session_actions,
                    "campaign_monitor": campaign_monitor,
                    "results": [],
                    "session_state": session_state,
                }
            # A resolved/verified pending campaign is now represented by the
            # campaign state; unresolved mutation remains a hard global block.
            unresolved = self.unresolved_symbols()
            if unresolved:
                return {
                    "status": "BLOCKED",
                    "recovery": recovery,
                    "pending_recovery": pending_recovery,
                    "active_recovery": active_recovery,
                    "campaign_monitor": campaign_monitor,
                    "results": [],
                    "reason": "campaign reconciliation required",
                }

        allowed, risk_reason = self._daily_entry_guard()
        if not allowed:
            self.db.log_event("WARNING", "daily_entry_blocked", risk_reason)
            return {"status": "RISK_BLOCKED", "results": [], "reason": risk_reason}

        balance = self._balance()
        if balance <= 0:
            return {
                "status": "WAIT",
                "results": [],
                "reason": "no USDT balance",
            }

        open_trades = self.open_trades()

        # A pending conditional campaign is normal in campaign mode; it is not
        # a recovery failure and must not block unrelated symbols.
        if not self.campaign_engine_enabled:
            pending_entries = self._pending_entries()
            if pending_entries:
                return {
                    "status": "BLOCKED",
                    "results": [],
                    "reason": "ENTRY_PENDING: durable entry intent requires recovery",
                }

        if self.max_open_positions > 0 and len(open_trades) >= self.max_open_positions:
            # Existing campaigns can still be monitored/add to; only new
            # independent campaigns are blocked by this portfolio capacity.
            if not self.campaign_engine_enabled:
                return {
                    "status": "POSITION_LIMIT",
                    "results": [],
                    "reason": f"MAX_OPEN_POSITIONS={self.max_open_positions}",
                    "open_positions": len(open_trades),
                }

        controller = PortfolioController(
            self.client,
            balance_quote=balance,
            symbols=self.symbols,
            db=self.db,
        )
        selections = controller.select_portfolio(
            open_risk_quote=self.reserved_risk_quote(),
            open_positions=len(open_trades),
        )
        results = (
            self.execute_campaign(selections)
            if self.campaign_engine_enabled
            else self.execute(selections)
        )
        return {
            "status": "EXECUTED",
            "recovery": recovery,
            "pending_recovery": pending_recovery if self.campaign_engine_enabled else [],
            "campaign_monitor": campaign_monitor if self.campaign_engine_enabled else [],
            "results": results,
            "open_positions": len(self.open_trades()),
            "reserved_risk_quote": self.reserved_risk_quote(),
            "campaign_engine": self.campaign_engine_enabled,
        }

