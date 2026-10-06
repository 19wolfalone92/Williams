import os
import uuid
from datetime import datetime, timezone

from portfolio_controller import PortfolioController
from db import Database


POSITION_STATES = {
    "FLAT",
    "ENTRY_PENDING",
    "OPEN",
    "EXIT_PENDING",
    "RECONCILE_REQUIRED",
}


class MultiPositionTrader:
    """Independent lifecycle manager for several Binance Spot positions.

    One SQLite trade row represents one managed position.  The row remains
    OPEN until exchange evidence proves the position was fully sold.

    Lifecycle:
        ENTRY_PENDING -> OPEN/EXIT_PENDING -> OPEN -> FLAT
        -> RECONCILE_REQUIRED on ambiguity
    """

    ENTRY_PREFIX = "WILLV4_ENTRY_"
    OCO_PREFIX = "WILLV4_OCO_"

    def __init__(self, client, db=None, symbols=None):
        self.client = client
        self.db = db or Database()
        self.symbols = symbols or [
            x.strip().upper()
            for x in os.getenv(
                "AUTO_SCAN_SYMBOLS",
                "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT",
            ).split(",")
            if x.strip()
        ]
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
                    or not str(sell.get("clientOrderId", "")).startswith(self.OCO_PREFIX)
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
        """Clear only stale barriers after exchange/SQLite evidence proves no active activity."""
        repaired = []
        for symbol in self.symbols:
            symbol = str(symbol).upper()
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

    def _balance(self):
        account = self.client.account()
        return sum(
            float(b.get("free", 0) or 0)
            + float(b.get("locked", 0) or 0)
            for b in account.get("balances", [])
            if b.get("asset") == "USDT"
        )

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
                client_id.startswith(self.OCO_PREFIX)
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

        if not (tp > entry > sl > sl_limit):
            raise RuntimeError(
                f"{symbol}: invalid rounded TP/SL"
            )

        client_id = f"{self.OCO_PREFIX}{uuid.uuid4().hex[:20]}"
        self.set_state(symbol, "EXIT_PENDING")

        result = self.client.create_oco_sell_safe(
            symbol,
            self.client.decimal_format(qty),
            self.client.decimal_format(tp),
            self.client.decimal_format(sl),
            self.client.decimal_format(sl_limit),
            client_id,
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

                if status in {"CANCELED", "EXPIRED", "REJECTED"}:
                    self.db.state_delete(
                        self._pending_key(symbol)
                    )
                    self.set_state(symbol, "FLAT")
                    results.append(
                        {"symbol": symbol, "state": "FLAT"}
                    )
                    continue

                if status != "FILLED":
                    self.set_state(symbol, "ENTRY_PENDING")
                    continue

                qty = float(
                    order.get("executedQty", 0) or 0
                )
                spent = float(
                    order.get("cummulativeQuoteQty", 0) or 0
                )
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
                        fees=0,
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

        if oco:
            if not trade.get("exit_order_list_id"):
                self.db.update_trade_oco(
                    trade["id"],
                    oco.get("orderListId"),
                    list_client_order_id=oco.get(
                        "listClientOrderId"
                    ),
                )
                trade = self.db.open_trade(symbol)

        exit_orders = self._bot_exit_orders(
            trade,
            all_orders,
            oco,
        )
        filled_exits = [
            order for order in exit_orders
            if str(order.get("status", "")).upper() == "FILLED"
        ]
        sold_qty, sold_quote, last_exit = self._filled_exit_metrics(
            filled_exits
        )

        entry_id = str(
            trade.get("entry_order_id") or ""
        )
        entry_order = next(
            (
                o for o in all_orders
                if str(o.get("orderId")) == entry_id
            ),
            None,
        )
        bought_qty = float(
            entry_order.get("executedQty", 0) or 0
        ) if entry_order else float(
            trade.get("quantity") or 0
        )

        managed_original_qty = min(
            float(trade.get("quantity") or bought_qty),
            bought_qty or float(trade.get("quantity") or 0),
        )
        remaining_expected = max(
            0.0,
            managed_original_qty - sold_qty,
        )

        if filled_exits and remaining_expected <= 0:
            if last_exit is None:
                raise RuntimeError(
                    f"{symbol}: filled managed exit has no usable execution"
                )
            entry_price = float(trade["entry_price"])
            exit_price = (
                sold_quote / sold_qty
                if sold_quote > 0 and sold_qty > 0
                else self._exit_price(last_exit)
            )
            realized_qty = min(
                managed_original_qty,
                sold_qty,
            )
            realized_quote = (
                sold_quote
                if sold_quote > 0
                else exit_price * realized_qty
            )
            pnl = (
                realized_quote
                - entry_price * realized_qty
            )
            pnl_pct = (
                exit_price / entry_price - 1.0
                if entry_price else 0.0
            )
            self.db.close_trade(
                trade["id"],
                datetime.fromtimestamp(
                    int(
                        last_exit.get(
                            "transactTime",
                            last_exit.get("time", 0),
                        )
                    ) / 1000,
                    tz=timezone.utc,
                ).isoformat(),
                exit_price,
                pnl,
                pnl_pct,
                self._exit_reason(last_exit),
                last_exit.get("orderListId"),
            )
            self.set_state(symbol, "FLAT")
            self.db.log_event(
                "INFO",
                "position_closed",
                f"{symbol} position fully closed by managed exit",
                {
                    "trade_id": trade["id"],
                    "quantity": managed_original_qty,
                    "sold_qty": sold_qty,
                    "exit_price": exit_price,
                    "reason": self._exit_reason(last_exit),
                },
            )
            return {
                "trade_id": trade["id"],
                "symbol": symbol,
                "state": "FLAT",
                "closed": True,
            }

        account = self.client.account()
        exchange_qty = self._asset_balance(
            symbol,
            account=account,
        )

        if exchange_qty < 0:
            raise RuntimeError(
                f"{symbol}: invalid negative exchange balance"
            )

        if remaining_expected < float(os.getenv("MIN_RECOVERY_QTY", "0.000001")):
            if filled_exits:
                last_exit = max(
                    filled_exits,
                    key=lambda row: int(
                        row.get(
                            "time",
                            row.get("transactTime", 0),
                        ) or 0
                    ),
                )
                self.db.close_trade(
                    trade["id"],
                    datetime.fromtimestamp(
                        int(
                            last_exit.get(
                                "transactTime",
                                last_exit.get("time", 0),
                            )
                        ) / 1000,
                        tz=timezone.utc,
                    ).isoformat(),
                    self._exit_price(last_exit),
                    0.0,
                    0.0,
                    self._exit_reason(last_exit),
                    last_exit.get("orderListId"),
                )
                self.set_state(symbol, "FLAT")
                return {
                    "trade_id": trade["id"],
                    "symbol": symbol,
                    "state": "FLAT",
                    "closed": True,
                }
            raise RuntimeError(
                f"{symbol}: open trade has no remaining quantity and no "
                "filled managed exit evidence"
            )

        tolerance = max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            remaining_expected * self.balance_tolerance_pct,
        )
        if abs(exchange_qty - remaining_expected) > tolerance:
            raise RuntimeError(
                f"{symbol}: managed quantity mismatch; "
                f"expected={remaining_expected:.12g}, "
                f"exchange={exchange_qty:.12g}"
            )

        # An unrelated user SELL against this managed symbol is ambiguous.
        open_orders = self.client.open_orders(symbol)
        unknown_sells = [
            order for order in open_orders
            if str(order.get("side", "")).upper() == "SELL"
            and str(order.get("orderListId", "")) != str(
                trade.get("exit_order_list_id") or ""
            )
            and not str(order.get("clientOrderId", "")).startswith(
                self.OCO_PREFIX
            )
        ]
        if unknown_sells:
            raise RuntimeError(
                f"{symbol}: unrecognized open SELL order conflicts "
                "with managed position"
            )

        # If the OCO is active, position is safely OPEN.
        active_list = False
        for row in open_lists:
            if str(row.get("symbol", "")).upper() != symbol:
                continue
            same_id = (
                str(row.get("orderListId", ""))
                == str(trade.get("exit_order_list_id") or "")
            )
            same_client = (
                str(row.get("listClientOrderId", ""))
                == str(trade.get("exit_order_list_client_id") or "")
            )
            prefix = str(
                row.get("listClientOrderId", "")
            ).startswith(self.OCO_PREFIX)
            if same_id or same_client or prefix:
                active_list = True
                break

        if active_list:
            self.set_state(symbol, "OPEN")
            if abs(exchange_qty - float(trade["quantity"])) > tolerance:
                self.db.update_trade_quantity(
                    trade["id"],
                    min(exchange_qty, remaining_expected),
                )
            return {
                "trade_id": trade["id"],
                "symbol": symbol,
                "state": "OPEN",
                "protected": True,
            }

        # No managed OCO is currently open, but the position exists.
        # Recreate protection using the originally stored price distances.
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

        self._create_oco(
            symbol,
            min(exchange_qty, remaining_expected),
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
                "quantity": min(exchange_qty, remaining_expected),
            },
        )
        return {
            "trade_id": trade["id"],
            "symbol": symbol,
            "state": "OPEN",
            "protected": True,
            "oco_restored": True,
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
        """Startup/full recovery. No new entry is allowed until it is clean."""
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
                    if list_id:
                        cancel(symbol, order_list_id=list_id)
                    else:
                        cancel(symbol, list_client_order_id=list_client)

            account = self.client.account()
            free_qty = self._asset_balance(
                symbol,
                account=account,
            )
            sell_qty = self._normalize_qty(
                symbol,
                min(float(trade["quantity"]), free_qty),
            )
            if sell_qty <= 0:
                raise RuntimeError(
                    f"{symbol}: managed quantity is no longer available"
                )

            sell = self.client.order_safe(
                symbol,
                "SELL",
                "MARKET",
                quantity=self.client.decimal_format(sell_qty),
                new_client_order_id=f"{self.ENTRY_PREFIX}MANUAL_SELL_{uuid.uuid4().hex[:16]}",
            )
            self.db.save_order(sell)

            executed_qty = float(
                sell.get("executedQty", 0) or 0
            )
            quote = float(
                sell.get("cummulativeQuoteQty", 0) or 0
            )
            if executed_qty <= 0:
                raise RuntimeError(
                    f"{symbol}: manual SELL returned no fill"
                )

            exit_price = (
                quote / executed_qty
                if quote > 0
                else float(sell.get("price", 0) or 0)
            )
            entry_price = float(trade["entry_price"])
            pnl = (
                exit_price - entry_price
            ) * min(
                executed_qty,
                float(trade["quantity"]),
            )
            pnl_pct = (
                exit_price / entry_price - 1.0
                if entry_price else 0.0
            )

            self.db.close_trade(
                trade["id"],
                datetime.now(timezone.utc).isoformat(),
                exit_price,
                pnl,
                pnl_pct,
                "MANUAL_SELL",
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
                "exit_price": exit_price,
                "pnl": pnl,
                "state": "FLAT",
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
    # New entries
    # ------------------------------------------------------------------

    def _can_enter(self, symbol):
        if symbol in self._locks:
            return False
        if self.state(symbol) == "RECONCILE_REQUIRED":
            return False
        if self.db.open_trade(symbol):
            return False
        if any(self.state(s) == "RECONCILE_REQUIRED" for s in self.symbols):
            return False
        return True

    def execute(self, selections):
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
            self.db.state_set(
                self._pending_key(symbol),
                client_id,
            )

            try:
                order = self.client.order_safe(
                    symbol,
                    "BUY",
                    "MARKET",
                    quote_order_qty=self.client.decimal_format(quote),
                    new_client_order_id=client_id,
                )
                self.db.save_order(order)

                qty = float(
                    order.get("executedQty", 0) or 0
                )
                spent = float(
                    order.get("cummulativeQuoteQty", 0) or 0
                )
                if qty <= 0 or spent <= 0:
                    raise RuntimeError(
                        f"{symbol}: BUY returned invalid fill"
                    )

                entry = spent / qty
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
                    fees=0,
                )

                self.db.state_delete(
                    self._pending_key(symbol)
                )

                self._create_oco(
                    symbol,
                    qty,
                    entry,
                    trade_id,
                    stop_fraction=stop_fraction,
                    target_fraction=target_fraction,
                    risk_pct=requested_risk_pct * 100.0,
                )

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

        balance = self._balance()
        if balance <= 0:
            return {
                "status": "WAIT",
                "results": [],
                "reason": "no USDT balance",
            }

        controller = PortfolioController(
            self.client,
            balance_quote=balance,
            symbols=self.symbols,
        )
        open_trades = self.open_trades()
        selections = controller.select_portfolio(
            open_risk_quote=self.reserved_risk_quote(),
            open_positions=len(open_trades),
        )
        return {
            "status": "EXECUTED",
            "recovery": recovery,
            "results": self.execute(selections),
            "open_positions": len(self.open_trades()),
            "reserved_risk_quote": self.reserved_risk_quote(),
        }
