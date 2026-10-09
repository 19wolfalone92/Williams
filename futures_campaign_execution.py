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
            if not math.isfinite(amount):
                raise FuturesCampaignExecutionError(
                    f"{sym}: non-finite positionAmt during account-wide reconciliation"
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
        available_quote: float | None = None,
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
        if available_quote is not None:
            available = float(available_quote)
            if not math.isfinite(available) or available < 0:
                raise FuturesCampaignExecutionError("Available Futures balance must be finite and non-negative")
            required_margin_buffer = notional * (1.0 + 2.0 * self.fee_buffer_per_side_pct)
            if required_margin_buffer > available:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: insufficient available Futures balance for 1x isolated margin "
                    f"(required with fee buffer={required_margin_buffer:.8f}, available={available:.8f})"
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
            campaign.initial_stop_price = stop
            campaign.current_stop_price = stop
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
            if status not in {"NEW", "WORKING", "PENDING_NEW"}:
                reason = f"{symbol}: entry algo status is not active: {status or 'MISSING'}"
                campaign.tags["entry_response_not_active"] = {
                    "client_algo_id": client_algo_id,
                    "algo_id": order_id,
                    "status": status,
                }
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(f"{reason}; reconciliation required before retry")
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
        try:
            amount = float(position.get("positionAmt", 0) or 0)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: invalid live position quantity; protection cannot be verified"
            ) from exc
        if not math.isfinite(amount):
            raise FuturesCampaignExecutionError(
                f"{symbol}: non-finite live position quantity; protection cannot be verified"
            )
        if (direction == "LONG" and amount <= 0) or (direction == "SHORT" and amount >= 0):
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective stop rejected because live position direction/quantity disagrees"
            )
        try:
            entry = float(position.get("entryPrice", 0) or 0)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: invalid live entry price; protection cannot be verified"
            ) from exc
        if not math.isfinite(entry) or entry <= 0:
            raise FuturesCampaignExecutionError(
                f"{symbol}: live entry price must be finite and positive"
            )
        stop = float(
            stop_price
            or campaign.current_stop_price
            or campaign.initial_stop_price
            or campaign.tags.get("initial_stop_price", 0)
            or 0
        )
        if not math.isfinite(stop) or stop <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: protective stop must be finite and positive")
        previous_stop = float(campaign.current_stop_price or campaign.initial_stop_price or 0.0)
        # A structural trailing stop is allowed to pass break-even. It must
        # tighten protection, never loosen it, and must remain on the safe side
        # of the current mark price.
        if previous_stop > 0:
            if direction == "LONG" and stop < previous_stop:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: LONG protective stop would loosen from {previous_stop} to {stop}"
                )
            if direction == "SHORT" and stop > previous_stop:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: SHORT protective stop would loosen from {previous_stop} to {stop}"
                )
        mark = self._market_mark(symbol)
        if (direction == "LONG" and stop >= mark) or (direction == "SHORT" and stop <= mark):
            raise FuturesCampaignExecutionError(
                f"{symbol}: structural stop has already been breached; use reduce-only exit"
            )
        normalized_stop = float(
            self.client.normalize_price(symbol, stop, direction=direction, purpose="STOP")
        )
        order_side = "SELL" if direction == "LONG" else "BUY"

        # A persisted pending clientAlgoId means a prior request may have reached
        # Binance even if this process never received the response. Never mint a
        # second ID until that exact order has been authoritatively reconciled.
        pending_client_id = str(
            campaign.tags.get("pending_protective_client_algo_id", "") or ""
        )
        if pending_client_id:
            try:
                pending_order = self.client.get_algo_order(
                    symbol, client_algo_id=pending_client_id
                )
            except Exception as exc:
                reason = (
                    f"{symbol}: prior protective submission {pending_client_id} "
                    f"cannot be reconciled; refusing duplicate submission: {exc}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.save_campaign(campaign)
                self.db.state_set(
                    f"campaign_state:{campaign.campaign_id}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                raise FuturesCampaignExecutionError(reason) from exc

            pending_status = str(pending_order.get("algoStatus", "") or "").upper()
            pending_side = str(pending_order.get("side", "") or "").upper()
            pending_trigger = pending_order.get("triggerPrice")
            active_statuses = {"NEW", "WORKING", "PENDING_NEW"}
            safe_terminal_statuses = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}
            try:
                trigger_matches = (
                    pending_trigger is not None
                    and math.isclose(
                        float(pending_trigger), normalized_stop,
                        rel_tol=0.0, abs_tol=1e-8,
                    )
                )
            except (TypeError, ValueError):
                trigger_matches = False

            if pending_status in active_statuses:
                if (
                    str(pending_order.get("clientAlgoId", "")) != pending_client_id
                    or pending_side != order_side
                    or not trigger_matches
                ):
                    reason = (
                        f"{symbol}: pending protective order identity/side/trigger "
                        "does not match the persisted intent; reconciliation required"
                    )
                    self.engine.mark_reconcile_required(campaign, reason)
                    self.db.save_campaign(campaign)
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    self.db.state_set(
                        f"position_state:{symbol}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    raise FuturesCampaignExecutionError(reason)
                algo_id = str(pending_order.get("algoId", "") or "")
                if not algo_id:
                    reason = f"{symbol}: active pending protective order has no algoId"
                    self.engine.mark_reconcile_required(campaign, reason)
                    self.db.save_campaign(campaign)
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    self.db.state_set(
                        f"position_state:{symbol}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    raise FuturesCampaignExecutionError(reason)
                campaign.tags["protective_client_algo_id"] = pending_client_id
                campaign.tags["protective_algo_id"] = algo_id
                campaign.tags["protection_active"] = True
                campaign.tags.pop("pending_protective_client_algo_id", None)
                campaign.tags.pop("pending_protective_stop_price", None)
                campaign.current_stop_price = normalized_stop
                if campaign.initial_stop_price <= 0:
                    campaign.initial_stop_price = normalized_stop
                self.db.save_campaign(campaign)
                return {
                    "symbol": symbol,
                    "direction": direction,
                    "algo_id": algo_id,
                    "client_algo_id": pending_client_id,
                    "stop_price": normalized_stop,
                    "status": pending_status,
                    "recovered_existing_order": True,
                }

            if pending_status not in safe_terminal_statuses:
                reason = (
                    f"{symbol}: prior protective order {pending_client_id} has "
                    f"ambiguous/non-retryable status {pending_status or 'UNKNOWN'}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.save_campaign(campaign)
                self.db.state_set(
                    f"campaign_state:{campaign.campaign_id}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                raise FuturesCampaignExecutionError(reason)

            # Binance has authoritatively confirmed that the prior order is
            # terminal and cannot protect the position. A new intent may now be
            # created, but the terminal order remains in the event/order history.
            campaign.tags.pop("pending_protective_client_algo_id", None)
            campaign.tags.pop("pending_protective_stop_price", None)
            self.db.save_campaign(campaign)

        client_algo_id = "W2FP_" + uuid.uuid4().hex[:24]
        # Persist the clientAlgoId before the request. After a timeout, recovery
        # can query the exact conditional order instead of risking a duplicate.
        campaign.tags["pending_protective_client_algo_id"] = client_algo_id
        campaign.tags["pending_protective_stop_price"] = normalized_stop
        self.db.save_campaign(campaign)
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
            try:
                fresh_amount = float(fresh.get("positionAmt", 0) or 0)
                fresh_entry = float(fresh.get("entryPrice", 0) or 0)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    "live position quantity/entry became invalid before protection submit"
                ) from exc
            if (
                not math.isfinite(fresh_amount)
                or not math.isfinite(fresh_entry)
                or fresh_entry <= 0
            ):
                raise FuturesCampaignExecutionError(
                    "live position quantity/entry is non-finite or invalid before protection submit"
                )
            if (direction == "LONG" and fresh_amount <= 0) or (direction == "SHORT" and fresh_amount >= 0):
                raise FuturesCampaignExecutionError("position changed direction before protection submit")
            fresh_mark = self._market_mark(symbol)
            if direction == "LONG" and normalized_stop >= fresh_mark:
                raise FuturesCampaignExecutionError("LONG stop is not below the fresh mark price")
            if direction == "SHORT" and normalized_stop <= fresh_mark:
                raise FuturesCampaignExecutionError("SHORT stop is not above the fresh mark price")
            if direction == "LONG" and previous_stop > 0 and normalized_stop < previous_stop:
                raise FuturesCampaignExecutionError("LONG stop update would loosen protection")
            if direction == "SHORT" and previous_stop > 0 and normalized_stop > previous_stop:
                raise FuturesCampaignExecutionError("SHORT stop update would loosen protection")

        try:
            result = self.barrier.execute(
                intent,
                lambda: self.client.protective_stop(
                    symbol, direction, str(normalized_stop), client_algo_id
                ),
                pre_submit_checks=check_position,
            )
        except Exception as exc:
            campaign.mark_reconcile_required(
                f"{symbol}: protection submission outcome requires reconciliation: {exc}"
            )
            self.db.save_campaign(campaign)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.log_event(
                "ERROR",
                "futures_protection_submit_ambiguous",
                f"{symbol}: {exc}",
                {
                    "campaign_id": campaign.campaign_id,
                    "client_algo_id": client_algo_id,
                    "stop_price": normalized_stop,
                },
            )
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective order outcome is unresolved; clientAlgoId={client_algo_id}"
            ) from exc

        if not result.accepted:
            # ExecutionBarrier proves that submit was never entered for a
            # validation/persistence block, so this reservation can be cleared.
            campaign.tags.pop("pending_protective_client_algo_id", None)
            campaign.tags.pop("pending_protective_stop_price", None)
            self.db.save_campaign(campaign)
            raise FuturesCampaignExecutionError(
                f"{symbol}: hard protection blocked: {result.reason}"
            )
        response = result.response or {}
        algo_id = str(response.get("algoId", "") or "")
        status = str(response.get("algoStatus", "") or response.get("status", "")).upper()
        if not algo_id and not response.get("clientAlgoId"):
            # The exchange may have accepted the protection order even when its
            # response is malformed. Never return to ordinary management with
            # an assumed-safe position: persist an explicit reconciliation lock.
            reason = (
                f"{symbol}: protection submission may have succeeded, but the "
                "response contains no authoritative algo identifier"
            )
            campaign.mark_reconcile_required(reason)
            campaign.tags["protection_response_unidentified"] = {
                "client_algo_id": client_algo_id,
                "stop_price": normalized_stop,
            }
            self.db.save_campaign(campaign)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(
                f"position_state:{symbol}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.log_event(
                "ERROR",
                "futures_protection_response_unidentified",
                reason,
                {
                    "campaign_id": campaign.campaign_id,
                    "client_algo_id": client_algo_id,
                    "stop_price": normalized_stop,
                },
            )
            raise FuturesCampaignExecutionError(
                f"{reason}; reconciliation required before further exposure"
            )
        if status not in {"NEW", "WORKING", "PENDING_NEW"}:
            # A syntactically valid exchange response is not proof of active
            # protection. Terminal/rejected states must never be persisted as
            # an armed stop; preserve the client ID for authoritative recovery.
            reason = (
                f"{symbol}: protective algo order is not confirmed active "
                f"(status={status or 'MISSING'}, algo_id={algo_id or 'MISSING'})"
            )
            campaign.tags["protection_response_unidentified"] = {
                "client_algo_id": client_algo_id,
                "algo_id": algo_id,
                "status": status,
                "stop_price": normalized_stop,
            }
            campaign.mark_reconcile_required(reason)
            self.db.save_campaign(campaign)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(
                f"position_state:{symbol}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.log_event(
                "ERROR",
                "futures_protection_not_active",
                reason,
                {
                    "campaign_id": campaign.campaign_id,
                    "client_algo_id": client_algo_id,
                    "algo_id": algo_id,
                    "status": status,
                },
            )
            raise FuturesCampaignExecutionError(
                f"{reason}; reconciliation required before further exposure"
            )

        campaign.tags["protective_client_algo_id"] = client_algo_id
        campaign.tags["protective_algo_id"] = algo_id
        campaign.tags.pop("pending_protective_client_algo_id", None)
        campaign.tags.pop("pending_protective_stop_price", None)
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

    def replace_protection(self, campaign, *, stop_price: float) -> dict[str, Any]:
        """Tighten a structural stop without leaving the position naked.

        New protection is confirmed first; the prior bot-owned algo order is
        cancelled second. If cancellation is ambiguous both IDs are retained in
        the event log and the campaign is marked for reconciliation.
        """
        symbol = campaign.symbol.upper()
        old_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
        old_algo_id = campaign.tags.get("protective_algo_id")
        old_stop = float(campaign.current_stop_price or campaign.initial_stop_price or 0.0)
        direction = self._campaign_direction(campaign)
        try:
            requested = float(stop_price)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(f"{symbol}: replacement stop price is invalid") from exc
        if not math.isfinite(requested) or requested <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: replacement stop price must be finite and positive")
        if not math.isfinite(old_stop) or old_stop < 0:
            raise FuturesCampaignExecutionError(f"{symbol}: stored protective stop is invalid; reconciliation required")
        if direction == "LONG" and old_stop > 0 and requested < old_stop:
            raise FuturesCampaignExecutionError("LONG trailing stop may only move upward")
        if direction == "SHORT" and old_stop > 0 and requested > old_stop:
            raise FuturesCampaignExecutionError("SHORT trailing stop may only move downward")

        if old_client_id or old_algo_id:
            # Keep the prior stop identifiers durable while the replacement is
            # created. A restart must know both orders if the second mutation
            # (cancel old stop) becomes ambiguous.
            campaign.tags["previous_protective_client_algo_id"] = old_client_id
            campaign.tags["previous_protective_algo_id"] = old_algo_id
            campaign.tags["previous_protective_stop_price"] = old_stop
            campaign.tags["protection_replace_reconcile_required"] = True
            self.db.save_campaign(campaign)

        new_protection = self.place_protection(campaign, stop_price=requested)
        if not old_client_id and not old_algo_id:
            return new_protection

        cancel_intent = OrderIntent.new(
            symbol,
            "SELL" if direction == "LONG" else "BUY",
            "CANCEL",
            {},
            purpose="CAMPAIGN_PROTECTION_REPLACE_CANCEL_OLD",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            client_order_id=str(old_client_id or old_algo_id),
        )
        try:
            result = self.barrier.execute(
                cancel_intent,
                lambda: self.client.cancel_algo_order_safe(
                    symbol,
                    algo_id=old_algo_id or None,
                    client_algo_id=old_client_id or None,
                ),
            )
            if not result.accepted:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: old protective order cancellation blocked: {result.reason}"
                )
            cancel_response = result.response if isinstance(result.response, dict) else {}
            cancel_status = str(
                cancel_response.get("algoStatus", "") or cancel_response.get("status", "")
            ).upper()
            if cancel_status not in {"CANCELED", "EXPIRED"}:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: old protective order cancellation is not confirmed terminal: {cancel_status or 'UNKNOWN'}"
                )
        except Exception as exc:
            reason = (
                f"{symbol}: new stop {new_protection.get('stop_price')} is active, "
                f"but old stop cancellation is uncertain (old_algo_id={old_algo_id}, "
                f"old_client_algo_id={old_client_id}): {exc}"
            )
            campaign.tags["protection_replace_reconcile_required"] = True
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.save_campaign(campaign)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(
                f"position_state:{symbol}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.log_event(
                "ERROR",
                "futures_stop_replace_ambiguous",
                reason,
                {"campaign_id": campaign.campaign_id},
            )
            raise FuturesCampaignExecutionError(reason) from exc

        campaign.tags.pop("previous_protective_client_algo_id", None)
        campaign.tags.pop("previous_protective_algo_id", None)
        campaign.tags.pop("previous_protective_stop_price", None)
        campaign.tags.pop("protection_replace_reconcile_required", None)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.PROTECTION_ARMED.value,
            order_id=str(new_protection.get("algo_id", "") or ""),
            reason=f"{direction} structural trailing stop tightened",
            payload={
                "old_stop": old_stop,
                "new_stop": new_protection.get("stop_price"),
                "old_algo_id": old_algo_id,
                "new_algo_id": new_protection.get("algo_id"),
            },
        )
        return {**new_protection, "previous_stop_price": old_stop}

    def cancel_pending_entry(self, campaign, *, reason: str) -> dict[str, Any]:
        """Cancel a bot-owned conditional ENTRY and verify it is terminal.

        Pausing or killing the runtime must not leave an armed entry capable of
        opening new exposure later. A missing/ambiguous exchange confirmation
        leaves the campaign in RECONCILE_REQUIRED; it is never treated as a
        successful cancellation.
        """
        symbol = campaign.symbol.upper()
        if campaign.state != CampaignState.ENTRY_PENDING:
            return {
                "symbol": symbol,
                "state": campaign.state.value,
                "action": "NO_PENDING_ENTRY",
            }

        client_algo_id = str(campaign.tags.get("entry_client_algo_id", "") or "")
        algo_id = campaign.tags.get("pending_algo_id")
        if not client_algo_id and not algo_id:
            self.engine.mark_reconcile_required(
                campaign,
                f"{reason}: ENTRY_PENDING campaign has no stable exchange algo identifier",
            )
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            return {
                "symbol": symbol,
                "state": "RECONCILE_REQUIRED",
                "action": "CANCEL_UNVERIFIED",
                "reason": "missing bot-owned entry algo identifier",
            }

        intent = OrderIntent.new(
            symbol,
            "BUY" if self._campaign_direction(campaign) == "LONG" else "SELL",
            "CANCEL",
            {},
            purpose="CANCEL_PENDING_FUTURES_ENTRY",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            client_order_id=str(client_algo_id or algo_id),
        )
        try:
            result = self.barrier.execute(
                intent,
                lambda: self.client.cancel_algo_order_safe(
                    symbol,
                    algo_id=algo_id or None,
                    client_algo_id=client_algo_id or None,
                ),
            )
            # Whether cancel returned accepted or reported a possibly-terminal
            # order, query the exchange's authoritative algo state before
            # releasing campaign risk or declaring the entry cancelled.
            verified = self.client.get_algo_order(
                symbol,
                algo_id=algo_id or None,
                client_algo_id=client_algo_id or None,
            )
            status = str(verified.get("algoStatus", "")).upper()
            position = self._position_amount(symbol)
            if status in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"} and abs(position) <= 1e-12:
                campaign.state = CampaignState.CLOSED
                campaign.next_action = "WAIT"
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                campaign.tags["pending_entry_cancel_reason"] = str(reason)
                campaign.tags["pending_entry_cancel_status"] = status
                self.db.save_campaign(campaign)
                self.db.set_campaign_signal_state(
                    campaign.current_signal_id,
                    SignalState.CANCELLED.value,
                )
                self.db.state_delete(f"futures_entry_pending:{symbol}")
                self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
                self.db.log_campaign_event(
                    campaign.campaign_id,
                    CampaignEventType.CAMPAIGN_CLOSED.value,
                    order_id=str(algo_id or client_algo_id),
                    reason=f"Pending entry verified {status.lower()} during {reason}",
                    payload={
                        "client_algo_id": client_algo_id,
                        "algo_status": status,
                        "exchange_position_amount": position,
                    },
                )
                return {
                    "symbol": symbol,
                    "state": "CLOSED",
                    "action": "ENTRY_CANCELLED",
                    "algo_status": status,
                }

            detail = (
                f"{reason}: entry cancel not conclusively verified "
                f"(barrier_accepted={result.accepted}, algo_status={status}, "
                f"position_amount={position})"
            )
            self.engine.mark_reconcile_required(campaign, detail)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            return {
                "symbol": symbol,
                "state": "RECONCILE_REQUIRED",
                "action": "CANCEL_UNVERIFIED",
                "reason": detail,
            }
        except Exception as exc:
            detail = f"{reason}: pending entry cancellation/reconciliation failed: {type(exc).__name__}: {exc}"
            self.engine.mark_reconcile_required(campaign, detail)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.log_event(
                "ERROR",
                "futures_pending_entry_cancel_failed",
                detail,
                {"campaign_id": campaign.campaign_id, "symbol": symbol},
            )
            return {
                "symbol": symbol,
                "state": "RECONCILE_REQUIRED",
                "action": "CANCEL_UNVERIFIED",
                "reason": detail,
            }

    def exit_position(self, campaign, *, reason: str) -> dict[str, Any]:
        """Reduce-only market exit; it remains available while entries are blocked."""
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        position = self._position_row(symbol)
        try:
            amount = float(position.get("positionAmt", 0) or 0)
        except (TypeError, ValueError):
            amount = float("nan")
        if not math.isfinite(amount):
            reason = "invalid or non-finite exchange quantity; reduce-only exit requires reconciliation"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            raise FuturesCampaignExecutionError(f"{symbol}: {reason}")
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

        # Resolve a durable prior exit intent before creating another one.
        # A timeout after Binance accepted a MARKET order must never cause a
        # restart to generate a fresh clientOrderId and duplicate the exit.
        pending_exit_id = str(campaign.tags.get("pending_exit_client_order_id", "") or "")
        if pending_exit_id:
            try:
                prior_exit = self.client.get_order(
                    symbol, orig_client_order_id=pending_exit_id
                )
                prior_status = str(prior_exit.get("status", "") or "").upper()
                if prior_status in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                    reason_text = (
                        f"{symbol}: prior reduce-only exit {pending_exit_id} is "
                        f"still {prior_status}; waiting for authoritative completion"
                    )
                    self.engine.mark_reconcile_required(campaign, reason_text)
                    self.db.save_campaign(campaign)
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    self.db.state_set(
                        f"position_state:{symbol}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    return {
                        "symbol": symbol,
                        "direction": direction,
                        "action": "RECONCILE_REQUIRED",
                        "client_order_id": pending_exit_id,
                        "status": prior_status,
                        "reason": reason_text,
                    }
                if prior_status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                    raise FuturesCampaignExecutionError(
                        f"prior exit status is ambiguous: {prior_status or 'UNKNOWN'}"
                    )
                # A terminal order is safe to reconcile against the live
                # position. FILLED with residual exposure may require a new
                # reduce-only order, but only after the previous order is proven
                # terminal by this authoritative lookup.
                if prior_status == "FILLED":
                    position_after_prior = self._position_row(symbol)
                    try:
                        remaining_after_prior = float(position_after_prior.get("positionAmt", 0) or 0)
                    except (TypeError, ValueError):
                        remaining_after_prior = float("nan")
                    if not math.isfinite(remaining_after_prior):
                        raise FuturesCampaignExecutionError(
                            "prior exit is FILLED but the refreshed position quantity is invalid"
                        )
                    if abs(remaining_after_prior) <= 1e-12:
                        reason_text = (
                            f"{symbol}: prior exit {pending_exit_id} is FILLED and the "
                            "exchange position is flat; trade history/PnL must be reconciled "
                            "before clearing the durable exit intent"
                        )
                        self.engine.mark_reconcile_required(campaign, reason_text)
                        self.db.save_campaign(campaign)
                        self.db.state_set(
                            f"campaign_state:{campaign.campaign_id}",
                            CampaignState.RECONCILE_REQUIRED.value,
                        )
                        self.db.state_set(
                            f"position_state:{symbol}",
                            CampaignState.RECONCILE_REQUIRED.value,
                        )
                        return {
                            "symbol": symbol,
                            "direction": direction,
                            "action": "RECONCILE_REQUIRED",
                            "client_order_id": pending_exit_id,
                            "status": prior_status,
                            "reason": reason_text,
                        }
                    if (direction == "LONG" and remaining_after_prior < 0) or (
                        direction == "SHORT" and remaining_after_prior > 0
                    ):
                        raise FuturesCampaignExecutionError(
                            "prior exit is FILLED but exchange position direction changed"
                        )
                campaign.tags["last_terminal_exit_client_order_id"] = pending_exit_id
                campaign.tags["last_terminal_exit_status"] = prior_status
                campaign.tags.pop("pending_exit_client_order_id", None)
                campaign.tags.pop("pending_exit_reason", None)
                self.db.save_campaign(campaign)
            except Exception as exc:
                reason_text = (
                    f"{symbol}: previous reduce-only exit outcome cannot be "
                    f"authoritatively reconciled ({pending_exit_id}): {type(exc).__name__}: {exc}"
                )
                self.engine.mark_reconcile_required(campaign, reason_text)
                self.db.save_campaign(campaign)
                self.db.state_set(
                    f"campaign_state:{campaign.campaign_id}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                return {
                    "symbol": symbol,
                    "direction": direction,
                    "action": "RECONCILE_REQUIRED",
                    "client_order_id": pending_exit_id,
                    "reason": reason_text,
                }

        # A cancel timeout must not prevent the reduce-only exit. A lingering
        # closePosition order cannot reverse exposure, but is reconciled after exit.
        protective_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
        protective_algo_id = campaign.tags.get("protective_algo_id")
        protection_cancel_confirmed = not (protective_client_id or protective_algo_id)
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
                cancel_result = self.barrier.execute(
                    cancel_intent,
                    lambda: self.client.cancel_algo_order_safe(
                        symbol,
                        algo_id=protective_algo_id or None,
                        client_algo_id=protective_client_id or None,
                    ),
                )
                cancel_response = cancel_result.response if isinstance(cancel_result.response, dict) else {}
                cancel_status = str(
                    cancel_response.get("algoStatus", "") or cancel_response.get("status", "")
                ).upper()
                protection_cancel_confirmed = (
                    cancel_result.accepted and cancel_status in {"CANCELED", "EXPIRED"}
                )
                if not protection_cancel_confirmed:
                    raise FuturesCampaignExecutionError(
                        f"protective cancel is not confirmed terminal: {cancel_status or 'UNKNOWN'}"
                    )
            except Exception as exc:
                protection_cancel_confirmed = False
                self.db.log_event(
                    "ERROR",
                    "futures_protection_cancel_ambiguous",
                    f"{symbol}: {exc}",
                    {"campaign_id": campaign.campaign_id, "reason": reason},
                )

        quantity = self.client.normalize_quantity(symbol, abs(amount), market=True)
        client_order_id = "W2FX_" + uuid.uuid4().hex[:24]
        campaign.tags["pending_exit_client_order_id"] = client_order_id
        campaign.tags["pending_exit_reason"] = str(reason)
        self.db.save_campaign(campaign)
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
        try:
            result = self.barrier.execute(
                intent,
                lambda: self.client.market_exit(
                    symbol, direction, quantity, client_order_id
                ),
            )
        except Exception as exc:
            reason_text = (
                f"{symbol}: reduce-only exit submission outcome is unknown; "
                f"clientOrderId={client_order_id}; reconcile before retry: {exc}"
            )
            self.engine.mark_reconcile_required(campaign, reason_text)
            self.db.save_campaign(campaign)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(
                f"position_state:{symbol}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.log_event(
                "ERROR",
                "futures_exit_submit_ambiguous",
                reason_text,
                {"campaign_id": campaign.campaign_id, "client_order_id": client_order_id},
            )
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "RECONCILE_REQUIRED",
                "client_order_id": client_order_id,
                "reason": reason_text,
            }
        if not result.accepted:
            self.engine.mark_reconcile_required(campaign, f"Futures reduce-only exit blocked: {result.reason}")
            raise FuturesCampaignExecutionError(f"{symbol}: exit blocked: {result.reason}")

        response = result.response or {}
        order_id = str(response.get("orderId", "") or "")
        status = str(response.get("status", "")).upper()
        try:
            fresh_amount = float(self._position_amount(symbol))
        except (TypeError, ValueError):
            fresh_amount = float("nan")
        if not math.isfinite(fresh_amount):
            reason = "exit was submitted but exchange position quantity is invalid; closure is unconfirmed"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "RECONCILE_REQUIRED",
                "order_id": order_id,
                "status": status,
                "reason": reason,
            }
        if abs(fresh_amount) <= 1e-12 and not protection_cancel_confirmed:
            reason_text = (
                "exchange position is flat, but prior protective algo cancellation "
                "is unconfirmed; orphaned protection must be reconciled"
            )
            self.engine.mark_reconcile_required(campaign, reason_text)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "RECONCILE_REQUIRED",
                "order_id": order_id,
                "status": status,
                "reason": reason_text,
            }
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
        campaign.tags.pop("pending_exit_client_order_id", None)
        campaign.tags.pop("pending_exit_reason", None)
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

    def _finalize_verified_protective_exit(
        self,
        campaign,
        protection: dict[str, Any],
        actual_order: dict[str, Any],
    ) -> dict[str, Any]:
        """Close a campaign only after the exchange confirms the stop fill.

        When commission is paid in a non-quote asset, preserve it in the
        audit record rather than inventing a USD-equivalent conversion.
        """
        symbol = campaign.symbol.upper()
        order_id = actual_order.get("orderId") or protection.get("actualOrderId")
        trades = self.client.user_trades(symbol, order_id=order_id, limit=1000)
        if not trades:
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective exit is filled but authoritative userTrades are unavailable"
            )

        executed_qty = sum(float(row.get("qty", 0) or 0) for row in trades)
        if not math.isfinite(executed_qty) or executed_qty <= 0:
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective exit has no positive authoritative trade quantity"
            )

        realized_pnl = sum(float(row.get("realizedPnl", 0) or 0) for row in trades)
        quote_commission = 0.0
        other_commission: dict[str, float] = {}
        for row in trades:
            asset = str(row.get("commissionAsset", "") or "").upper()
            commission = max(0.0, float(row.get("commission", 0) or 0))
            if asset in {"USDT", "USDC"}:
                quote_commission += commission
            elif asset:
                other_commission[asset] = other_commission.get(asset, 0.0) + commission

        net_known_quote = realized_pnl - quote_commission
        campaign.realized_pnl_quote = float(campaign.realized_pnl_quote or 0.0) + net_known_quote
        campaign.tags["last_protective_exit"] = {
            "algo_id": protection.get("algoId"),
            "client_algo_id": protection.get("clientAlgoId") or campaign.tags.get("protective_client_algo_id"),
            "actual_order_id": str(order_id or ""),
            "algo_status": str(protection.get("algoStatus", "")).upper(),
            "order_status": str(actual_order.get("status", "")).upper(),
            "executed_qty_from_user_trades": executed_qty,
            "realized_pnl_quote_before_commission": realized_pnl,
            "quote_commission": quote_commission,
            "unconverted_commission_by_asset": other_commission,
            "pnl_basis": "exchange userTrades; non-USDT/USDC fees are separately recorded, not converted",
        }
        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protection_active"] = False
        campaign.exit_reason = "EXCHANGE_PROTECTIVE_STOP_FILLED"
        campaign.tags["last_exit_reason"] = campaign.exit_reason

        # Normalize any non-terminal state through the explicit recovery
        # state before closing. This is allowed only after exchange evidence.
        campaign.mark_reconcile_required(
            "Exchange position is flat; protection algo and actual order fill confirmed"
        )
        campaign.transition(
            CampaignState.EXIT_PENDING,
            reason="protective algo actual order is confirmed FILLED",
        )
        campaign.transition(
            CampaignState.CLOSED,
            reason="protective stop fill reconciled from Binance userTrades",
        )
        self.db.save_campaign(campaign)
        self.db.state_set(f"position_state:{symbol}", "FLAT")
        self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
        self.db.state_delete(f"futures_entry_pending:{symbol}")
        self.db.set_campaign_signal_state(
            campaign.current_signal_id,
            SignalState.CANCELLED.value,
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.EXIT_FILLED.value,
            order_id=str(order_id or ""),
            reason="Exchange-side protective stop filled; campaign closed by reconciliation",
            payload=campaign.tags["last_protective_exit"],
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.CAMPAIGN_CLOSED.value,
            order_id=str(order_id or ""),
            reason=campaign.exit_reason,
            payload={"realized_pnl_quote_net_known_fees": net_known_quote},
        )
        return {
            "symbol": symbol,
            "state": "CLOSED",
            "reason": campaign.exit_reason,
            "actual_order_id": str(order_id or ""),
            "executed_qty": executed_qty,
            "realized_pnl_quote_net_known_fees": net_known_quote,
            "unconverted_commission_by_asset": other_commission,
        }

    def arm_add_on(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_fraction: float,
        available_quote: float | None = None,
    ) -> dict[str, Any]:
        """Reserve and submit a directional Futures add-on with durable identity."""
        symbol = signal.symbol.upper()
        direction = signal_direction(signal)
        if signal.role != SignalRole.ADD_ON:
            raise FuturesCampaignExecutionError("Futures add-on requires SignalRole.ADD_ON")
        if signal.signal_type not in {SignalType.SUPER_AO, SignalType.FRACTAL}:
            raise FuturesCampaignExecutionError("Only Super AO or valid fractal signals may add exposure")
        if signal.expires_at_ms and int(signal.expires_at_ms) < int(time.time() * 1000):
            raise FuturesCampaignExecutionError("Williams add-on signal has expired")
        equity = float(equity_quote)
        risk_fraction = float(candidate_risk_fraction)
        if not math.isfinite(equity) or equity <= 0:
            raise FuturesCampaignExecutionError("Equity must be finite and positive")
        if not math.isfinite(risk_fraction) or risk_fraction <= 0:
            raise FuturesCampaignExecutionError("Add-on risk fraction must be finite and positive")

        campaign = self._find_active_campaign(symbol)
        if campaign is None or campaign.position_qty <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: add-on requires an existing managed position")
        if self._campaign_direction(campaign) != direction:
            raise FuturesCampaignExecutionError("Add-on direction conflicts with the live campaign")
        if campaign.state not in {
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
            CampaignState.EXHAUSTION_WATCH,
        }:
            raise FuturesCampaignExecutionError(
                f"{symbol}: campaign state {campaign.state.value} does not admit an add-on"
            )
        if campaign.additions >= 2:
            raise FuturesCampaignExecutionError("Campaign has reached the maximum of two add-ons")
        if int(signal.signal_bar_time_ms) <= int(campaign.tags.get("last_signal_time_ms", 0) or 0):
            raise FuturesCampaignExecutionError("Add-on signal is not newer than the last campaign signal")
        if self.db.state_get(f"position_state:{symbol}", "FLAT") == CampaignState.RECONCILE_REQUIRED.value:
            raise FuturesCampaignExecutionError(f"{symbol}: position reconciliation lock blocks add-on")

        position = self._position_row(symbol)
        try:
            live_amount = float(position.get("positionAmt", 0) or 0)
            old_qty = float(campaign.position_qty)
            old_entry = float(campaign.average_entry_price)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(f"{symbol}: invalid live/local position values") from exc
        if not all(math.isfinite(x) for x in (live_amount, old_qty, old_entry)) or old_qty <= 0 or old_entry <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: invalid live/local position values")
        if (direction == "LONG" and live_amount <= 0) or (direction == "SHORT" and live_amount >= 0):
            raise FuturesCampaignExecutionError(f"{symbol}: live position direction mismatch")
        if abs(abs(live_amount) - old_qty) > max(1e-8, old_qty * 1e-6):
            raise FuturesCampaignExecutionError(f"{symbol}: live/local quantity mismatch blocks add-on")
        self._assert_isolated_1x(symbol)

        # Permit only the campaign's own hard stop; every other open order must
        # be reconciled before a second exposure-increasing order is admitted.
        if self.client.open_orders(symbol):
            raise FuturesCampaignExecutionError(f"{symbol}: unrelated open orders block add-on")
        protective_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
        protective_algo_id = str(campaign.tags.get("protective_algo_id", "") or "")
        open_algos = self.client.open_algo_orders(symbol)
        foreign_algos = [
            row for row in open_algos
            if str(row.get("clientAlgoId", "") or "") != protective_id
            and str(row.get("algoId", "") or "") != protective_algo_id
        ]
        if foreign_algos:
            raise FuturesCampaignExecutionError(f"{symbol}: unrelated conditional orders block add-on")
        if not protective_id and not protective_algo_id:
            raise FuturesCampaignExecutionError(f"{symbol}: add-on requires a confirmed hard protective stop")

        mark = self._market_mark(symbol)
        trigger = float(self.client.normalize_price(
            symbol, signal.trigger_price, direction=direction, purpose="ENTRY"
        ))
        stop = float(self.client.normalize_price(
            symbol, campaign.current_stop_price or campaign.initial_stop_price,
            direction=direction, purpose="STOP",
        ))
        if direction == "LONG":
            valid_geometry = stop < mark < trigger
        else:
            valid_geometry = trigger < mark < stop
        if not valid_geometry:
            raise FuturesCampaignExecutionError(
                f"{symbol}: add-on trigger is stale or structural stop geometry is invalid"
            )
        if self.require_htf_confirmation and not bool(signal.htf_confirmed):
            raise FuturesCampaignExecutionError(f"{symbol}: add-on requires higher-timeframe confirmation")
        spread = self._spread_pct(symbol)
        if spread > self.max_spread_pct:
            raise FuturesCampaignExecutionError(
                f"{symbol}: spread {spread:.4%} exceeds {self.max_spread_pct:.4%}"
            )

        budget = float(campaign.tags.get("risk_budget_quote", 0.0) or 0.0)
        open_risk = float(campaign.open_risk_quote or 0.0)
        pending_risk = float(campaign.pending_risk_quote or 0.0)
        if not all(math.isfinite(x) for x in (budget, open_risk, pending_risk)) or budget <= 0 or open_risk < 0 or pending_risk < 0:
            raise FuturesCampaignExecutionError(f"{symbol}: campaign risk reservation is invalid")
        campaign_remaining = max(0.0, budget - open_risk - pending_risk)
        portfolio_remaining = self.engine.remaining_portfolio_risk_quote(equity)
        requested_risk = min(
            equity * min(risk_fraction, self.engine.campaign_risk_limit_pct),
            campaign_remaining,
            portfolio_remaining,
        )
        if requested_risk <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: no remaining risk capacity for add-on")
        raw_qty = requested_risk / max(
            abs(trigger - stop) + (trigger * (2.0 * self.fee_buffer_per_side_pct + self.slippage_buffer_pct)),
            1e-12,
        )
        quantity_text = self.client.normalize_quantity(symbol, raw_qty, market=False)
        quantity = float(quantity_text)
        notional = quantity * trigger
        if quantity <= 0 or notional < self._min_notional(symbol):
            raise FuturesCampaignExecutionError(f"{symbol}: add-on quantity fails Futures lot/notional filters")
        actual_risk = self._actual_risk_quote(quantity, trigger, stop)
        if actual_risk <= 0 or actual_risk > requested_risk + max(1e-8, requested_risk * 1e-9):
            raise FuturesCampaignExecutionError(f"{symbol}: normalized add-on exceeds reserved risk")
        if available_quote is not None:
            available = float(available_quote)
            if not math.isfinite(available) or available < 0:
                raise FuturesCampaignExecutionError("Available Futures balance is invalid")
            if notional * (1.0 + 2.0 * self.fee_buffer_per_side_pct) > available:
                raise FuturesCampaignExecutionError(f"{symbol}: insufficient available margin for add-on")

        client_algo_id = "W2FA_" + uuid.uuid4().hex[:24]
        claim_key = f"futures_entry_pending:{symbol}"
        if not self.db.try_claim_state(claim_key, client_algo_id):
            raise FuturesCampaignExecutionError(f"{symbol}: another entry/add-on intent is pending")
        campaign.tags.update({
            "pending_add_on_client_algo_id": client_algo_id,
            "pending_add_on_trigger_price": trigger,
            "pending_add_on_stop_price": stop,
            "pending_add_on_quantity": quantity,
            "pending_add_on_risk_quote": actual_risk,
            "pending_add_on_original_qty": old_qty,
            "pending_add_on_original_entry": old_entry,
            "pending_add_on_direction": direction,
            "last_signal_time_ms": int(signal.signal_bar_time_ms),
            "execution_mode": "FUTURES",
        })
        try:
            self.engine.arm_add_on(
                campaign,
                signal,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
            )
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.ADD_ON_PENDING.value)
            order_side = "BUY" if direction == "LONG" else "SELL"
            intent = OrderIntent.new(
                symbol,
                order_side,
                "STOP_MARKET",
                self._context_versions(signal),
                hypothesis_id=f"WILLIAMS_ADD_ON_{signal.signal_type.value}_{direction}",
                invalidation_level=stop,
                quantity=quantity_text,
                client_order_id=client_algo_id,
                purpose="CAMPAIGN_ADD_ON",
                permission_interval=signal.timeframe,
                campaign_id=campaign.campaign_id,
                signal_id=signal.signal_id,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
            )

            def pre_submit(_snapshot) -> None:
                fresh = self._position_row(symbol)
                amount = float(fresh.get("positionAmt", 0) or 0)
                fresh_mark = self._market_mark(symbol)
                if not math.isfinite(amount) or abs(abs(amount) - old_qty) > max(1e-8, old_qty * 1e-6):
                    raise FuturesCampaignExecutionError("live position changed before add-on submission")
                if direction == "LONG" and not stop < fresh_mark < trigger:
                    raise FuturesCampaignExecutionError("LONG add-on trigger/stop geometry changed before submit")
                if direction == "SHORT" and not trigger < fresh_mark < stop:
                    raise FuturesCampaignExecutionError("SHORT add-on trigger/stop geometry changed before submit")

            result = self.barrier.execute(
                intent,
                lambda: self.client.stop_entry(symbol, direction, quantity_text, str(trigger), client_algo_id),
                pre_submit_checks=pre_submit,
            )
            if not result.accepted:
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                campaign.tags.pop("pending_add_on_client_algo_id", None)
                campaign.tags.pop("pending_add_on_trigger_price", None)
                campaign.tags.pop("pending_add_on_stop_price", None)
                campaign.tags.pop("pending_add_on_quantity", None)
                campaign.tags.pop("pending_add_on_risk_quote", None)
                campaign.tags.pop("pending_add_on_original_qty", None)
                campaign.tags.pop("pending_add_on_original_entry", None)
                campaign.tags.pop("pending_add_on_direction", None)
                campaign.transition(CampaignState.TREND_ACTIVE, reason="add-on admission blocked before exchange submit")
                self.db.save_campaign(campaign)
                self.db.state_delete(claim_key)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", campaign.state.value)
                raise FuturesCampaignExecutionError(f"{symbol}: add-on blocked: {result.reason}")

            response = result.response or {}
            status = str(response.get("algoStatus", "") or response.get("status", "")).upper()
            algo_id = str(response.get("algoId", "") or "")
            if status not in {"NEW", "WORKING", "PENDING_NEW"} or not algo_id:
                reason = f"{symbol}: add-on order response is not confirmed active (status={status or 'UNKNOWN'})"
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(f"{reason}; recovery must query clientAlgoId={client_algo_id}")

            campaign.tags["pending_add_on_algo_id"] = algo_id
            self.db.save_campaign_order(PendingOrderRecord(
                order_id=algo_id,
                client_order_id=client_algo_id,
                symbol=symbol,
                side=order_side,
                order_type="STOP_MARKET",
                purpose="ADD_ON",
                status=status,
                stop_price=trigger,
                quantity=quantity,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
                signal_id=signal.signal_id,
                campaign_id=campaign.campaign_id,
            ))
            self.db.save_campaign(campaign)
            self.db.log_campaign_event(
                campaign.campaign_id,
                CampaignEventType.ADD_ON_ARMED.value,
                signal_id=signal.signal_id,
                order_id=algo_id,
                reason=f"{direction} conditional add-on submitted",
                payload={
                    "client_algo_id": client_algo_id,
                    "trigger_price": trigger,
                    "stop_price": stop,
                    "quantity": quantity,
                    "risk_quote": actual_risk,
                },
            )
            return {
                "campaign_id": campaign.campaign_id,
                "symbol": symbol,
                "direction": direction,
                "action": "ADD_ON_ARMED",
                "algo_id": algo_id,
                "client_algo_id": client_algo_id,
                "trigger_price": trigger,
                "quantity": quantity,
                "risk_quote": actual_risk,
                "status": status,
            }
        except Exception as exc:
            # An exception may occur after Binance accepted the request. Keep
            # the durable ID and risk reservation; never clear them on timeout.
            if campaign.state != CampaignState.RECONCILE_REQUIRED and isinstance(exc, FuturesCampaignExecutionError) and "add-on blocked:" in str(exc):
                raise
            self.engine.mark_reconcile_required(
                campaign,
                f"add-on submission outcome requires reconciliation: {type(exc).__name__}: {exc}",
            )
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            raise FuturesCampaignExecutionError(
                f"{symbol}: add-on submission is unresolved; clientAlgoId={client_algo_id}"
            ) from exc

    def manage_campaign(self, campaign, indicators, *, atr: float) -> dict[str, Any]:
        """Manage an exchange-confirmed position using only closed Williams bars.

        The policy uses a two-bar structural reversal for hard exit and an
        Alligator/fractal-based trailing stop after favorable movement. It does
        not add exposure; every proposed stop is monotonically risk-reducing.
        """
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        if indicators is None or len(indicators) < 3:
            return {"symbol": symbol, "action": "WAIT", "reason": "insufficient closed candles"}
        if not math.isfinite(float(atr)) or float(atr) <= 0:
            return {"symbol": symbol, "action": "WAIT", "reason": "ATR unavailable"}

        position = self._position_row(symbol)
        try:
            signed_qty = float(position.get("positionAmt", 0) or 0.0)
        except (TypeError, ValueError):
            signed_qty = float("nan")
        if not math.isfinite(signed_qty):
            reason = "invalid or non-finite exchange quantity during campaign management"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": reason}
        if abs(signed_qty) <= 1e-12:
            return self.reconcile_symbol(symbol)
        if (direction == "LONG" and signed_qty < 0) or (direction == "SHORT" and signed_qty > 0):
            self.engine.mark_reconcile_required(
                campaign,
                "Position direction mismatch during campaign management",
            )
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": "direction mismatch"}

        rows = indicators.tail(2)
        latest = rows.iloc[-1]
        opposite_structure: list[bool] = []
        for _, row in rows.iterrows():
            teeth = float(row.get("teeth_shifted", 0.0) or 0.0)
            close = float(row.get("close", 0.0) or 0.0)
            ao = float(row.get("ao", 0.0) or 0.0)
            if direction == "LONG":
                opposite_structure.append(
                    bool(row.get("bearish_alligator", False))
                    and teeth > 0 and close < teeth and ao < 0
                )
            else:
                opposite_structure.append(
                    bool(row.get("bullish_alligator", False))
                    and teeth > 0 and close > teeth and ao > 0
                )
        if len(opposite_structure) == 2 and all(opposite_structure):
            result = self.exit_position(
                campaign,
                reason="WILLIAMS_TWO_BAR_STRUCTURAL_REVERSAL",
            )
            return {**result, "management_signal": "HARD_EXIT"}

        entry = float(position.get("entryPrice", campaign.average_entry_price or 0.0) or 0.0)
        mark = self._market_mark(symbol)
        if entry <= 0:
            self.engine.mark_reconcile_required(campaign, "Exchange position has no entryPrice")
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": "entryPrice missing"}

        favorable = mark - entry if direction == "LONG" else entry - mark
        progress_atr = favorable / float(atr)
        if progress_atr < 0.5:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "progress_atr": progress_atr,
                "stop_price": campaign.current_stop_price,
            }

        teeth = float(latest.get("teeth_shifted", 0.0) or 0.0)
        if teeth <= 0:
            return {"symbol": symbol, "action": "HOLD_PROTECTION", "reason": "Teeth unavailable"}

        if direction == "LONG":
            fractal = float(latest.get("last_down_level", 0.0) or 0.0)
            structure = max(teeth, fractal) if fractal > 0 else teeth
            proposed = structure - 0.25 * float(atr)
            safe = proposed > 0 and proposed < mark - 0.1 * float(atr)
            tighter = proposed > float(campaign.current_stop_price or campaign.initial_stop_price or 0.0)
        else:
            fractal = float(latest.get("last_up_level", 0.0) or 0.0)
            structure = min(teeth, fractal) if fractal > 0 else teeth
            proposed = structure + 0.25 * float(atr)
            safe = proposed > mark + 0.1 * float(atr)
            old_stop = float(campaign.current_stop_price or campaign.initial_stop_price or 0.0)
            tighter = old_stop <= 0 or proposed < old_stop

        if not safe or not tighter:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "progress_atr": progress_atr,
                "proposed_stop": proposed,
                "reason": "no safe, strictly tighter structural stop",
            }

        try:
            result = self.replace_protection(campaign, stop_price=proposed)
        except Exception as exc:
            self.engine.mark_reconcile_required(
                campaign,
                f"Futures structural stop replacement failed: {type(exc).__name__}: {exc}",
            )
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {
                "symbol": symbol,
                "action": "RECONCILE_REQUIRED",
                "reason": f"structural stop replacement failed: {exc}",
            }

        if campaign.state == CampaignState.OPEN_INITIAL:
            campaign.transition(CampaignState.TREND_ACTIVE, reason="favorable movement supports structural management")
        if campaign.state == CampaignState.TREND_ACTIVE:
            campaign.transition(CampaignState.TRAILING, reason="Williams structural stop tightened")
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.STOP_MOVED.value,
            reason=f"{direction} protective stop moved in favor of the campaign",
            payload={
                "direction": direction,
                "entry_price": entry,
                "mark_price": mark,
                "atr": float(atr),
                "progress_atr": progress_atr,
                "new_stop": result.get("stop_price"),
                "fractal_reference": fractal,
                "teeth": teeth,
            },
        )
        return {
            "symbol": symbol,
            "direction": direction,
            "action": "TRAILING_STOP_MOVED",
            "progress_atr": progress_atr,
            **result,
        }

    def reconcile_symbol(self, symbol: str) -> dict[str, Any]:
        """Reconcile local campaign against authoritative Futures position/order state."""
        symbol = str(symbol).upper()
        position = self._position_row(symbol)
        try:
            amount = float(position.get("positionAmt", 0) or 0)
        except (TypeError, ValueError):
            amount = float("nan")
        if not math.isfinite(amount):
            reason = "invalid exchange position quantity; reconciliation required"
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}
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
            protective_client_id = str(
                campaign.tags.get("protective_client_algo_id", "") or ""
            )
            protective_algo_id = campaign.tags.get("protective_algo_id")
            if protective_client_id or protective_algo_id:
                try:
                    protection = self.client.get_algo_order(
                        symbol,
                        algo_id=protective_algo_id or None,
                        client_algo_id=protective_client_id or None,
                    )
                    algo_status = str(protection.get("algoStatus", "")).upper()
                    actual_order_id = protection.get("actualOrderId")
                    if actual_order_id and algo_status in {"TRIGGERED", "FINISHED"}:
                        actual_order = self.client.get_order(
                            symbol,
                            order_id=actual_order_id,
                        )
                        if str(actual_order.get("status", "")).upper() == "FILLED":
                            return self._finalize_verified_protective_exit(
                                campaign,
                                protection,
                                actual_order,
                            )
                except Exception as exc:
                    self.db.log_event(
                        "WARNING",
                        "futures_protective_exit_reconciliation_pending",
                        f"{symbol}: unable to verify the protective exit fill: {exc}",
                        {"campaign_id": campaign.campaign_id},
                    )

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
                fill_order_id=str(campaign.tags.get("pending_algo_id", "") or entry_client_algo_id),
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
            # If stop replacement was interrupted after the new stop was
            # created but before the old stop was confirmed cancelled, reconcile
            # both stable IDs before doing any other campaign mutation.
            if campaign.tags.get("protection_replace_reconcile_required"):
                new_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
                new_algo_id = campaign.tags.get("protective_algo_id")
                old_client_id = str(campaign.tags.get("previous_protective_client_algo_id", "") or "")
                old_algo_id = campaign.tags.get("previous_protective_algo_id")
                try:
                    new_order = self.client.get_algo_order(
                        symbol,
                        algo_id=new_algo_id or None,
                        client_algo_id=new_client_id or None,
                    )
                    old_order = self.client.get_algo_order(
                        symbol,
                        algo_id=old_algo_id or None,
                        client_algo_id=old_client_id or None,
                    ) if (old_client_id or old_algo_id) else {}
                    active_statuses = {"NEW", "WORKING", "PENDING"}
                    terminal_statuses = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED", "FINISHED"}
                    new_status = str(new_order.get("algoStatus", "")).upper()
                    old_status = str(old_order.get("algoStatus", "")).upper()
                    if new_status not in active_statuses | terminal_statuses:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: replacement stop status is ambiguous ({new_status or 'UNKNOWN'})"
                        )
                    if old_client_id or old_algo_id:
                        if old_status not in active_statuses | terminal_statuses:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: prior stop status is ambiguous ({old_status or 'UNKNOWN'})"
                            )
                    if new_status in active_statuses:
                        if old_status in active_statuses:
                            cancel_intent = OrderIntent.new(
                                symbol,
                                "SELL" if direction == "LONG" else "BUY",
                                "CANCEL",
                                {},
                                purpose="CAMPAIGN_PROTECTION_RECONCILE_CANCEL_OLD",
                                campaign_id=campaign.campaign_id,
                                signal_id=campaign.current_signal_id,
                                client_order_id=str(old_client_id or old_algo_id),
                            )
                            cancel_result = self.barrier.execute(
                                cancel_intent,
                                lambda: self.client.cancel_algo_order_safe(
                                    symbol,
                                    algo_id=old_algo_id or None,
                                    client_algo_id=old_client_id or None,
                                ),
                            )
                            if not cancel_result.accepted:
                                raise FuturesCampaignExecutionError(
                                    f"{symbol}: prior stop cancellation remains uncertain: {cancel_result.reason}"
                                )
                            cancel_response = cancel_result.response if isinstance(cancel_result.response, dict) else {}
                            cancel_status = str(
                                cancel_response.get("algoStatus", "") or cancel_response.get("status", "")
                            ).upper()
                            if cancel_status not in {"CANCELED", "EXPIRED"}:
                                raise FuturesCampaignExecutionError(
                                    f"{symbol}: prior stop cancellation is not confirmed terminal: {cancel_status or 'UNKNOWN'}"
                                )
                        # The new stop is authoritative and active.
                    elif old_status in active_statuses:
                        # New stop is terminal, but old protection is still live.
                        # Restore the old stop as canonical; the next cycle may
                        # safely retry tightening from this confirmed baseline.
                        campaign.tags["protective_client_algo_id"] = old_client_id
                        campaign.tags["protective_algo_id"] = old_algo_id
                        previous_stop = float(campaign.tags.get("previous_protective_stop_price", 0) or 0)
                        if previous_stop > 0:
                            campaign.current_stop_price = previous_stop
                    else:
                        # Both are terminal. The ordinary protection check below
                        # must establish a fresh stop or fail closed.
                        campaign.tags["protection_active"] = False
                    campaign.tags.pop("previous_protective_client_algo_id", None)
                    campaign.tags.pop("previous_protective_algo_id", None)
                    campaign.tags.pop("previous_protective_stop_price", None)
                    campaign.tags.pop("protection_replace_reconcile_required", None)
                    self.db.save_campaign(campaign)
                except Exception as exc:
                    self.engine.mark_reconcile_required(
                        campaign,
                        f"Protective stop replacement reconciliation failed: {type(exc).__name__}: {exc}",
                    )
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    self.db.state_set(
                        f"position_state:{symbol}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    return {
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "reason": f"protective stop replacement unresolved: {exc}",
                    }

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
            # Add-on order placement/fill reconciliation is not yet implemented as
            # a complete exchange lifecycle. Never silently report an expanding
            # campaign as healthy merely because its original protective stop is
            # active: a crash/partial fill could leave local quantity and risk
            # reservations inconsistent with the authoritative exchange position.
            if campaign.state in {
                CampaignState.ADD_ON_ARMING,
                CampaignState.ADD_ON_PENDING,
                CampaignState.POSITION_EXPANDING,
            }:
                reason = (
                    "add-on lifecycle is unresolved; exchange position and add-on "
                    "order/fill history require explicit reconciliation"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(
                    f"campaign_state:{campaign.campaign_id}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                return {
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": reason,
                    "position_qty": abs(amount),
                    "protection": "CONFIRMED",
                }

            # For ordinary active campaigns, a live exchange quantity that
            # differs materially from the persisted campaign quantity is also
            # a reconciliation event, not a healthy state.
            expected_qty = float(campaign.position_qty or 0.0)
            live_qty = abs(amount)
            quantity_tolerance = max(1e-8, expected_qty * 1e-6)
            if abs(live_qty - expected_qty) > quantity_tolerance:
                reason = (
                    f"exchange/local quantity mismatch: exchange={live_qty} "
                    f"campaign={expected_qty}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(
                    f"campaign_state:{campaign.campaign_id}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                return {
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": reason,
                    "position_qty": live_qty,
                    "protection": "CONFIRMED",
                }

            self.db.state_set(f"position_state:{symbol}", campaign.state.value)
            return {
                "symbol": symbol,
                "state": campaign.state.value,
                "direction": direction,
                "position_qty": live_qty,
                "average_entry_price": float(position.get("entryPrice", 0) or 0),
                "protection": "CONFIRMED",
            }

        self.engine.mark_reconcile_required(
            campaign,
            "Exchange position exists but campaign has no recorded entry/add-on fill state",
        )
        self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
        return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "state mismatch"}
