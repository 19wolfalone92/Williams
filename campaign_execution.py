"""Execution adapter for Williams conditional campaign entries and stops."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
import json
import os
import uuid

from binance_client import BinanceAPIError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingOrderRecord, SignalSpec, SignalState, SignalType
from execution_barrier import ExecutionBarrier, OrderIntent
from pending_signal import PendingSignal
from recovery_matrix import RecoveryMatrix
from williams.execution_economics import ExecutionFeasibilityGate
from williams.intraday_policy import IntradayPolicy


class CampaignExecutionError(RuntimeError):
    pass


class CampaignExecutionService:
    ENTRY_PREFIX = "WILLV5_ENTRY_"
    STOP_PREFIX = "WILLV5_STOP_"
    EXIT_PREFIX = "WILLV5_EXIT_"

    def __init__(self, client, db, execution_barrier: ExecutionBarrier | None = None):
        self.client = client
        self.db = db
        self.barrier = execution_barrier
        self.core_mode = str(os.getenv("WILLIAMS_MODE", "INTRADAY_CORE")).upper() == "INTRADAY_CORE"
        self.economics_gate = ExecutionFeasibilityGate(
            fee_per_side_pct=float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")),
            slippage_pct=float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")),
            max_spread_pct=float(os.getenv("MAX_SPREAD_PCT", "0.0015")),
        )
        self.intraday_policy = IntradayPolicy(
            session_start_utc=os.getenv("TRADING_SESSION_START_UTC", "08:00"),
            no_new_entries_utc=os.getenv("NO_NEW_ENTRIES_UTC", "18:00"),
            flat_time_utc=os.getenv("MANDATORY_FLAT_UTC", "20:00"),
        )
        self.engine = CampaignEngine(
            db,
            portfolio_risk_limit_pct=float(os.getenv("MAX_TOTAL_RISK_PCT", "0.006")),
            campaign_risk_limit_pct=float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.0025" if self.core_mode else "0.005")),
            initial_risk_fraction_of_campaign=float(
                os.getenv("CAMPAIGN_INITIAL_RISK_FRACTION", "0.4166666667")
            ),
        )

    @staticmethod
    def _symbols_rows(info: dict[str, Any]) -> dict[str, dict]:
        return {
            str(row.get("symbol", "")).upper(): row
            for row in info.get("symbols", [])
            if isinstance(row, dict)
        }

    def _rules(self, symbol: str) -> dict[str, Any]:
        info = self.client.exchange_info(symbol)
        row = self._symbols_rows(info).get(symbol.upper())
        if row is None:
            raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable")
        return {
            f.get("filterType"): f
            for f in row.get("filters", [])
            if isinstance(f, dict)
        }

    @staticmethod
    def _floor(value: float, step: float) -> float:
        if step <= 0:
            return float(value)
        import math
        return math.floor(float(value) / step + 1e-12) * step

    def _normalize_qty(self, symbol: str, quantity: float) -> float:
        filters = self._rules(symbol)
        lot = filters.get("LOT_SIZE") or filters.get("MARKET_LOT_SIZE") or {}
        step = float(lot.get("stepSize", "0") or 0)
        minimum = float(lot.get("minQty", "0") or 0)
        maximum = float(lot.get("maxQty", "inf") or "inf")
        qty = self._floor(float(quantity), step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty < minimum:
            raise CampaignExecutionError(
                f"{symbol}: quantity {qty:.12g} below LOT_SIZE {minimum:.12g}"
            )
        return qty

    def _normalized_entry_qty(self, symbol: str, notional: float, trigger: float) -> float:
        filters = self._rules(symbol)
        lot = filters.get("LOT_SIZE") or {}
        step = float(lot.get("stepSize", "0") or 0)
        minimum = float(lot.get("minQty", "0") or 0)
        maximum = float(lot.get("maxQty", "inf") or "inf")
        qty = self._floor(notional / max(trigger, 1e-12), step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty < minimum:
            raise CampaignExecutionError(
                f"{symbol}: campaign entry quantity {qty:.12g} below LOT_SIZE {minimum:.12g}"
            )
        nf = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
        min_notional = float(nf.get("minNotional", "0") or 0)
        if qty * trigger < min_notional:
            raise CampaignExecutionError(
                f"{symbol}: campaign entry notional {qty * trigger:.8f} below minimum {min_notional:.8f}"
            )
        return qty

    def _normalize_price(self, symbol: str, price: float) -> float:
        filters = self._rules(symbol)
        pf = filters.get("PRICE_FILTER") or {}
        tick = float(pf.get("tickSize", "0") or 0)
        if tick <= 0:
            raise CampaignExecutionError(f"{symbol}: PRICE_FILTER.tickSize unavailable")
        return self._floor(price, tick)

    def _check_buy_position_capacity(self, symbol: str, quantity: float) -> None:
        filters = self._rules(symbol)
        max_position_filter = filters.get("MAX_POSITION") or {}
        max_position = float(
            max_position_filter.get("maxPosition", "inf") or "inf"
        )
        if not max_position < float("inf"):
            return

        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise CampaignExecutionError(
                f"{symbol}: exchangeInfo unavailable for MAX_POSITION check"
            )
        asset = str(rows[0].get("baseAsset", "")).upper()
        account = self.client.account()
        base_total = 0.0
        for balance in account.get("balances", []):
            if str(balance.get("asset", "")).upper() == asset:
                base_total = (
                    float(balance.get("free", 0) or 0)
                    + float(balance.get("locked", 0) or 0)
                )
                break

        open_orders = self.client.open_orders(symbol)
        pending_buy_qty = sum(
            max(
                0.0,
                float(o.get("origQty", 0) or 0)
                - float(o.get("executedQty", 0) or 0),
            )
            for o in open_orders
            if str(o.get("side", "")).upper() == "BUY"
        )
        tolerance = max(1e-12, max_position * 1e-9)
        if base_total + pending_buy_qty + float(quantity) > max_position + tolerance:
            raise CampaignExecutionError(
                f"{symbol}: MAX_POSITION would be exceeded "
                f"(base={base_total:.12g}, pending_buy={pending_buy_qty:.12g}, "
                f"new={float(quantity):.12g}, max={max_position:.12g})"
            )

    def _current_price(self, symbol: str) -> float:
        row = self.client.ticker_price(symbol)
        price = float(row.get("price", 0) or 0)
        if price <= 0:
            raise CampaignExecutionError(f"{symbol}: invalid current market price")
        return price

    def _check_algo_capacity(self, symbol: str, additional: int = 1) -> None:
        filters = self._rules(symbol)
        open_orders = self.client.open_orders(symbol)
        max_orders = int((filters.get("MAX_NUM_ORDERS") or {}).get("maxNumOrders", 10**9))
        max_algo = int((filters.get("MAX_NUM_ALGO_ORDERS") or {}).get("maxNumAlgoOrders", 10**9))
        if len(open_orders) + additional > max_orders:
            raise CampaignExecutionError(f"{symbol}: MAX_NUM_ORDERS would be exceeded")
        algo_open = sum(
            1
            for row in open_orders
            if str(row.get("type", "")).upper()
            in {"STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"}
        )
        if algo_open + additional > max_algo:
            raise CampaignExecutionError(f"{symbol}: MAX_NUM_ALGO_ORDERS would be exceeded")

    def _submit(self, intent: OrderIntent, submit, pre_submit_checks):
        if self.barrier is None:
            raise CampaignExecutionError(
                "Campaign execution requires the canonical ExecutionBarrier"
            )
        result = self.barrier.execute(
            intent,
            submit,
            pre_submit_checks=pre_submit_checks,
        )
        if not result.accepted:
            raise CampaignExecutionError(
                f"ExecutionBarrier blocked {intent.purpose}: {result.reason}"
            )
        return result.response

    def arm_initial_entry(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
        capital_fraction: float = 0.25,
    ) -> dict[str, Any]:
        if signal.side != "BUY":
            raise CampaignExecutionError("Current Spot campaign executor only arms LONG entries")
        if self.core_mode and not self.intraday_policy.allows_new_campaign(datetime.now(timezone.utc)):
            raise CampaignExecutionError("INTRADAY_SESSION_BLOCKED: new campaign not allowed at current UTC time")

        reserved = self.engine.portfolio_reserved_risk_quote()
        capacity = max(0.0, float(equity_quote) * self.engine.portfolio_risk_limit_pct)
        remaining_risk = max(0.0, capacity - reserved)
        requested_risk = min(
            float(candidate_risk_pct),
            self.engine.initial_risk_pct(),
            remaining_risk / max(float(equity_quote), 1e-12),
        )
        if requested_risk <= 0:
            raise CampaignExecutionError("No portfolio risk capacity for campaign entry")

        current = self._current_price(signal.symbol)
        trigger = self._normalize_price(signal.symbol, signal.trigger_price)
        stop = self._normalize_price(
            signal.symbol,
            float(signal.protective_reference) - max(
                float(self._rules(signal.symbol).get("PRICE_FILTER", {}).get("tickSize", "0") or 0),
                1e-12,
            ),
        )

        if trigger <= current:
            raise CampaignExecutionError(
                f"{signal.symbol}: signal trigger {trigger:.12g} is not above current price {current:.12g}; "
                "trigger was missed, no market-order substitution is allowed"
            )
        if stop <= 0 or stop >= trigger:
            raise CampaignExecutionError(
                f"{signal.symbol}: invalid campaign structural stop {stop:.12g}"
            )

        spread_pct = 0.0
        try:
            book = self.client.book_ticker(signal.symbol)
            bid = float(book.get("bidPrice", 0) or 0.0)
            ask = float(book.get("askPrice", 0) or 0.0)
            if bid > 0 and ask > 0:
                spread_pct = (ask - bid) / ((ask + bid) / 2.0)
        except Exception:
            # Failure to obtain spread does not falsify Williams Core. The
            # execution barrier remains the final admission layer.
            spread_pct = 0.0

        economics = self.economics_gate.evaluate(
            entry=trigger,
            stop=stop,
            spread_pct=spread_pct,
            estimated_slippage_pct=float(os.getenv("MAX_L2_SLIPPAGE_PCT", "0.0015")),
            minimum_notional_ok=True,
            balance_ok=float(equity_quote) > 0.0,
            time_to_eod_ok=True,
        )
        if not economics.feasible:
            raise CampaignExecutionError(
                "BLOCKED_BY_EXECUTION_ECONOMICS: " + ",".join(economics.reasons)
            )

        stop_fraction = (trigger - stop) / trigger
        effective_loss_fraction = (
            stop_fraction
            + 2.0 * float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001"))
            + float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        )
        risk_quote = float(equity_quote) * requested_risk
        notional = min(
            float(equity_quote) * capital_fraction,
            risk_quote / max(effective_loss_fraction, 1e-12),
        )
        qty = self._normalized_entry_qty(signal.symbol, notional, trigger)
        client_id = f"{self.ENTRY_PREFIX}{uuid.uuid4().hex[:20]}"

        claimed = self.db.try_claim_state(
            f"entry_client_order_id:{signal.symbol}",
            client_id,
        )
        if not claimed:
            raise CampaignExecutionError(
                f"{signal.symbol}: another conditional entry is already reserved"
            )

        pending_signal = PendingSignal.from_spec(signal)
        campaign = self.engine.create_campaign(
            signal,
            initial_risk_pct=requested_risk,
        )
        campaign.tags["pending_signal"] = pending_signal.to_dict()
        campaign.tags["signal_role"] = signal.role.value
        campaign.tags["initial_stop_price"] = stop
        campaign.initial_stop_price = stop
        campaign.current_stop_price = stop
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = qty * trigger
        self.db.save_campaign(campaign)
        campaign.tags["pending_order_client_id"] = client_id
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_trigger"] = trigger
        # IMPORTANT: persist ENTRY_PENDING before touching Binance. A fast
        # conditional fill can arrive on the user stream immediately.
        self.engine.arm_entry(campaign, signal)
        campaign.tags["pending_signal"] = PendingSignal.from_spec(
            signal, state=SignalState.ARMED
        ).to_dict()
        self.db.save_campaign(campaign)
        self.db.state_set(f"campaign_state:{campaign.campaign_id}", campaign.state.value)

        intent = OrderIntent.new(
            signal.symbol,
            "BUY",
            "STOP_LOSS",
            required_context_versions=dict(signal.context_versions),
            hypothesis_id=f"WILLIAMS_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=client_id,
            purpose="CAMPAIGN_ENTRY",
            permission_interval=signal.timeframe,
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=risk_quote,
            capital_reserved_quote=qty * trigger,
        )

        def check(snapshot):
            # Last-mile market condition: this is a conditional order, never a
            # substitute for a missed trigger. If price already crossed the
            # trigger, abort rather than turn it into a MARKET BUY.
            now_price = self._current_price(signal.symbol)
            if now_price >= trigger:
                raise CampaignExecutionError(
                    f"{signal.symbol}: trigger already crossed at {now_price:.12g}"
                )

            # For the fractal signal, validity is specifically evaluated at
            # trigger time relative to Teeth. At arm time we require trigger
            # to remain above the latest Teeth as an early sanity check.
            if signal.signal_type == SignalType.FRACTAL:
                try:
                    from data import fetch_klines
                    from strategy import calculate_indicators, config_from_env
                    df = fetch_klines(
                        self.client,
                        signal.symbol,
                        signal.timeframe,
                        limit=160,
                    )
                    closed = df.iloc[:-1].copy() if len(df) > 1 else df
                    ind = calculate_indicators(closed, config_from_env())
                    teeth = float(ind.iloc[-1].get("teeth_shifted", 0.0) or 0.0)
                    if teeth > 0 and trigger <= teeth:
                        raise CampaignExecutionError(
                            f"{signal.symbol}: fractal trigger {trigger:.12g} is now not above Teeth {teeth:.12g}"
                        )
                except CampaignExecutionError:
                    raise
                except Exception as exc:
                    raise CampaignExecutionError(
                        f"{signal.symbol}: fractal last-mile validation failed: {exc}"
                    ) from exc

            self._check_buy_position_capacity(
                signal.symbol,
                qty,
            )
            self._check_algo_capacity(signal.symbol, additional=1)

        try:
            order = self._submit(
                intent,
                lambda: self.client.order_safe(
                    signal.symbol,
                    "BUY",
                    "STOP_LOSS",
                    quantity=self.client.decimal_format(qty),
                    stop_price=self.client.decimal_format(trigger),
                    new_client_order_id=client_id,
                ),
                check,
            )
            exchange_status = str(order.get("status", "")).upper()
            executed_now = float(order.get("executedQty", 0) or 0)
            if exchange_status in {"CANCELED", "EXPIRED", "REJECTED", "EXPIRED_IN_MATCH"} and executed_now <= 0:
                # Known terminal no-fill outcomes release the pending reservation
                # immediately. EXPIRED_IN_MATCH is Binance STP and is not an
                # unknown execution outcome.
                campaign.state = CampaignState.CLOSED
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                campaign.next_action = "WAIT"
                self.db.set_campaign_signal_state(
                    signal.signal_id,
                    SignalState.CANCELLED.value,
                )
                self.db.state_delete(f"entry_client_order_id:{signal.symbol}")
                self.db.save_campaign(campaign)
                return {
                    "campaign_id": campaign.campaign_id,
                    "signal_id": signal.signal_id,
                    "client_order_id": client_id,
                    "order_id": order.get("orderId"),
                    "state": CampaignState.CLOSED.value,
                    "order_status": exchange_status,
                }

            self.db.save_campaign_order(
                PendingOrderRecord(
                    order_id=str(order.get("orderId", "") or ""),
                    client_order_id=client_id,
                    symbol=signal.symbol,
                    side="BUY",
                    order_type="STOP_LOSS",
                    purpose="ENTRY",
                    status=str(order.get("status", "NEW")),
                    stop_price=trigger,
                    quantity=qty,
                    risk_quote=risk_quote,
                    capital_reserved_quote=qty * trigger,
                    signal_id=signal.signal_id,
                    campaign_id=campaign.campaign_id,
                )
            )
            campaign.tags["pending_order_id"] = str(order.get("orderId", "") or "")
            self.db.save_campaign(campaign)
            return {
                "campaign_id": campaign.campaign_id,
                "signal_id": signal.signal_id,
                "order_id": order.get("orderId"),
                "client_order_id": client_id,
                "trigger_price": trigger,
                "structural_stop": stop,
                "quantity": qty,
                "risk_quote": risk_quote,
            }
        except Exception as exc:
            # A pre-submit barrier rejection is safe to clear. Any exchange
            # mutation ambiguity remains fail-closed and recoverable by CID.
            message = str(exc)
            if "ExecutionBarrier blocked" in message:
                campaign.state = CampaignState.CLOSED
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                self.db.set_campaign_signal_state(
                    signal.signal_id,
                    SignalState.INVALIDATED.value,
                )
                self.db.state_delete(f"entry_client_order_id:{signal.symbol}")
                self.db.save_campaign(campaign)
            else:
                self.engine.mark_reconcile_required(campaign, message)
            raise

    def _find_campaign_by_pending_client_id(self, client_id: str):
        row = self.db.conn.execute(
            "SELECT campaign_id FROM campaign_orders WHERE client_order_id=? "
            "ORDER BY id DESC LIMIT 1",
            (str(client_id),),
        ).fetchone()
        if row:
            return self.engine.load_campaign(str(row["campaign_id"]))

        # Recovery race guard: Binance may have accepted the conditional order
        # before campaign_orders was persisted. Find the durable campaign by
        # its persisted pending clientOrderId tag.
        for item in self.db.open_campaigns():
            try:
                tags = json.loads(item.get("tags_json") or "{}")
            except Exception:
                tags = {}
            if str(tags.get("pending_order_client_id", "")) == str(client_id):
                return self.engine.load_campaign(str(item["campaign_id"]))
        return None

    def reconcile_pending_entries(self) -> list[dict[str, Any]]:
        """Adopt conditional BUYs after triggers, partial fills, restart or crash."""
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state "
            "WHERE key LIKE 'entry_client_order_id:%' "
            "AND value LIKE 'WILLV5_ENTRY_%'"
        ).fetchall()
        results: list[dict[str, Any]] = []

        for row in rows:
            symbol = str(row["key"]).split(":", 1)[1].upper()
            client_id = str(row["value"])
            campaign = None
            try:
                order = self.client.get_order(
                    symbol,
                    orig_client_order_id=client_id,
                )
                self.db.save_order(order)
                status = str(order.get("status", "")).upper()
                campaign = self._find_campaign_by_pending_client_id(client_id)

                if campaign is None:
                    self.db.state_set(
                        f"position_state:{symbol}",
                        "RECONCILE_REQUIRED",
                    )
                    results.append({
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "reason": "conditional order has no persisted campaign",
                    })
                    continue

                order_row = self.db.conn.execute(
                    "SELECT purpose,signal_id FROM campaign_orders "
                    "WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
                    (client_id,),
                ).fetchone()
                purpose = str(order_row["purpose"] if order_row else "ENTRY").upper()
                signal_id = str(
                    order_row["signal_id"]
                    if order_row
                    else campaign.current_signal_id
                )

                executed = float(order.get("executedQty", 0) or 0)
                quote = float(order.get("cummulativeQuoteQty", 0) or 0)

                # Williams' Fractal rule is evaluated at the instant the
                # breakout is hit, not only when the pending order is armed.
                # Teeth can move while a conditional order waits on Binance.
                if (
                    str(campaign.current_signal_type or "").upper() == SignalType.FRACTAL.value
                    and status in {"NEW", "PENDING_NEW", "PARTIALLY_FILLED", "FILLED"}
                ):
                    try:
                        from data import fetch_klines
                        from strategy import calculate_indicators, config_from_env
                        df = fetch_klines(
                            self.client,
                            symbol,
                            campaign.execution_timeframe,
                            limit=160,
                        )
                        closed = df.iloc[:-1].copy() if len(df) > 1 else df
                        ind = calculate_indicators(closed, config_from_env())
                        teeth = float(ind.iloc[-1].get("teeth_shifted", 0.0) or 0.0)
                        hit_price = float(order.get("stopPrice", 0) or 0)
                        fractal_valid = teeth <= 0.0 or hit_price > teeth
                    except Exception as exc:
                        self.engine.mark_reconcile_required(
                            campaign,
                            f"Fractal hit-time validation unavailable: {exc}",
                        )
                        raise CampaignExecutionError(
                            f"{symbol}: cannot verify Fractal Teeth filter at trigger time"
                        ) from exc

                    if not fractal_valid:
                        if executed <= 0:
                            self._execute_cancel(
                                campaign,
                                int(order.get("orderId")),
                                "CAMPAIGN_FRACTAL_INVALIDATED",
                            )
                            self.db.set_campaign_signal_state(
                                signal_id,
                                SignalState.INVALIDATED.value,
                            )
                            campaign.pending_risk_quote = 0.0
                            campaign.capital_reserved_quote = 0.0
                            campaign.next_action = "WAIT"
                            campaign.transition(
                                CampaignState.CLOSED,
                                reason="Fractal failed Teeth filter before fill",
                            )
                            self.db.state_delete(f"entry_client_order_id:{symbol}")
                            self.db.save_campaign(campaign)
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": "INVALIDATED",
                                "reason": "Fractal trigger no longer above Teeth",
                            })
                            continue

                        # A fill that violates the hit-time Fractal filter is
                        # not a valid Williams entry. Protect it first, then
                        # flatten only the campaign inventory.
                        protection = self.create_hard_stop(
                            campaign,
                            quantity=executed,
                            stop_price=float(campaign.current_stop_price),
                        )
                        campaign.tags["protective_order_id"] = protection.get("order_id", "")
                        campaign.position_qty = executed
                        campaign.average_entry_price = quote / executed if quote > 0 else float(order.get("price", 0) or 0)
                        campaign.open_risk_quote = self.engine.refresh_open_risk(campaign)
                        campaign.state = CampaignState.OPEN_INITIAL
                        self.db.save_campaign(campaign)
                        self.exit_market(
                            campaign,
                            reason="FRACTAL_TEETH_FILTER_FAILED_AT_FILL",
                        )
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": "CLOSED",
                            "reason": "Fractal trigger failed Teeth filter at fill",
                        })
                        continue
                if quote <= 0 and executed > 0 and hasattr(self.client, "my_trades"):
                    try:
                        fills = self.client.my_trades(
                            symbol,
                            order_id=order.get("orderId"),
                            limit=1000,
                        ) or []
                        quote = sum(
                            float(x.get("price", 0) or 0) *
                            float(x.get("qty", x.get("executedQty", 0)) or 0)
                            for x in fills
                        )
                    except Exception:
                        pass

                if status in {"NEW", "PENDING_NEW"} and executed <= 0:
                    # Still waiting for the conditional trigger.
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "ENTRY_PENDING",
                        "order_status": status,
                    })
                    continue

                recovery = RecoveryMatrix.explain(
                    lifecycle=campaign.state.value,
                    exchange_status=status,
                    executed_qty=executed,
                )
                if status == "PARTIALLY_FILLED" and order.get("orderId") is not None:
                    try:
                        self._execute_cancel(
                            campaign,
                            int(order.get("orderId")),
                            "CAMPAIGN_ENTRY_PARTIAL_CANCEL",
                        )
                        self.db.log_campaign_event(
                            campaign.campaign_id,
                            "ENTRY_PARTIAL_FILL",
                            order_id=str(order.get("orderId", "")),
                            reason="recovery matrix: CANCEL_AND_PROTECT",
                            payload=recovery,
                        )
                    except Exception as exc:
                        self.engine.mark_reconcile_required(
                            campaign,
                            f"partial conditional BUY cancel ambiguous: {exc}",
                        )
                        raise

                if executed > 0:
                    if quote <= 0:
                        raise CampaignExecutionError(
                            f"{symbol}: executed conditional BUY has no authoritative quote quantity"
                        )
                    avg = quote / executed

                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        if campaign.state == CampaignState.ADD_ON_PENDING:
                            campaign.transition(
                                CampaignState.POSITION_EXPANDING,
                                reason="conditional add-on triggered",
                            )

                        old_qty = float(campaign.position_qty)
                        total_qty = old_qty + executed
                        current_stop = float(campaign.current_stop_price or 0.0)
                        if current_stop <= 0 or current_stop >= avg:
                            raise CampaignExecutionError(
                                f"{symbol}: existing structural stop {current_stop:.12g} "
                                f"is invalid for add-on average {avg:.12g}"
                            )

                        protective_id = str(
                            campaign.tags.get("protective_order_id", "") or ""
                        )
                        if protective_id:
                            self.replace_structural_stop(
                                campaign,
                                existing_order_id=int(protective_id),
                                quantity=total_qty,
                                proposed_stop=current_stop,
                            )
                        else:
                            protection = self.create_hard_stop(
                                campaign,
                                quantity=total_qty,
                                stop_price=current_stop,
                            )
                            campaign.tags["protective_order_id"] = protection.get(
                                "order_id", ""
                            )

                        self.engine.record_add_on_fill(
                            campaign,
                            quantity=executed,
                            average_entry_price=avg,
                            fill_order_id=str(order.get("orderId", "")),
                            risk_quote=float(campaign.pending_risk_quote),
                            fee_quote=0.0,
                        )
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.FILLED.value,
                        )
                        trade = self.db.open_trade(symbol)
                        if trade is not None:
                            self.db.update_trade_quantity(
                                trade["id"],
                                total_qty,
                            )
                            self.db.conn.execute(
                                "UPDATE trades SET entry_price=?, stop_price=?, "
                                "risk_pct=?, updated_at=CURRENT_TIMESTAMP "
                                "WHERE id=? AND exit_time IS NULL",
                                (
                                    campaign.average_entry_price,
                                    campaign.current_stop_price,
                                    float(
                                        campaign.open_risk_quote /
                                        max(self._current_equity_quote(), 1e-12)
                                    ) * 100.0,
                                    int(trade["id"]),
                                ),
                            )
                            self.db.conn.commit()

                        self.db.state_delete(f"entry_client_order_id:{symbol}")
                        self.db.state_set(f"position_state:{symbol}", "OPEN")
                        campaign.tags.pop("pending_order_id", None)
                        self.db.save_campaign(campaign)
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": campaign.state.value,
                            "action": "ADD_ON_FILLED",
                            "filled_quantity": executed,
                        })
                        continue

                    if campaign.state == CampaignState.ENTRY_PENDING:
                        self.engine.mark_triggered(
                            campaign,
                            signal_id,
                            str(order.get("orderId", "")),
                        )
                    elif campaign.state == CampaignState.SIGNAL_DETECTED:
                        # Defensive recovery for a crash between state creation
                        # and campaign arming. The durable Binance order is proof
                        # that entry was already submitted; recover it, don't arm twice.
                        campaign.transition(
                            CampaignState.ENTRY_PENDING,
                            reason="recovered submitted conditional entry",
                        )
                        self.engine.mark_triggered(
                            campaign,
                            signal_id,
                            str(order.get("orderId", "")),
                        )
                    else:
                        raise CampaignExecutionError(
                            f"{symbol}: unexpected campaign state {campaign.state.value} for initial fill"
                        )

                    stop = float(
                        campaign.initial_stop_price or
                        campaign.tags.get("initial_stop_price", 0.0) or
                        0.0
                    )
                    if stop <= 0:
                        raise CampaignExecutionError(
                            f"{symbol}: campaign has no initial structural stop"
                        )

                    protection = self.create_hard_stop(
                        campaign,
                        quantity=executed,
                        stop_price=stop,
                    )
                    campaign.tags["protective_order_id"] = protection.get(
                        "order_id", ""
                    )

                    self.engine.record_initial_fill(
                        campaign,
                        quantity=executed,
                        average_entry_price=avg,
                        initial_stop_price=stop,
                        fill_order_id=str(order.get("orderId", "")),
                        risk_quote=float(campaign.pending_risk_quote),
                        fee_quote=0.0,
                    )
                    self.db.set_campaign_signal_state(
                        signal_id,
                        SignalState.FILLED.value,
                    )

                    if self.db.open_trade(symbol) is None:
                        self.db.save_trade(
                            entry_time=datetime.fromtimestamp(
                                int(
                                    order.get(
                                        "transactTime",
                                        order.get("time", 0),
                                    ) or 0
                                ) / 1000,
                                tz=timezone.utc,
                            ).isoformat(),
                            symbol=symbol,
                            side="LONG",
                            entry_price=avg,
                            quantity=executed,
                            entry_order_id=str(order.get("orderId", "")),
                            entry_client_order_id=client_id,
                            stop_price=stop,
                            take_profit_price=None,
                            risk_pct=float(
                                campaign.tags.get("initial_risk_pct", 0.0) or 0.0
                            ) * 100.0,
                            fees=0.0,
                        )

                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    campaign.tags.pop("pending_order_id", None)
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "OPEN",
                        "filled_quantity": executed,
                        "partial_entry": status == "PARTIALLY_FILLED",
                    })
                    continue

                if status in {"CANCELED", "EXPIRED", "REJECTED", "EXPIRED_IN_MATCH"}:
                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        try:
                            campaign.transition(
                                CampaignState.TREND_ACTIVE,
                                reason=f"add-on terminal status {status}",
                            )
                        except ValueError:
                            campaign.state = CampaignState.TREND_ACTIVE
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.CANCELLED.value,
                        )
                    else:
                        campaign.state = CampaignState.CLOSED
                        campaign.next_action = "WAIT"
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.CANCELLED.value,
                        )
                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(
                        f"position_state:{symbol}",
                        "OPEN" if self.db.open_trade(symbol) else "FLAT",
                    )
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": campaign.state.value,
                        "order_status": status,
                    })
                    continue

                self.engine.mark_reconcile_required(
                    campaign,
                    f"unknown pending conditional order status {status}",
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    "RECONCILE_REQUIRED",
                )
                results.append({
                    "symbol": symbol,
                    "campaign_id": campaign.campaign_id,
                    "state": "RECONCILE_REQUIRED",
                    "status": status,
                })
            except Exception as exc:
                if campaign is not None:
                    self.engine.mark_reconcile_required(
                        campaign,
                        str(exc),
                    )
                self.db.state_set(
                    f"position_state:{symbol}",
                    "RECONCILE_REQUIRED",
                )
                results.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def cancel_pending_campaign_entries(self, reason: str = "NO_NEW_ENTRIES") -> list[dict[str, Any]]:
        """Cancel bot-owned pending campaign BUYs and release reservations."""
        results: list[dict[str, Any]] = []
        for row in list(self.db.open_campaigns()):
            campaign_id = str(row.get("campaign_id") or "")
            if not campaign_id:
                continue
            campaign = self.engine.load_campaign(campaign_id)
            if campaign is None or campaign.position_qty > 0:
                continue
            if campaign.state not in {
                CampaignState.ENTRY_PENDING,
                CampaignState.ENTRY_ARMING,
                CampaignState.SIGNAL_DETECTED,
                CampaignState.ADD_ON_PENDING,
                CampaignState.ADD_ON_ARMING,
            }:
                continue
            try:
                pending_id = str(campaign.tags.get("pending_order_id", "") or "").strip()
                if pending_id:
                    self._execute_cancel(campaign, int(pending_id), reason)
                signal_id = str(campaign.current_signal_id or campaign.origin_signal_id or "")
                if signal_id:
                    self.db.set_campaign_signal_state(signal_id, SignalState.CANCELLED.value)
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                campaign.tags.pop("pending_order_id", None)
                campaign.next_action = "WAIT"
                campaign.state = CampaignState.CLOSED
                self.db.state_delete(f"entry_client_order_id:{campaign.symbol}")
                self.db.state_set(f"position_state:{campaign.symbol}", "FLAT")
                self.db.save_campaign(campaign)
                results.append({
                    "campaign_id": campaign_id,
                    "symbol": campaign.symbol,
                    "action": "PENDING_CANCELLED",
                    "reason": reason,
                })
            except Exception as exc:
                self.engine.mark_reconcile_required(campaign, str(exc))
                self.db.state_set(f"position_state:{campaign.symbol}", "RECONCILE_REQUIRED")
                results.append({
                    "campaign_id": campaign_id,
                    "symbol": campaign.symbol,
                    "action": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def force_end_of_day(self) -> list[dict[str, Any]]:
        """Cancel pending entries, then flatten every remaining Spot campaign."""
        results = self.cancel_pending_campaign_entries("END_OF_DAY_PENDING_CANCEL")
        for row in list(self.db.open_campaigns()):
            campaign_id = str(row.get("campaign_id") or "")
            if not campaign_id:
                continue
            campaign = self.engine.load_campaign(campaign_id)
            if campaign is None or campaign.position_qty <= 0:
                continue
            try:
                results.append({
                    "campaign_id": campaign_id,
                    "symbol": campaign.symbol,
                    "action": "FORCE_FLAT",
                    "exit": self.exit_market(campaign, reason="END_OF_DAY"),
                })
            except Exception as exc:
                self.engine.mark_reconcile_required(campaign, str(exc))
                self.db.state_set(f"position_state:{campaign.symbol}", "RECONCILE_REQUIRED")
                results.append({
                    "campaign_id": campaign_id,
                    "symbol": campaign.symbol,
                    "action": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def _current_equity_quote(self) -> float:
        account = self.client.account()
        equity = sum(
            float(row.get("free", 0) or 0) + float(row.get("locked", 0) or 0)
            for row in account.get("balances", [])
            if str(row.get("asset", "")).upper() == "USDT"
        )
        # Use the same mark-to-market portfolio base as the legacy trader.
        # This prevents campaign risk from being calculated on USDT only when
        # existing Spot positions already hold part of the portfolio.
        for row in self.db.open_campaigns():
            qty = float(row.get("position_qty", 0) or 0)
            symbol = str(row.get("symbol", "")).upper()
            if qty <= 0 or not symbol:
                continue
            try:
                price = float(self.client.ticker_price(symbol).get("price", 0) or 0)
            except Exception:
                price = 0.0
            if price > 0:
                equity += qty * price
        return max(equity, 0.0)

    def reconcile_active_campaigns(self) -> list[dict[str, Any]]:
        """Rebuild active campaign protection without invoking the legacy OCO path."""
        results = []
        campaigns = self.db.open_campaigns()
        for row in campaigns:
            try:
                campaign = self.engine.load_campaign(row["campaign_id"])
                if campaign is None:
                    continue
                symbol = campaign.symbol.upper()
                if campaign.state in {
                    CampaignState.ENTRY_PENDING,
                    CampaignState.ENTRY_ARMING,
                    CampaignState.SIGNAL_DETECTED,
                    CampaignState.ADD_ON_PENDING,
                    CampaignState.ADD_ON_ARMING,
                }:
                    # Pending orders are handled by reconcile_pending_entries.
                    continue

                info = self.client.exchange_info(symbol)
                rows_info = info.get("symbols", [])
                if not rows_info:
                    raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable during campaign recovery")
                asset = str(rows_info[0].get("baseAsset", "")).upper()
                account = self.client.account()
                total_base = 0.0
                for balance in account.get("balances", []):
                    if str(balance.get("asset", "")).upper() == asset:
                        total_base = (
                            float(balance.get("free", 0) or 0) +
                            float(balance.get("locked", 0) or 0)
                        )
                        break

                expected = float(campaign.position_qty or 0.0)
                tolerance = max(
                    float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
                    max(expected, 1.0) * float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
                )
                if expected <= 0:
                    campaign.mark_reconcile_required("active campaign has no position quantity")
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                if total_base + tolerance < expected:
                    campaign.mark_reconcile_required(
                        f"campaign inventory below expected: expected={expected:.12g} actual={total_base:.12g}"
                    )
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                open_orders = self.client.open_orders(symbol)
                unknown_sells = [
                    o for o in open_orders
                    if str(o.get("side", "")).upper() == "SELL"
                    and not str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
                ]
                if unknown_sells:
                    raise CampaignExecutionError(
                        f"{symbol}: unrecognized open SELL order conflicts with campaign"
                    )

                managed_stops = [
                    o for o in open_orders
                    if str(o.get("side", "")).upper() == "SELL"
                    and str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
                    and str(o.get("type", "")).upper() in {"STOP_LOSS", "STOP_LOSS_LIMIT"}
                ]
                if len(managed_stops) > 1:
                    campaign.mark_reconcile_required(
                        "multiple active campaign protective stops found"
                    )
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                if not managed_stops:
                    protection = self.create_hard_stop(
                        campaign,
                        quantity=expected,
                        stop_price=float(campaign.current_stop_price),
                    )
                    campaign.tags["protective_order_id"] = protection.get("order_id", "")
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "action": "PROTECTION_RESTORED",
                    })
                    continue

                stop = managed_stops[0]
                stop_qty = float(stop.get("origQty", 0) or 0)
                if abs(stop_qty - expected) > tolerance:
                    order_id = stop.get("orderId")
                    if order_id is None:
                        raise CampaignExecutionError(f"{symbol}: protective stop has no orderId")
                    protection = self.replace_structural_stop(
                        campaign,
                        existing_order_id=int(order_id),
                        quantity=expected,
                        proposed_stop=float(campaign.current_stop_price),
                    )
                    new_id = str(
                        (protection.get("newOrderResponse") or {}).get("orderId", "")
                    )
                    if not new_id:
                        raise CampaignExecutionError(
                            f"{symbol}: protective stop replacement returned no new order id"
                        )
                    campaign.tags["protective_order_id"] = new_id
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "action": "PROTECTION_RESIZED",
                        "old_quantity": stop_qty,
                        "new_quantity": expected,
                    })
                    continue

                campaign.tags["protective_order_id"] = str(stop.get("orderId", ""))
                campaign.health = "GREEN"
                campaign.next_action = "MONITOR_CAMPAIGN"
                campaign.reconciliation_state = "CLEAN"
                self.db.save_campaign(campaign)
                self.db.state_set(f"position_state:{symbol}", "OPEN")
                results.append({
                    "symbol": symbol,
                    "campaign_id": campaign.campaign_id,
                    "action": "CAMPAIGN_VERIFIED",
                })
            except Exception as exc:
                if row.get("campaign_id"):
                    campaign = self.engine.load_campaign(row["campaign_id"])
                    if campaign is not None:
                        self.engine.mark_reconcile_required(campaign, str(exc))
                results.append({
                    "campaign_id": row.get("campaign_id"),
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def _active_campaign_for_symbol(self, symbol: str):
        rows = self.db.conn.execute(
            "SELECT campaign_id FROM campaigns WHERE symbol=? "
            "AND state NOT IN ('CLOSED','FLAT') ORDER BY updated_at DESC LIMIT 1",
            (str(symbol).upper(),),
        ).fetchall()
        return self.engine.load_campaign(str(rows[0]["campaign_id"])) if rows else None

    def _validate_add_on_submission(self, symbol: str, qty: float, trigger: float) -> None:
        if self._current_price(symbol) >= trigger:
            raise CampaignExecutionError(
                f"{symbol}: add-on trigger crossed before submission"
            )
        self._check_buy_position_capacity(symbol, qty)
        self._check_algo_capacity(symbol, 1)

        # Final last-mile aggregate risk assertion. The campaign row already
        # contains pending_risk_quote at this point, so this check catches
        # concurrent/stale reservations before the exchange mutation.
        equity = self._current_equity_quote()
        capacity = equity * self.engine.portfolio_risk_limit_pct
        reserved = float(self.db.campaign_risk_reserved_quote())
        if reserved > capacity + 1e-9:
            raise CampaignExecutionError(
                f"{symbol}: aggregate campaign risk {reserved:.12g} exceeds "
                f"portfolio capacity {capacity:.12g}"
            )

    def arm_add_on(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
    ) -> dict[str, Any]:
        campaign = self._active_campaign_for_symbol(signal.symbol)
        if self.core_mode and not self.intraday_policy.allows_new_campaign(datetime.now(timezone.utc)):
            raise CampaignExecutionError("INTRADAY_SESSION_BLOCKED: add-on not allowed after UTC cutoff")
        if campaign is None or campaign.position_qty <= 0:
            raise CampaignExecutionError(f"{signal.symbol}: no active campaign for add-on")
        if signal.signal_bar_time_ms <= 0:
            raise CampaignExecutionError("add-on signal time is invalid")
        if signal.signal_bar_time_ms <= int(campaign.tags.get("last_signal_time_ms", 0) or 0):
            raise CampaignExecutionError("signal is not newer than current campaign signal")

        reserved = float(campaign.open_risk_quote or 0) + float(campaign.pending_risk_quote or 0)
        campaign_capacity = float(equity_quote) * self.engine.campaign_risk_limit_pct
        campaign_remaining = max(0.0, campaign_capacity - reserved)

        # P0 aggregate invariant: an add-on must fit BOTH the campaign cap and
        # the portfolio cap after every other campaign's open/pending risk.
        portfolio_capacity = float(equity_quote) * self.engine.portfolio_risk_limit_pct
        portfolio_reserved = float(self.db.campaign_risk_reserved_quote())
        portfolio_remaining = max(0.0, portfolio_capacity - portfolio_reserved)
        weighted_risk_pct = self.engine.next_add_on_risk_pct(
            campaign_reserved_risk_quote=reserved,
            equity_quote=float(equity_quote),
            tranche_index=max(1, int(campaign.tranche_index or 1)),
        )
        requested = min(
            campaign_remaining,
            portfolio_remaining,
            float(equity_quote) * min(
                float(candidate_risk_pct),
                weighted_risk_pct,
                float(os.getenv("CAMPAIGN_ADD_RISK_MAX_PCT", "0.002")),
            ),
        )
        if requested <= 0:
            raise CampaignExecutionError("campaign risk budget exhausted")

        current = self._current_price(signal.symbol)
        trigger = self._normalize_price(signal.symbol, signal.trigger_price)
        if trigger <= current:
            raise CampaignExecutionError(
                f"{signal.symbol}: add-on trigger already crossed; no market substitution"
            )

        current_stop = float(campaign.current_stop_price or 0.0)
        protective_reference = float(signal.protective_reference or 0.0)
        # The add-on must inherit the already-protective campaign stop; a new
        # signal can never move that stop backward.
        stop = self._normalize_price(
            signal.symbol,
            current_stop if current_stop > 0.0 else protective_reference,
        )
        if stop <= 0 or stop >= trigger:
            raise CampaignExecutionError(f"{signal.symbol}: invalid add-on protection reference")

        stop_fraction = (trigger - stop) / trigger
        effective_loss_fraction = stop_fraction + 0.002 + float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        notional = min(
            equity_quote * float(os.getenv("CAMPAIGN_ADD_CAPITAL_FRACTION", "0.25")),
            requested / max(effective_loss_fraction, 1e-12),
        )
        qty = self._normalized_entry_qty(signal.symbol, notional, trigger)
        cid = f"{self.ENTRY_PREFIX}ADD_{uuid.uuid4().hex[:16]}"

        prior = self.db.conn.execute(
            "SELECT state FROM campaign_signals WHERE campaign_id=? AND signal_id=?",
            (campaign.campaign_id, signal.signal_id),
        ).fetchone()
        if prior and str(prior["state"]).upper() in {"ARMED", "TRIGGERED", "FILLED"}:
            raise CampaignExecutionError("signal is already active or filled for this campaign")
        self.db.save_campaign_signal(signal, campaign.campaign_id, state=SignalState.DETECTED.value)
        pending_signal = PendingSignal.from_spec(signal)
        campaign.tags["pending_signal"] = pending_signal.to_dict()
        campaign.tags["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["pending_add_signal_id"] = signal.signal_id
        campaign.pending_risk_quote = requested
        campaign.capital_reserved_quote = qty * trigger
        self.engine.arm_add_on(
            campaign,
            signal,
            risk_quote=requested,
            capital_reserved_quote=qty * trigger,
        )
        campaign.tags["pending_signal"] = PendingSignal.from_spec(
            signal, state=SignalState.ARMED
        ).to_dict()
        self.db.save_campaign(campaign)
        # Reuse the same durable per-symbol pending key. Only one conditional
        # order for a symbol may be waiting at a time; this keeps startup
        # recovery idempotent and avoids a second persistence protocol.
        claimed = self.db.try_claim_state(
            f"entry_client_order_id:{signal.symbol}",
            cid,
        )
        if not claimed:
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            try:
                campaign.transition(
                    CampaignState.TREND_ACTIVE,
                    reason="another pending order already exists for symbol",
                )
            except ValueError:
                pass
            self.db.save_campaign(campaign)
            raise CampaignExecutionError(
                f"{signal.symbol}: another pending conditional order exists"
            )
        intent = OrderIntent.new(
            signal.symbol,
            "BUY",
            "STOP_LOSS",
            required_context_versions=dict(signal.context_versions),
            hypothesis_id=f"WILLIAMS_ADD_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_ADD_ON",
            permission_interval=signal.timeframe,
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=requested,
            capital_reserved_quote=qty * trigger,
        )
        order = self._submit(
            intent,
            lambda: self.client.order_safe(
                signal.symbol,
                "BUY",
                "STOP_LOSS",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(trigger),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._validate_add_on_submission(
                signal.symbol,
                qty,
                trigger,
            ),
        )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(order.get("orderId", "") or ""),
                client_order_id=cid,
                symbol=signal.symbol,
                side="BUY",
                order_type="STOP_LOSS",
                purpose="ADD_ON",
                status=str(order.get("status", "NEW")),
                stop_price=trigger,
                quantity=qty,
                risk_quote=requested,
                capital_reserved_quote=qty * trigger,
                signal_id=signal.signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        self.db.save_campaign(campaign)
        return {
            "campaign_id": campaign.campaign_id,
            "signal_id": signal.signal_id,
            "order_id": order.get("orderId"),
            "client_order_id": cid,
            "trigger_price": trigger,
            "quantity": qty,
            "risk_quote": requested,
        }

    def create_hard_stop(
        self,
        campaign,
        *,
        quantity: float,
        stop_price: float,
    ) -> dict[str, Any]:
        stop = self._normalize_price(campaign.symbol, stop_price)
        if stop <= 0:
            raise CampaignExecutionError("invalid protective stop")
        qty = self._normalize_qty(campaign.symbol, quantity)
        nf = self._rules(campaign.symbol).get("NOTIONAL") or self._rules(campaign.symbol).get("MIN_NOTIONAL") or {}
        min_notional = float(nf.get("minNotional", "0") or 0)
        if min_notional > 0.0 and qty * stop < min_notional:
            raise CampaignExecutionError(
                f"{campaign.symbol}: protective stop notional {qty * stop:.8f} below Binance minimum {min_notional:.8f}"
            )
        self._check_algo_capacity(campaign.symbol, 1)
        cid = f"{self.STOP_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "STOP_LOSS",
            required_context_versions={},
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_PROTECTION",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            risk_quote=campaign.open_risk_quote,
        )
        order = self._submit(
            intent,
            lambda: self.client.order_safe(
                campaign.symbol,
                "SELL",
                "STOP_LOSS",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(stop),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(campaign.symbol, 1),
        )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(order.get("orderId", "") or ""),
                client_order_id=cid,
                symbol=campaign.symbol,
                side="SELL",
                order_type="STOP_LOSS",
                purpose="PROTECTION",
                status=str(order.get("status", "NEW")),
                stop_price=stop,
                quantity=qty,
                risk_quote=campaign.open_risk_quote,
                signal_id=campaign.current_signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        return {
            "order_id": order.get("orderId"),
            "client_order_id": cid,
            "stop_price": stop,
            "quantity": qty,
        }

    def _execute_cancel(self, campaign, order_id: int, purpose: str) -> dict[str, Any]:
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "CANCEL",
            required_context_versions={},
            client_order_id=f"{self.STOP_PREFIX}CANCEL_{uuid.uuid4().hex[:14]}",
            purpose=purpose,
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        return self._submit(
            intent,
            lambda: self.client.cancel_order(
                campaign.symbol,
                order_id=order_id,
            ),
            lambda _snapshot: None,
        )

    def exit_market(
        self,
        campaign,
        *,
        reason: str,
    ) -> dict[str, Any]:
        """Cancel only the campaign protection, then market-sell actual free inventory."""
        symbol = campaign.symbol.upper()
        protective_id = str(
            campaign.tags.get("protective_order_id", "") or ""
        )
        if protective_id:
            try:
                self._execute_cancel(
                    campaign,
                    int(protective_id),
                    "CAMPAIGN_PROTECTION_CANCEL",
                )
            except Exception as exc:
                # An already-filled/cancelled stop is harmless; anything else is
                # ambiguous and must be reconciled instead of guessing.
                try:
                    current = self.client.get_order(
                        symbol,
                        order_id=int(protective_id),
                    )
                    status = str(current.get("status", "")).upper()
                    if status == "FILLED":
                        self.engine.mark_reconcile_required(
                            campaign,
                            "protective stop filled while exit was being prepared",
                        )
                        raise CampaignExecutionError(
                            f"{symbol}: protective stop filled; campaign must reconcile before exit"
                        )
                    if status not in {"CANCELED", "EXPIRED", "REJECTED"}:
                        raise
                except Exception:
                    self.engine.mark_reconcile_required(
                        campaign,
                        f"cannot cancel campaign protection before exit: {exc}",
                    )
                    raise CampaignExecutionError(str(exc)) from exc

        open_orders = self.client.open_orders(symbol)
        unknown_sells = [
            o for o in open_orders
            if str(o.get("side", "")).upper() == "SELL"
            and not str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
        ]
        if unknown_sells:
            self.engine.mark_reconcile_required(
                campaign,
                "unrecognized open SELL order prevents campaign exit",
            )
            raise CampaignExecutionError(
                f"{symbol}: unrecognized open SELL order prevents safe campaign exit"
            )

        account = self.client.account()
        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable")
        asset = str(rows[0].get("baseAsset", "")).upper()
        free_qty = next(
            (
                float(b.get("free", 0) or 0)
                for b in account.get("balances", [])
                if str(b.get("asset", "")).upper() == asset
            ),
            0.0,
        )
        expected_qty = max(0.0, float(campaign.position_qty))
        tolerance = max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            expected_qty * float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
        )
        if free_qty + tolerance < expected_qty:
            self.engine.mark_reconcile_required(
                campaign,
                f"campaign exit inventory below expected: expected={expected_qty:.12g} actual={free_qty:.12g}",
            )
            raise CampaignExecutionError(
                f"{symbol}: campaign inventory below expected before exit"
            )
        qty = self._normalize_qty(symbol, min(expected_qty, free_qty))
        if qty <= 0:
            raise CampaignExecutionError(
                f"{symbol}: no free campaign inventory after protection cancel"
            )

        cid = f"{self.EXIT_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            symbol,
            "SELL",
            "MARKET",
            required_context_versions={},
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_EXIT",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        order = self._submit(
            intent,
            lambda: self.client.order_safe(
                symbol,
                "SELL",
                "MARKET",
                quantity=self.client.decimal_format(qty),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(symbol, 0),
        )
        self.db.save_order(order)
        executed = float(order.get("executedQty", 0) or 0)
        quote = float(order.get("cummulativeQuoteQty", 0) or 0)
        if executed <= 0 or quote <= 0:
            raise CampaignExecutionError(
                f"{symbol}: campaign exit returned no authoritative fill"
            )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(order.get("orderId", "") or ""),
                client_order_id=cid,
                symbol=symbol,
                side="SELL",
                order_type="MARKET",
                purpose="EXIT",
                status=str(order.get("status", "FILLED")),
                quantity=executed,
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
            )
        )
        exit_price = quote / executed
        remaining = max(0.0, float(campaign.position_qty) - executed)
        self.db.log_campaign_event(
            campaign.campaign_id,
            "EXIT_SUBMITTED",
            order_id=str(order.get("orderId", "")),
            reason=reason,
            payload={
                "quantity": executed,
                "exit_price": exit_price,
                "requested_quantity": qty,
            },
        )
        if remaining <= max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            float(campaign.position_qty) * float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
        ):
            campaign.position_qty = 0.0
            campaign.open_risk_quote = 0.0
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            campaign.exit_reason = reason
            campaign.next_action = "WAIT"
            campaign.transition(CampaignState.EXIT_PENDING, reason=reason)
            campaign.transition(CampaignState.CLOSED, reason="exit fill complete")
            self.db.save_campaign(campaign)
            trade = self.db.open_trade(symbol)
            if trade is not None:
                entry = float(trade.get("entry_price") or campaign.average_entry_price or 0.0)
                entry_fee = float(trade.get("fees") or 0.0)
                pnl = quote - entry * float(trade.get("quantity") or campaign.position_qty) - entry_fee
                self.db.close_trade(
                    trade["id"],
                    datetime.now(timezone.utc).isoformat(),
                    exit_price,
                    pnl,
                    (exit_price / entry - 1.0) if entry > 0 else 0.0,
                    reason,
                    fees=entry_fee,
                )
            self.db.state_set(f"position_state:{symbol}", "FLAT")
            return {
                "campaign_id": campaign.campaign_id,
                "symbol": symbol,
                "state": "CLOSED",
                "quantity": executed,
                "exit_price": exit_price,
                "reason": reason,
            }

        campaign.position_qty = remaining
        self.db.save_campaign(campaign)
        campaign.state = CampaignState.RECONCILE_REQUIRED
        self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
        raise CampaignExecutionError(
            f"{symbol}: campaign exit partially filled; residual {remaining:.12g} requires reconciliation"
        )

    def replace_structural_stop(
        self,
        campaign,
        *,
        existing_order_id: int,
        quantity: float,
        proposed_stop: float,
    ) -> dict[str, Any]:
        if not campaign.current_stop_price <= proposed_stop:
            raise CampaignExecutionError("structural stop would loosen LONG risk")
        new_stop = self._normalize_price(campaign.symbol, proposed_stop)
        cid = f"{self.STOP_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "STOP_LOSS",
            required_context_versions={},
            invalidation_level=new_stop,
            quantity=self.client.decimal_format(quantity),
            client_order_id=cid,
            purpose="CAMPAIGN_TRAIL",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        result = self._submit(
            intent,
            lambda: self.client.cancel_replace(
                campaign.symbol,
                existing_order_id,
                "SELL",
                "STOP_LOSS",
                quantity=self.client.decimal_format(quantity),
                stop_price=self.client.decimal_format(new_stop),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(campaign.symbol, 0),
        )

        cancel_result = str(result.get("cancelResult", "")).upper()
        new_result = str(result.get("newOrderResult", "")).upper()
        # Any non-success or transport ambiguity must be reconciled before
        # another mutation; cancelReplace is not atomic.
        if cancel_result != "SUCCESS" or new_result != "SUCCESS":
            campaign.mark_reconcile_required(
                f"cancelReplace ambiguous: cancel={cancel_result} new={new_result}"
            )
            self.db.save_campaign(campaign)
            raise CampaignExecutionError(
                f"{campaign.symbol}: structural stop replacement ambiguous"
            )

        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(
                    (result.get("newOrderResponse") or {}).get("orderId", "")
                ),
                client_order_id=cid,
                symbol=campaign.symbol,
                side="SELL",
                order_type="STOP_LOSS",
                purpose="PROTECTION",
                status="NEW",
                stop_price=new_stop,
                quantity=quantity,
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
            )
        )
        return result
