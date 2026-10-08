"""Canonical Digital Williams orchestration boundary.

This module bridges the existing SignalSpec/PendingSignal infrastructure to
the new immutable domain contracts without giving the Pure Williams layer any
access to Binance, persistence or order submission.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from campaign_model import SignalSpec, SignalType
from decision_trace import DecisionTrace
from domain.contracts import SignalDirection, WilliamsDecision
from pending_signal import PendingSignal, should_replace
from proof_engine import ProofEvaluation, WilliamsProofEngine, price_proof
from why_not_engine import WhyNotEngine


@dataclass(frozen=True)
class CoreComposition:
    """Backward-compatible composition result plus the canonical decision."""

    decision: WilliamsDecision | None
    signal: SignalSpec | None
    pending: PendingSignal | None
    trace: DecisionTrace | None
    action: str
    vetoes: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "action": self.action,
            "vetoes": list(self.vetoes),
            "decision": self.decision.to_dict() if self.decision else None,
            "signal": self.signal.to_dict() if self.signal else None,
            "pending_signal": self.pending.to_dict() if self.pending else None,
            "decision_trace": self.trace.to_dict() if self.trace else None,
        }


class DigitalWilliamsCore:
    """Single deterministic composition point for Williams evidence."""

    VERSION = "2.0.0"

    def __init__(self) -> None:
        # Pure Williams composition has no exchange/client dependency.
        pass

    @staticmethod
    def _wise_man_stage(signal: SignalSpec) -> int:
        return {
            SignalType.REVERSAL: 1,
            SignalType.SUPER_AO: 2,
            SignalType.FRACTAL: 3,
        }[signal.signal_type]

    @staticmethod
    def _direction(signal: SignalSpec) -> SignalDirection:
        side = str(signal.side).upper()
        if side == "BUY":
            return SignalDirection.LONG
        if side == "SELL":
            return SignalDirection.SHORT
        return SignalDirection.FLAT

    @staticmethod
    def _context_regime(signal: SignalSpec) -> str:
        if signal.alligator_bullish and signal.alligator_awake:
            return "BULLISH_AWAKE"
        if signal.alligator_bullish:
            return "BULLISH"
        if signal.htf_confirmed:
            return "HTF_CONFIRMED"
        return "UNKNOWN"

    def select_initial(
        self,
        signals: Iterable[SignalSpec],
        *,
        now_ms: int | None = None,
    ) -> SignalSpec | None:
        candidates = [
            s for s in signals
            if s.side == "BUY"
            and s.role.value == "ENTRY"
            and s.trigger_price > 0
        ]
        armable = []
        for signal in candidates:
            pending = PendingSignal.from_spec(signal)
            evaluation = WilliamsProofEngine.evaluate(signal)
            if pending.actionable(now_ms) and evaluation.armable:
                armable.append(signal)
        return min(
            armable,
            key=lambda s: (s.signal_bar_time_ms, s.created_at_ms),
            default=None,
        )

    def select_armable(
        self,
        signals: Iterable[SignalSpec],
        *,
        role: str,
        now_ms: int | None = None,
    ) -> SignalSpec | None:
        candidates = [
            s for s in signals
            if s.side == "BUY"
            and s.role.value == str(role)
            and s.trigger_price > 0
        ]
        armable = []
        for signal in candidates:
            pending = PendingSignal.from_spec(signal)
            evaluation = WilliamsProofEngine.evaluate(signal)
            if pending.actionable(now_ms) and evaluation.armable:
                armable.append(signal)
        return min(
            armable,
            key=lambda s: (s.signal_bar_time_ms, s.created_at_ms),
            default=None,
        )

    def evaluate_signal(
        self,
        signal: SignalSpec,
        *,
        campaign_id: str = "",
        now_ms: int | None = None,
    ) -> CoreComposition:
        pending = PendingSignal.from_spec(signal, campaign_id=campaign_id)
        actionable = pending.actionable(now_ms)
        evaluation = WilliamsProofEngine.evaluate(signal)
        proof = evaluation.proof_vector
        why_not = WhyNotEngine.explain_pre_price(
            proof,
            wise_man_stage=evaluation.wise_man_stage,
            pending_actionable=actionable,
        )
        trace = DecisionTrace.from_signal(
            signal,
            proof_vector=proof.to_dict(),
            why_not=why_not,
            system_versions={
                "digital_williams_core": self.VERSION,
                "proof_engine": "1.0.0",
                "why_not_engine": "1.0.0",
            },
        )

        invalidation = float(
            signal.invalidation_price
            or signal.protective_reference
            or 0.0
        )
        decision = WilliamsDecision(
            timestamp=int(signal.created_at_ms),
            symbol=signal.symbol,
            direction=self._direction(signal),
            wise_man_stage=self._wise_man_stage(signal),
            trigger_price=float(signal.trigger_price),
            invalidation_price=invalidation,
            proof_vector=proof,
            context_regime=self._context_regime(signal),
        )

        if not actionable or not evaluation.armable:
            vetoes = list(why_not)
            if not actionable and "pending_signal_expired_or_invalid" not in vetoes:
                vetoes.insert(0, "pending_signal_expired_or_invalid")
            for reason in vetoes:
                trace.veto(reason)
            action = "BLOCK"
        else:
            action = (
                "ARM_ADD_ON"
                if signal.role.value == "ADD_ON"
                else "ARM_ENTRY"
            )
        return CoreComposition(
            decision,
            signal,
            pending,
            trace,
            action,
            tuple(dict.fromkeys(why_not if action == "BLOCK" else ())),
        )

    def compose(
        self,
        signals: Iterable[SignalSpec],
        *,
        campaign_id: str = "",
        now_ms: int | None = None,
    ) -> CoreComposition:
        signal = self.select_initial(signals, now_ms=now_ms)
        if signal is None:
            return CoreComposition(None, None, None, None, "WAIT", ())
        return self.evaluate_signal(
            signal,
            campaign_id=campaign_id,
            now_ms=now_ms,
        )


    def replace_pending(
        self,
        current: PendingSignal,
        candidate: SignalSpec,
        *,
        min_price_delta: float = 0.0,
        campaign_id: str = "",
    ) -> PendingSignal | None:
        replacement = PendingSignal.from_spec(
            candidate,
            campaign_id=campaign_id or current.campaign_id,
            replacement_of=current.signal_id,
        )
        return replacement if should_replace(current, replacement, min_price_delta) else None

    def contract(self) -> dict[str, Any]:
        return {
            "core_version": self.VERSION,
            "contracts_version": "2.0.0",
            "sequence": [
                "CONTEXT", "BEHAVIOR", "STRUCTURE", "LOCATION",
                "MOMENTUM", "PRICE_PROOF", "ENTRY", "ADD",
                "CAMPAIGN", "EXHAUSTION", "EXIT",
            ],
            "domain_contracts": [
                "SignalDirection",
                "ProofVector",
                "WilliamsDecision",
                "RiskDecision",
                "ExecutionIntent",
            ],
            "proof_policy": {
                "diagnostic_score": "diagnostic_only",
                "no_score_threshold_gate": True,
                "price_proof_before_conditional_arm": False,
            },
            "execution_contract": {
                "initial_entry": "BUY_STOP conditional; never convert missed trigger to MARKET",
                "add_on": "BUY_STOP conditional; same campaign",
                "hard_protection": "SELL STOP exchange-side",
                "trailing": "structural stop only moves to reduce risk",
                "exit": "cancel protection -> MARKET SELL -> authoritative fill -> reconcile residual",
                "fixed_take_profit": False,
                "ambiguity": "RECONCILE_REQUIRED; no blind replay",
            },
            "self_healing": {
                "safe_only": True,
                "max_attempts": 3,
                "never": [
                    "invent a signal",
                    "blindly replay ambiguous order mutation",
                    "loosen protective stop",
                    "bypass reconciliation",
                ],
            },
        }


    def prove_price(self, decision: WilliamsDecision, *, market_price: float) -> WilliamsDecision:
        """Attach the final price-proof bit without changing any prior evidence."""
        evaluation = price_proof(decision, market_price=market_price)
        return WilliamsDecision(
            timestamp=decision.timestamp,
            symbol=decision.symbol,
            direction=decision.direction,
            wise_man_stage=decision.wise_man_stage,
            trigger_price=decision.trigger_price,
            invalidation_price=decision.invalidation_price,
            proof_vector=evaluation.proof_vector,
            context_regime=decision.context_regime,
        )
