"""Core Williams trading-campaign domain model.

This module is deliberately exchange-agnostic.  Strategy produces SignalSpec
objects; CampaignEngine decides whether a signal is an initial entry or an
add-on; execution adapters turn an admitted intent into a Binance order.

Book-alignment:
- First Wise Man (reversal) is the preferred early entry.
- Second Wise Man (Super AO) normally adds to an existing campaign, but may be
  the first entry if it appears first.
- Third Wise Man (fractal) normally adds, but may also be the first entry.
- A valid fractal remains actionable until it triggers or a newer signal
  supersedes it.
- Entry triggers are one tick beyond the signal-bar/fractal extreme.
- Fixed take-profit is not the primary campaign exit; structural trailing and
  opposite/exhaustion signals govern the campaign exit.

Risk adaptation:
The book's reverse-pyramid quantity ratios are 1:5:4:3:2.  Williams applies
those as *risk-budget weights* here rather than blindly copying historical
contract/share counts.  Every live campaign remains bounded by the portfolio
risk budget.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Mapping, Sequence
import time
import uuid


class CampaignState(str, Enum):
    FLAT = "FLAT"
    SIGNAL_DETECTED = "SIGNAL_DETECTED"
    ENTRY_ARMING = "ENTRY_ARMING"
    ENTRY_PENDING = "ENTRY_PENDING"
    ENTRY_TRIGGERED = "ENTRY_TRIGGERED"
    OPEN_INITIAL = "OPEN_INITIAL"
    ADD_ON_ARMING = "ADD_ON_ARMING"
    ADD_ON_PENDING = "ADD_ON_PENDING"
    POSITION_EXPANDING = "POSITION_EXPANDING"
    TREND_ACTIVE = "TREND_ACTIVE"
    TRAILING = "TRAILING"
    EXHAUSTION_WATCH = "EXHAUSTION_WATCH"
    EXIT_SIGNALLED = "EXIT_SIGNALLED"
    EXIT_PENDING = "EXIT_PENDING"
    CLOSED = "CLOSED"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


class SignalType(str, Enum):
    REVERSAL = "REVERSAL"
    SUPER_AO = "SUPER_AO"
    FRACTAL = "FRACTAL"


class SignalRole(str, Enum):
    ENTRY = "ENTRY"
    ADD_ON = "ADD_ON"


class SignalState(str, Enum):
    DETECTED = "DETECTED"
    ARMED = "ARMED"
    TRIGGERED = "TRIGGERED"
    FILLED = "FILLED"
    REPLACEMENT_REQUESTED = "REPLACEMENT_REQUESTED"
    REPLACED = "REPLACED"
    INVALIDATED = "INVALIDATED"
    EXPIRED = "EXPIRED"
    CANCEL_REQUESTED = "CANCEL_REQUESTED"
    CANCELLED = "CANCELLED"


class CampaignEventType(str, Enum):
    SIGNAL_DETECTED = "SIGNAL_DETECTED"
    ENTRY_ARMED = "ENTRY_ARMED"
    ENTRY_REPLACED = "ENTRY_REPLACED"
    ENTRY_INVALIDATED = "ENTRY_INVALIDATED"
    ENTRY_TRIGGERED = "ENTRY_TRIGGERED"
    ENTRY_PARTIAL_FILL = "ENTRY_PARTIAL_FILL"
    ENTRY_FILLED = "ENTRY_FILLED"
    PROTECTION_ARMED = "PROTECTION_ARMED"
    PROTECTION_REPLACED = "PROTECTION_REPLACED"
    PROTECTION_BLOCKED = "PROTECTION_BLOCKED"
    WM2_CONFIRMED = "WM2_CONFIRMED"
    WM3_CONFIRMED = "WM3_CONFIRMED"
    ADD_ON_ARMED = "ADD_ON_ARMED"
    ADD_ON_REPLACED = "ADD_ON_REPLACED"
    ADD_ON_CANCELLED = "ADD_ON_CANCELLED"
    ADD_ON_FILLED = "ADD_ON_FILLED"
    STOP_PROPOSED = "STOP_PROPOSED"
    STOP_MOVED = "STOP_MOVED"
    STOP_LOOSEN_ATTEMPT_BLOCKED = "STOP_LOOSEN_ATTEMPT_BLOCKED"
    EXHAUSTION_WARNING = "EXHAUSTION_WARNING"
    EXIT_SIGNAL = "EXIT_SIGNAL"
    EXIT_SUBMITTED = "EXIT_SUBMITTED"
    EXIT_PARTIAL_FILL = "EXIT_PARTIAL_FILL"
    EXIT_FILLED = "EXIT_FILLED"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"
    RECONCILED = "RECONCILED"
    CAMPAIGN_CLOSED = "CAMPAIGN_CLOSED"


@dataclass(frozen=True)
class SignalSpec:
    signal_id: str
    symbol: str
    side: str
    signal_type: SignalType
    role: SignalRole
    timeframe: str
    signal_bar_time_ms: int
    trigger_price: float
    protective_reference: float
    trigger_buffer_ticks: int = 1
    invalidation_price: float = 0.0
    teeth_at_detection: float = 0.0
    alligator_bullish: bool = False
    alligator_awake: bool = False
    angulation_score: float = 0.0
    wave_confidence: float = 0.0
    wave_exhaustion_risk: float = 0.0
    htf_confirmed: bool = False
    context_versions: Mapping[str, int] = field(default_factory=dict)
    reason: str = ""
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    expires_at_ms: int = 0
    source_candle_index: int = -1
    # Direction describes intended exposure, not exchange order side.
    # LONG entry=BUY/exit=SELL; SHORT entry=SELL/exit=BUY.
    direction: str = ""
    alligator_bearish: bool = False

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["signal_type"] = self.signal_type.value
        data["role"] = self.role.value
        return data

    @classmethod
    def new(
        cls,
        *,
        symbol: str,
        side: str,
        signal_type: SignalType,
        role: SignalRole,
        timeframe: str,
        signal_bar_time_ms: int,
        trigger_price: float,
        protective_reference: float,
        **kwargs: Any,
    ) -> "SignalSpec":
        normalized_side = str(side).upper()
        direction = str(kwargs.pop("direction", "") or "").upper()
        if not direction:
            direction = {
                "BUY": "LONG",
                "LONG": "LONG",
                "SELL": "SHORT",
                "SHORT": "SHORT",
            }.get(normalized_side, "")
        if direction not in {"LONG", "SHORT"}:
            raise ValueError("SignalSpec requires direction LONG or SHORT")
        if normalized_side not in {"BUY", "SELL", "LONG", "SHORT"}:
            raise ValueError("SignalSpec side must be BUY/SELL or LONG/SHORT")
        side_direction = "LONG" if normalized_side in {"BUY", "LONG"} else "SHORT"
        if direction != side_direction:
            raise ValueError(
                f"SignalSpec side/direction conflict: side={normalized_side} direction={direction}"
            )
        signal_id = (
            f"{str(symbol).upper()}:{str(timeframe).lower()}:"
            f"{signal_type.value}:{direction}:{int(signal_bar_time_ms)}"
        )
        return cls(
            signal_id=signal_id,
            symbol=str(symbol).upper(),
            side=normalized_side,
            signal_type=signal_type,
            role=role,
            timeframe=str(timeframe).lower(),
            signal_bar_time_ms=int(signal_bar_time_ms),
            trigger_price=float(trigger_price),
            protective_reference=float(protective_reference),
            direction=direction,
            **kwargs,
        )


@dataclass
class PendingOrderRecord:
    order_id: str = ""
    client_order_id: str = ""
    symbol: str = ""
    side: str = ""
    order_type: str = ""
    purpose: str = ""
    status: str = ""
    price: float = 0.0
    stop_price: float = 0.0
    quantity: float = 0.0
    risk_quote: float = 0.0
    capital_reserved_quote: float = 0.0
    signal_id: str = ""
    campaign_id: str = ""
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class CampaignRiskPlan:
    portfolio_risk_limit_pct: float
    campaign_risk_limit_pct: float
    current_open_risk_quote: float
    current_pending_risk_quote: float
    initial_risk_pct: float
    add_on_risk_budget_pct: float
    tranche_index: int = 0
    reverse_pyramid_weights: Sequence[int] = (1, 5, 4, 3, 2)

    @property
    def reserved_risk_pct(self) -> float:
        return (
            float(self.current_open_risk_quote)
            + float(self.current_pending_risk_quote)
        )

    def next_weight(self) -> int:
        idx = min(max(int(self.tranche_index), 0), len(self.reverse_pyramid_weights) - 1)
        return int(self.reverse_pyramid_weights[idx])

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["reverse_pyramid_weights"] = list(self.reverse_pyramid_weights)
        return data


@dataclass
class TradingCampaign:
    campaign_id: str
    symbol: str
    side: str
    execution_timeframe: str
    state: CampaignState = CampaignState.SIGNAL_DETECTED
    origin_signal_id: str = ""
    current_signal_id: str = ""
    current_signal_type: str = ""
    position_qty: float = 0.0
    average_entry_price: float = 0.0
    initial_stop_price: float = 0.0
    current_stop_price: float = 0.0
    structural_stop_source: str = ""
    additions: int = 0
    tranche_index: int = 0
    realized_pnl_quote: float = 0.0
    unrealized_pnl_quote: float = 0.0
    open_risk_quote: float = 0.0
    pending_risk_quote: float = 0.0
    capital_reserved_quote: float = 0.0
    wave_position: int = 0
    wave_phase: str = "UNKNOWN"
    wave_confidence: float = 0.0
    wave_exhaustion_risk: float = 0.0
    htf_confirmed: bool = False
    health: str = "GREEN"
    next_action: str = "WAIT"
    reconciliation_state: str = "CLEAN"
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    updated_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    exit_reason: str = ""
    tags: dict[str, Any] = field(default_factory=dict)

    def transition(self, next_state: CampaignState, *, reason: str = "") -> None:
        if not campaign_transition_allowed(self.state, next_state):
            raise ValueError(
                f"Invalid campaign transition {self.state.value} -> {next_state.value}"
            )
        self.state = next_state
        self.updated_at_ms = int(time.time() * 1000)
        if reason:
            self.tags["last_transition_reason"] = reason

    def mark_reconcile_required(self, reason: str) -> None:
        self.state = CampaignState.RECONCILE_REQUIRED
        self.reconciliation_state = "REQUIRED"
        self.updated_at_ms = int(time.time() * 1000)
        self.tags["reconcile_reason"] = str(reason)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["state"] = self.state.value
        return data


def campaign_transition_allowed(
    current: CampaignState,
    nxt: CampaignState,
) -> bool:
    if current == nxt:
        return True
    if nxt == CampaignState.RECONCILE_REQUIRED:
        return current != CampaignState.CLOSED

    allowed: dict[CampaignState, set[CampaignState]] = {
        CampaignState.FLAT: {CampaignState.SIGNAL_DETECTED},
        CampaignState.SIGNAL_DETECTED: {
            CampaignState.ENTRY_ARMING,
            CampaignState.ENTRY_PENDING,
            CampaignState.CLOSED,
        },
        CampaignState.ENTRY_ARMING: {
            CampaignState.ENTRY_PENDING,
            CampaignState.SIGNAL_DETECTED,
        },
        CampaignState.ENTRY_PENDING: {
            CampaignState.ENTRY_TRIGGERED,
            CampaignState.CLOSED,
            CampaignState.SIGNAL_DETECTED,
        },
        CampaignState.ENTRY_TRIGGERED: {
            CampaignState.OPEN_INITIAL,
            CampaignState.ENTRY_PENDING,
        },
        CampaignState.OPEN_INITIAL: {
            CampaignState.ADD_ON_ARMING,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
            CampaignState.EXHAUSTION_WATCH,
            CampaignState.EXIT_SIGNALLED,
        },
        CampaignState.ADD_ON_ARMING: {
            CampaignState.ADD_ON_PENDING,
            CampaignState.TREND_ACTIVE,
        },
        CampaignState.ADD_ON_PENDING: {
            CampaignState.POSITION_EXPANDING,
            CampaignState.TREND_ACTIVE,
            CampaignState.OPEN_INITIAL,
        },
        CampaignState.POSITION_EXPANDING: {
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
            CampaignState.EXHAUSTION_WATCH,
            CampaignState.EXIT_SIGNALLED,
        },
        CampaignState.TREND_ACTIVE: {
            CampaignState.ADD_ON_ARMING,
            CampaignState.TRAILING,
            CampaignState.EXHAUSTION_WATCH,
            CampaignState.EXIT_SIGNALLED,
        },
        CampaignState.TRAILING: {
            CampaignState.ADD_ON_ARMING,
            CampaignState.EXHAUSTION_WATCH,
            CampaignState.EXIT_SIGNALLED,
        },
        CampaignState.EXHAUSTION_WATCH: {
            CampaignState.TRAILING,
            CampaignState.EXIT_SIGNALLED,
        },
        CampaignState.EXIT_SIGNALLED: {
            CampaignState.EXIT_PENDING,
            CampaignState.TRAILING,
        },
        CampaignState.EXIT_PENDING: {
            CampaignState.CLOSED,
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
        },
        CampaignState.CLOSED: set(),
        CampaignState.RECONCILE_REQUIRED: {
            CampaignState.FLAT,
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.EXIT_PENDING,
        },
    }
    return nxt in allowed.get(current, set())


def structural_stop_for_long(
    *,
    signal_type: SignalType,
    signal_bar_low: float,
    recent_lows: Sequence[float],
    teeth: float,
    wave_invalidation: float = 0.0,
    buffer: float = 0.0,
) -> tuple[float, str]:
    """Return a long structural stop and its source.

    Priority is deliberately conservative: the closest meaningful structural
    invalidation is preferred, while the stop remains below entry.  The
    caller must enforce the invariant that a later stop never loosens risk.
    """
    candidates: list[tuple[float, str]] = []
    if signal_bar_low > 0:
        candidates.append((signal_bar_low - max(0.0, buffer), f"{signal_type.value}_SIGNAL_BAR"))
    if recent_lows:
        valid_lows = [float(x) for x in recent_lows if float(x) > 0]
        if valid_lows:
            # For a LONG trail, the closest valid protection is the highest
            # recent structural low, not the lowest low in the window.
            low = max(valid_lows)
            candidates.append((low - max(0.0, buffer), "3_5_BAR_STRUCTURE"))
    if teeth > 0:
        candidates.append((teeth - max(0.0, buffer), "TEETH"))
    if wave_invalidation > 0:
        candidates.append((wave_invalidation - max(0.0, buffer), "WAVE_INVALIDATION"))
    if not candidates:
        return 0.0, "UNAVAILABLE"
    return max(candidates, key=lambda item: item[0])


def stop_only_reduces_risk(side: str, current_stop: float, proposed_stop: float) -> bool:
    """Hard invariant: a protective stop may never move against the position."""
    if current_stop <= 0:
        return proposed_stop > 0
    if str(side).upper() in {"LONG", "BUY"}:
        return proposed_stop >= current_stop
    if str(side).upper() in {"SHORT", "SELL"}:
        return proposed_stop <= current_stop
    return False


def reverse_pyramid_risk_weight(tranche_index: int) -> int:
    weights = (1, 5, 4, 3, 2)
    idx = min(max(int(tranche_index), 0), len(weights) - 1)
    return weights[idx]
