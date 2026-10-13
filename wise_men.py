"""Durable Williams Wise-Men sequence state machine."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from enum import Enum


class WiseMenPhase(str, Enum):
    NONE = "NONE"
    WM1_TRIGGERED = "WM1_TRIGGERED"
    WM2_TRIGGERED = "WM2_TRIGGERED"
    WM3_TRIGGERED = "WM3_TRIGGERED"
    TREND_ACTIVE = "TREND_ACTIVE"
    EXHAUSTION = "EXHAUSTION"
    INVALIDATED = "INVALIDATED"


@dataclass(frozen=True)
class WiseMenState:
    symbol: str
    side: str
    phase: WiseMenPhase = WiseMenPhase.NONE
    last_candle: str = ""
    wm1_level: float = 0.0
    wm2_level: float = 0.0
    wm3_level: float = 0.0
    additions: int = 0

    def to_dict(self) -> dict:
        return {
            "symbol": self.symbol,
            "side": self.side,
            "phase": self.phase.value,
            "last_candle": self.last_candle,
            "wm1_level": self.wm1_level,
            "wm2_level": self.wm2_level,
            "wm3_level": self.wm3_level,
            "additions": self.additions,
        }

    @classmethod
    def from_dict(cls, payload: dict, symbol: str, side: str) -> "WiseMenState":
        phase = str(payload.get("phase", WiseMenPhase.NONE.value))
        try:
            phase_enum = WiseMenPhase(phase)
        except ValueError as exc:
            raise ValueError(f"Unknown persisted Wise-Men phase: {phase!r}") from exc
        levels = {
            key: float(payload.get(key, 0.0) or 0.0)
            for key in ("wm1_level", "wm2_level", "wm3_level")
        }
        if any(not math.isfinite(value) or value < 0 for value in levels.values()):
            raise ValueError("Persisted Wise-Men levels must be finite and non-negative")
        additions = int(payload.get("additions", 0) or 0)
        if additions < 0:
            raise ValueError("Persisted Wise-Men additions cannot be negative")
        return cls(
            symbol=symbol,
            side=side,
            phase=phase_enum,
            last_candle=str(payload.get("last_candle", "")),
            wm1_level=levels["wm1_level"],
            wm2_level=levels["wm2_level"],
            wm3_level=levels["wm3_level"],
            additions=additions,
        )


class WiseMenStateMachine:
    def __init__(self, db, symbol: str, side: str) -> None:
        self.db = db
        self.symbol = symbol.upper()
        self.side = side.upper()
        self.key = f"wise_men:{self.symbol}:{self.side}"
        self.state = self._load()

    def _load(self) -> WiseMenState:
        raw = self.db.state_get(self.key)
        if not raw:
            return WiseMenState(self.symbol, self.side)
        try:
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("Persisted Wise-Men state must be a JSON object")
            return WiseMenState.from_dict(payload, self.symbol, self.side)
        except Exception as exc:
            # A corrupted checkpoint is not equivalent to a fresh campaign.
            # Silently resetting could re-arm a signal whose prior state is unknown.
            raise RuntimeError(
                f"Corrupt persisted Wise-Men state for {self.symbol}/{self.side}; "
                "recovery/reconciliation is required before trading"
            ) from exc

    def _save(self) -> None:
        self.db.state_set(self.key, json.dumps(self.state.to_dict(), sort_keys=True))

    def reset(self, phase: WiseMenPhase = WiseMenPhase.NONE) -> WiseMenState:
        self.state = WiseMenState(self.symbol, self.side, phase=phase)
        self._save()
        return self.state

    def observe(
        self,
        candle_id: str,
        *,
        wm1_triggered: bool = False,
        wm2_triggered: bool = False,
        wm3_triggered: bool = False,
        invalidated: bool = False,
        exhausted: bool = False,
        level: float = 0.0,
    ) -> WiseMenState:
        if candle_id == self.state.last_candle:
            return self.state

        # Terminal states cannot be revived by a late/replayed signal.
        # An explicit reset is required after invalidation or exhaustion.
        if self.state.phase in {WiseMenPhase.INVALIDATED, WiseMenPhase.EXHAUSTION}:
            return self.state

        # Wise-Men evidence is monotonic within one campaign. A late WM1/WM2
        # event must not roll an already active trend back to an earlier phase.
        phase_rank = {
            WiseMenPhase.NONE: 0,
            WiseMenPhase.WM1_TRIGGERED: 1,
            WiseMenPhase.WM2_TRIGGERED: 2,
            WiseMenPhase.WM3_TRIGGERED: 3,
            WiseMenPhase.TREND_ACTIVE: 4,
        }
        next_state = self.state
        if invalidated:
            next_state = WiseMenState(self.symbol, self.side, WiseMenPhase.INVALIDATED, candle_id)
        elif exhausted:
            next_state = WiseMenState(
                self.symbol, self.side, WiseMenPhase.EXHAUSTION, candle_id,
                self.state.wm1_level, self.state.wm2_level, self.state.wm3_level,
                self.state.additions,
            )
        elif wm3_triggered:
            next_state = WiseMenState(
                self.symbol, self.side, WiseMenPhase.TREND_ACTIVE, candle_id,
                self.state.wm1_level, self.state.wm2_level, level or self.state.wm3_level,
                self.state.additions,
            )
        elif wm2_triggered:
            next_state = WiseMenState(
                self.symbol, self.side, WiseMenPhase.WM2_TRIGGERED, candle_id,
                self.state.wm1_level, level or self.state.wm2_level, self.state.wm3_level,
                self.state.additions,
            )
        elif wm1_triggered:
            next_state = WiseMenState(
                self.symbol, self.side, WiseMenPhase.WM1_TRIGGERED, candle_id,
                level or self.state.wm1_level, self.state.wm2_level, self.state.wm3_level,
                self.state.additions,
            )
        else:
            next_state = WiseMenState(
                self.symbol, self.side, self.state.phase, candle_id,
                self.state.wm1_level, self.state.wm2_level, self.state.wm3_level,
                self.state.additions,
            )

        if next_state.phase in phase_rank and self.state.phase in phase_rank:
            if phase_rank[next_state.phase] < phase_rank[self.state.phase]:
                next_state = WiseMenState(
                    self.symbol, self.side, self.state.phase, candle_id,
                    self.state.wm1_level, self.state.wm2_level, self.state.wm3_level,
                    self.state.additions,
                )

        self.state = next_state
        self._save()
        return self.state
