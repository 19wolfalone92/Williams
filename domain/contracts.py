"""Canonical immutable contracts for Digital Bill Williams.

These contracts belong to the Pure Williams / policy boundary. They do not
know about Binance, persistence, Android, HTTP or order execution.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any, Optional


class SignalDirection(str, Enum):
    LONG = "LONG"
    SHORT = "SHORT"
    FLAT = "FLAT"


@dataclass(frozen=True, slots=True)
class ProofVector:
    """Eight-stage evidence vector.

    diagnostic_score is informational only. It must never be used as a
    substitute for the individual proof gates.
    """

    context_pass: bool
    behavior_pass: bool
    structure_pass: bool
    location_pass: bool
    angulation_pass: bool
    momentum_pass: bool
    price_proof_pass: bool
    invalidation_present: bool

    @property
    def is_fully_proven(self) -> bool:
        return all((
            self.context_pass,
            self.behavior_pass,
            self.structure_pass,
            self.location_pass,
            self.angulation_pass,
            self.momentum_pass,
            self.price_proof_pass,
            self.invalidation_present,
        ))

    @property
    def diagnostic_score(self) -> float:
        return sum(
            bool(value)
            for value in (
                self.context_pass,
                self.behavior_pass,
                self.structure_pass,
                self.location_pass,
                self.angulation_pass,
                self.momentum_pass,
                self.price_proof_pass,
                self.invalidation_present,
            )
        ) / 8.0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class WilliamsDecision:
    """Immutable Williams-side decision contract.

    A decision contains evidence and the prices that define the hypothesis.
    It does not contain execution instructions and cannot call an exchange.
    """

    timestamp: int
    symbol: str
    direction: SignalDirection
    wise_man_stage: int
    trigger_price: float
    invalidation_price: float
    proof_vector: ProofVector
    context_regime: str

    def __post_init__(self) -> None:
        symbol = str(self.symbol).upper().strip()
        if not symbol:
            raise ValueError("WilliamsDecision.symbol is required")
        if int(self.timestamp) < 0:
            raise ValueError("WilliamsDecision.timestamp must be >= 0")
        if int(self.wise_man_stage) < 0 or int(self.wise_man_stage) > 3:
            raise ValueError("wise_man_stage must be in [0, 3]")
        if float(self.trigger_price) <= 0:
            raise ValueError("trigger_price must be > 0")
        if float(self.invalidation_price) <= 0:
            raise ValueError("invalidation_price must be > 0")
        if not isinstance(self.direction, SignalDirection):
            raise ValueError("direction must be SignalDirection")
        if not isinstance(self.proof_vector, ProofVector):
            raise ValueError("proof_vector must be ProofVector")
        object.__setattr__(self, "symbol", symbol)
        object.__setattr__(self, "context_regime", str(self.context_regime or "UNKNOWN").upper())

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["direction"] = self.direction.value
        data["proof_vector"] = self.proof_vector.to_dict()
        return data


@dataclass(frozen=True, slots=True)
class RiskDecision:
    """Immutable risk admission result derived from a WilliamsDecision."""

    williams_decision: WilliamsDecision
    approved: bool
    allocated_r_multiple: float
    calculated_quantity: float
    max_allowed_slippage: float
    rejection_reason: Optional[str] = None

    def __post_init__(self) -> None:
        if not isinstance(self.williams_decision, WilliamsDecision):
            raise ValueError("williams_decision must be WilliamsDecision")
        if float(self.allocated_r_multiple) < 0:
            raise ValueError("allocated_r_multiple must be >= 0")
        if float(self.calculated_quantity) < 0:
            raise ValueError("calculated_quantity must be >= 0")
        if float(self.max_allowed_slippage) < 0:
            raise ValueError("max_allowed_slippage must be >= 0")
        if self.approved and self.rejection_reason:
            raise ValueError("approved RiskDecision cannot have rejection_reason")
        if not self.approved and not str(self.rejection_reason or "").strip():
            raise ValueError("rejected RiskDecision requires rejection_reason")

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["williams_decision"] = self.williams_decision.to_dict()
        return data


@dataclass(frozen=True, slots=True)
class ExecutionIntent:
    """Immutable hand-off from risk policy to the execution barrier."""

    risk_decision: RiskDecision
    order_type: str
    client_order_id: str
    recv_window: int
    time_in_force: str
    reduce_only: bool

    def __post_init__(self) -> None:
        if not isinstance(self.risk_decision, RiskDecision):
            raise ValueError("risk_decision must be RiskDecision")
        order_type = str(self.order_type or "").upper().strip()
        client_id = str(self.client_order_id or "").strip()
        tif = str(self.time_in_force or "").upper().strip()
        if not self.risk_decision.approved:
            raise ValueError("ExecutionIntent requires an approved RiskDecision")
        if not order_type:
            raise ValueError("ExecutionIntent.order_type is required")
        if not client_id:
            raise ValueError("ExecutionIntent.client_order_id is required")
        if int(self.recv_window) <= 0:
            raise ValueError("ExecutionIntent.recv_window must be > 0")
        if int(self.recv_window) > 60_000:
            raise ValueError("ExecutionIntent.recv_window must be <= 60000")
        if not tif:
            raise ValueError("ExecutionIntent.time_in_force is required")
        object.__setattr__(self, "order_type", order_type)
        object.__setattr__(self, "client_order_id", client_id)
        object.__setattr__(self, "time_in_force", tif)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["risk_decision"] = self.risk_decision.to_dict()
        return data