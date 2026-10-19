"""Causal event policy; contains no wave forecasts or exchange mutation API."""
from dataclasses import dataclass
from math import isfinite


@dataclass(frozen=True)
class LevelEvent:
    state: str
    known_at: int = 0
    basis: str = "NONE"

    def to_dict(self):
        return {"state": self.state, "known_at_ms": self.known_at or None, "basis": self.basis}


def evaluate_level_event(side, trigger, signal_closed_at, scope_started_at, mark,
                         history, now, expires_at, frame_ms=3_600_000, invalidation=None):
    if (side not in {"LONG", "SHORT"} or not isfinite(trigger) or trigger <= 0
            or not isfinite(mark) or mark <= 0 or scope_started_at <= 0
            or not scope_started_at < signal_closed_at <= now
            or frame_ms <= 0 or expires_at <= signal_closed_at):
        return LevelEvent("UNVERIFIED", basis="INVALID_EVENT_INPUT")
    long = side == "LONG"
    if invalidation is not None and (not isfinite(invalidation) or invalidation <= 0
            or (invalidation >= trigger if long else invalidation <= trigger)):
        return LevelEvent("UNVERIFIED", basis="INVALID_BOUNDARY")
    def hit_trigger(price):
        return price >= trigger if long else price <= trigger
    def hit_invalid(price):
        return invalidation is not None and (price <= invalidation if long else price >= invalidation)
    horizon = min(now, expires_at)
    closed = [b for b in history if signal_closed_at <= b.t <= horizon-frame_ms]
    expected = signal_closed_at
    for b in closed:
        if (b.t != expected or not all(isfinite(v) and v > 0 for v in (b.o,b.h,b.l,b.c))
                or b.h < max(b.o,b.l,b.c) or b.l > min(b.o,b.h,b.c)):
            return LevelEvent("UNVERIFIED", basis="CLOSED_HISTORY_NOT_PROVEN")
        known = b.t+frame_ms
        if hit_trigger(b.o):
            return LevelEvent("TRIGGERED", known, "CLOSED_BAR_OPEN")
        if hit_invalid(b.o):
            return LevelEvent("INVALIDATED", known, "CLOSED_BAR_OPEN")
        crossed, broken = hit_trigger(b.h if long else b.l), hit_invalid(b.l if long else b.h)
        if crossed and broken:
            return LevelEvent("AMBIGUOUS", known, "BOTH_BOUNDARIES_IN_ONE_BAR")
        if broken:
            return LevelEvent("INVALIDATED", known, "CLOSED_BAR_BOUNDARY")
        if crossed:
            return LevelEvent("TRIGGERED", known, "CLOSED_BAR_EXTREME")
        expected = known
    if expected <= horizon-frame_ms:
        return LevelEvent("UNVERIFIED", basis="CLOSED_HISTORY_NOT_PROVEN")
    if now >= expires_at:
        return LevelEvent("EXPIRED")
    if hit_invalid(mark):
        return LevelEvent("INVALIDATED", now, "LIVE_MARK")
    if hit_trigger(mark):
        return LevelEvent("TRIGGERED", now, "LIVE_MARK")
    return LevelEvent("WAITING")


@dataclass(frozen=True)
class ScenarioSignal:
    id: str
    direction: str
    profile: str
    semantics: str
    signal_at: int
    trigger: float
    invalidation: float | None
    expires_at: int


def scenario_opposition(direction, started_at, signals, bars, mark, now, frame_ms=3_600_000):
    if direction not in {"LONG", "SHORT"} or started_at <= 0 or frame_ms <= 0:
        return None
    return assess_scenario_opposition(direction,started_at,signals,bars,mark,now,frame_ms)["opposition"]


def assess_scenario_opposition(direction, started_at, signals, bars, mark, now, frame_ms=3_600_000):
    if direction not in {"LONG", "SHORT"} or started_at <= 0 or frame_ms <= 0:
        raise ValueError("INVALID_SCENARIO_SCOPE")
    events = []
    for signal in signals:
        if signal.direction not in {"LONG", "SHORT"} or signal.direction == direction or signal.signal_at <= 0:
            continue
        known = signal.signal_at+frame_ms
        if known <= started_at or known > now:
            continue
        if signal.semantics == "STOP_ENTRY":
            event = evaluate_level_event(signal.direction, signal.trigger, known, started_at,
                mark, bars, now, signal.expires_at, frame_ms, signal.invalidation)
        elif (signal.semantics == "MARKET_ON_CLOSE" and signal.profile == "NTD_FIVE_DIMENSIONS"
                and started_at < known <= now and (now-known)//frame_ms < 5):
            event = LevelEvent("TRIGGERED", known, "CONFIRMED_CLOSE_EVENT")
        else:
            continue
        events.append((signal,event))
    triggered=[item for item in events if item[1].state=="TRIGGERED"]
    uncertain=[item for item in events if item[1].state in {"AMBIGUOUS","UNVERIFIED"}]
    opposition=min(triggered,key=lambda item:(item[1].known_at,item[0].id)) if triggered else None
    blocker=("H1_PARENT_OPPOSITE_TRIGGERED" if opposition else "H1_OPPOSITION_AMBIGUOUS"
        if any(item[1].state=="AMBIGUOUS" for item in uncertain)
        else "H1_OPPOSITION_HISTORY_UNVERIFIED" if uncertain else None)
    return {"opposition":opposition,"uncertain":uncertain,"blocker":blocker}
