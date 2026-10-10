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
from dataclasses import replace
from typing import Any

from campaign_engine import CampaignEngine
from tc2_campaign_rules import tc2_price_bar_trailing_candidate

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
        max_open_positions: int = 3,
        portfolio_risk_limit_pct: float = 0.03,
        campaign_risk_limit_pct: float = 0.01,
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
        self.portfolio_risk_limit_pct = min(0.03, max(0.0, float(portfolio_risk_limit_pct)))
        self.campaign_risk_limit_pct = min(0.01, max(0.0, float(campaign_risk_limit_pct)))
        self.max_spread_pct = max(0.0, float(os.getenv("MAX_SPREAD_PCT", "0.0015")))
        self.max_atr_pct = max(0.0, float(os.getenv("MAX_ATR_PCT", "0.08")))
        self.max_daily_loss_pct = min(0.25, max(0.0, float(os.getenv("MAX_DAILY_LOSS_PCT", "0.01"))))
        self.fee_buffer_per_side_pct = max(0.0, float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        self.slippage_buffer_pct = max(0.0, float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        self.require_htf_confirmation = os.getenv("REQUIRE_HTF_CONFIRMATION", "true").lower() == "true"

    @staticmethod
    def _validate_user_trade_row(
        trade: dict[str, Any],
        symbol: str,
        source: str,
        *,
        require_realized_pnl: bool = True,
    ) -> None:
        required = ["qty", "price", "commission", "commissionAsset"]
        if require_realized_pnl:
            required.append("realizedPnl")
        missing = [
            field for field in required
            if field not in trade
            or trade[field] is None
            or str(trade[field]).strip() == ""
        ]
        if missing:
            raise FuturesCampaignExecutionError(
                f"{symbol}: {source} userTrades row omitted required fields: {', '.join(missing)}"
            )

    @staticmethod
    def _rows(value: Any) -> list[dict[str, Any]]:
        if isinstance(value, list):
            if any(not isinstance(row, dict) for row in value):
                raise FuturesCampaignExecutionError("Futures positionRisk contains a malformed row")
            return value
        if isinstance(value, dict) and "symbol" in value:
            return [value]
        raise FuturesCampaignExecutionError("Futures positionRisk returned a malformed payload")

    def _cancel_algo_via_barrier(
        self,
        campaign,
        symbol: str,
        side: str,
        *,
        algo_id: str | int | None,
        client_algo_id: str | None,
        purpose: str,
    ) -> dict[str, Any]:
        target_id = str(client_algo_id or algo_id or "").strip()
        if not target_id:
            raise FuturesCampaignExecutionError(f"{symbol}: algo cancellation lacks stable target identity")
        intent = OrderIntent.new(
            symbol,
            side,
            "CANCEL",
            {},
            purpose=purpose,
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            client_order_id=target_id,
        )
        result = self.barrier.execute(
            intent,
            lambda: self.client.cancel_algo_order_safe(
                symbol,
                algo_id=algo_id or None,
                client_algo_id=client_algo_id or None,
            ),
        )
        if not result.accepted:
            raise FuturesCampaignExecutionError(
                f"{symbol}: durable algo cancellation intent was blocked: {result.reason}"
            )
        return result.response if isinstance(result.response, dict) else {}

    def _cancel_child_order_via_barrier(
        self,
        campaign,
        symbol: str,
        side: str,
        *,
        order_id: str | int,
        purpose: str,
    ) -> dict[str, Any]:
        target_id = str(order_id or "").strip()
        if not target_id:
            raise FuturesCampaignExecutionError(f"{symbol}: child-order cancellation lacks orderId")
        intent = OrderIntent.new(
            symbol,
            side,
            "CANCEL",
            {},
            purpose=purpose,
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            client_order_id=target_id,
        )
        result = self.barrier.execute(
            intent,
            lambda: self.client.cancel_order_safe(symbol, order_id=order_id),
        )
        if not result.accepted:
            raise FuturesCampaignExecutionError(
                f"{symbol}: durable child-order cancellation intent was blocked: {result.reason}"
            )
        return result.response if isinstance(result.response, dict) else {}

    def _position_row(self, symbol: str) -> dict[str, Any]:
        symbol = str(symbol).upper()
        rows = self._rows(self.client.position_risk(symbol))
        # Binance positionRisk V3 only returns symbols with positions or open
        # orders. A valid empty list for a symbol therefore means flat; transport
        # errors and malformed payloads are raised by the client/row parser.
        if not rows:
            return {
                "symbol": symbol,
                "positionAmt": "0",
                "entryPrice": "0",
                "_position_risk_empty": True,
            }
        row = next((x for x in rows if str(x.get("symbol", "")).upper() == symbol), None)
        if row is None:
            raise FuturesCampaignExecutionError(
                f"{symbol}: authoritative positionRisk response returned a different symbol"
            )
        raw_amount = row.get("positionAmt")
        if raw_amount is None or str(raw_amount).strip() == "":
            raise FuturesCampaignExecutionError(
                f"{symbol}: authoritative positionRisk omitted positionAmt"
            )
        try:
            amount = float(raw_amount)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: invalid positionAmt in authoritative positionRisk"
            ) from exc
        if not math.isfinite(amount):
            raise FuturesCampaignExecutionError(
                f"{symbol}: non-finite positionAmt in authoritative positionRisk"
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
        try:
            configuration = self.client.symbol_configuration(symbol)
        except Exception as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: margin/leverage configuration could not be verified"
            ) from exc
        if str(configuration.get("symbol", "")).upper() != str(symbol).upper():
            raise FuturesCampaignExecutionError(
                f"{symbol}: symbolConfig returned a different symbol"
            )
        margin_type = str(configuration.get("marginType", "") or "").upper()
        if margin_type != "ISOLATED":
            raise FuturesCampaignExecutionError(
                f"{symbol}: isolated margin is required; symbolConfig reports {margin_type or 'UNKNOWN'}"
            )
        try:
            leverage_value = float(configuration.get("leverage"))
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: symbolConfig leverage is missing/invalid"
            ) from exc
        if not math.isfinite(leverage_value) or not leverage_value.is_integer() or leverage_value < 1:
            raise FuturesCampaignExecutionError(
                f"{symbol}: symbolConfig leverage is not a valid positive integer"
            )
        leverage = int(leverage_value)
        if leverage != 1:
            raise FuturesCampaignExecutionError(
                f"{symbol}: current leverage is {leverage}x; this build requires 1x"
            )
        return configuration

    def _active_rows(self) -> list[dict[str, Any]]:
        rows = self.db.open_campaigns()
        return [r for r in rows if str(r.get("state", "")).upper() not in {"CLOSED", "FLAT"}]

    def _assert_position_capacity(self) -> None:
        """Reject new Futures campaigns when durable ownership/capacity is unclear.

        arm_initial_entry calls this once for fast rejection and again inside
        Database.transaction(immediate=True), immediately before writing the
        new campaign. That second check and the campaign write are serialized
        across database connections/processes. Explicit Spot campaigns do not
        consume a Futures slot; campaigns with missing/unknown mode fail closed.
        """
        active_futures = []
        for row in self._active_rows():
            mode = str(self._row_tags(row).get("execution_mode", "") or "").strip().upper()
            if mode == "SPOT":
                continue
            if mode != "FUTURES":
                raise FuturesCampaignExecutionError(
                    "Active campaign execution mode is missing/unknown; "
                    "new Futures campaign blocked until ownership is reconciled"
                )
            active_futures.append(row)
        if len(active_futures) >= self.max_open_positions:
            raise FuturesCampaignExecutionError(
                "Maximum open Futures campaign count reached; new campaign blocked"
            )

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
            sym = str(row.get("symbol", "") or "").upper()
            if not sym:
                raise FuturesCampaignExecutionError(
                    "Futures positionRisk contains a row without a symbol"
                )
            raw_amount = row.get("positionAmt")
            if raw_amount is None or str(raw_amount).strip() == "":
                raise FuturesCampaignExecutionError(
                    f"{sym}: positionRisk omitted positionAmt during account-wide reconciliation"
                )
            try:
                amount = float(raw_amount)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    f"{sym}: invalid positionAmt during account-wide reconciliation"
                ) from exc
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

        # A flat position is not sufficient proof that the account is clean:
        # an orphan conditional entry on ANY symbol can recreate exposure after
        # restart. Query account-wide open orders and match each to durable IDs
        # belonging to the same symbol; never ignore an order from an unconfigured
        # symbol or a malformed row.
        known_by_symbol: dict[str, dict[str, set[str]]] = {}
        for row in active:
            if self._row_tags(row).get("execution_mode") != "FUTURES":
                continue
            managed_symbol = str(row.get("symbol", "") or "").upper()
            if not managed_symbol:
                raise FuturesCampaignExecutionError(
                    "Active Futures campaign has no symbol during account-wide order reconciliation"
                )
            known = known_by_symbol.setdefault(managed_symbol, {
                "algo_ids": set(),
                "client_algo_ids": set(),
                "order_ids": set(),
                "client_order_ids": set(),
            })
            tags = self._row_tags(row)
            for key in (
                "pending_algo_id", "pending_add_on_algo_id", "protective_algo_id",
                "previous_protective_algo_id", "pending_protective_algo_id",
            ):
                value = tags.get(key)
                if value not in (None, ""):
                    known["algo_ids"].add(str(value))
            for key in (
                "entry_client_algo_id", "pending_add_on_client_algo_id",
                "protective_client_algo_id", "previous_protective_client_algo_id",
                "pending_protective_client_algo_id",
            ):
                value = tags.get(key)
                if value not in (None, ""):
                    known["client_algo_ids"].add(str(value))
            for key in (
                "entry_actual_order_id", "pending_exit_order_id",
                "last_add_on_exchange_order_id",
            ):
                value = tags.get(key)
                if value not in (None, ""):
                    known["order_ids"].add(str(value))
            for key in (
                "pending_exit_client_order_id", "last_terminal_exit_client_order_id",
            ):
                value = tags.get(key)
                if value:
                    known["client_order_ids"].add(str(value))

        try:
            # The Binance endpoints support account-wide listing when symbol is
            # omitted. A failure here must block exposure; per-symbol fallback
            # could miss an orphan order on a different symbol.
            open_algo = self.client.open_algo_orders()
            open_standard = self.client.open_orders()
        except Exception as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: cannot enumerate account-wide open exchange orders before exposure admission: "
                f"{type(exc).__name__}: {exc}"
            ) from exc
        if not isinstance(open_algo, list) or not isinstance(open_standard, list):
            raise FuturesCampaignExecutionError(
                f"{symbol}: account-wide open-order responses are malformed; new exposure is blocked"
            )

        orphaned_algo = []
        for order in open_algo:
            if not isinstance(order, dict):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: malformed account-wide open Algo order blocks new exposure"
                )
            order_symbol = str(order.get("symbol", "") or "").upper()
            if not order_symbol:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: account-wide open Algo order omitted symbol; new exposure is blocked"
                )
            known = known_by_symbol.get(order_symbol, {})
            algo_id = str(order.get("algoId", "") or "")
            client_id = str(order.get("clientAlgoId", "") or "")
            if (
                algo_id not in known.get("algo_ids", set())
                and client_id not in known.get("client_algo_ids", set())
            ):
                orphaned_algo.append({
                    "symbol": order_symbol,
                    "algoId": algo_id,
                    "clientAlgoId": client_id,
                })

        orphaned_standard = []
        for order in open_standard:
            if not isinstance(order, dict):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: malformed account-wide open standard order blocks new exposure"
                )
            order_symbol = str(order.get("symbol", "") or "").upper()
            if not order_symbol:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: account-wide open standard order omitted symbol; new exposure is blocked"
                )
            known = known_by_symbol.get(order_symbol, {})
            order_id = str(order.get("orderId", "") or "")
            client_id = str(order.get("clientOrderId", "") or "")
            if (
                order_id not in known.get("order_ids", set())
                and client_id not in known.get("client_order_ids", set())
            ):
                orphaned_standard.append({
                    "symbol": order_symbol,
                    "orderId": order_id,
                    "clientOrderId": client_id,
                })

        if orphaned_algo or orphaned_standard:
            orphan_symbols = {
                str(order.get("symbol", "")).upper()
                for order in orphaned_algo + orphaned_standard
                if str(order.get("symbol", "")).strip()
            }
            for orphan_symbol in orphan_symbols:
                self.db.state_set(
                    f"position_state:{orphan_symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
            raise FuturesCampaignExecutionError(
                f"{symbol}: orphan/unowned open exchange orders block new exposure "
                f"(algo={orphaned_algo}, standard={orphaned_standard})"
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

    def _tc2_core_context_allowed(
        self,
        signal: SignalSpec,
        snapshot=None,
        *,
        allow_early_wm1: bool = False,
    ) -> bool:
        """Validate H1 signal evidence and fresh H4 context for intraday TC2.

        H1 is the canonical decision timeframe. H4 is the sole higher-timeframe
        context, not a duplicate directional trigger. D1 is not an entry gate.
        WM1 requires its reversal/angulation evidence; WM2 requires its third
        same-colour AO bar; WM3 requires a confirmed fractal trigger beyond
        Teeth at confirmation and at submission.
        A fully aligned H1 Alligator is not a universal gate for all Wise Men.
        """
        direction = str(getattr(signal, "direction", "") or "").upper()
        timeframe = str(getattr(signal, "timeframe", "") or "").lower()
        is_reversal = signal.signal_type == SignalType.REVERSAL
        is_super_ao = signal.signal_type == SignalType.SUPER_AO
        is_fractal = signal.signal_type == SignalType.FRACTAL
        if not (is_reversal or is_super_ao or is_fractal):
            return False
        if direction not in {"LONG", "SHORT"} or timeframe != "1h":
            return False

        if allow_early_wm1:
            if not is_reversal:
                return False
            try:
                angle = float(signal.angulation_score)
            except (TypeError, ValueError, OverflowError):
                return False
            if not math.isfinite(angle) or angle <= 0.0:
                return False

        snapshot = snapshot or self.barrier.context_cache.snapshot()
        versions = {
            str(key).lower(): int(value)
            for key, value in dict(signal.context_versions or {}).items()
            if str(key).lower() in {"1h", "4h"}
        }
        now_ms = int(time.time() * 1000)
        durations_ms = {"1h": 3_600_000, "4h": 14_400_000}
        contexts = {}

        for interval, duration_ms in durations_ms.items():
            context = snapshot.context(signal.symbol, interval)
            if context is None or interval not in versions:
                return False
            if int(context.version) != versions[interval]:
                return False
            if not bool(context.williams_core_ready):
                return False
            try:
                close_ms = int(context.candle_close_time_ms)
                ao = float(context.ao_value)
                price = float(context.price)
                atr = float(context.atr)
                jaw = float(context.jaw)
                teeth = float(context.teeth)
                lips = float(context.lips)
            except (TypeError, ValueError, OverflowError):
                return False
            age_ms = now_ms - close_ms
            if (
                close_ms <= 0
                or age_ms < -60_000
                or age_ms > max(120_000, 2 * duration_ms)
                or not all(math.isfinite(value) for value in (ao, price, atr, jaw, teeth, lips))
                or min(price, atr, jaw, teeth, lips) <= 0.0
            ):
                return False
            contexts[interval] = context

        operative = contexts["1h"]
        state = str(operative.alligator_state or "").strip().upper()
        try:
            ao_value = float(operative.ao_value)
        except (TypeError, ValueError, OverflowError):
            return False
        if not math.isfinite(ao_value):
            return False
        if is_reversal:
            # WM1 is a counter-trend presenting signal. In the strict first
            # pass it may pass only when the H1 Alligator already permits the
            # new side; the caller retries with allow_early_wm1=True after its
            # independently validated positive angulation evidence.
            if not allow_early_wm1:
                if direction == "LONG" and not (
                    state == "BULLISH" and operative.alligator_awake
                ):
                    return False
                if direction == "SHORT" and not (
                    state == "BEARISH" and operative.alligator_awake
                ):
                    return False
        elif is_fractal:
            # TC2 WM3 is filtered at execution by the operative red Balance
            # Line (Teeth), not by requiring the whole Alligator mouth to have
            # already aligned with the breakout direction.
            try:
                trigger = float(signal.trigger_price)
                teeth_now = float(operative.teeth)
            except (TypeError, ValueError, OverflowError):
                return False
            if not math.isfinite(trigger) or trigger <= 0.0 or not math.isfinite(teeth_now) or teeth_now <= 0.0:
                return False
            if direction == "LONG" and trigger <= teeth_now:
                return False
            if direction == "SHORT" and trigger >= teeth_now:
                return False
        # WM2 (Super AO) is independent of a pre-existing fractal and is not
        # universally gated by the sign of AO or an already-awake directional
        # Alligator. Its detector must prove the three same-colour AO bars.

        return True

    def _tc2_wm1_early_context_allowed(self, signal: SignalSpec, snapshot=None) -> bool:
        return self._tc2_core_context_allowed(
            signal,
            snapshot,
            allow_early_wm1=True,
        )

    def _context_versions(self, signal: SignalSpec) -> dict[str, int]:
        snapshot = self.barrier.context_cache.snapshot()
        versions = {
            str(key).lower(): int(value)
            for key, value in dict(signal.context_versions or {}).items()
        }
        tc2_core = (
            os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
            == "TC2_THREE_WISE_MEN"
        )
        operative = str(signal.timeframe).lower()
        if tc2_core:
            # The TC2 admission dependency set is exactly H1 decision + H4 context.
            # M15 is execution monitoring; D1 is informational only.
            versions = {
                key: value for key, value in versions.items()
                if key in {"1h", "4h"}
            }
            if operative != "1h":
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: TC2 campaigns require H1 as the decision timeframe"
                )
            required_intervals = ("1h", "4h")
        else:
            required_intervals = (operative,)

        for interval in required_intervals:
            ctx = snapshot.context(signal.symbol, interval)
            if ctx is None:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: no current MarketContext for {interval}"
                )
            current_version = int(ctx.version)
            if interval in versions and versions[interval] != current_version:
                # Preserve the signal's original dependency so stale scans cannot
                # be silently re-authorized against newer market context.
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: signal context {interval} is stale "
                    f"(signal={versions[interval]}, current={current_version})"
                )
            versions.setdefault(interval, current_version)

        for interval, version in list(versions.items()):
            ctx = snapshot.context(signal.symbol, interval)
            if ctx is None:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: signal depends on absent MarketContext {interval}"
                )
            if int(ctx.version) != int(version):
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: signal context {interval} version mismatch"
                )
        return versions

    def _validate_signal(self, signal: SignalSpec) -> tuple[str, float, float]:
        direction = signal_direction(signal)
        if signal.role != SignalRole.ENTRY:
            raise FuturesCampaignExecutionError("Initial entry requires a SignalRole.ENTRY signal")
        if signal.signal_type == SignalType.REVERSAL:
            # The scanner blocks WM1 by default because the numeric angulation
            # is not source-verified. Enforce the same rule at the final execution
            # boundary so a restored, manually constructed, or stale SignalSpec
            # cannot bypass the detector-level fail-closed gate.
            allow_approximation = str(
                os.getenv("WILLIAMS_ALLOW_APPROXIMATE_ANGULATION", "false")
            ).strip().lower() in {"1", "true", "yes", "on"}
            if not allow_approximation:
                raise FuturesCampaignExecutionError(
                    "WM1 blocked: angulation formula is an unverified approximation"
                )
            try:
                angulation = float(signal.angulation_score)
            except (TypeError, ValueError, OverflowError) as exc:
                raise FuturesCampaignExecutionError("WM1 angulation evidence is not numeric") from exc
            if not math.isfinite(angulation) or angulation <= 0.0:
                raise FuturesCampaignExecutionError("WM1 requires finite positive angulation evidence")
        if not math.isfinite(float(signal.trigger_price)) or float(signal.trigger_price) <= 0:
            raise FuturesCampaignExecutionError("Signal trigger price must be finite and positive")
        if int(signal.expires_at_ms or 0) <= int(time.time() * 1000):
            raise FuturesCampaignExecutionError("Williams signal has expired or has no valid expiry")
        # Zero means an older SignalSpec did not provide a separate
        # invalidation field; only that legacy/missing case may use the
        # protective reference. An explicit malformed or wrong-side stop is a
        # contract violation and must never be silently replaced by another
        # level while preserving entry authorization.
        try:
            raw_stop = float(signal.invalidation_price or 0.0)
        except (TypeError, ValueError, OverflowError) as exc:
            raise FuturesCampaignExecutionError("Structural invalidation is not numeric") from exc
        if not math.isfinite(raw_stop):
            raise FuturesCampaignExecutionError("Structural invalidation must be finite")
        if raw_stop == 0.0:
            try:
                raw_stop = float(signal.protective_reference or 0.0)
            except (TypeError, ValueError, OverflowError) as exc:
                raise FuturesCampaignExecutionError("Protective reference is not numeric") from exc
            if not math.isfinite(raw_stop):
                raise FuturesCampaignExecutionError("Protective reference must be finite")
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
        tc2_core = (
            os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
            == "TC2_THREE_WISE_MEN"
        )
        if tc2_core:
            snapshot = self.barrier.context_cache.snapshot()
            context_ok = self._tc2_core_context_allowed(signal, snapshot)
            if (
                not context_ok
                and signal.signal_type == SignalType.REVERSAL
            ):
                context_ok = self._tc2_core_context_allowed(
                    signal, snapshot, allow_early_wm1=True
                )
            if not context_ok:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: TC2 H1 signal evidence or H4 context validation failed"
                )
        elif self.require_htf_confirmation and not bool(signal.htf_confirmed):
            raise FuturesCampaignExecutionError(
                f"{symbol}: selected profile requires higher-timeframe confirmation"
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

        tc2_core = (
            os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
            == "TC2_THREE_WISE_MEN"
        )
        if tc2_core:
            signal = replace(signal, context_versions=self._context_versions(signal))
        # Runtime enforces the same cap during portfolio selection, but this
        # final service boundary must also reject direct callers that bypass it.
        self._assert_position_capacity()
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
            # TC2's core exit is structural trailing/exhaustion, not a
            # synthetic ATR take-profit. Keep the legacy R:R admission gate
            # only for explicitly non-TC2 profiles.
            enforce_min_rr=not tc2_core,
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

        campaign = None
        try:
            # Re-check capacity while holding SQLite's write reservation and
            # persist the campaign in the same transaction. A prior read-only
            # count is useful for fast rejection but is not concurrency-safe:
            # two independent callers could otherwise both admit the last slot.
            try:
                with self.db.transaction(immediate=True):
                    self._assert_position_capacity()
                    # Re-read aggregate risk under the same SQLite write lock
                    # that persists this campaign. Pre-transaction capacity reads
                    # are advisory only: two different symbols could otherwise
                    # both spend the same remaining portfolio risk.
                    reserved_at_commit = self.engine.portfolio_reserved_risk_quote()
                    portfolio_tolerance = max(1e-8, capacity_quote * 1e-9)
                    if reserved_at_commit + actual_risk > capacity_quote + portfolio_tolerance:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: portfolio risk capacity changed before durable entry reservation"
                        )
                    campaign_capacity_quote = equity * self.campaign_risk_limit_pct
                    campaign_tolerance = max(1e-8, campaign_capacity_quote * 1e-9)
                    if actual_risk > campaign_capacity_quote + campaign_tolerance:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: initial entry exceeds the hard campaign risk cap"
                        )
                    if not self.db.try_claim_state(claim_key, client_algo_id):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: another Futures entry intent is already reserved"
                        )
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
                        "entry_quantity": quantity,
                        "entry_fill_reconciliation_pending": True,
                        "initial_stop_price": stop,
                        "last_signal_time_ms": int(signal.signal_bar_time_ms),
                        "last_signal_confirmation_time_ms": int(
                            getattr(signal, "confirmation_time_ms", 0) or signal.signal_bar_time_ms
                        ),
                        "entry_expires_at_ms": int(signal.expires_at_ms or 0),
                        "position_side_mode": "ONE_WAY",
                        "leverage": 1,
                        "isolated_margin": True,
                        # The campaign cap is the lifetime budget across the
                        # initial tranche and later Wise-Men add-ons; the initial
                        # tranche receives only its configured fraction of this cap.
                        "risk_budget_quote": equity * self.campaign_risk_limit_pct,
                    })
                    self.db.save_campaign(campaign)
                    self.engine.arm_entry(campaign, signal)
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.ENTRY_PENDING.value,
                    )
            except Exception:
                # The symbol claim and campaign writes belong to the same
                # transaction. Rollback therefore removes any claim acquired
                # by this attempt without risking deletion of another caller's
                # durable claim.
                campaign = None
                raise

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
                context_admission_mode=(
                    "TC2_WM1_EARLY"
                    if (
                        signal.signal_type == SignalType.REVERSAL
                        and str(signal.timeframe).lower() == "1h"
                        and not bool(signal.htf_confirmed)
                    )
                    else "STRICT_DIRECTIONAL"
                ),
                signal_type=signal.signal_type.value,
                angulation_score=float(signal.angulation_score or 0.0),
                campaign_id=campaign.campaign_id,
                signal_id=signal.signal_id,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
            )

            def last_mile(snapshot) -> None:
                ctx = snapshot.context(symbol, signal.timeframe)
                if ctx is None:
                    raise FuturesCampaignExecutionError("operative MarketContext disappeared")
                tc2_core = (
                    os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
                    == "TC2_THREE_WISE_MEN"
                )
                if tc2_core:
                    context_ok = self._tc2_core_context_allowed(signal, snapshot)
                    if (
                        not context_ok
                        and signal.signal_type == SignalType.REVERSAL
                    ):
                        context_ok = self._tc2_core_context_allowed(
                            signal, snapshot, allow_early_wm1=True
                        )
                    if not context_ok:
                        raise FuturesCampaignExecutionError(
                            "TC2 context became stale or no longer admits this Wise-Man signal"
                        )
                elif signal.signal_type == SignalType.REVERSAL:
                    if not self._tc2_wm1_early_context_allowed(signal, snapshot):
                        raise FuturesCampaignExecutionError(
                            "WM1 early context/angulation/H4 context admission no longer valid"
                        )
                else:
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
            if status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
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
            response_client_id = str(order.get("clientAlgoId", "") or "")
            if response_client_id and response_client_id != client_algo_id:
                reason = f"{symbol}: entry response clientAlgoId does not match durable intent"
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(reason)
            if order_id:
                campaign.tags["pending_algo_id"] = order_id
            self.db.save_campaign(campaign)
            try:
                verified_entry = self.client.get_algo_order(
                    symbol,
                    algo_id=order_id or None,
                    client_algo_id=client_algo_id,
                )
                verified_status = str(verified_entry.get("algoStatus", "") or "").upper()
                verified_id = str(verified_entry.get("algoId", "") or "")
                verified_client_id = str(verified_entry.get("clientAlgoId", "") or "")
                verified_side = str(verified_entry.get("side", "") or "").upper()
                verified_type = str(
                    verified_entry.get("orderType", verified_entry.get("type", "")) or ""
                ).upper()
                verified_close_position = str(verified_entry.get("closePosition", "")).lower() in {"true", "1"}
                verified_trigger = float(verified_entry.get("triggerPrice"))
                verified_quantity = float(verified_entry.get("quantity"))
                if (
                    verified_status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
                    or not verified_id
                    or (order_id and verified_id != order_id)
                    or verified_client_id != client_algo_id
                    or str(verified_entry.get("symbol", symbol)).upper() != symbol
                    or verified_side != order_side
                    or verified_type != "STOP_MARKET"
                    or verified_close_position
                    or not math.isfinite(verified_trigger)
                    or not math.isclose(verified_trigger, trigger, rel_tol=0.0, abs_tol=1e-8)
                    or not math.isfinite(verified_quantity)
                    or not math.isclose(verified_quantity, quantity, rel_tol=0.0, abs_tol=1e-8)
                ):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: entry algo failed authoritative identity/side/type/trigger/quantity verification"
                    )
                order_id = verified_id
                status = verified_status
                campaign.tags["pending_algo_id"] = order_id
                self.db.save_campaign(campaign)
            except Exception as exc:
                reason = (
                    f"{symbol}: entry submission response could not be authoritatively verified: "
                    f"{type(exc).__name__}: {exc}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.save_campaign(campaign)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(reason) from exc
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
        try:
            position = self._position_row(symbol)
        except Exception as exc:
            reason = (
                "live position quantity/identity is invalid or unavailable; "
                "protective stop cannot be verified"
            )
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.RECONCILE_REQUIRED.value,
            )
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            raise FuturesCampaignExecutionError(
                f"{symbol}: {reason}: {type(exc).__name__}: {exc}"
            ) from exc
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
            pending_type = str(pending_order.get("orderType", pending_order.get("type", "")) or "").upper()
            pending_close_position = str(pending_order.get("closePosition", "")).lower() in {"true", "1"}
            pending_trigger = pending_order.get("triggerPrice")
            active_statuses = {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
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
                    or pending_type != "STOP_MARKET"
                    or not pending_close_position
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
        if status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
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

        try:
            verified_order = self.client.get_algo_order(
                symbol,
                algo_id=algo_id or None,
                client_algo_id=client_algo_id,
            )
            verified_status = str(verified_order.get("algoStatus", "") or "").upper()
            verified_client_id = str(verified_order.get("clientAlgoId", "") or "")
            verified_algo_id = str(verified_order.get("algoId", "") or "")
            if not algo_id and verified_algo_id:
                algo_id = verified_algo_id
            verified_side = str(verified_order.get("side", "") or "").upper()
            verified_type = str(
                verified_order.get("orderType", verified_order.get("type", "")) or ""
            ).upper()
            verified_close_position = str(verified_order.get("closePosition", "")).lower() in {"true", "1"}
            verified_trigger = float(verified_order.get("triggerPrice"))
            if (
                verified_status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
                or verified_client_id != client_algo_id
                or verified_side != order_side
                or verified_type != "STOP_MARKET"
                or not verified_close_position
                or not math.isfinite(verified_trigger)
                or not math.isclose(verified_trigger, normalized_stop, rel_tol=0.0, abs_tol=1e-8)
                or not verified_algo_id
                or verified_algo_id != algo_id
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: submitted protection failed authoritative identity/side/type/trigger verification"
                )
        except Exception as exc:
            reason = (
                f"{symbol}: protective submit response was not authoritatively verified; "
                f"old protection must remain until reconciliation: {type(exc).__name__}: {exc}"
            )
            campaign.mark_reconcile_required(reason)
            self.db.save_campaign(campaign)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.log_event(
                "ERROR",
                "futures_protection_authoritative_verification_failed",
                reason,
                {"campaign_id": campaign.campaign_id, "client_algo_id": client_algo_id, "algo_id": algo_id},
            )
            raise FuturesCampaignExecutionError(reason) from exc

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
            campaign.tags["protection_replace_target_stop_price"] = requested
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
            verified_old = self.client.get_algo_order(
                symbol,
                algo_id=old_algo_id or None,
                client_algo_id=old_client_id or None,
            )
            verified_old_status = str(verified_old.get("algoStatus", "") or "").upper()
            if verified_old_status not in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: old protective order remains nonterminal after cancellation re-query "
                    f"({verified_old_status or 'UNKNOWN'})"
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
        campaign.tags.pop("protection_replace_target_stop_price", None)
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

    def cancel_pending_add_on(self, campaign, *, reason: str) -> dict[str, Any]:
        """Cancel a pending exposure-increasing add-on and reconcile any raced fill."""
        symbol = campaign.symbol.upper()
        client_id = str(campaign.tags.get("pending_add_on_client_algo_id", "") or "")
        if not client_id:
            if campaign.state not in {
                CampaignState.ADD_ON_ARMING,
                CampaignState.ADD_ON_PENDING,
                CampaignState.POSITION_EXPANDING,
            }:
                return {"symbol": symbol, "state": campaign.state.value, "action": "NO_PENDING_ADD_ON"}
            detail = f"{reason}: add-on campaign state has no durable clientAlgoId"
            self.engine.mark_reconcile_required(campaign, detail)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "action": "ADD_ON_CANCEL_UNVERIFIED", "reason": detail}

        try:
            algo = self.client.get_algo_order(symbol, client_algo_id=client_id)
            status = str(algo.get("algoStatus", "") or "").upper()
            if status in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
                self._cancel_algo_via_barrier(
                    campaign, symbol, "BUY" if self._campaign_direction(campaign) == "LONG" else "SELL",
                    algo_id=algo.get("algoId") or campaign.tags.get("pending_add_on_algo_id") or None,
                    client_algo_id=client_id,
                    purpose="CAMPAIGN_ADD_ON_CANCEL",
                )
                algo = self.client.get_algo_order(symbol, client_algo_id=client_id)
                status = str(algo.get("algoStatus", "") or "").upper()
            if status in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
                raise FuturesCampaignExecutionError(
                    f"add-on cancellation is not terminal after exchange re-query ({status})"
                )
            # Reconciliation verifies the actual triggered order, cancels any
            # residual partial fill, and either books the fill or releases the
            # reservation only after terminal no-fill evidence.
            result = self.reconcile_symbol(symbol)
            if result.get("state") == "RECONCILE_REQUIRED":
                return {
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "action": "ADD_ON_CANCEL_UNVERIFIED",
                    "reason": result.get("reason", reason),
                }
            if result.get("action") == "ADD_ON_TERMINAL_UNFILLED":
                return {**result, "action": "ADD_ON_CANCELLED", "cancel_reason": str(reason)}
            return {**result, "cancel_reason": str(reason)}
        except Exception as exc:
            detail = (
                f"{reason}: add-on cancel/reconciliation is ambiguous for {client_id}: "
                f"{type(exc).__name__}: {exc}"
            )
            self.engine.mark_reconcile_required(campaign, detail)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.log_event(
                "ERROR",
                "futures_add_on_cancel_ambiguous",
                detail,
                {"campaign_id": campaign.campaign_id, "client_algo_id": client_id},
            )
            return {
                "symbol": symbol,
                "state": "RECONCILE_REQUIRED",
                "action": "ADD_ON_CANCEL_UNVERIFIED",
                "reason": detail,
            }

    def cancel_pending_entry(self, campaign, *, reason: str) -> dict[str, Any]:
        """Cancel a bot-owned conditional ENTRY and verify it is terminal.

        Pausing or killing the runtime must not leave an armed entry capable of
        opening new exposure later. A missing/ambiguous exchange confirmation
        leaves the campaign in RECONCILE_REQUIRED; it is never treated as a
        successful cancellation.
        """
        symbol = campaign.symbol.upper()
        if campaign.state != CampaignState.ENTRY_PENDING and not (
            campaign.state == CampaignState.RECONCILE_REQUIRED
            and campaign.tags.get("entry_fill_reconciliation_pending")
        ):
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
            if status in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                # Reuse the same child-order/userTrades reconciliation used at
                # startup. A terminal algo may still have a partially filled
                # child order, so terminal algo status alone cannot release risk.
                reconciled = self.reconcile_symbol(symbol)
                reconciled_state = str(reconciled.get("state", "")).upper()
                if reconciled_state not in {"RECONCILE_REQUIRED", "ENTRY_PENDING"}:
                    action = "ENTRY_CANCELLED" if reconciled_state == "CLOSED" else "ENTRY_FILL_RECONCILED"
                    return {
                        **reconciled,
                        "action": action,
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
        add_on_cancel_confirmed = True
        if campaign.tags.get("pending_add_on_client_algo_id") or campaign.state in {
            CampaignState.ADD_ON_ARMING,
            CampaignState.ADD_ON_PENDING,
            CampaignState.POSITION_EXPANDING,
        }:
            add_on_cancel_result = self.cancel_pending_add_on(
                campaign, reason=f"EXIT_PRECHECK:{reason}"
            )
            add_on_cancel_confirmed = (
                str(add_on_cancel_result.get("state", "")).upper() != "RECONCILE_REQUIRED"
            )
            campaign = self.engine.load_campaign(campaign.campaign_id) or campaign
            direction = self._campaign_direction(campaign)
        if campaign.state == CampaignState.ENTRY_PENDING or campaign.tags.get("entry_fill_reconciliation_pending"):
            entry_cancel_result = self.cancel_pending_entry(
                campaign, reason=f"EXIT_PRECHECK:{reason}"
            )
            entry_cancel_confirmed = (
                str(entry_cancel_result.get("state", "")).upper() != "RECONCILE_REQUIRED"
            )
            campaign = self.engine.load_campaign(campaign.campaign_id) or campaign
            direction = self._campaign_direction(campaign)
            if campaign.state == CampaignState.CLOSED:
                refreshed = self._position_row(symbol)
                try:
                    refreshed_amount = float(refreshed.get("positionAmt", 0) or 0)
                except (TypeError, ValueError):
                    refreshed_amount = float("nan")
                if math.isfinite(refreshed_amount) and abs(refreshed_amount) <= 1e-12:
                    return {
                        "symbol": symbol,
                        "direction": direction,
                        "action": "ENTRY_CANCELLED",
                        "reason": reason,
                    }
        else:
            entry_cancel_confirmed = True
        try:
            position = self._position_row(symbol)
            amount = float(position.get("positionAmt"))
        except Exception as exc:
            reason = (
                "invalid or non-finite exchange quantity (positionAmt); "
                "reduce-only exit requires reconciliation"
            )
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            raise FuturesCampaignExecutionError(
                f"{symbol}: {reason}: {type(exc).__name__}: {exc}"
            ) from exc
        if not math.isfinite(amount):
            reason = (
                "invalid or non-finite exchange quantity (positionAmt); "
                "reduce-only exit requires reconciliation"
            )
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            raise FuturesCampaignExecutionError(f"{symbol}: {reason}")
        # Resolve a durable prior exit intent even when the exchange position
        # is already flat: the previous MARKET order may have filled just before
        # a crash, and that is precisely when we must not submit a new exit.
        pending_exit_id = str(campaign.tags.get("pending_exit_client_order_id", "") or "")
        if abs(amount) <= 1e-12 and not pending_exit_id:
            self.engine.mark_reconcile_required(
                campaign,
                f"Cannot confirm exit: exchange position is flat but campaign was active ({reason})",
            )
            raise FuturesCampaignExecutionError(
                f"{symbol}: exchange is flat but campaign state requires reconciliation"
            )
        if abs(amount) > 1e-12 and (
            (direction == "LONG" and amount < 0) or (direction == "SHORT" and amount > 0)
        ):
            self.engine.mark_reconcile_required(
                campaign,
                f"Position direction mismatch during exit ({reason})",
            )
            raise FuturesCampaignExecutionError(f"{symbol}: direction mismatch; exit blocked for reconciliation")
        order_side = "SELL" if direction == "LONG" else "BUY"

        # A timeout after Binance accepted a MARKET order must never cause a
        # restart to generate a fresh clientOrderId and duplicate the exit.
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
                # Always refresh the live position after a terminal prior
                # exit; the quantity read before the order lookup may be stale.
                position_after_prior = self._position_row(symbol)
                try:
                    remaining_after_prior = float(position_after_prior.get("positionAmt", 0) or 0)
                except (TypeError, ValueError):
                    remaining_after_prior = float("nan")
                if not math.isfinite(remaining_after_prior):
                    raise FuturesCampaignExecutionError(
                        "prior exit is terminal but the refreshed position quantity is invalid"
                    )
                if abs(remaining_after_prior) <= 1e-12:
                    if prior_status == "FILLED":
                        # A crash may occur after the market exit fills but
                        # before local stop-cancellation cleanup. Reconcile
                        # every owned protective identity before closing local
                        # campaign state.
                        orphan_protection_result = self._reconcile_flat_position_protection(campaign)
                        if orphan_protection_result is not None:
                            return orphan_protection_result
                        return self._finalize_verified_market_exit(
                            campaign,
                            prior_exit,
                            reason=str(campaign.tags.get("pending_exit_reason", reason) or reason),
                        )
                    reason_text = (
                        f"{symbol}: prior exit is {prior_status} and position is flat, but "
                        "the exit fill history is not reconciled; retaining the durable intent"
                    )
                    self.engine.mark_reconcile_required(campaign, reason_text)
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
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
                        "prior exit is terminal but exchange position direction changed"
                    )
                position = position_after_prior
                amount = remaining_after_prior
                try:
                    prior_executed_qty = float(prior_exit.get("executedQty", 0) or 0)
                except (TypeError, ValueError):
                    prior_executed_qty = float("nan")
                if not math.isfinite(prior_executed_qty) or prior_executed_qty < 0:
                    raise FuturesCampaignExecutionError("prior exit executedQty is invalid")
                if prior_executed_qty > 0:
                    # Preserve fills from terminal partial exits before creating
                    # a new reduce-only order for the refreshed residual amount.
                    self._record_terminal_exit_fill(campaign, prior_exit)
                campaign.tags["last_terminal_exit_client_order_id"] = pending_exit_id
                campaign.tags["last_terminal_exit_status"] = prior_status
                campaign.tags.pop("pending_exit_client_order_id", None)
                campaign.tags.pop("pending_exit_reason", None)
                campaign.tags.pop("pending_exit_expected_qty", None)
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

        # Keep the exchange-side closePosition stop active while submitting
        # the reduce-only market exit. Cancelling protection first creates a
        # naked-position window if the exit is rejected, blocked, or times out.
        # A closePosition stop cannot reverse exposure; once the position is
        # flat, _reconcile_flat_position_protection() cancels/reconciles the
        # remaining stop and merges any racing child fill into the exit ledger.
        quantity = self.client.normalize_quantity(symbol, abs(amount), market=True)
        client_order_id = "W2FX_" + uuid.uuid4().hex[:24]
        if not campaign.tags.get("exit_cycle_original_qty"):
            # Keep the pre-exit campaign quantity as the conservation target.
            # A protective stop may have partially filled before this market
            # order; the remaining reduce-only exit quantity is then smaller.
            try:
                original_cycle_qty = float(campaign.position_qty or abs(amount))
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: tracked campaign quantity is invalid before exit"
                ) from exc
            if not math.isfinite(original_cycle_qty) or original_cycle_qty <= 0:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: tracked campaign quantity is non-finite or non-positive before exit"
                )
            campaign.tags["exit_cycle_original_qty"] = original_cycle_qty
        campaign.tags["pending_exit_client_order_id"] = client_order_id
        campaign.tags["pending_exit_reason"] = str(reason)
        campaign.tags["pending_exit_expected_qty"] = abs(amount)
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
        if abs(fresh_amount) <= 1e-12 and (
            not add_on_cancel_confirmed
            or not entry_cancel_confirmed
            or bool(campaign.tags.get("pending_add_on_client_algo_id"))
            or bool(campaign.tags.get("entry_fill_reconciliation_pending"))
        ):
            reason_text = (
                "exchange position is flat, but pending-entry or add-on cancellation is "
                "unconfirmed; orphaned orders must be reconciled"
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
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "RECONCILE_REQUIRED",
                "order_id": order_id,
                "status": status,
                "residual_position_amt": fresh_amount,
            }

        if status != "FILLED":
            reason_text = (
                f"{symbol}: exchange position is flat but exit order status is "
                f"{status or 'UNKNOWN'}; fill history must be reconciled"
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
        try:
            protective_reconcile = self._reconcile_flat_position_protection(campaign)
            if protective_reconcile is not None:
                return protective_reconcile
            return self._finalize_verified_market_exit(
                campaign,
                response,
                reason=str(reason),
            )
        except Exception as exc:
            reason_text = (
                f"{symbol}: exit order is FILLED and position is flat, but trade "
                f"history/PnL reconciliation failed: {type(exc).__name__}: {exc}"
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

    def _record_terminal_exit_fill(self, campaign, order: dict[str, Any]) -> None:
        """Durably retain a terminal prior exit so a later exit cannot lose its fills."""
        order_id = str(order.get("orderId", "") or "")
        status = str(order.get("status", "") or "").upper()
        try:
            executed = float(order.get("executedQty", 0) or 0)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError("prior exit has invalid executedQty") from exc
        if not order_id or status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
            raise FuturesCampaignExecutionError("prior exit is not a stable terminal exchange order")
        if not math.isfinite(executed) or executed < 0:
            raise FuturesCampaignExecutionError("prior exit executedQty is invalid")
        if executed <= 0:
            return
        records = list(campaign.tags.get("unreconciled_terminal_exit_orders", []) or [])
        if not any(str(item.get("order_id", "")) == order_id for item in records if isinstance(item, dict)):
            records.append({
                "order_id": order_id,
                "client_order_id": str(order.get("clientOrderId", "") or ""),
                "status": status,
                "executed_qty": executed,
            })
        campaign.tags["unreconciled_terminal_exit_orders"] = records
        campaign.tags.setdefault(
            "exit_cycle_original_qty",
            float(campaign.position_qty or campaign.tags.get("pending_exit_expected_qty", 0) or 0),
        )
        self.db.save_campaign(campaign)

    def _finalize_verified_market_exit(
        self,
        campaign,
        order: dict[str, Any],
        *,
        reason: str,
    ) -> dict[str, Any]:
        """Finalize only after all market-exit child orders and userTrades reconcile."""
        symbol = campaign.symbol.upper()
        current_order_id = str(order.get("orderId", "") or "")
        current_client_id = str(
            order.get("clientOrderId", "") or campaign.tags.get("pending_exit_client_order_id", "") or ""
        )
        if campaign.tags.get("pending_add_on_client_algo_id") or campaign.tags.get("entry_fill_reconciliation_pending"):
            raise FuturesCampaignExecutionError(
                f"{symbol}: cannot finalize exit while an entry/add-on order may still be live"
            )
        if not current_order_id or not current_client_id:
            raise FuturesCampaignExecutionError(
                f"{symbol}: filled exit lacks stable exchange order/client identity"
            )
        expected_client_id = str(campaign.tags.get("pending_exit_client_order_id", "") or "")
        expected_side = "SELL" if self._campaign_direction(campaign) == "LONG" else "BUY"
        response_symbol = str(order.get("symbol", "") or "").upper()
        response_side = str(order.get("side", "") or "").upper()
        response_client_id = str(order.get("clientOrderId", "") or "")
        if (
            response_symbol != symbol
            or response_side != expected_side
            or not response_client_id
            or (expected_client_id and response_client_id != expected_client_id)
            or (expected_client_id and current_client_id != expected_client_id)
        ):
            raise FuturesCampaignExecutionError(
                f"{symbol}: filled market exit does not match the durable symbol/side/clientOrderId intent"
            )
        if str(order.get("status", "") or "").upper() != "FILLED":
            raise FuturesCampaignExecutionError(f"{symbol}: current exit is not authoritatively FILLED")

        records = list(campaign.tags.get("unreconciled_terminal_exit_orders", []) or [])
        if not any(
            isinstance(item, dict) and str(item.get("order_id", "")) == current_order_id
            for item in records
        ):
            records.append({
                "order_id": current_order_id,
                "client_order_id": current_client_id,
                "status": "FILLED",
                "executed_qty": order.get("executedQty", 0),
            })

        market_exit_executed_qty = 0.0
        protective_exit_executed_qty = 0.0
        realized_pnl = 0.0
        quote_commission = 0.0
        other_commission: dict[str, float] = {}
        reconciled_orders: list[dict[str, Any]] = []
        for record in records:
            if not isinstance(record, dict):
                raise FuturesCampaignExecutionError(f"{symbol}: malformed prior exit ledger record")
            record_order_id = str(record.get("order_id", "") or "")
            if not record_order_id:
                raise FuturesCampaignExecutionError(f"{symbol}: terminal exit ledger lacks orderId")
            if record_order_id == current_order_id:
                exchange_order = order
            else:
                exchange_order = self.client.get_order(symbol, order_id=record_order_id)
            exchange_status = str(exchange_order.get("status", "") or "").upper()
            if exchange_status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: prior exit order {record_order_id} is not terminal ({exchange_status or 'UNKNOWN'})"
                )
            if str(exchange_order.get("orderId", "") or "") != record_order_id:
                raise FuturesCampaignExecutionError(f"{symbol}: exit order lookup returned a missing/different orderId")
            exchange_symbol = str(exchange_order.get("symbol", "") or "").upper()
            exchange_side = str(exchange_order.get("side", "") or "").upper()
            expected_exit_side = "SELL" if self._campaign_direction(campaign) == "LONG" else "BUY"
            if exchange_symbol != symbol or exchange_side != expected_exit_side:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: exit order symbol/side does not match campaign direction"
                )
            saved_client_id = str(record.get("client_order_id", "") or "")
            exchange_client_id = str(exchange_order.get("clientOrderId", "") or "")
            if saved_client_id and exchange_client_id and saved_client_id != exchange_client_id:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: exit order clientOrderId does not match durable exit ledger"
                )
            try:
                order_executed = float(exchange_order.get("executedQty", 0) or 0)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(f"{symbol}: exit order executedQty is invalid") from exc
            if not math.isfinite(order_executed) or order_executed < 0:
                raise FuturesCampaignExecutionError(f"{symbol}: exit order executedQty is non-finite")
            order_trade_qty = 0.0
            if order_executed > 0:
                trades = self.client.user_trades(symbol, order_id=record_order_id, limit=1000)
                if not trades:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: exit order {record_order_id} has execution but no authoritative userTrades"
                    )
                for trade in trades:
                    self._validate_user_trade_row(trade, symbol, "market exit", require_realized_pnl=True)
                    trade_order_id = trade.get("orderId")
                    if trade_order_id is not None and str(trade_order_id) != record_order_id:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: userTrades returned a fill for a different exit order"
                        )
                    try:
                        qty = float(trade.get("qty", 0) or 0)
                        price = float(trade.get("price", 0) or 0)
                        pnl = float(trade.get("realizedPnl", 0) or 0)
                        commission = float(trade.get("commission", 0) or 0)
                    except (TypeError, ValueError) as exc:
                        raise FuturesCampaignExecutionError(f"{symbol}: invalid exit userTrades row") from exc
                    if (
                        not all(math.isfinite(value) for value in (qty, price, pnl, commission))
                        or qty <= 0 or price <= 0 or commission < 0
                    ):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: non-finite/invalid exit userTrades values"
                        )
                    order_trade_qty += qty
                    realized_pnl += pnl
                    asset = str(trade.get("commissionAsset", "") or "").upper()
                    if commission > 0 and not asset:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: userTrades commission has no asset"
                        )
                    if asset == "USDT":
                        quote_commission += commission
                    elif asset:
                        other_commission[asset] = other_commission.get(asset, 0.0) + commission
                if abs(order_trade_qty - order_executed) > max(1e-8, order_executed * 1e-6):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: exit order {record_order_id} executedQty disagrees with userTrades"
                    )
            market_exit_executed_qty += order_executed
            reconciled_orders.append({
                "source": "MARKET_EXIT",
                "order_id": record_order_id,
                "status": exchange_status,
                "executed_qty": order_executed,
                "user_trades_qty": order_trade_qty,
            })

        # Resolve protective stop child orders too. These fills can race with a
        # reduce-only market exit; excluding them would make a flat exchange
        # position appear inconsistent and would omit realized PnL/commissions.
        protective_records = list(
            campaign.tags.get("unreconciled_protective_exit_orders", []) or []
        )
        expected_stop_side = "SELL" if self._campaign_direction(campaign) == "LONG" else "BUY"
        seen_protective_ids: set[str] = set()
        for record in protective_records:
            if not isinstance(record, dict):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: malformed protective-exit ledger record"
                )
            record_order_id = str(record.get("order_id", "") or "")
            if not record_order_id or record_order_id in seen_protective_ids:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective-exit ledger has a missing/duplicate orderId"
                )
            seen_protective_ids.add(record_order_id)
            exchange_order = self.client.get_order(symbol, order_id=record_order_id)
            exchange_status = str(exchange_order.get("status", "") or "").upper()
            if exchange_status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child {record_order_id} is not terminal "
                    f"({exchange_status or 'UNKNOWN'})"
                )
            exchange_symbol = str(exchange_order.get("symbol", symbol) or "").upper()
            exchange_id = str(exchange_order.get("orderId", "") or "")
            exchange_side = str(exchange_order.get("side", "") or "").upper()
            saved_child_client_id = str(record.get("client_order_id", "") or "")
            exchange_child_client_id = str(exchange_order.get("clientOrderId", "") or "")
            if (
                exchange_symbol != symbol
                or exchange_id != record_order_id
                or exchange_side != expected_stop_side
                or (
                    saved_child_client_id and exchange_child_client_id
                    and saved_child_client_id != exchange_child_client_id
                )
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child {record_order_id} failed identity/side verification"
                )
            try:
                order_executed = float(exchange_order.get("executedQty", 0) or 0)
                previously_observed = float(record.get("executed_qty", order_executed) or 0)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child executedQty is invalid"
                ) from exc
            if (
                not math.isfinite(order_executed) or order_executed < 0
                or not math.isfinite(previously_observed) or previously_observed <= 0
                or abs(order_executed - previously_observed) > max(1e-8, order_executed * 1e-6)
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child executedQty changed or conflicts with the durable ledger"
                )
            order_trade_qty = 0.0
            if order_executed > 0:
                trades = self.client.user_trades(symbol, order_id=record_order_id, limit=1000)
                if not trades:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child {record_order_id} has execution but no userTrades"
                    )
                for trade in trades:
                    self._validate_user_trade_row(trade, symbol, "protective exit", require_realized_pnl=True)
                    trade_order_id = trade.get("orderId")
                    if trade_order_id is not None and str(trade_order_id) != record_order_id:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: protective userTrades returned a different orderId"
                        )
                    try:
                        qty = float(trade.get("qty", 0) or 0)
                        price = float(trade.get("price", 0) or 0)
                        pnl = float(trade.get("realizedPnl", 0) or 0)
                        commission = float(trade.get("commission", 0) or 0)
                    except (TypeError, ValueError) as exc:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: invalid protective userTrades row"
                        ) from exc
                    if (
                        not all(math.isfinite(value) for value in (qty, price, pnl, commission))
                        or qty <= 0 or price <= 0 or commission < 0
                    ):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: non-finite/invalid protective userTrades values"
                        )
                    order_trade_qty += qty
                    realized_pnl += pnl
                    asset = str(trade.get("commissionAsset", "") or "").upper()
                    if commission > 0 and not asset:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: userTrades commission has no asset"
                        )
                    if asset == "USDT":
                        quote_commission += commission
                    elif asset:
                        other_commission[asset] = other_commission.get(asset, 0.0) + commission
                if abs(order_trade_qty - order_executed) > max(1e-8, order_executed * 1e-6):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child {record_order_id} executedQty disagrees with userTrades"
                    )
            protective_exit_executed_qty += order_executed
            reconciled_orders.append({
                "source": "PROTECTIVE_STOP",
                "order_id": record_order_id,
                "algo_id": str(record.get("algo_id", "") or ""),
                "client_algo_id": str(record.get("client_algo_id", "") or ""),
                "status": exchange_status,
                "executed_qty": order_executed,
                "user_trades_qty": order_trade_qty,
            })

        total_executed = market_exit_executed_qty + protective_exit_executed_qty
        try:
            original_qty = float(
                campaign.tags.get(
                    "exit_cycle_original_qty",
                    campaign.tags.get("pending_exit_expected_qty", campaign.position_qty),
                ) or 0
            )
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(f"{symbol}: original exit quantity is invalid") from exc
        if not math.isfinite(original_qty) or original_qty <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: original exit quantity is missing or invalid")
        if abs(total_executed - original_qty) > max(1e-8, original_qty * 1e-6):
            raise FuturesCampaignExecutionError(
                f"{symbol}: aggregate exit fills {total_executed} do not reconcile to original quantity {original_qty}"
            )
        position = self._position_row(symbol)
        try:
            amount = float(position.get("positionAmt", 0) or 0)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(f"{symbol}: invalid position after filled exit") from exc
        if not math.isfinite(amount) or abs(amount) > 1e-12:
            raise FuturesCampaignExecutionError(
                f"{symbol}: exit trades are filled but authoritative position is not flat ({amount})"
            )

        net_known_quote = realized_pnl - quote_commission
        campaign.realized_pnl_quote = float(campaign.realized_pnl_quote or 0.0) + net_known_quote
        campaign.tags["last_market_exit"] = {
            "orders": reconciled_orders,
            "executed_qty": total_executed,
            "realized_pnl_quote_before_commission": realized_pnl,
            "quote_commission": quote_commission,
            "unconverted_commission_by_asset": other_commission,
            "market_exit_executed_qty": market_exit_executed_qty,
            "protective_stop_executed_qty": protective_exit_executed_qty,
            "pnl_basis": "Binance Futures userTrades across market exits and protective stop children; non-USDT fees separately recorded",
        }
        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protection_active"] = False
        for key in (
            "pending_exit_client_order_id", "pending_exit_reason", "pending_exit_expected_qty",
            "exit_cycle_original_qty", "unreconciled_terminal_exit_orders",
            "unreconciled_protective_exit_orders",
        ):
            campaign.tags.pop(key, None)
        campaign.tags["last_exit_reason"] = str(reason)
        campaign.exit_reason = str(reason)
        campaign.mark_reconcile_required("Reduce-only exit orders and userTrades verified; exchange position is flat")
        campaign.transition(CampaignState.EXIT_PENDING, reason="market exit fills and trade history reconciled")
        campaign.transition(CampaignState.CLOSED, reason="authoritative Futures position and aggregate exit fills confirm flat")
        campaign.next_action = "WAIT"
        self.db.save_campaign(campaign)
        self.db.state_set(f"position_state:{symbol}", "FLAT")
        self.db.state_delete(f"futures_entry_pending:{symbol}")
        self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
        self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.EXIT_FILLED.value,
            order_id=current_order_id,
            reason=reason,
            payload=campaign.tags["last_market_exit"],
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.CAMPAIGN_CLOSED.value,
            order_id=current_order_id,
            reason=reason,
            payload={"realized_pnl_quote_net_known_fees": net_known_quote},
        )
        return {
            "symbol": symbol,
            "direction": self._campaign_direction(campaign),
            "action": "CLOSED",
            "order_id": current_order_id,
            "client_order_id": current_client_id,
            "status": "FILLED",
            "realized_pnl_quote_net_known_fees": net_known_quote,
            "unconverted_commission_by_asset": other_commission,
            "reason": reason,
        }

    def _reconcile_flat_position_protection(self, campaign) -> dict[str, Any] | None:
        """Cancel orphaned protection on a flat position and reconcile any stop fill."""
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        stop_side = "SELL" if direction == "LONG" else "BUY"
        candidates: list[tuple[dict[str, Any], dict[str, Any]]] = []
        seen: set[tuple[str, str]] = set()
        identifiers = [
            (
                campaign.tags.get("protective_client_algo_id"),
                campaign.tags.get("protective_algo_id"),
            ),
            (
                campaign.tags.get("previous_protective_client_algo_id"),
                campaign.tags.get("previous_protective_algo_id"),
            ),
            (
                campaign.tags.get("pending_protective_client_algo_id"),
                campaign.tags.get("pending_protective_algo_id"),
            ),
        ]
        for raw_client_id, raw_algo_id in identifiers:
            client_id = str(raw_client_id or "")
            algo_id = raw_algo_id or None
            if not client_id and not algo_id:
                continue
            key = (client_id, str(algo_id or ""))
            if key in seen:
                continue
            seen.add(key)
            protection = self.client.get_algo_order(
                symbol,
                algo_id=algo_id,
                client_algo_id=client_id or None,
            )
            # A successful lookup is not enough: verify it resolved the exact
            # durable protection identity before cancelling or booking its fill.
            response_symbol = str(protection.get("symbol", symbol) or "").upper()
            response_algo_id = str(protection.get("algoId", "") or "")
            response_client_id = str(protection.get("clientAlgoId", "") or "")
            if (
                response_symbol != symbol
                or (algo_id is not None and response_algo_id and response_algo_id != str(algo_id))
                or (client_id and response_client_id != client_id)
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective lookup returned an identity mismatch; flat-position "
                    "reconciliation cannot safely continue"
                )
            status = str(protection.get("algoStatus", "") or "").upper()
            active_statuses = {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
            terminal_statuses = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED", "FINISHED", "TRIGGERED"}
            if status in active_statuses:
                self._cancel_algo_via_barrier(
                    campaign, symbol, stop_side,
                    algo_id=protection.get("algoId") or algo_id,
                    client_algo_id=str(protection.get("clientAlgoId", "") or client_id) or None,
                    purpose="CAMPAIGN_FLAT_ORPHAN_PROTECTION_CANCEL",
                )
                protection = self.client.get_algo_order(
                    symbol,
                    algo_id=protection.get("algoId") or algo_id,
                    client_algo_id=str(protection.get("clientAlgoId", "") or client_id) or None,
                )
                status = str(protection.get("algoStatus", "") or "").upper()
                if status in active_statuses:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: orphan protective algo remains active after cancellation"
                    )
            if status not in terminal_statuses:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective algo has ambiguous flat-position status ({status or 'UNKNOWN'})"
                )
            actual_order_id = protection.get("actualOrderId")
            if not actual_order_id:
                if status in {"TRIGGERED", "FINISHED"}:
                    # A triggered stop without its child order ID is not proof
                    # that the protective execution is absent. Binance's child
                    # order/fills may still be propagating; never close a
                    # campaign based only on the position being momentarily flat.
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective algo is {status} but actualOrderId is missing; "
                        "child execution and trade history must be reconciled"
                    )
                continue
            actual_order = self.client.get_order(symbol, order_id=actual_order_id)
            child_symbol = str(actual_order.get("symbol", symbol) or "").upper()
            child_order_id = str(actual_order.get("orderId", actual_order_id) or "")
            child_side = str(actual_order.get("side", stop_side) or "").upper()
            if (
                child_symbol != symbol
                or child_order_id != str(actual_order_id)
                or child_side != stop_side
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child lookup returned a symbol/order/side mismatch"
                )
            order_status = str(actual_order.get("status", "") or "").upper()
            child_active = {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}
            child_terminal = {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}
            if order_status in child_active:
                self._cancel_child_order_via_barrier(
                    campaign, symbol, stop_side, order_id=actual_order_id,
                    purpose="CAMPAIGN_FLAT_ORPHAN_PROTECTION_CHILD_CANCEL",
                )
                actual_order = self.client.get_order(symbol, order_id=actual_order_id)
                order_status = str(actual_order.get("status", "") or "").upper()
                if order_status in child_active:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child order remains active while position is flat"
                    )
            if order_status not in child_terminal:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child order has ambiguous status ({order_status or 'UNKNOWN'})"
                )
            try:
                executed = float(actual_order.get("executedQty", 0) or 0)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child order executedQty is invalid"
                ) from exc
            if not math.isfinite(executed) or executed < 0:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective child order executedQty is non-finite"
                )
            if executed > 0:
                candidates.append((protection, actual_order))

        if not candidates:
            campaign.tags["protection_active"] = False
            self.db.save_campaign(campaign)
            return None

        # If a reduce-only market exit is also in flight or has terminal fills,
        # keep the stop-child executions in a durable ledger and reconcile both
        # execution paths together. A stop may have partially filled just as
        # the market exit closes its residual; treating either order alone as
        # the whole campaign would lose quantity and realized PnL.
        market_exit_in_flight = bool(
            campaign.tags.get("pending_exit_client_order_id")
            or campaign.tags.get("unreconciled_terminal_exit_orders")
        )
        if market_exit_in_flight:
            ledger = list(campaign.tags.get("unreconciled_protective_exit_orders", []) or [])
            known = {
                str(row.get("order_id", ""))
                for row in ledger
                if isinstance(row, dict) and row.get("order_id") not in (None, "")
            }
            for protection, actual_order in candidates:
                child_id = str(actual_order.get("orderId", "") or protection.get("actualOrderId") or "")
                if not child_id:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child fill lacks stable orderId"
                    )
                if child_id in known:
                    continue
                try:
                    executed_qty = float(actual_order.get("executedQty", 0) or 0)
                except (TypeError, ValueError) as exc:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child executedQty is invalid"
                    ) from exc
                if not math.isfinite(executed_qty) or executed_qty <= 0:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: protective child fill quantity is invalid"
                    )
                ledger.append({
                    "order_id": child_id,
                    "algo_id": str(protection.get("algoId", "") or ""),
                    "client_algo_id": str(protection.get("clientAlgoId", "") or ""),
                    "client_order_id": str(actual_order.get("clientOrderId", "") or ""),
                    "status": str(actual_order.get("status", "") or "").upper(),
                    "executed_qty": executed_qty,
                })
                known.add(child_id)
            campaign.tags["unreconciled_protective_exit_orders"] = ledger
            campaign.tags["protection_active"] = False
            self.db.save_campaign(campaign)
            return None

        if len(candidates) > 1:
            raise FuturesCampaignExecutionError(
                f"{symbol}: multiple protective child orders report fills while position is flat"
            )
        protection, actual_order = candidates[0]
        return self._finalize_verified_protective_exit(campaign, protection, actual_order)

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
        if not order_id or str(actual_order.get("status", "") or "").upper() != "FILLED":
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective child order is not authoritatively FILLED"
            )
        try:
            response_qty = float(actual_order.get("executedQty", 0) or 0)
            expected_qty = float(campaign.position_qty or 0)
        except (TypeError, ValueError) as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective exit quantity is invalid"
            ) from exc
        if not all(math.isfinite(value) and value > 0 for value in (response_qty, expected_qty)):
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective exit quantity is missing or non-finite"
            )
        trades = self.client.user_trades(symbol, order_id=order_id, limit=1000)
        if not trades:
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective exit is filled but authoritative userTrades are unavailable"
            )

        executed_qty = 0.0
        realized_pnl = 0.0
        quote_commission = 0.0
        other_commission: dict[str, float] = {}
        for row in trades:
            self._validate_user_trade_row(
                row, symbol, "protective stop", require_realized_pnl=True
            )
            trade_order_id = row.get("orderId")
            if trade_order_id is not None and str(trade_order_id) != str(order_id):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective userTrades contain a different orderId"
                )
            try:
                qty = float(row.get("qty", 0) or 0)
                price = float(row.get("price", 0) or 0)
                pnl = float(row.get("realizedPnl", 0) or 0)
                commission = float(row.get("commission", 0) or 0)
            except (TypeError, ValueError) as exc:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective userTrades contain invalid numeric values"
                ) from exc
            if (
                not all(math.isfinite(value) for value in (qty, price, pnl, commission))
                or qty <= 0 or price <= 0 or commission < 0
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: protective userTrades contain non-finite/invalid values"
                )
            executed_qty += qty
            realized_pnl += pnl
            asset = str(row.get("commissionAsset", "") or "").upper()
            if commission > 0 and not asset:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: userTrades commission has no asset"
                )
            if asset == "USDT":
                quote_commission += commission
            elif asset:
                other_commission[asset] = other_commission.get(asset, 0.0) + commission
        tolerance = max(1e-8, expected_qty * 1e-6)
        if (
            abs(executed_qty - response_qty) > tolerance
            or abs(executed_qty - expected_qty) > tolerance
        ):
            raise FuturesCampaignExecutionError(
                f"{symbol}: protective userTrades qty {executed_qty} does not match "
                f"order qty {response_qty} and campaign qty {expected_qty}"
            )

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
            "pnl_basis": "exchange userTrades; non-USDT fees are separately recorded, not converted",
        }
        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protection_active"] = False
        for key in (
            "protective_client_algo_id", "protective_algo_id",
            "previous_protective_client_algo_id", "previous_protective_algo_id",
            "previous_protective_stop_price", "protection_replace_target_stop_price",
            "protection_replace_reconcile_required",
            "pending_protective_client_algo_id", "pending_protective_algo_id",
            "pending_protective_stop_price",
        ):
            campaign.tags.pop(key, None)
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
        if int(signal.expires_at_ms or 0) <= int(time.time() * 1000):
            raise FuturesCampaignExecutionError("Williams add-on signal has expired or has no valid expiry")
        equity = float(equity_quote)
        risk_fraction = float(candidate_risk_fraction)
        if not math.isfinite(equity) or equity <= 0:
            raise FuturesCampaignExecutionError("Equity must be finite and positive")
        if not math.isfinite(risk_fraction) or risk_fraction <= 0:
            raise FuturesCampaignExecutionError("Add-on risk fraction must be finite and positive")

        campaign = self._find_active_campaign(symbol)
        if campaign is None or campaign.position_qty <= 0:
            raise FuturesCampaignExecutionError(f"{symbol}: add-on requires an existing managed position")
        self._assert_no_unmanaged_positions(symbol)
        if self._campaign_direction(campaign) != direction:
            raise FuturesCampaignExecutionError("Add-on direction conflicts with the live campaign")
        if campaign.state not in {
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
        }:
            raise FuturesCampaignExecutionError(
                f"{symbol}: campaign state {campaign.state.value} does not admit an add-on"
            )
        if campaign.additions >= 2:
            raise FuturesCampaignExecutionError("Campaign has reached the maximum of two add-ons")
        latest_confirmation = int(
            campaign.tags.get(
                "last_signal_confirmation_time_ms",
                campaign.tags.get("last_signal_time_ms", 0),
            ) or 0
        )
        signal_confirmation = int(
            getattr(signal, "confirmation_time_ms", 0) or signal.signal_bar_time_ms
        )
        if signal_confirmation <= latest_confirmation:
            raise FuturesCampaignExecutionError(
                "Add-on signal is not newer than the last actionable confirmation"
            )
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
        try:
            protection = self.client.get_algo_order(
                symbol,
                algo_id=protective_algo_id or None,
                client_algo_id=protective_id or None,
            )
            protection_status = str(protection.get("algoStatus", "") or "").upper()
            protection_side = str(protection.get("side", "") or "").upper()
            protection_type = str(protection.get("orderType", protection.get("type", "")) or "").upper()
            protection_close_position = str(protection.get("closePosition", "")).lower() in {"true", "1"}
            protection_client_id = str(protection.get("clientAlgoId", "") or "")
            protection_trigger = float(protection.get("triggerPrice"))
        except Exception as exc:
            raise FuturesCampaignExecutionError(
                f"{symbol}: existing hard stop cannot be authoritatively verified before add-on: {exc}"
            ) from exc
        expected_protection_side = "SELL" if direction == "LONG" else "BUY"
        expected_stop = float(campaign.current_stop_price or campaign.initial_stop_price or 0)
        if (
            protection_status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
            or protection_side != expected_protection_side
            or protection_type != "STOP_MARKET"
            or not protection_close_position
            or (protective_id and protection_client_id != protective_id)
            or not math.isfinite(protection_trigger)
            or not math.isfinite(expected_stop)
            or expected_stop <= 0
            or not math.isclose(protection_trigger, expected_stop, rel_tol=0.0, abs_tol=1e-8)
        ):
            raise FuturesCampaignExecutionError(
                f"{symbol}: add-on blocked because the existing protective stop identity/side/type/trigger is invalid"
            )

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
        tc2_core = (
            os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
            == "TC2_THREE_WISE_MEN"
        )
        if tc2_core:
            snapshot = self.barrier.context_cache.snapshot()
            if not self._tc2_core_context_allowed(signal, snapshot):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: TC2 add-on requires fresh H1 signal evidence and valid H4 context"
                )
        elif self.require_htf_confirmation and not bool(signal.htf_confirmed):
            raise FuturesCampaignExecutionError(f"{symbol}: selected profile requires add-on higher-timeframe confirmation")
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

        # Reserve this add-on atomically with the global risk recheck. A claim
        # scoped only to this symbol does not serialize risk reservations on
        # other symbols, so the portfolio ceiling must be tested inside BEGIN
        # IMMEDIATE before pending risk becomes durable and before any submit.
        with self.db.transaction(immediate=True):
            if not self.db.try_claim_state(claim_key, client_algo_id):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: another entry/add-on intent is pending"
                )

            latest_campaign = self.engine.load_campaign(campaign.campaign_id)
            if latest_campaign is None:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: durable campaign disappeared before add-on reservation"
                )
            campaign = latest_campaign
            if campaign.state not in {
                CampaignState.OPEN_INITIAL,
                CampaignState.TREND_ACTIVE,
                CampaignState.TRAILING,
            } or campaign.position_qty <= 0:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: campaign state changed before add-on reservation"
                )
            if campaign.additions >= 2:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: campaign reached add-on cap before reservation"
                )
            latest_confirmation = int(
                campaign.tags.get(
                    "last_signal_confirmation_time_ms",
                    campaign.tags.get("last_signal_time_ms", 0),
                ) or 0
            )
            if signal_confirmation <= latest_confirmation:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: add-on signal became stale before durable reservation"
                )
            latest_qty = float(campaign.position_qty)
            if not math.isfinite(latest_qty) or abs(latest_qty - old_qty) > max(1e-8, old_qty * 1e-6):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: persisted position changed before add-on reservation"
                )

            portfolio_capacity_quote = equity * self.engine.portfolio_risk_limit_pct
            reserved_at_commit = self.engine.portfolio_reserved_risk_quote()
            portfolio_tolerance = max(1e-8, portfolio_capacity_quote * 1e-9)
            if reserved_at_commit + actual_risk > portfolio_capacity_quote + portfolio_tolerance:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: portfolio risk capacity changed before add-on reservation"
                )

            budget = float(campaign.tags.get("risk_budget_quote", 0.0) or 0.0)
            open_risk = float(campaign.open_risk_quote or 0.0)
            pending_risk = float(campaign.pending_risk_quote or 0.0)
            if (
                not all(math.isfinite(value) for value in (budget, open_risk, pending_risk))
                or budget <= 0 or open_risk < 0 or pending_risk < 0
            ):
                raise FuturesCampaignExecutionError(
                    f"{symbol}: campaign risk reservation is invalid at commit"
                )
            campaign_tolerance = max(1e-8, budget * 1e-9)
            if open_risk + pending_risk + actual_risk > budget + campaign_tolerance:
                raise FuturesCampaignExecutionError(
                    f"{symbol}: add-on would exceed campaign risk cap at commit"
                )

            campaign.tags.update({
                "pending_add_on_client_algo_id": client_algo_id,
                "pending_add_on_trigger_price": trigger,
                "pending_add_on_stop_price": stop,
                "pending_add_on_quantity": quantity,
                "pending_add_on_risk_quote": actual_risk,
                "pending_add_on_expires_at_ms": int(signal.expires_at_ms or 0),
                "pending_add_on_original_qty": old_qty,
                "pending_add_on_original_entry": old_entry,
                "pending_add_on_direction": direction,
                "last_signal_time_ms": int(signal.signal_bar_time_ms),
                "last_signal_confirmation_time_ms": signal_confirmation,
                "execution_mode": "FUTURES",
            })
            self.engine.arm_add_on(
                campaign,
                signal,
                risk_quote=actual_risk,
                capital_reserved_quote=notional,
            )
            self.db.state_set(
                f"campaign_state:{campaign.campaign_id}",
                CampaignState.ADD_ON_PENDING.value,
            )

        try:
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
                campaign.tags.pop("pending_add_on_expires_at_ms", None)
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
            if status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"} or not algo_id:
                reason = f"{symbol}: add-on order response is not confirmed active (status={status or 'UNKNOWN'})"
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(f"{reason}; recovery must query clientAlgoId={client_algo_id}")

            campaign.tags["pending_add_on_algo_id"] = algo_id
            self.db.save_campaign(campaign)
            try:
                verified_add_on = self.client.get_algo_order(
                    symbol,
                    algo_id=algo_id,
                    client_algo_id=client_algo_id,
                )
                verified_status = str(verified_add_on.get("algoStatus", "") or "").upper()
                verified_id = str(verified_add_on.get("algoId", "") or "")
                verified_client_id = str(verified_add_on.get("clientAlgoId", "") or "")
                verified_side = str(verified_add_on.get("side", "") or "").upper()
                verified_type = str(
                    verified_add_on.get("orderType", verified_add_on.get("type", "")) or ""
                ).upper()
                verified_close_position = str(verified_add_on.get("closePosition", "")).lower() in {"true", "1"}
                verified_reduce_only = str(verified_add_on.get("reduceOnly", "")).lower() in {"true", "1"}
                verified_trigger = float(verified_add_on.get("triggerPrice"))
                verified_quantity = float(verified_add_on.get("quantity"))
                if (
                    verified_status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
                    or verified_id != algo_id
                    or verified_client_id != client_algo_id
                    or str(verified_add_on.get("symbol", symbol)).upper() != symbol
                    or verified_side != order_side
                    or verified_type != "STOP_MARKET"
                    or verified_close_position
                    or verified_reduce_only
                    or not math.isfinite(verified_trigger)
                    or not math.isclose(verified_trigger, trigger, rel_tol=0.0, abs_tol=1e-8)
                    or not math.isfinite(verified_quantity)
                    or not math.isclose(verified_quantity, quantity, rel_tol=0.0, abs_tol=1e-8)
                ):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on algo failed authoritative identity/side/type/trigger/quantity verification"
                    )
                status = verified_status
            except Exception as exc:
                reason = (
                    f"{symbol}: add-on submission could not be authoritatively verified: "
                    f"{type(exc).__name__}: {exc}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.save_campaign(campaign)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                raise FuturesCampaignExecutionError(reason) from exc
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
        """Manage a TC2 campaign with a price-bar structural trail.

        The TC2 core trail is behind the extreme of the last 3 closed decision
        bars by default. A 5-bar trail can be explicitly selected with
        WILLIAMS_TC2_TRAILING_BARS=5. ATR/Teeth smoothing and the legacy two-bar
        reversal are not silently mixed into this source profile. They remain
        available only through the named non-TC2 legacy profile / explicit exit
        overlay. Exchange protection remains authoritative.
        """
        profile = os.getenv(
            "WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN"
        ).strip().upper()
        if profile != "TC2_THREE_WISE_MEN":
            return self._manage_legacy_campaign(campaign, indicators, atr=atr)

        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        if str(getattr(campaign, "execution_timeframe", "") or "").lower() != "1h":
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": "TC2 campaign management requires canonical H1 closed candles",
            }
        if indicators is None:
            return {"symbol": symbol, "action": "HOLD_PROTECTION", "reason": "closed H1 candles unavailable"}

        try:
            trail_bars = int(os.getenv("WILLIAMS_TC2_TRAILING_BARS", "3"))
        except (TypeError, ValueError, OverflowError):
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": "WILLIAMS_TC2_TRAILING_BARS must be 3 or 5",
            }
        if trail_bars not in {3, 5}:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": "WILLIAMS_TC2_TRAILING_BARS must be 3 or 5",
            }

        if len(indicators) < trail_bars:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": f"need {trail_bars} closed H1 bars for TC2 structural trailing",
            }

        position = self._position_row(symbol)
        try:
            signed_qty = float(position.get("positionAmt", 0) or 0.0)
        except (TypeError, ValueError, OverflowError):
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
            reason = "Position direction mismatch during campaign management"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": reason}

        # A two-bar Alligator/AO reversal is a separate overlay, not the core
        # TC2 stop rule. If explicitly enabled, keep its decision auditable.
        if os.getenv("WILLIAMS_TC2_TWO_BAR_REVERSAL_EXIT", "false").strip().lower() == "true":
            if len(indicators) < 2:
                return {"symbol": symbol, "action": "HOLD_PROTECTION", "reason": "two-bar overlay lacks data"}
            opposite_structure: list[bool] = []
            for _, row in indicators.tail(2).iterrows():
                try:
                    teeth = float(row.get("teeth_shifted", float("nan")))
                    close = float(row.get("close", float("nan")))
                    ao = float(row.get("ao", float("nan")))
                except (TypeError, ValueError, OverflowError):
                    opposite_structure.append(False)
                    continue
                if not all(math.isfinite(value) for value in (teeth, close, ao)):
                    opposite_structure.append(False)
                elif direction == "LONG":
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
                    reason="SYSTEM_OVERLAY_TWO_BAR_STRUCTURAL_REVERSAL",
                )
                return {**result, "management_signal": "OPTIONAL_TWO_BAR_EXIT_OVERLAY"}

        try:
            rules = self._rules(symbol)
            tick_size = float((rules.get("PRICE_FILTER") or {}).get("tickSize", "0") or 0.0)
        except Exception as exc:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": f"exchange tick-size metadata unavailable for TC2 trailing: {exc}",
            }
        if not math.isfinite(tick_size) or tick_size <= 0:
            return {"symbol": symbol, "action": "HOLD_PROTECTION", "reason": "exchange tick size invalid"}

        rows = indicators.tail(trail_bars)
        try:
            proposal = tc2_price_bar_trailing_candidate(
                direction=direction,
                lows=rows["low"].tolist(),
                highs=rows["high"].tolist(),
                tick_size=tick_size,
                trailing_bars=trail_bars,
            )
            structural_extreme = float(proposal["structural_extreme"])
            raw_stop = float(proposal["raw_stop_price"])
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": f"TC2 structural trail data invalid: {exc}",
            }

        try:
            proposed = float(
                self.client.normalize_price(
                    symbol, raw_stop, direction=direction, purpose="STOP"
                )
            )
            mark = float(self._market_mark(symbol))
        except Exception as exc:
            return {
                "symbol": symbol,
                "action": "HOLD_PROTECTION",
                "reason": f"TC2 structural stop normalization/mark unavailable: {exc}",
            }
        if not math.isfinite(proposed) or proposed <= 0 or not math.isfinite(mark) or mark <= 0:
            return {"symbol": symbol, "action": "HOLD_PROTECTION", "reason": "TC2 structural stop or mark price invalid"}

        old_stop = float(campaign.current_stop_price or campaign.initial_stop_price or 0.0)
        if direction == "LONG":
            safe = proposed < mark
            tighter = old_stop <= 0 or proposed > old_stop
        else:
            safe = proposed > mark
            tighter = old_stop <= 0 or proposed < old_stop

        if not safe or not tighter:
            return {
                "symbol": symbol,
                "direction": direction,
                "action": "HOLD_PROTECTION",
                "trailing_bars": trail_bars,
                "structural_extreme": structural_extreme,
                "proposed_stop": proposed,
                "current_stop": old_stop,
                "mark_price": mark,
                "reason": "no safe, strictly tighter TC2 price-bar stop",
            }

        try:
            result = self.replace_protection(campaign, stop_price=proposed)
        except Exception as exc:
            reason = f"TC2 structural stop replacement failed: {type(exc).__name__}: {exc}"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": reason}

        if campaign.state == CampaignState.OPEN_INITIAL:
            campaign.transition(CampaignState.TREND_ACTIVE, reason="TC2 price-bar structure supports trailing")
        if campaign.state == CampaignState.TREND_ACTIVE:
            campaign.transition(CampaignState.TRAILING, reason="TC2 price-bar stop tightened")
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.STOP_MOVED.value,
            reason=f"{direction} TC2 {trail_bars}-bar structural trailing stop advanced",
            payload={
                "direction": direction,
                "mark_price": mark,
                "trailing_bars": trail_bars,
                "structural_extreme": structural_extreme,
                "new_stop": result.get("stop_price", proposed),
                "tick_size": tick_size,
                "source_profile": "TC2_THREE_WISE_MEN",
            },
        )
        return {
            "symbol": symbol,
            "direction": direction,
            "action": "TRAILING_STOP_MOVED",
            "trailing_bars": trail_bars,
            "source_profile": "TC2_THREE_WISE_MEN",
            **result,
        }

    def _manage_legacy_campaign(self, campaign, indicators, *, atr: float) -> dict[str, Any]:
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

    def _reconcile_pending_initial_entry(
        self,
        campaign,
        position: dict[str, Any],
        amount: float,
    ) -> dict[str, Any]:
        """Reconcile a conditional entry from stable algo ID, child order and userTrades."""
        symbol = campaign.symbol.upper()
        direction = self._campaign_direction(campaign)
        client_id = str(campaign.tags.get("entry_client_algo_id", "") or "")
        if not client_id:
            reason = f"{symbol}: pending initial entry has no durable clientAlgoId"
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}

        def unresolved(reason: str) -> dict[str, Any]:
            campaign.tags["entry_fill_reconciliation_pending"] = True
            self.engine.mark_reconcile_required(campaign, reason)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
            self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}

        try:
            trigger = float(campaign.tags.get("entry_trigger_price", 0) or 0)
            expected_qty = float(campaign.tags.get("entry_quantity", 0) or 0)
            stop = float(campaign.tags.get("initial_stop_price", campaign.initial_stop_price) or 0)
            if not all(math.isfinite(value) and value > 0 for value in (trigger, expected_qty, stop)):
                return unresolved(f"{symbol}: durable initial entry trigger/quantity/stop is invalid")

            # When a live position exists, protection takes priority over
            # waiting for history endpoints. An existing stop is queried by its
            # stable ID; a timeout never triggers a blind duplicate submission.
            if abs(amount) > 1e-12:
                if (direction == "LONG" and amount < 0) or (direction == "SHORT" and amount > 0):
                    return unresolved(f"{symbol}: live position direction conflicts with pending entry")
                protective_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
                protective_algo_id = campaign.tags.get("protective_algo_id")
                if protective_client_id or protective_algo_id:
                    protection = self.client.get_algo_order(
                        symbol,
                        algo_id=protective_algo_id or None,
                        client_algo_id=protective_client_id or None,
                    )
                    pstatus = str(protection.get("algoStatus", "") or "").upper()
                    if pstatus in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
                        expected_side = "SELL" if direction == "LONG" else "BUY"
                        actual_trigger = float(protection.get("triggerPrice"))
                        expected_trigger = float(campaign.current_stop_price or stop)
                        if (
                            str(protection.get("clientAlgoId", "") or "") != protective_client_id
                            or str(protection.get("side", "") or "").upper() != expected_side
                            or str(protection.get("orderType", protection.get("type", "")) or "").upper() != "STOP_MARKET"
                            or str(protection.get("closePosition", "")).lower() not in {"true", "1"}
                            or not math.isfinite(actual_trigger)
                            or not math.isclose(actual_trigger, expected_trigger, rel_tol=0.0, abs_tol=1e-8)
                        ):
                            return unresolved(f"{symbol}: existing protective stop identity/side/trigger is inconsistent")
                    elif pstatus in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                        self.place_protection(campaign, stop_price=stop)
                    else:
                        return unresolved(
                            f"{symbol}: existing protective stop status is ambiguous ({pstatus or 'UNKNOWN'})"
                        )
                else:
                    self.place_protection(campaign, stop_price=stop)

            algo = self.client.get_algo_order(symbol, client_algo_id=client_id)
            algo_status = str(algo.get("algoStatus", "") or "").upper()
            expected_side = "BUY" if direction == "LONG" else "SELL"
            algo_side = str(algo.get("side", "") or "").upper()
            algo_type = str(algo.get("orderType", algo.get("type", "")) or "").upper()
            algo_client_id = str(algo.get("clientAlgoId", "") or "")
            try:
                algo_trigger = float(algo.get("triggerPrice"))
                algo_qty = float(algo.get("quantity"))
            except (TypeError, ValueError):
                algo_trigger = algo_qty = float("nan")
            if (
                algo_client_id != client_id
                or algo_side != expected_side
                or algo_type != "STOP_MARKET"
                or str(algo.get("closePosition", "")).lower() not in {"false", "0"}
                or not math.isfinite(algo_trigger)
                or not math.isclose(algo_trigger, trigger, rel_tol=0.0, abs_tol=1e-8)
                or not math.isfinite(algo_qty)
                or not math.isclose(algo_qty, expected_qty, rel_tol=0.0, abs_tol=1e-8)
            ):
                return unresolved(f"{symbol}: initial entry algo does not match the durable entry intent")

            active_statuses = {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
            terminal_no_fill = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}
            actual_order_id = algo.get("actualOrderId")
            expires_at_ms = int(campaign.tags.get("entry_expires_at_ms", 0) or 0)
            if algo_status in active_statuses and (expires_at_ms <= 0 or int(time.time() * 1000) >= expires_at_ms):
                # Exchange-side stop entries do not inherit the local SignalSpec
                # expiry. Cancel an expired armed order before it can create a
                # stale position; cancellation is verified through the normal
                # durable client-ID reconciliation path.
                return self.cancel_pending_entry(campaign, reason="SIGNAL_EXPIRED")
            if algo_status in active_statuses and not actual_order_id:
                if abs(amount) <= 1e-12 and campaign.state == CampaignState.ENTRY_PENDING:
                    return {"symbol": symbol, "state": "ENTRY_PENDING", "algo_status": algo_status}
                # A live position with an apparently untriggered entry, or a
                # RECONCILE_REQUIRED campaign with a still-live entry, must not
                # leave another exposure-increasing trigger armed.
                self._cancel_algo_via_barrier(
                    campaign, symbol, expected_side,
                    algo_id=algo.get("algoId") or campaign.tags.get("pending_algo_id") or None,
                    client_algo_id=client_id,
                    purpose="CAMPAIGN_ENTRY_CANCEL_RECOVERY",
                )
                algo = self.client.get_algo_order(symbol, client_algo_id=client_id)
                algo_status = str(algo.get("algoStatus", "") or "").upper()
                actual_order_id = algo.get("actualOrderId")
                if algo_status in active_statuses and not actual_order_id:
                    return unresolved(
                        f"{symbol}: pending entry remains active after cancellation/re-query"
                    )
            if not actual_order_id:
                if abs(amount) <= 1e-12 and algo_status in terminal_no_fill:
                    campaign.state = CampaignState.CLOSED
                    campaign.next_action = "WAIT"
                    campaign.pending_risk_quote = 0.0
                    campaign.capital_reserved_quote = 0.0
                    campaign.tags.pop("entry_fill_reconciliation_pending", None)
                    self.db.save_campaign(campaign)
                    self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
                    self.db.state_delete(f"futures_entry_pending:{symbol}")
                    self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
                    self.db.state_set(f"position_state:{symbol}", "FLAT")
                    return {"symbol": symbol, "state": "CLOSED", "algo_status": algo_status}
                return unresolved(
                    f"{symbol}: initial entry algo has no child order ID and cannot prove fill/no-fill"
                )

            actual_order = self.client.get_order(symbol, order_id=actual_order_id)
            order_status = str(actual_order.get("status", "") or "").upper()
            if (
                str(actual_order.get("symbol", symbol)).upper() != symbol
                or str(actual_order.get("side", "") or "").upper() != expected_side
                or str(actual_order.get("type", "") or "").upper() != "MARKET"
                or str(actual_order.get("orderId", "")) != str(actual_order_id)
            ):
                return unresolved(f"{symbol}: triggered initial order identity/side/type mismatch")
            try:
                executed = float(actual_order.get("executedQty", 0) or 0)
            except (TypeError, ValueError):
                executed = float("nan")
            if not math.isfinite(executed) or executed < 0:
                return unresolved(f"{symbol}: triggered initial order has invalid executedQty")

            if order_status in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                self._cancel_child_order_via_barrier(
                    campaign, symbol, expected_side, order_id=actual_order_id,
                    purpose="CAMPAIGN_ENTRY_CHILD_CANCEL_RECOVERY",
                )
                actual_order = self.client.get_order(symbol, order_id=actual_order_id)
                order_status = str(actual_order.get("status", "") or "").upper()
                try:
                    executed = float(actual_order.get("executedQty", 0) or 0)
                except (TypeError, ValueError):
                    executed = float("nan")
                if not math.isfinite(executed) or executed < 0:
                    return unresolved(f"{symbol}: initial executedQty invalid after cancel")
            if order_status in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                return unresolved(f"{symbol}: initial child order remains nonterminal after cancellation")
            if order_status not in terminal_no_fill | {"FILLED"}:
                return unresolved(f"{symbol}: initial child order status is ambiguous ({order_status or 'UNKNOWN'})")

            if executed <= 0:
                if abs(amount) > 1e-12:
                    return unresolved(f"{symbol}: live position exists but the entry child order has no fills")
                if order_status not in terminal_no_fill:
                    return unresolved(f"{symbol}: no fill confirmed but child order is not terminal")
                campaign.state = CampaignState.CLOSED
                campaign.next_action = "WAIT"
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                campaign.tags.pop("entry_fill_reconciliation_pending", None)
                self.db.save_campaign(campaign)
                self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
                self.db.state_delete(f"futures_entry_pending:{symbol}")
                self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
                self.db.state_set(f"position_state:{symbol}", "FLAT")
                return {"symbol": symbol, "state": "CLOSED", "algo_status": algo_status}

            if order_status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                return unresolved(f"{symbol}: initial partial fill is not terminal")
            tolerance = max(1e-8, executed * 1e-6)
            if abs(abs(amount) - executed) > tolerance:
                return unresolved(
                    f"{symbol}: initial fill/position mismatch; child={executed}, exchange={abs(amount)}"
                )
            try:
                average_fill = float(actual_order.get("avgPrice", 0) or 0)
                if average_fill <= 0:
                    cumulative_quote = float(
                        actual_order.get("cumQuote", actual_order.get("cumQuoteQty", 0)) or 0
                    )
                    average_fill = cumulative_quote / executed if cumulative_quote > 0 else 0.0
                exchange_entry = float(position.get("entryPrice", 0) or 0)
            except (TypeError, ValueError):
                average_fill = exchange_entry = 0.0
            if not all(math.isfinite(value) and value > 0 for value in (average_fill, exchange_entry)):
                return unresolved(f"{symbol}: initial fill/position entry price is invalid")
            if not math.isclose(average_fill, exchange_entry, rel_tol=1e-5, abs_tol=1e-8):
                return unresolved(
                    f"{symbol}: child average fill {average_fill} disagrees with position entry {exchange_entry}"
                )

            trades = self.client.user_trades(symbol, order_id=actual_order_id, limit=1000)
            if not trades:
                return unresolved(f"{symbol}: initial fill confirmed but userTrades are not yet available")
            trade_qty = 0.0
            trade_quote = 0.0
            fee_quote = 0.0
            fee_by_asset: dict[str, float] = {}
            for trade in trades:
                self._validate_user_trade_row(trade, symbol, "initial entry", require_realized_pnl=False)
                if trade.get("orderId") is not None and str(trade.get("orderId")) != str(actual_order_id):
                    return unresolved(f"{symbol}: initial userTrades contain a different order ID")
                try:
                    qty = float(trade.get("qty", 0) or 0)
                    price = float(trade.get("price", 0) or 0)
                    commission = float(trade.get("commission", 0) or 0)
                except (TypeError, ValueError):
                    return unresolved(f"{symbol}: initial userTrades contain invalid numeric values")
                if not all(math.isfinite(value) for value in (qty, price, commission)) or qty <= 0 or price <= 0 or commission < 0:
                    return unresolved(f"{symbol}: initial userTrades contain non-finite values")
                trade_qty += qty
                trade_quote += qty * price
                asset = str(trade.get("commissionAsset", "") or "").upper()
                if commission > 0 and not asset:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: userTrades commission has no asset"
                    )
                if asset == "USDT":
                    fee_quote += commission
                elif asset:
                    fee_by_asset[asset] = fee_by_asset.get(asset, 0.0) + commission
            if abs(trade_qty - executed) > max(1e-8, executed * 1e-6):
                return unresolved(f"{symbol}: initial userTrades quantity disagrees with child order")
            trade_average = trade_quote / trade_qty
            if not math.isclose(trade_average, average_fill, rel_tol=1e-5, abs_tol=1e-8):
                return unresolved(f"{symbol}: userTrades average price disagrees with child order")

            actual_risk = self._actual_risk_quote(executed, average_fill, stop)
            reserved_risk = float(campaign.pending_risk_quote or 0)
            if (
                not math.isfinite(actual_risk)
                or actual_risk <= 0
                or not math.isfinite(reserved_risk)
                or reserved_risk <= 0
                or actual_risk > reserved_risk + max(1e-8, reserved_risk * 1e-9)
            ):
                return unresolved(f"{symbol}: actual initial fill risk exceeds durable reservation")

            if campaign.state == CampaignState.RECONCILE_REQUIRED:
                campaign.state = CampaignState.ENTRY_PENDING
            self.engine.mark_triggered(campaign, campaign.current_signal_id, str(actual_order_id))
            self.engine.record_initial_fill(
                campaign,
                quantity=executed,
                average_entry_price=average_fill,
                initial_stop_price=stop,
                fill_order_id=str(actual_order_id),
                risk_quote=actual_risk,
                fee_quote=fee_quote,
            )
            campaign.tags["entry_fee_by_asset"] = fee_by_asset
            campaign.tags.pop("entry_fill_reconciliation_pending", None)
            campaign.tags["entry_actual_order_id"] = str(actual_order_id)
            self.db.save_campaign(campaign)
            self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.FILLED.value)
            self.db.state_delete(f"futures_entry_pending:{symbol}")
            self.db.state_set(f"position_state:{symbol}", CampaignState.OPEN_INITIAL.value)
            self.db.state_delete(f"campaign_state:{campaign.campaign_id}")
            return {
                "symbol": symbol,
                "state": CampaignState.OPEN_INITIAL.value,
                "direction": direction,
                "position_qty": executed,
                "average_entry_price": average_fill,
                "entry_order_id": str(actual_order_id),
                "protection": "CONFIRMED",
                "partial_entry": order_status != "FILLED",
            }
        except Exception as exc:
            return unresolved(
                f"{symbol}: initial entry reconciliation failed: {type(exc).__name__}: {exc}"
            )

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
            # A zero position does not prove that the symbol is safe when no
            # durable campaign owns its exchange orders. A leftover conditional
            # entry can create exposure after this scan, so require manual/order
            # reconciliation rather than reporting a false FLAT state.
            try:
                standard_orders = self.client.open_orders(symbol)
                algo_orders = self.client.open_algo_orders(symbol)
                if not isinstance(standard_orders, list) or not isinstance(algo_orders, list):
                    raise FuturesCampaignExecutionError(
                        "exchange open-order endpoints returned malformed responses"
                    )
                if standard_orders or algo_orders:
                    self.db.state_set(
                        f"position_state:{symbol}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    return {
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "reason": (
                            "flat exchange position has live standard/Algo orders but no "
                            "matching managed campaign"
                        ),
                        "open_standard_orders": len(standard_orders),
                        "open_algo_orders": len(algo_orders),
                    }
            except Exception as exc:
                self.db.state_set(
                    f"position_state:{symbol}",
                    CampaignState.RECONCILE_REQUIRED.value,
                )
                return {
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": (
                        "cannot confirm that a flat symbol with no campaign is free of "
                        f"open exchange orders: {type(exc).__name__}: {exc}"
                    ),
                }
            self.db.state_set(f"position_state:{symbol}", "FLAT")
            return {"symbol": symbol, "state": "FLAT"}

        direction = self._campaign_direction(campaign)
        entry_client_algo_id = str(campaign.tags.get("entry_client_algo_id", "") or "")
        if campaign.state == CampaignState.ENTRY_PENDING or (
            campaign.state == CampaignState.RECONCILE_REQUIRED
            and campaign.tags.get("entry_fill_reconciliation_pending")
        ):
            return self._reconcile_pending_initial_entry(campaign, position, amount)
        pending_exit_id = str(campaign.tags.get("pending_exit_client_order_id", "") or "")
        if (
            pending_exit_id
            and not campaign.tags.get("pending_add_on_client_algo_id")
            and campaign.state not in {
                CampaignState.ADD_ON_ARMING,
                CampaignState.ADD_ON_PENDING,
                CampaignState.POSITION_EXPANDING,
            }
        ):
            return self.exit_position(
                campaign,
                reason=str(campaign.tags.get("pending_exit_reason", "RECOVERY_PENDING_EXIT") or "RECOVERY_PENDING_EXIT"),
            )
        if abs(amount) <= 1e-12:
            try:
                protective_result = self._reconcile_flat_position_protection(campaign)
                if protective_result is not None:
                    return protective_result
            except Exception as exc:
                reason = (
                    f"{symbol}: flat-position protective-order reconciliation failed: "
                    f"{type(exc).__name__}: {exc}"
                )
                self.engine.mark_reconcile_required(campaign, reason)
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                self.db.log_event(
                    "ERROR",
                    "futures_flat_protection_reconciliation_failed",
                    reason,
                    {"campaign_id": campaign.campaign_id},
                )
                return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}

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

        if campaign.position_qty > 0:
            # If stop replacement was interrupted after the new stop was
            # created but before the old stop was confirmed cancelled, reconcile
            # both stable IDs before doing any other campaign mutation.
            if campaign.tags.get("protection_replace_reconcile_required"):
                try:
                    pending_stop_client_id = str(
                        campaign.tags.get("pending_protective_client_algo_id", "") or ""
                    )
                    old_client_id = str(campaign.tags.get("previous_protective_client_algo_id", "") or "")
                    old_algo_id = campaign.tags.get("previous_protective_algo_id")
                    current_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
                    target_stop_raw = (
                        campaign.tags.get("protection_replace_target_stop_price")
                        or campaign.tags.get("pending_protective_stop_price")
                    )
                    if pending_stop_client_id or not current_client_id or current_client_id == old_client_id:
                        if target_stop_raw is None:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: replacement target stop was not durably recorded; "
                                "preserving the prior protective order"
                            )
                        self.place_protection(campaign, stop_price=float(target_stop_raw))
                    new_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
                    new_algo_id = campaign.tags.get("protective_algo_id")
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
                    active_statuses = {"NEW", "WORKING", "PENDING", "PENDING_NEW"}
                    terminal_statuses = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED", "FINISHED", "TRIGGERED"}
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
                        expected_new_side = "SELL" if direction == "LONG" else "BUY"
                        try:
                            new_trigger = float(new_order.get("triggerPrice"))
                            expected_new_trigger = float(campaign.current_stop_price or campaign.initial_stop_price or 0)
                        except (TypeError, ValueError):
                            new_trigger = expected_new_trigger = float("nan")
                        if (
                            str(new_order.get("clientAlgoId", "") or "") != new_client_id
                            or str(new_order.get("side", "") or "").upper() != expected_new_side
                            or str(new_order.get("orderType", new_order.get("type", "")) or "").upper() != "STOP_MARKET"
                            or str(new_order.get("closePosition", "")).lower() not in {"true", "1"}
                            or not math.isfinite(new_trigger)
                            or not math.isfinite(expected_new_trigger)
                            or not math.isclose(new_trigger, expected_new_trigger, rel_tol=0.0, abs_tol=1e-8)
                        ):
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: replacement stop identity/side/type/trigger mismatch; "
                                "old protection must not be cancelled"
                            )
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
                            verified_old = self.client.get_algo_order(
                                symbol,
                                algo_id=old_algo_id or None,
                                client_algo_id=old_client_id or None,
                            )
                            verified_old_status = str(verified_old.get("algoStatus", "") or "").upper()
                            if verified_old_status not in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                                raise FuturesCampaignExecutionError(
                                    f"{symbol}: prior stop remains nonterminal after cancellation re-query "
                                    f"({verified_old_status or 'UNKNOWN'})"
                                )
                        # The new stop is authoritative and active.
                    elif old_status in active_statuses:
                        if new_status in {"TRIGGERED", "FINISHED"}:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: replacement stop triggered while prior stop is still active; "
                                "child fill reconciliation is required before clearing either identity"
                            )
                        # New stop is terminal without a trigger, but old
                        # protection is still live. Restore the old stop.
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
                    campaign.tags.pop("protection_replace_target_stop_price", None)
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
                except Exception as exc:
                    reason = (
                        f"{symbol}: existing protective stop lookup is unavailable; "
                        f"refusing to submit a duplicate stop: {type(exc).__name__}: {exc}"
                    )
                    self.engine.mark_reconcile_required(campaign, reason)
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                    return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}

                status = str(protection.get("algoStatus", "")).upper()
                if status in {"TRIGGERED", "FINISHED"}:
                    # A triggered/finished closePosition stop may have only
                    # partially closed the live position. Do not silently arm
                    # another stop and lose the old child's fill history. Force
                    # a reduce-only residual exit; its durable order and trade
                    # history must reconcile before the campaign can close.
                    reason = (
                        f"{symbol}: protective stop is {status} while exchange position remains open; "
                        "residual position requires emergency reduction and fill reconciliation"
                    )
                    self.engine.mark_reconcile_required(campaign, reason)
                    self.db.state_set(
                        f"campaign_state:{campaign.campaign_id}",
                        CampaignState.RECONCILE_REQUIRED.value,
                    )
                    self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                    return self.exit_position(
                        campaign,
                        reason="PROTECTIVE_STOP_TRIGGERED_WITH_RESIDUAL_POSITION",
                    )
                if status not in {"NEW", "WORKING", "PENDING", "PENDING_NEW"}:
                    try:
                        self.place_protection(campaign)
                    except Exception as protect_exc:
                        reason = (
                            f"live position lacks confirmed protection: prior status={status or 'UNKNOWN'}; "
                            f"replacement submit={type(protect_exc).__name__}: {protect_exc}"
                        )
                        self.engine.mark_reconcile_required(campaign, reason)
                        self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                        self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                        return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "protective stop not confirmed"}
                else:
                    expected_side = "SELL" if direction == "LONG" else "BUY"
                    actual_side = str(protection.get("side", "") or "").upper()
                    actual_type = str(protection.get("orderType", protection.get("type", "")) or "").upper()
                    close_position = str(protection.get("closePosition", "")).lower() in {"true", "1"}
                    actual_client_id = str(protection.get("clientAlgoId", "") or "")
                    expected_client_id = str(campaign.tags.get("protective_client_algo_id", "") or "")
                    try:
                        actual_trigger = float(protection.get("triggerPrice"))
                        expected_trigger = float(
                            campaign.current_stop_price or campaign.initial_stop_price or 0
                        )
                    except (TypeError, ValueError):
                        actual_trigger = expected_trigger = float("nan")
                    if (
                        actual_side != expected_side
                        or actual_type != "STOP_MARKET"
                        or not close_position
                        or not actual_client_id
                        or actual_client_id != expected_client_id
                        or not math.isfinite(actual_trigger)
                        or not math.isfinite(expected_trigger)
                        or expected_trigger <= 0
                        or not math.isclose(actual_trigger, expected_trigger, rel_tol=0.0, abs_tol=1e-8)
                    ):
                        reason = (
                            f"{symbol}: active protective stop identity/side/type/closePosition/trigger mismatch; "
                            "refusing to create another stop until the existing order is reconciled"
                        )
                        self.engine.mark_reconcile_required(campaign, reason)
                        self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                        self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                        return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": reason}
            else:
                try:
                    self.place_protection(campaign)
                except Exception as exc:
                    self.engine.mark_reconcile_required(campaign, f"live position has no protective stop: {exc}")
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.RECONCILE_REQUIRED.value)
                    self.db.state_set(f"position_state:{symbol}", CampaignState.RECONCILE_REQUIRED.value)
                    return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "missing protection"}
            if campaign.state in {
                CampaignState.ADD_ON_ARMING,
                CampaignState.ADD_ON_PENDING,
                CampaignState.POSITION_EXPANDING,
            } or campaign.tags.get("pending_add_on_client_algo_id"):
                client_add_id = str(campaign.tags.get("pending_add_on_client_algo_id", "") or "")
                original_qty = float(campaign.tags.get("pending_add_on_original_qty", 0) or 0)
                trigger_price = float(campaign.tags.get("pending_add_on_trigger_price", 0) or 0)
                stop_price = float(campaign.tags.get("pending_add_on_stop_price", 0) or 0)
                if not client_add_id or not all(
                    math.isfinite(value) and value > 0
                    for value in (original_qty, trigger_price, stop_price)
                ):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: durable add-on intent is missing stable ID/quantity/price data"
                    )

                algo = self.client.get_algo_order(symbol, client_algo_id=client_add_id)
                algo_status = str(algo.get("algoStatus", "") or "").upper()
                algo_client_id = str(algo.get("clientAlgoId", "") or "")
                algo_side = str(algo.get("side", "") or "").upper()
                algo_type = str(algo.get("orderType", algo.get("type", "")) or "").upper()
                algo_close_position_raw = str(algo.get("closePosition", "")).lower()
                try:
                    algo_trigger = float(algo.get("triggerPrice"))
                    algo_quantity = float(algo.get("quantity"))
                    expected_add_quantity = float(campaign.tags.get("pending_add_on_quantity", 0) or 0)
                except (TypeError, ValueError):
                    algo_trigger = algo_quantity = expected_add_quantity = float("nan")
                expected_add_side = "BUY" if direction == "LONG" else "SELL"
                if (
                    algo_client_id != client_add_id
                    or algo_side != expected_add_side
                    or algo_type != "STOP_MARKET"
                    or algo_close_position_raw not in {"false", "0"}
                    or not math.isfinite(algo_trigger)
                    or not math.isclose(algo_trigger, trigger_price, rel_tol=0.0, abs_tol=1e-8)
                    or not math.isfinite(algo_quantity)
                    or not math.isfinite(expected_add_quantity)
                    or expected_add_quantity <= 0
                    or not math.isclose(algo_quantity, expected_add_quantity, rel_tol=0.0, abs_tol=1e-8)
                ):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on algo identity/side/type/quantity/trigger does not match durable intent"
                    )
                active_add_statuses = {"NEW", "WORKING", "PENDING_NEW", "PENDING"}
                terminal_add_statuses = {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}
                actual_order_id = algo.get("actualOrderId")
                add_on_expires_at_ms = int(campaign.tags.get("pending_add_on_expires_at_ms", 0) or 0)
                if algo_status in active_add_statuses and (add_on_expires_at_ms <= 0 or int(time.time() * 1000) >= add_on_expires_at_ms):
                    return self.cancel_pending_add_on(campaign, reason="SIGNAL_EXPIRED")
                if algo_status in active_add_statuses and not actual_order_id:
                    if campaign.state in {
                        CampaignState.RECONCILE_REQUIRED,
                        CampaignState.POSITION_EXPANDING,
                    }:
                        self._cancel_algo_via_barrier(
                            campaign, symbol, expected_add_side,
                            algo_id=algo.get("algoId") or campaign.tags.get("pending_add_on_algo_id") or None,
                            client_algo_id=client_add_id,
                            purpose="CAMPAIGN_ADD_ON_CANCEL_RECOVERY",
                        )
                        algo = self.client.get_algo_order(symbol, client_algo_id=client_add_id)
                        algo_status = str(algo.get("algoStatus", "") or "").upper()
                        actual_order_id = algo.get("actualOrderId")
                        if algo_status in active_add_statuses and not actual_order_id:
                            reason = (
                                f"{symbol}: add-on remains active after cancellation/re-query; "
                                "exposure-increasing order remains unresolved"
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
                                "protection": "CONFIRMED",
                            }
                    else:
                        if abs(abs(amount) - original_qty) > max(1e-8, original_qty * 1e-6):
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: exchange quantity changed while add-on algo remains active"
                            )
                        if campaign.state == CampaignState.ADD_ON_ARMING:
                            campaign.transition(
                                CampaignState.ADD_ON_PENDING,
                                reason="recovered active add-on order by stable clientAlgoId",
                            )
                            self.db.save_campaign(campaign)
                            self.db.state_set(
                                f"campaign_state:{campaign.campaign_id}",
                                CampaignState.ADD_ON_PENDING.value,
                            )
                        return {
                            "symbol": symbol,
                            "state": "ADD_ON_PENDING",
                            "algo_status": algo_status,
                            "client_algo_id": client_add_id,
                            "position_qty": abs(amount),
                            "protection": "CONFIRMED",
                        }
                actual_order: dict[str, Any] = {}
                executed = 0.0
                average_fill = 0.0
                order_status = ""
                if actual_order_id:
                    actual_order = self.client.get_order(symbol, order_id=actual_order_id)
                    order_status = str(actual_order.get("status", "") or "").upper()
                    if (
                        str(actual_order.get("symbol", symbol)).upper() != symbol
                        or str(actual_order.get("orderId", "")) != str(actual_order_id)
                        or str(actual_order.get("side", "") or "").upper() != expected_add_side
                        or str(actual_order.get("type", "") or "").upper() != "MARKET"
                    ):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: triggered add-on actual order identity/side/type mismatch"
                        )
                    try:
                        executed = float(actual_order.get("executedQty", 0) or 0)
                    except (TypeError, ValueError):
                        executed = float("nan")
                    if not math.isfinite(executed) or executed < 0:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on actual order has invalid executedQty"
                        )

                    if order_status in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                        # Do not leave a residual child order capable of
                        # expanding risk after the local campaign has moved on.
                        self._cancel_child_order_via_barrier(
                            campaign, symbol, expected_add_side, order_id=actual_order_id,
                            purpose="CAMPAIGN_ADD_ON_CHILD_CANCEL_RECOVERY",
                        )
                        actual_order = self.client.get_order(symbol, order_id=actual_order_id)
                        order_status = str(actual_order.get("status", "") or "").upper()
                        if (
                            str(actual_order.get("symbol", symbol)).upper() != symbol
                            or str(actual_order.get("orderId", "")) != str(actual_order_id)
                            or str(actual_order.get("side", "") or "").upper() != expected_add_side
                            or str(actual_order.get("type", "") or "").upper() != "MARKET"
                        ):
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: post-cancel add-on actual order identity/side/type mismatch"
                            )
                        try:
                            executed = float(actual_order.get("executedQty", 0) or 0)
                        except (TypeError, ValueError):
                            executed = float("nan")
                        if not math.isfinite(executed) or executed < 0:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: add-on executedQty invalid after remainder cancellation"
                            )

                    if order_status in {"NEW", "PARTIALLY_FILLED", "PENDING_NEW"}:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on child order remains active after cancellation attempt; "
                            "exposure can still increase and must remain locked"
                        )

                    if order_status not in terminal_add_statuses | {"FILLED"}:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on actual order status is ambiguous ({order_status or 'UNKNOWN'})"
                        )
                    if executed > 0:
                        try:
                            average_fill = float(actual_order.get("avgPrice", 0) or 0)
                        except (TypeError, ValueError):
                            average_fill = 0.0
                        if average_fill <= 0:
                            try:
                                cumulative_quote = float(
                                    actual_order.get("cumQuote", actual_order.get("cumQuoteQty", 0)) or 0
                                )
                            except (TypeError, ValueError):
                                cumulative_quote = 0.0
                            if math.isfinite(cumulative_quote) and cumulative_quote > 0:
                                average_fill = cumulative_quote / executed
                        if not math.isfinite(average_fill) or average_fill <= 0:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: add-on fill has no authoritative average price"
                            )
                elif algo_status not in terminal_add_statuses:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on algo status is unresolved ({algo_status or 'UNKNOWN'})"
                    )

                if executed <= 0:
                    if abs(abs(amount) - original_qty) > max(1e-8, original_qty * 1e-6):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on is unfilled but exchange quantity differs from its baseline"
                        )
                    if actual_order_id:
                        if order_status not in terminal_add_statuses:
                            raise FuturesCampaignExecutionError(
                                f"{symbol}: add-on child order is not terminal despite zero execution"
                            )
                    elif algo_status not in terminal_add_statuses:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on has no actual order and is not authoritatively terminal"
                        )
                    campaign.pending_risk_quote = 0.0
                    campaign.capital_reserved_quote = 0.0
                    campaign.transition(
                        CampaignState.TREND_ACTIVE,
                        reason=f"add-on ended without a fill ({algo_status})",
                    )
                    campaign.tags["last_add_on_terminal_status"] = algo_status
                    for key in (
                        "pending_add_on_client_algo_id", "pending_add_on_algo_id",
                        "pending_add_on_trigger_price", "pending_add_on_stop_price",
                        "pending_add_on_quantity", "pending_add_on_risk_quote", "pending_add_on_expires_at_ms",
                        "pending_add_on_original_qty", "pending_add_on_original_entry",
                        "pending_add_on_direction",
                    ):
                        campaign.tags.pop(key, None)
                    self.db.save_campaign(campaign)
                    self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
                    self.db.state_delete(f"futures_entry_pending:{symbol}")
                    self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.TREND_ACTIVE.value)
                    self.db.state_set(f"position_state:{symbol}", CampaignState.TREND_ACTIVE.value)
                    return {
                        "symbol": symbol,
                        "state": CampaignState.TREND_ACTIVE.value,
                        "action": "ADD_ON_TERMINAL_UNFILLED",
                        "algo_status": algo_status,
                    }

                expected_qty = original_qty + executed
                if abs(abs(amount) - expected_qty) > max(1e-8, expected_qty * 1e-6):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on fill/position mismatch; expected={expected_qty}, exchange={abs(amount)}"
                    )
                if order_status not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: partial add-on fill is not terminal after cancellation"
                    )
                fill_risk = self._actual_risk_quote(executed, average_fill, stop_price)
                if not math.isfinite(fill_risk) or fill_risk <= 0:
                    raise FuturesCampaignExecutionError(f"{symbol}: actual add-on fill risk is invalid")
                fee_quote = 0.0
                fee_by_asset: dict[str, float] = {}
                trade_qty = 0.0
                trade_quote = 0.0
                trades = self.client.user_trades(symbol, order_id=actual_order_id, limit=1000)
                if not trades:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on fill is confirmed but authoritative userTrades are not yet available"
                    )
                for trade in trades:
                    self._validate_user_trade_row(trade, symbol, "add-on entry", require_realized_pnl=False)
                    if trade.get("orderId") is not None and str(trade.get("orderId")) != str(actual_order_id):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on userTrades contain a different orderId"
                        )
                    try:
                        trade_quantity = float(trade.get("qty", 0) or 0)
                        trade_price = float(trade.get("price", 0) or 0)
                        commission = float(trade.get("commission", 0) or 0)
                    except (TypeError, ValueError) as exc:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on userTrades contain invalid numeric values"
                        ) from exc
                    if (
                        not all(math.isfinite(value) for value in (trade_quantity, trade_price, commission))
                        or trade_quantity <= 0
                        or trade_price <= 0
                        or commission < 0
                    ):
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: add-on userTrades contain non-finite/invalid values"
                        )
                    trade_qty += trade_quantity
                    trade_quote += trade_quantity * trade_price
                    asset = str(trade.get("commissionAsset", "") or "").upper()
                    if commission > 0 and not asset:
                        raise FuturesCampaignExecutionError(
                            f"{symbol}: userTrades commission has no asset"
                        )
                    if asset == "USDT":
                        fee_quote += commission
                    elif asset:
                        fee_by_asset[asset] = fee_by_asset.get(asset, 0.0) + commission
                if abs(trade_qty - executed) > max(1e-8, executed * 1e-6):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on userTrades quantity disagrees with triggered order"
                    )
                trade_average = trade_quote / trade_qty
                if not math.isclose(trade_average, average_fill, rel_tol=1e-5, abs_tol=1e-8):
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: add-on userTrades average price disagrees with child order"
                    )

                if campaign.state in {CampaignState.ADD_ON_ARMING, CampaignState.ADD_ON_PENDING}:
                    if campaign.state == CampaignState.ADD_ON_ARMING:
                        campaign.transition(CampaignState.ADD_ON_PENDING, reason="recovered durable add-on intent")
                    campaign.transition(CampaignState.POSITION_EXPANDING, reason="exchange confirms add-on execution")
                self.engine.record_add_on_fill(
                    campaign,
                    quantity=executed,
                    average_entry_price=average_fill,
                    fill_order_id=str(actual_order_id),
                    risk_quote=fill_risk,
                    fee_quote=fee_quote,
                )
                campaign.tags["last_add_on_exchange_order_id"] = str(actual_order_id)
                campaign.tags["last_add_on_fee_by_asset"] = fee_by_asset
                campaign.tags["last_add_on_order_status"] = order_status
                for key in (
                    "pending_add_on_client_algo_id", "pending_add_on_algo_id",
                    "pending_add_on_trigger_price", "pending_add_on_stop_price",
                    "pending_add_on_quantity", "pending_add_on_risk_quote", "pending_add_on_expires_at_ms",
                    "pending_add_on_original_qty", "pending_add_on_original_entry",
                    "pending_add_on_direction",
                ):
                    campaign.tags.pop(key, None)
                self.db.save_campaign(campaign)
                self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.FILLED.value)
                self.db.state_delete(f"futures_entry_pending:{symbol}")
                self.db.state_set(f"campaign_state:{campaign.campaign_id}", CampaignState.TREND_ACTIVE.value)
                self.db.state_set(f"position_state:{symbol}", CampaignState.TREND_ACTIVE.value)
                return {
                    "symbol": symbol,
                    "state": CampaignState.TREND_ACTIVE.value,
                    "action": "ADD_ON_FILLED",
                    "filled_quantity": executed,
                    "average_fill_price": average_fill,
                    "order_status": order_status,
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
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", campaign.state.value)
            if (
                not campaign.tags.get("entry_fill_reconciliation_pending")
                and not campaign.tags.get("pending_add_on_client_algo_id")
                and campaign.state not in {
                    CampaignState.ENTRY_PENDING,
                    CampaignState.ADD_ON_ARMING,
                    CampaignState.ADD_ON_PENDING,
                    CampaignState.POSITION_EXPANDING,
                }
            ):
                # Recover a stale claim left by a crash after campaign save.
                self.db.state_delete(f"futures_entry_pending:{symbol}")
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
