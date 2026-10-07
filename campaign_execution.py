"""Execution adapter for Williams conditional campaign entries and stops."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
import os
import uuid

from binance_client import BinanceAPIError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingOrderRecord, SignalSpec, SignalType
from execution_barrier import ExecutionBarrier, OrderIntent


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
        self.engine = CampaignEngine(
            db,
            portfolio_risk_limit_pct=float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01")),
            campaign_risk_limit_pct=float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005")),
            initial_risk_fraction_of_campaign=float(
                os.getenv("CAMPAIGN_INITIAL_RISK_FRACTION", "0.40")
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

        campaign = self.engine.create_campaign(
            signal,
            initial_risk_pct=requested_risk,
        )
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = qty * trigger
        self.db.save_campaign(campaign)
        self.db.state_set(f"campaign_state:{campaign.campaign_id}", campaign.state.value)

        claimed = self.db.try_claim_state(
            f"entry_client_order_id:{signal.symbol}",
            client_id,
        )
        if not claimed:
            self.engine.mark_reconcile_required(
                campaign,
                "another unresolved entry intent exists for symbol",
            )
            raise CampaignExecutionError(
                f"{signal.symbol}: another conditional entry is already reserved"
            )

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
            self.engine.arm_entry(campaign, signal)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                campaign.state.value,
            )
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
            self.engine.mark_reconcile_required(campaign, str(exc))
            raise

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
        qty = self._normalized_entry_qty(campaign.symbol, quantity, max(stop, 1e-12))
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
        if cancel_result not in {"SUCCESS", "NOT_FOUND"} or new_result not in {"SUCCESS", ""}:
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
