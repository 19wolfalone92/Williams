"""Durable Williams Wise-Men sequence state machine."""
from __future__ import annotations

import json
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
        except ValueError:
            phase_enum = WiseMenPhase.NONE
        return cls(
            symbol=symbol,
            side=side,
            phase=phase_enum,
            last_candle=str(payload.get("last_candle", "")),
            wm1_level=float(payload.get("wm1_level", 0.0) or 0.0),
            wm2_level=float(payload.get("wm2_level", 0.0) or 0.0),
            wm3_level=float(payload.get("wm3_level", 0.0) or 0.0),
            additions=int(payload.get("additions", 0) or 0),
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
            return WiseMenState.from_dict(json.loads(raw), self.symbol, self.side)
        except Exception:
            return WiseMenState(self.symbol, self.side)

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

        self.state = next_state
        self._save()
        return self.state
