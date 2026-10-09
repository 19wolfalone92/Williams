"""USDⓈ-M Futures campaign execution for directional Williams signals.

This module is deliberately separate from the legacy Spot trader. The domain
direction is LONG/SHORT; only this adapter maps that direction to exchange-side
BUY/SELL. All order/algo-order mutations pass through the shared ExecutionBarrier.
"""
from __future__ import annotations

import json
import math
import os
import time
import uuid
from typing import Any

from campaign_engine import CampaignEngine
from campaign_model import (
    CampaignEventType,
    CampaignState,
    PendingOrderRecord,
    SignalRole,
    SignalSpec,
    SignalState,
    SignalType,
)
from execution_barrier import ExecutionBarrier, OrderIntent
from risk_engine import RiskEngine


class FuturesCampaignExecutionError(RuntimeError):
    pass


OPEN_CAMPAIGN_STATES = {
    CampaignState.ENTRY_PENDING,
    CampaignState.ENTRY_TRIGGERED,
    CampaignState.OPEN_INITIAL,
    CampaignState.ADD_ON_PENDING,
    CampaignState.POSITION_EXPANDING,
    CampaignState.TREND_ACTIVE,
    CampaignState.TRAILING,
    CampaignState.EXHAUSTION_WATCH,
    CampaignState.EXIT_SIGNALLED,
    CampaignState.EXIT_PENDING,
    CampaignState.RECONCILE_REQUIRED,
}


def signal_direction(signal: SignalSpec) -> str:
    direction = str(getattr(signal, "direction", "") or "").upper()
    if direction not in {"LONG", "SHORT"}:
        direction = {
            "BUY": "LONG",
            "LONG": "LONG",
            "SELL": "SHORT",
            "SHORT": "SHORT",
        }.get(str(signal.side).upper(), "")
    expected_side = "BUY" if direction == "LONG" else "SELL" if direction == "SHORT" else ""
    if not expected_side:
        raise FuturesCampaignExecutionError("Signal must declare LONG or SHORT direction")
    if str(signal.side).upper() not in {expected_side, direction}:
        raise FuturesCampaignExecutionError(
            f"Signal side/direction conflict: side={signal.side} direction={direction}"
        )
    return direction


class FuturesCampaignExecutionService:
    """Risk-admitted Futures order execution with durable intent and reconciliation.

    Production defaults are Testnet-first. This service does not change account
    margin mode or leverage automatically; symbol configuration must already be
    one-way, isolated and <= 1x before new exposure is admitted.
    """

    def __init__(
        self,
        client,
        db,
        *,
        execution_barrier: ExecutionBarrier,
        max_open_positions: int = 5,
        portfolio_risk_limit_pct: float = 0.01,
        campaign_risk_limit_pct: float = 0.005,
        initial_risk_fraction_of_campaign: float = 0.40,
    ) -> None:
        if execution_barrier is None:
            raise ValueError("Futures execution requires the canonical ExecutionBarrier")
        if execution_barrier.db is not db:
            raise ValueError("Futures executor and ExecutionBarrier must share one durable DB")
        self.client = client
        self.db = db
        self.barrier = execution_barrier
        self.engine = CampaignEngine(
            db,
            portfolio_risk_limit_pct=portfolio_risk_limit_pct,
            campaign_risk_limit_pct=campaign_risk_limit_pct,
            initial_risk_fraction_of_campaign=initial_risk_fraction_of_campaign,
        )
        self.max_open_positions = max(1, int(max_open_positions))
        self.portfolio_risk_limit_pct = min(0.01, max(0.0, float(portfolio_risk_limit_pct)))
        self.campaign_risk_limit_pct = min(0.005, max(0.0, float(campaign_risk_limit_pct)))
        self.max_spread_pct = max(0.0, float(os.getenv("MAX_SPREAD_PCT", "0.0015")))
        self.max_atr_pct = max(0.0, float(os.getenv("MAX_ATR_PCT", "0.08")))
        self.max_daily_loss_pct = min(0.25, max(0.0, float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03"))))
        self.fee_buffer_per_side_pct = max(0.0, float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        self.slippage_buffer_pct = max(0.0, float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        self.require_htf_confirmation = os.getenv("REQUIRE_HTF_CONFIRMATION", "true").lower() == "true"

    @staticmethod
    def _rows(value: Any) -> list[dict[str, Any]]:
        if isinstance(value, list):
            return [row for row in value if isinstance(row, dict)]
        if isinstance(value, dict):
            if isinstance(value.get("positions"), list):
                return [row for row in value["positions"] if isinstance(row, dict)]
            if "symbol" in value:
                return [value]
        return []

    def _position_row(self, symbol: str) -> dict[str, Any]:
        symbol = str(symbol).upper()
        rows = self._rows(self.client.position_risk(symbol))
        row = next((x for x in rows if str(x.get("symbol", "")).upper() == symbol), None)
        if row is None:
            raise FuturesCampaignExecutionError(
                f"{symbol}: authoritative positionRisk response omitted the symbol"
            )
        return row

    def _position_amount(self, symbol: str) -> float:
        row = self._position_row(symbol)
        try:
            value = float(row.get("positionAmt"))
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: invalid positionAmt in Futures positionRisk"
            ) from exc
        if not math.isfinite(value):
            raise FuturesCampaignExecutionError(
                f"{symbol}: non-finite positionAmt in Futures positionRisk"
            )
        return value

    def _assert_isolated_1x(self, symbol: str) -> dict[str, Any]:
        self.client.ensure_one_way_mode()
        row = self._position_row(symbol)
        isolated_raw = row.get("isolated")
        isolated = isolated_raw is True or str(isolated_raw).lower() in {"true", "1"}
        try:
            leverage = int(row.get("leverage"))
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: Futures leverage is not available from positionRisk"
            ) from exc
        if not isolated:
            raise FuturesCampaignExecutionError(
                f"{symbol}: isolated margin is required; configure it before trading"
            )
        if leverage != 1:
            raise FuturesCampaignExecutionError(
                f"{symbol}: current leverage is {leverage}x; this build requires 1x"
            )
        return row

    def _active_rows(self) -> list[dict[str, Any]]:
        rows = self.db.open_campaigns()
        return [r for r in rows if str(r.get("state", "")).upper() not in {"CLOSED", "FLAT"}]

    @staticmethod
    def _row_tags(row: dict[str, Any]) -> dict[str, Any]:
        raw = row.get("tags_json", "{}")
        if isinstance(raw, dict):
            return raw
        try:
            parsed = json.loads(raw or "{}")
            return parsed if isinstance(parsed, dict) else {}
        except (TypeError, ValueError):
            return {}

    def _find_active_campaign(self, symbol: str):
        symbol = str(symbol).upper()
        matches = [
            row for row in self._active_rows()
            if str(row.get("symbol", "")).upper() == symbol
        ]
        if len(matches) > 1:
            raise FuturesCampaignExecutionError(
                f"{symbol}: multiple non-terminal campaigns exist; reconciliation required"
            )
        return self.engine.load_campaign(matches[0]["campaign_id"]) if matches else None

    def _assert_no_unmanaged_positions(self, symbol: str) -> None:
        rows = self._rows(self.client.position_risk())
        active = self._active_rows()
        managed_symbols = {
            str(row.get("symbol", "")).upper()
            for row in active
            if self._row_tags(row).get("execution_mode") == "FUTURES"
        }
        unmanaged = []
        for row in rows:
            sym = str(row.get("symbol", "")).upper()
            try:
                amount = float(row.get("positionAmt", 0) or 0)
            except (TypeError, ValueError):
                raise FuturesCampaignExecutionError(
                    f"{sym}: invalid positionAmt during account-wide reconciliation"
                )
            if abs(amount) > 0.0 and sym not in managed_symbols:
                unmanaged.append(sym)
        if unmanaged:
            raise FuturesCampaignExecutionError(
                "Unmanaged Futures exposure blocks new entries: " + ", ".join(sorted(set(unmanaged)))
            )

        unresolved = [
            row.get("symbol", "")
            for row in active
            if str(row.get("state", "")).upper() == CampaignState.RECONCILE_REQUIRED.value
            and self._row_tags(row).get("execution_mode") == "FUTURES"
        ]
        if unresolved:
            raise FuturesCampaignExecutionError(
                "RECONCILE_REQUIRED blocks new exposure for the Futures runtime"
            )

    def _market_mark(self, symbol: str) -> float:
        row = self.client.mark_price(symbol)
        try:
            value = float(row.get("markPrice"))
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(f"{symbol}: invalid mark price") from exc
        if not math.isfinite(value) or value <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: non-positive mark price")
        return value

    def _spread_pct(self, symbol: str) -> float:
        row = self.client.book_ticker(symbol)
        try:
            bid = float(row.get("bidPrice"))
            ask = float(row.get("askPrice"))
        except (TypeError, ValueError, AttributeError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: Futures order book did not provide bid/ask"
            ) from exc
        mid = (bid + ask) / 2.0
        if bid <= 0 or ask <= 0 or ask < bid or mid <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: invalid Futures order book")
        return (ask - bid) / mid

    def _min_notional(self, symbol: str) -> float:
        filters = self.client.symbol_filters(symbol)
        for key in ("NOTIONAL", "MIN_NOTIONAL"):
            row = filters.get(key)
            if isinstance(row, dict):
                value = row.get("minNotional", row.get("notional", 0))
                try:
                    return max(0.0, float(value or 0.0))
                except (TypeError, ValueError):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: invalid {key} exchange filter"
                    )
        return 0.0

    def _context_versions(self, signal: SignalSpec) -> dict[str, int]:
        snapshot = self.barrier.context_cache.snapshot()
        versions = {str(k).lower(): int(v) for k, v in dict(signal.context_versions or {}).items()}
        operative = str(signal.timeframe).lower()
        ctx = snapshot.context(signal.symbol, operative)
        if ctx is None:
            raise FuturesCampaignExecutionError(
                f"{signal.symbol}: no current MarketContext for {operative}"
            )
        versions.setdefault(operative, int(ctx.version))
        for interval, version in list(versions.items()):
            if snapshot.context(signal.symbol, interval) is None:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: signal depends on absent MarketContext {interval}"
                )
        return versions

    def _validate_signal(self, signal: SignalSpec) -> tuple[str, float, float]:
        direction = signal_direction(signal)
        if signal.role != SignalRole.ENTRY:
            raise FuturesCampaignExecutionError("Initial entry requires a SignalRole.ENTRY signal")
        if not math.isfinite(float(signal.trigger_price)) or float(signal.trigger_price) <= 0:
            raise FuturesCampaignExecutionError("Signal trigger price must be finite and positive")
        if signal.expires_at_ms and int(signal.expires_at_ms) < int(time.time() * 1000):
            raise FuturesCampaignExecutionError("Williams signal has expired")
        raw_stop = float(signal.invalidation_price or 0.0)
        if direction == "LONG":
            valid_stop = 0 < raw_stop < float(signal.trigger_price)
        else:
            valid_stop = raw_stop > float(signal.trigger_price)
        if not valid_stop:
            raw_stop = float(signal.protective_reference or 0.0)
        if direction == "LONG" and not 0 < raw_stop < float(signal.trigger_price):
            raise FuturesCampaignExecutionError("LONG structural invalidation must be below entry trigger")
        if direction == "SHORT" and not raw_stop > float(signal.trigger_price):
            raise FuturesCampaignExecutionError("SHORT structural invalidation must be above entry trigger")
        return direction, float(signal.trigger_price), raw_stop

    def _entry_preflight(
        self,
        signal: SignalSpec,
        direction: str,
        trigger: float,
        stop: float,
        *,
        allow_campaign_id: str = "",
    ) -> None:
        symbol = signal.symbol.upper()
        self._assert_no_unmanaged_positions(symbol)
        active = self._find_active_campaign(symbol)
        if active is not None and active.campaign_id != allow_campaign_id:
            raise FuturesCampaignExecutionError(
                f"{symbol}: an active campaign already exists; use campaign reconciliation/add-on path"
            )
        self._assert_isolated_1x(symbol)
        if self.client.open_orders(symbol) or self.client.open_algo_orders(symbol):
            raise FuturesCampaignExecutionError(
                f"{symbol}: existing normal/algo orders must be reconciled before a new campaign"
            )
        mark = self._market_mark(symbol)
        if direction == "LONG" and not (stop < mark < trigger):
            raise FuturesCampaignExecutionError(
                f"{symbol}: LONG entry missed/stale or stop breached (stop < mark < trigger required)"
            )
        if direction == "SHORT" and not (trigger < mark < stop):
            raise FuturesCampaignExecutionError(
                f"{symbol}: SHORT entry missed/stale or stop breached (trigger < mark < stop required)"
            )
        spread = self._spread_pct(symbol)
        if spread > self.max_spread_pct:
            raise FuturesCampaignExecutionError(
                f"{symbol}: spread {spread:.4%} exceeds {self.max_spread_pct:.4%}"
            )
        if self.require_htf_confirmation and not bool(signal.htf_confirmed):
            raise FuturesCampaignExecutionError(
                f"{symbol}: direction-specific higher-timeframe confirmation is required"
            )

    def _actual_risk_quote(self, quantity: float, entry: float, stop: float) -> float:
        notional = quantity * entry
        loss_fraction = abs(entry - stop) / entry
        return notional * (
            loss_fraction + (2.0 * self.fee_buffer_per_side_pct) + self.slippage_buffer_pct
        )

    def arm_initial_entry(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        atr: float,
        candidate_risk_fraction: float,
    ) -> dict[str, Any]:
        direction, raw_trigger, raw_stop = self._validate_signal(signal)
        symbol = signal.symbol.upper()
        equity = float(equity_quote)
        if not math.isfinite(equity) or equity <= 0:
            raise FuturesCampaignExecutionError("Equity must be finite and positive")
        if not math.isfinite(float(atr)) or float(atr) <= 0:
            raise FuturesCampaignExecutionError("ATR must be finite and positive")
        trigger = float(self.client.normalize_price(
            symbol, raw_trigger, direction=direction, purpose="ENTRY"
        ))
        stop = float(self.client.normalize_price(
            symbol, raw_stop, direction=direction, purpose="STOP"
        ))
        if direction == "LONG" and not stop < trigger:
            raise FuturesCampaignExecutionError("Rounded LONG stop must remain below entry")
        if direction == "SHORT" and not stop > trigger:
            raise FuturesCampaignExecutionError("Rounded SHORT stop must remain above entry")

        self._entry_preflight(signal, direction, trigger, stop)
        capacity_quote = equity * self.portfolio_risk_limit_pct
        portfolio_reserved = self.engine.portfolio_reserved_risk_quote()
        remaining_portfolio_risk = max(0.0, capacity_quote - portfolio_reserved)
        requested_fraction = min(
            max(0.0, float(candidate_risk_fraction)),
            self.engine.initial_risk_pct(),
            remaining_portfolio_risk / equity,
        )
        if requested_fraction <= 0:
            raise FuturesCampaignExecutionError("Portfolio risk capacity is exhausted")

        spread = self._spread_pct(symbol)
        risk = RiskEngine(
            equity,
            risk_per_trade_pct=requested_fraction,
            max_position_fraction=0.25,
            max_daily_loss_pct=self.max_daily_loss_pct,
            min_rr=float(os.getenv("MIN_RISK_REWARD", "1.5")),
            max_atr_pct=self.max_atr_pct,
            fee_buffer_per_side_pct=self.fee_buffer_per_side_pct,
            slippage_buffer_pct=self.slippage_buffer_pct,
        ).analyse(
            symbol=symbol,
            entry_price=trigger,
            atr=float(atr),
            signal_strength=1.0,
            htf_confirmed=bool(signal.htf_confirmed),
            spread_pct=spread,
            max_spread_pct=self.max_spread_pct,
            risk_pct_override=requested_fraction,
            side=direction,
            invalidation_price=stop,
            min_notional=self._min_notional(symbol),
        )
        if not risk.allowed:
            raise FuturesCampaignExecutionError(f"{symbol}: risk blocked entry: {risk.reason}")

        raw_qty = float(risk.position_quote) / trigger
        quantity_text = self.client.normalize_quantity(symbol, raw_qty, market=False)
        quantity = float(quantity_text)
        notional = quantity * trigger
        min_notional = self._min_notional(symbol)
        if quantity <= 0 or (min_notional > 0 and notional < min_notional):
            raise FuturesCampaignExecutionError(
                f"{symbol}: rounded quantity fails Futures lot/notional constraints"
            )
        actual_risk = self._actual_risk_quote(quantity, trigger, stop)
        if actual_risk > equity * requested_fraction * 1.000001:
            raise FuturesCampaignExecutionError(
                f"{symbol}: rounded order exceeds admitted risk budget"
            )

        client_algo_id = "W2FE_" + uuid.uuid4().hex[:24]
        claim_key = f"futures_entry_pending:{symbol}"
        if not self.db.try_claim_state(claim_key, client_algo_id):
            raise FuturesCampaignExecutionError(
                f"{symbol}: another Futures entry intent is already reserved"
            )

        campaign = None
        try:
            campaign = self.engine.create_campaign(
                signal,
                initial_risk_pct=requested_fraction,
            )
            campaign.pending_risk_quote = actual_risk
            campaign.capital_reserved_quote = notional
            campaign.tags.update({
                "execution_mode": "FUTURES",
                "direction": direction,
                "entry_client_algo_id": client_algo_id,
                "entry_trigger_price": trigger,
                "initial_stop_price": stop,
                "last_signal_time_ms": int(signal.signal_bar_time_ms),
                "position_side_mode": "ONE_WAY",
                "leverage": 1,
                "isolated_margin": True,
                "risk_budget_quote": equity * requested_fraction,
            })
            self.db.save_campaign(campaign)
            self.engine.arm_entry(campaign, signal)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.ENTRY_PENDING.value)

            versions = self._context_versions(signal)
            order_side = "BUY" if direction == "LONG" else "SELL"
            intent = OrderIntent.new(
                symbol,
                order_side,
                "STOP_MARKET",
                versions,
                hypothesis_id=f"WILLIAMS_{signal.signal_type.value}_{direction}",
                invalidation_level=stop,
                quantity=quantity_text,
                client_order_id=client_algo_id,
                purpose="CAMPAIGN_ENTRY",
                permission_interval=signal.timeframe,
                campaign_id=campaign.campaign_id,
                signal_id=signal.signal_id,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
            )

            def last_mile(snapshot) -> None:
                ctx = snapshot.context(symbol, signal.timeframe)
                if ctx is None:
                    raise FuturesCampaignExecutionError("operative MarketContext disappeared")
                allow = ctx.allow_long if direction == "LONG" else ctx.allow_short
                if not allow:
                    raise FuturesCampaignExecutionError(
                        f"operative MarketContext no longer allows {direction}"
                    )
                self._entry_preflight(
                    signal,
                    direction,
                    trigger,
                    stop,
                    allow_campaign_id=campaign.campaign_id,
                )

            result = self.barrier.execute(
                intent,
                lambda: self.client.stop_entry(
                    symbol,
                    direction,
                    quantity_text,
                    str(trigger),
                    client_algo_id,
                ),
                pre_submit_checks=last_mile,
            )
            if not result.accepted:
                reason = str(result.reason or "ExecutionBarrier blocked entry")
                if "persistence_failed" in reason:
                    self.engine.mark_reconcile_required(campaign, reason)
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    raise FuturesCampaignExecutionError(reason)
                campaign.state = CampaignState.CLOSED
                campaign.next_action = "WAIT"
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                self.db.save_campaign(campaign)
                self.db.set_campaign_signal_state(signal.signal_id, SignalState.CANCELLED.value)
                self.db.state_delete(claim_key)
                self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
                raise FuturesCampaignExecutionError(reason)

            order = result.response or {}
            order_id = str(order.get("algoId", "") or "")
            status = str(order.get("algoStatus", "") or order.get("status", "NEW")).upper()
            self.db.save_campaign_order(PendingOrderRecord(
                order_id=order_id,
                client_order_id=client_algo_id,
                symbol=symbol,
                side=order_side,
                order_type="STOP_MARKET",
                purpose="ENTRY",
                status=status,
                stop_price=trigger,
                quantity=quantity,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
                signal_id=signal.signal_id,
                campaign_id=campaign.campaign_id,
            ))
            campaign.tags["pending_algo_id"] = order_id
            self.db.save_campaign(campaign)
            self.db.log_campaign_event(
                campaign.campaign_id,
                CampaignEventType.ENTRY_ARMED.value,
                signal_id=signal.signal_id,
                order_id=order_id,
                reason=f"{direction} conditional Futures entry submitted",
                payload={
                    "direction": direction,
                    "order_side": order_side,
                    "trigger_price": trigger,
                    "structural_stop": stop,
                    "quantity": quantity,
                    "risk_quote": actual_risk,
                    "client_algo_id": client_algo_id,
                },
            )
            return {
                "campaign_id": campaign.campaign_id,
                "symbol": symbol,
                "direction": direction,
                "action": "ENTRY_ARMED",
                "algo_id": order_id,
                "client_algo_id": client_algo_id,
                "trigger_price": trigger,
                "structural_stop": stop,
                "quantity": quantity,
                "risk_quote": actual_risk,
                "status": status,
            }
        except FuturesCampaignExecutionError:
            raise
        except Exception as exc:
            if campaign is not None:
                try:
                    self.engine.mark_reconcile_required(
                        campaign,
                        f"initial Futures entry failed with unresolved state: {type(exc).__name__}: {exc}",
                    )
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                except Exception:
                    pass
            raise FuturesCampaignExecutionError(
                f"{symbol}: Futures entry could not be confirmed; reconcile before retry: {exc}"
            ) from exc

    def _campaign_direction(self, campaign) -> str:
        direction = str(campaign.tags.get("direction", "") or "").upper()
        if direction in {"LONG", "SHORT"}:
            return direction
        side = str(campaign.side).upper()
        if side in {"BUY", "LONG"}:
            return "LONG"
        if side in {"SELL", "SHORT"}:
            return "SHORT"
        raise FuturesCampaignExecutionError(
            f"{campaign.symbol}: campaign has unknown direction {campaign.side}"
        )

    def place_protection(self, campaign, *, stop_price: float | None = None) -> dict[str, Any]:
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        position = self._position_row(symbol)
        amount = float(position.get("positionAmt", 0) or 0)
        if (direction == "LONG" and amount <= 0) or (direction == "SHORT" and amount >= 0):
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective stop rejected because live position direction/quantity disagrees"
            )
        entry = float(position.get("entryPrice", 0) or 0)
        if entry <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: live entry price unavailable")
        stop = float(
            stop_price
            or campaign.current_stop_price
            or campaign.initial_stop_price
            or campaign.tags.get("initial_stop_price", 0)
            or 0
        )
        if direction == "LONG" and not 0 < stop < entry:
            raise FuturesCampaignExecutionError(f"{symbol}: LONG protective stop must be below live entry")
        if direction == "SHORT" and not stop > entry:
            raise FuturesCampaignExecutionError(f"{symbol}: SHORT protective stop must be above live entry")
        mark = self._market_mark(symbol)
        if (direction == "LONG" and stop >= mark) or (direction == "SHORT" and stop <= mark):
            raise FuturesCampaignExecutionError(
                f"{symbol}: structural stop has already been breached; use reduce-only exit"
            )
        normalized_stop = float(
            self.client.normalize_price(symbol, stop, direction=direction, purpose="STOP")
        )
        client_algo_id = "W2FP_" + uuid.uuid4().hex[:24]
        order_side = "SELL" if direction == "LONG" else "BUY"
        intent = OrderIntent.new(
            symbol,
            order_side,
            "STOP_MARKET",
            {},
            hypothesis_id=f"WILLIAMS_PROTECTION_{direction}",
            invalidation_level=normalized_stop,
            quantity="",
            client_order_id=client_algo_id,
            purpose="CAMPAIGN_PROTECTION",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            risk_quote=campaign.open_risk_quote,
            capital_reserved_quote=campaign.capital_reserved_quote,
        )

        def check_position(_snapshot) -> None:
            fresh = self._position_row(symbol)
            fresh_amount = float(fresh.get("positionAmt", 0) or 0)
            fresh_entry = float(fresh.get("entryPrice", 0) or 0)
            if (direction == "LONG" and fresh_amount <= 0) or (direction == "SHORT" and fresh_amount >= 0):
                raise FuturesCampaignExecutionError("position changed direction before protection submit")
            if direction == "LONG" and normalized_stop >= fresh_entry:
                raise FuturesCampaignExecutionError("LONG stop is not below fresh entry price")
            if direction == "SHORT" and normalized_stop <= fresh_entry:
                raise FuturesCampaignExecutionError("SHORT stop is not above fresh entry price")

        result = self.barrier.execute(
            intent,
            lambda: self.client.protective_stop(
                symbol, direction, str(normalized_stop), client_algo_id
            ),
            pre_submit_checks=check_position,
        )
        if not result.accepted:
            raise FuturesCampaignExecutionError(
                f"{symbol}: hard protection blocked: {result.reason}"
            )
        response = result.response or {}
        algo_id = str(response.get("algoId", "") or "")
        status = str(response.get("algoStatus", "") or response.get("status", "")).upper()
        if not algo_id and not response.get("clientAlgoId"):
            raise FuturesCampaignExecutionError(
                f"{symbol}: protection response has no authoritative algo identifier"
            )
        campaign.tags["protective_client_algo_id"] = client_algo_id
        campaign.tags["protective_algo_id"] = algo_id
        campaign.tags["protection_active"] = True
        campaign.current_stop_price = normalized_stop
        if campaign.initial_stop_price <= 0:
            campaign.initial_stop_price = normalized_stop
        self.db.save_campaign(campaign)
        self.db.save_campaign_order(PendingOrderRecord(
            order_id=algo_id,
            client_order_id=client_algo_id,
            symbol=symbol,
            side=order_side,
            order_type="STOP_MARKET",
            purpose="PROTECTION",
            status=status,
            stop_price=normalized_stop,
            quantity=0.0,
            risk_quote=campaign.open_risk_quote,
            signal_id=campaign.current_signal_id,
            campaign_id=campaign.campaign_id,
        ))
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.PROTECTION_ARMED.value,
            order_id=algo_id,
            reason=f"{direction} closePosition Futures stop armed",
            payload={"stop_price": normalized_stop, "client_algo_id": client_algo_id},
        )
        return {
            "symbol": symbol,
            "direction": direction,
            "algo_id": algo_id,
            "client_algo_id": client_algo_id,
            "stop_price": normalized_stop,
            "status": status,
        }

    def exit_position(self, campaign, *, reason: str) -> dict[str, Any]:
        """Reduce-only market exit; it remains available while entries are blocked."""
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        position = self._position_row(symbol)
        amount = float(position.get("positionAmt", 0) or 0)
        if abs(amount) <= 0:
            self.engine.mark_reconcile_required(
                campaign,
                f"Cannot confirm exit: exchange position is flat but campaign was active ({reason})",
            )
            raise FuturesCampaignExecutionError(
                f"{symbol}: exchange is flat but campaign state requires reconciliation"
            )
        if (direction == "LONG" and amount < 0) or (direction == "SHORT" and amount > 0):
            self.engine.mark_reconcile_required(
                campaign,
                f"Position direction mismatch during exit ({reason})",
            )
            raise FuturesCampaignExecutionError(f"{symbol}: direction mismatch; exit blocked for reconciliation")
        order_side = "SELL" if direction == "LONG" else "BUY"

        # A cancel timeout must not prevent the reduce-only exit. A lingering
        # closePosition order cannot reverse exposure, but is reconciled after exit.
        protective_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
        protective_algo_id = campaign.tags.get("protective_algo_id")
        if protective_client_id or protective_algo_id:
            cancel_intent = OrderIntent.new(
                symbol,
                order_side,
                "CANCEL",
                {},
                purpose="CAMPAIGN_EXIT_CANCEL_PROTECTION",
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
                client_order_id=str(protective_client_id or protective_algo_id),
            )
            try:
                self.barrier.execute(
                    cancel_intent,
                    lambda: self.client.cancel_algo_order_safe(
                        symbol,
                        algo_id=protective_algo_id or None,
                        client_algo_id=protective_client_id or None,
                    ),
                )
            except Exception as exc:
                self.db.log_event(
                    "ERROR",
                    "futures_protection_cancel_ambiguous",
                    f"{symbol}: {exc}",
                    {"campaign_id": campaign.campaign_id, "reason": reason},
                )

        quantity = self.client.normalize_quantity(symbol, abs(amount), market=True)
        client_order_id = "W2FX_" + uuid.uuid4().hex[:24]
        intent = OrderIntent.new(
            symbol,
            order_side,
            "MARKET",
            {},
            hypothesis_id=f"WILLIAMS_EXIT_{direction}",
            quantity=quantity,
            client_order_id=client_order_id,
            purpose="CAMPAIGN_EXIT",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            risk_quote=campaign.open_risk_quote,
        )
        result = self.barrier.execute(
            intent,
            lambda: self.client.market_exit(
                symbol, direction, quantity, client_order_id
            ),
        )
        if not result.accepted:
            self.engine.mark_reconcile_required(campaign, f"Futures reduce-only exit blocked: {result.reason}")
            raise FuturesCampaignExecutionError(f"{symbol}: exit blocked: {result.reason}")

        response = result.response or {}
        order_id = str(response.get("orderId", "") or "")
        status = str(response.get("status", "")).upper()
        fresh_amount = self._position_amount(symbol)
        if abs(fresh_amount) > 1e-12:
            self.engine.mark_reconcile_required(
                campaign,
                f"Futures exit not fully reconciled; residual positionAmt={fresh_amount}",
            )
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "RECONCILE_REQUIRED",
                "order_id": order_id,
                "status": status,
                "residual_position_amt": fresh_amount,
            }

        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protection_active"] = False
        campaign.tags["last_exit_reason"] = str(reason)
        campaign.exit_reason = str(reason)
        if campaign.state not in {CampaignState.EXIT_PENDING, CampaignState.RECONCILE_REQUIRED}:
            if campaign.state != CampaignState.EXIT_SIGNALLED:
                campaign.transition(CampaignState.EXIT_SIGNALLED, reason=reason)
            campaign.transition(CampaignState.EXIT_PENDING, reason="reduce-only Futures exit submitted")
        elif campaign.state == CampaignState.RECONCILE_REQUIRED:
            campaign.transition(CampaignState.EXIT_PENDING, reason="reconciled reduce-only exit")
        campaign.transition(CampaignState.CLOSED, reason="authoritative Futures position is flat")
        campaign.next_action = "WAIT"
        self.db.save_campaign(campaign)
        self.db.state_delete(f"futures_entry_pending:{symbol}")
        self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
        self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.EXIT_FILLED.value,
            order_id=order_id,
            reason=reason,
            payload={"direction": direction, "status": status, "position_amt_after_exit": fresh_amount},
        )
        return {
            "symbol": symbol,
            "direction": direction,
            "action": "CLOSED",
            "order_id": order_id,
            "status": status,
            "reason": reason,
        }

    def reconcile_symbol(self, symbol: str) -> dict[str, Any]:
        """Reconcile local campaign against authoritative Futures position/order state."""
        symbol = str(symbol).upper()
        position = self._position_row(symbol)
        amount = float(position.get("positionAmt", 0) or 0)
        campaign = self._find_active_campaign(symbol)
        if campaign is None:
            if abs(amount) > 0:
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                return {
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": "exchange Futures position has no matching managed campaign",
                }
            self.db.state_set(f"position_state:{symbol}", "FLAT")
            return {"symbol": symbol, "state": "FLAT"}

        direction = self._campaign_direction(campaign)
        entry_client_algo_id = str(campaign.tags.get("entry_client_algo_id", "") or "")
        if abs(amount) <= 1e-12:
            if campaign.state == CampaignState.ENTRY_PENDING and entry_client_algo_id:
                try:
                    algo = self.client.get_algo_order(symbol, client_algo_id=entry_client_algo_id)
                except Exception as exc:
                    self.engine.mark_reconcile_required(campaign, f"pending entry lookup failed: {exc}")
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": str(exc)}
                status = str(algo.get("algoStatus", "")).upper()
                if status in {"NEW", "WORKING", "PENDING"}:
                    return {"symbol": symbol, "state": "ENTRY_PENDING", "algo_status": status}
                if status in {"CANCELED", "EXPIRED", "REJECTED"}:
                    campaign.state = CampaignState.CLOSED
                    campaign.next_action = "WAIT"
                    campaign.pending_risk_quote = 0.0
                    campaign.capital_reserved_quote = 0.0
                    self.db.save_campaign(campaign)
                    self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
                    self.db.state_delete(f"futures_entry_pending:{symbol}")
                    self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
                    return {"symbol": symbol, "state": "CLOSED", "algo_status": status}
            self.engine.mark_reconcile_required(
                campaign,
                "Local active Futures campaign has no live exchange position; closure/fill history must be verified",
            )
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "unexplained flat position"}

        if (direction == "LONG" and amount < 0) or (direction == "SHORT" and amount > 0):
            self.engine.mark_reconcile_required(
                campaign,
                f"Exchange position direction disagrees with campaign direction {direction}",
            )
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "direction mismatch"}

        if campaign.state == CampaignState.ENTRY_PENDING:
            self.engine.mark_triggered(campaign, campaign.current_signal_id, str(campaign.tags.get("pending_algo_id", "")))
            campaign.tags["position_confirmed_by_exchange"] = True
            try:
                self.place_protection(campaign, stop_price=float(campaign.tags.get("initial_stop_price", 0) or 0))
            except Exception as exc:
                self.engine.mark_reconcile_required(campaign, f"entry filled but hard protection failed: {exc}")
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": f"protection failed: {exc}"}
            entry = float(position.get("entryPrice", 0) or 0)
            if entry <= 0:
                self.engine.mark_reconcile_required(campaign, "positionRisk omitted entryPrice after entry trigger")
                return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "missing entryPrice"}
            self.engine.record_initial_fill(
                campaign,
                quantity=abs(amount),
                average_entry_price=entry,
                initial_stop_price=float(campaign.current_stop_price),
                fill_order_id=str(campaign.tags.get("pending_algo_id", "")),
                risk_quote=float(campaign.pending_risk_quote),
                fee_quote=0.0,
            )
            self.db.state_delete(f"futures_entry_pending:{symbol}")
            self.db.state_set(f"position_state:{symbol}", "OPEN")
            self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
            return {
                "symbol": symbol,
                "state": "OPEN",
                "direction": direction,
                "position_qty": abs(amount),
                "average_entry_price": entry,
                "protective_client_algo_id": campaign.tags.get("protective_client_algo_id", ""),
            }

        if campaign.position_qty > 0:
            expected_direction_sign = 1 if direction == "LONG" else -1
            if amount * expected_direction_sign <= 0:
                self.engine.mark_reconcile_required(campaign, "position sign changed unexpectedly")
                return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "position sign changed"}
            if str(campaign.tags.get("protective_client_algo_id", "") or ""):
                try:
                    protection = self.client.get_algo_order(
                        symbol,
                        algo_id=campaign.tags.get("protective_algo_id") or None,
                        client_algo_id=campaign.tags.get("protective_client_algo_id") or None,
                    )
                    status = str(protection.get("algoStatus", "")).upper()
                    if status not in {"NEW", "WORKING", "PENDING"}:
                        raise FuturesCampaignExecutionError(
                            f"protective algo order status is {status or 'UNKNOWN'}"
                        )
                except Exception as exc:
                    try:
                        self.place_protection(campaign)
                    except Exception as protect_exc:
                        self.engine.mark_reconcile_required(
                            campaign,
                            f"live position lacks confirmed protection: lookup={exc}; new_stop={protect_exc}",
                        )
                        self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                        self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                        return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "protective stop not confirmed"}
            else:
                try:
                    self.place_protection(campaign)
                except Exception as exc:
                    self.engine.mark_reconcile_required(campaign, f"live position has no protective stop: {exc}")
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                    return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "missing protection"}
            self.db.state_set(f"position_state:{symbol}", campaign.state.value)
            return {
                "symbol": symbol,
                "state": campaign.state.value,
                "direction": direction,
                "position_qty": abs(amount),
                "average_entry_price": float(position.get("entryPrice", 0) or 0),
                "protection": "CONFIRMED",
            }

        self.engine.mark_reconcile_required(
            campaign,
            "Exchange position exists but campaign has no recorded entry/add-on fill state",
        )
        self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
        return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "state mismatch"}
