"""Causal, competing Williams wave hypotheses and explicit trade decisions.

TC1 ch.7 (PDF 50-65), NTD ch.9, TC2 ch.9-12.  Elliott labels remain
hypotheses: containment is observable, a uniquely correct Elliott count is not.
No candle interpolation, confidence probability, or order submission lives here.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from math import isfinite
from typing import Mapping, Sequence
from williams_complex_waves import detect_complex_wave_patterns

FRAME_MS = {"1m":60_000,"3m":180_000,"5m":300_000,"15m": 900_000, "30m": 1_800_000, "1h": 3_600_000,
            "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
            "8h": 28_800_000, "12h": 43_200_000, "1d": 86_400_000}
REQUIRED_FRAMES = ("1d", "6h", "1h", "15m")


def wave_bars_from_dataframe(frame):
    """Use exchange timestamps; never invent timestamps for an analytical scale."""
    import pandas as pd
    bars = []
    for index, row in frame.iterrows():
        stamp = row.get("open_time", index)
        if isinstance(stamp, pd.Timestamp):
            t = int(stamp.timestamp() * 1000)
        elif isinstance(stamp, (int, float)):
            t = int(stamp)
        else:
            t = int(pd.Timestamp(stamp).timestamp() * 1000)
        bars.append(WaveBar(t, *[float(row[col]) for col in ("open", "high", "low", "close", "volume")]))
    return bars


@dataclass(frozen=True)
class WaveBar:
    t: int
    o: float
    h: float
    l: float
    c: float
    v: float = 0.0


@dataclass(frozen=True)
class WaveAnchor:
    kind: str
    price: float
    index: int
    extreme_at: int
    known_at: int


@dataclass
class WaveHypothesis:
    scenario_id: str
    frame: str
    direction: str
    label: str
    anchors: list[WaveAnchor]
    observed_at: int
    leg_start: int
    leg_low: float
    leg_high: float
    invalidation: float
    status: str = "PROVISIONAL"
    invalid_reasons: list[str] = field(default_factory=list)
    scale_frame: str = ""
    scale_bars: int = 0
    w4_zero: str = "UNCONFIRMED"
    divergence: bool = False
    momentum_shift: bool = False
    terminal_fractal: bool = False
    squat: bool = False
    target_zone: bool = False
    alternatives: list[str] = field(default_factory=list)

    def to_dict(self):
        return asdict(self)


@dataclass
class WaveHierarchy:
    as_of: int
    scenarios: list[WaveHypothesis]
    links: list[dict]
    frame_errors: dict[str, str]
    warnings: list[dict]
    frame_candle_counts: dict[str,int] = field(default_factory=dict)

    def to_dict(self):
        by_id={s.scenario_id:s for s in self.scenarios}
        edges={(e["parent"],e["child"]) for e in self.links}
        def path(node,index):
            if index==len(REQUIRED_FRAMES)-1:
                return True
            return any(p==node and by_id.get(c) and by_id[c].frame==REQUIRED_FRAMES[index+1] and path(c,index+1)
                       for p,c in edges)
        contained=any(s.frame=="1d" and path(s.scenario_id,0) for s in self.scenarios)
        complex_patterns = []
        # The outer leg's changing label is not proof of the internal degree.
        # Serialize non-trading complex alternatives beside existing counts.
        for scenario in self.scenarios:
            if len(scenario.anchors) < 6:
                continue
            for p in detect_complex_wave_patterns(scenario.anchors,
                                                  direction=scenario.direction,
                                                  as_of=self.as_of):
                complex_patterns.append(p.to_dict() | {
                    "source_scenario_id":scenario.scenario_id,
                    "frame":scenario.frame})
        return asdict(self) | {"complex_wave_patterns":complex_patterns[:48],
                              "schema_version": 1,
                              "wave_labels": "HYPOTHESES_NOT_CERTAINTY",
                              "chain": list(REQUIRED_FRAMES),"contained_chain_observed":contained,
                              "full_history":False,"can_place_orders":False,
                              "squat_volume_input":"KLINE_BASE_VOLUME_PROXY_NOT_CANONICAL_TICK_VOLUME"}


def closed_wave_bars(bars: Sequence[WaveBar], frame: str, as_of: int) -> list[WaveBar]:
    duration = FRAME_MS[frame]
    closed = [b for b in bars if b.t + duration <= as_of]
    if len(closed) < 40:
        raise ValueError("INSUFFICIENT_CLOSED_HISTORY")
    for i, b in enumerate(closed):
        if (b.t < 0 or not all(isfinite(x) for x in (b.o, b.h, b.l, b.c, b.v))
                or min(b.o, b.l, b.c) <= 0 or b.h < max(b.o, b.c, b.l)
                or b.l > min(b.o, b.c) or b.v < 0):
            raise ValueError("INVALID_OHLCV")
        if i and b.t - closed[i - 1].t != duration:
            raise ValueError("GAPPED_OR_UNORDERED_HISTORY")
    if as_of - (closed[-1].t + duration) >= duration * 2:
        raise ValueError("STALE_CLOSED_HISTORY")
    return closed


def confirmed_wave_anchors(bars: Sequence[WaveBar], duration: int) -> list[WaveAnchor]:
    """Equal extremes are excluded from the two required neighbours (TC1 p68).

    Outside bars that are both extremes have unknown intrabar order. They
    cannot silently manufacture two chronologically ordered wave anchors.
    """
    raw = []
    for i in range(2, len(bars) - 2):
        matches = []
        for kind in ("LOW", "HIGH"):
            extreme = bars[i].l if kind == "LOW" else bars[i].h
            confirmation = None
            good = True
            for step in (-1, 1):
                count, j = 0, i + step
                while 0 <= j < len(bars) and count < 2:
                    value = bars[j].l if kind == "LOW" else bars[j].h
                    if value == extreme:
                        # Only the last equal extreme is the visible anchor.
                        if step == 1:
                            good = False
                            break
                    elif (value > extreme) == (kind == "LOW"):
                        count += 1
                    else:
                        good = False
                        break
                    if step == 1 and count == 2:
                        confirmation = j
                    j += step
                good = good and count == 2
            if good and confirmation is not None:
                matches.append(WaveAnchor(kind, extreme, i, bars[i].t,
                                          bars[confirmation].t + duration))
        if len(matches) == 1:
            raw.extend(matches)
    alternating = []
    for p in raw:
        if not alternating or p.kind != alternating[-1].kind:
            alternating.append(p)
        elif (p.price > alternating[-1].price if p.kind=="HIGH" else p.price < alternating[-1].price):
            alternating[-1] = p
    return alternating


def _ao(bars):
    mid = [(b.h + b.l) / 2 for b in bars]
    return [sum(mid[i-4:i+1]) / 5 - sum(mid[i-33:i+1]) / 34
            if i >= 33 else float("nan") for i in range(len(mid))]


def _rhythm(h: WaveHypothesis, frames: Mapping[str, list[WaveBar]]) -> None:
    if len(h.anchors) < 4:
        return
    start = h.anchors[0].extreme_at
    stop = h.observed_at
    # Count actual bars belonging to this proposed sequence. Cached history
    # length and a 140-bar screen window do not determine its wave degree.
    options = []
    for frame, bars in frames.items():
        indexes = [i for i, b in enumerate(bars) if b.t >= start and b.t < stop]
        if not indexes or indexes[0] < 33 or bars[0].t > start:
            continue
        count = len(indexes)
        options.append((not 100 <= count <= 140, abs(count - 120), frame, indexes))
    if not options:
        h.w4_zero = "MISSING_SCALE_HISTORY"
        return
    _, _, frame, indexes = min(options)
    h.scale_frame, h.scale_bars = frame, len(indexes)
    bars = frames[frame]
    osc = _ao(bars)
    sign = 1 if h.direction == "UP" else -1
    p = h.anchors

    def peak(a, b):
        values = [sign * osc[i] for i in indexes
                  if a <= bars[i].t <= b and isfinite(osc[i])]
        return max(values) if values else None

    # The oscillator can lag the price extreme by 1-5 bars (TC1 p60).
    third_end = p[3].extreme_at + 5 * FRAME_MS[frame]
    if len(p) >= 5:
        third_end = min(third_end, p[4].extreme_at)
    w3_indexes = [i for i in indexes if p[2].extreme_at <= bars[i].t <= third_end]
    if not w3_indexes:
        h.w4_zero = "MISSING_W3_MOMENTUM"
        return
    w3 = max(w3_indexes, key=lambda i: sign * osc[i])
    end = p[4].extreme_at if len(p) >= 5 else stop
    cross = any(sign * osc[i] < 0 for i in indexes
                if i > w3 and bars[i].t <= end)
    if not 100 <= h.scale_bars <= 140:
        h.w4_zero = "WRONG_SCALE"
    elif sign * osc[w3] <= 0:
        h.w4_zero = "MISSING_W3_MOMENTUM"
    else:
        h.w4_zero = "MINIMUM_ONLY" if cross else "NO_ZERO_CROSS"
    if len(p) >= 6:
        third = peak(p[2].extreme_at, third_end)
        fifth = peak(p[4].extreme_at, min(stop, p[5].extreme_at + 5 * FRAME_MS[frame]))
        h.divergence = bool(third is not None and fifth is not None and
                            0 < fifth < third and sign * (p[5].price - p[3].price) > 0)
        extension = abs(p[3].price - p[0].price)
        target1 = p[4].price + sign * extension * .62
        target2 = p[4].price + sign * extension
        h.target_zone = min(target1, target2) <= p[5].price <= max(target1, target2)
    if h.w4_zero != "MINIMUM_ONLY" and len(p) >= 4:
        h.alternatives.append("W3_EXTENSION_OR_WRONG_DEGREE")


def hypotheses_from_anchors(frame: str, bars: list[WaveBar],
                            anchors: Sequence[WaveAnchor]) -> list[WaveHypothesis]:
    """Enumerate both orientations; do not turn the latest eight-bar trend into a count."""
    out = []
    osc = _ao(bars)
    duration = FRAME_MS[frame]
    for n in range(2, min(9, len(anchors)) + 1):
        p = list(anchors[-n:])
        sign = 1 if p[0].kind == "LOW" else -1
        price = [sign * x.price for x in p]
        reasons = []
        if any(a.known_at > bars[-1].t + duration for a in p):
            continue
        if any(p[i].extreme_at >= p[i+1].extreme_at for i in range(n-1)):
            continue
        if price[1] <= price[0]:
            reasons.append("W1_NOT_IMPULSIVE")
        if n >= 3 and not price[0] < price[2] < price[1]:
            reasons.append("W2_INVALIDATES_ORIGIN")
        if n >= 4 and price[3] <= price[1]:
            reasons.append("W3_DOES_NOT_EXTEND_W1")
        # A wave-four/W1 overlap rules out an ordinary impulse but not
        # every leading/ending diagonal. Keep it a NON-EXECUTABLE alternative
        # only if W4 still holds above W3 origin in oriented price space.
        diagonal_overlap = n >= 5 and price[2] < price[4] <= price[1]
        if n >= 5 and not price[2] < price[4] < price[3]:
            reasons.append("W4_BREACHES_W3_OR_W4_END")
        elif n >= 5 and price[4] <= price[1] and not diagonal_overlap:
            reasons.append("CLASSIC_W4_OVERLAP_OR_INVALID_RETRACEMENT")
        if n >= 6:
            legs = [price[1]-price[0], price[3]-price[2], price[5]-price[4]]
            if min(legs) <= 0 or legs[1] < min(legs[0], legs[2]):
                reasons.append("W3_SHORTEST_OR_NEGATIVE_MOTIVE_LEG")
        label = f"W{n}" if n <= 5 else {6:"A", 7:"B", 8:"C", 9:"ABC_COMPLETE"}[n]
        if n >= 7 and price[6] >= price[5]:
            reasons.append("A_NOT_CORRECTIVE")
        if n >= 8 and price[7] <= price[6]:
            reasons.append("B_NOT_RETRACING_A")
        if n >= 9 and price[8] >= price[6]:
            reasons.append("C_NOT_EXTENDING_A_COMPLEX_CORRECTION_POSSIBLE")
        inv = p[0].price if n <= 3 else p[1].price if n <= 5 else p[5].price
        last_bar = bars[-1]
        # A/C must remain on the corrective side of the old W5. B may exceed
        # it in an irregular correction: keep that alternative explicitly.
        if n <= 5 and sign * (last_bar.c - inv) <= 0:
            reasons.append("LIVE_PRICE_INVALIDATED_COUNT")
        leg = [b for b in bars if b.t >= p[-1].extreme_at]
        h = WaveHypothesis(
            f"{frame}:{'UP' if sign == 1 else 'DOWN'}:{p[0].extreme_at}:{n}",
            frame, "UP" if sign == 1 else "DOWN", label, p,
            last_bar.t + duration, p[-1].extreme_at,
            min(b.l for b in leg), max(b.h for b in leg), inv,
            "INVALIDATED" if reasons else ("ALTERNATIVE" if diagonal_overlap else "PROVISIONAL"), reasons)
        if diagonal_overlap and not reasons:
            h.alternatives.append("UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP")
        active_sign = -sign if n in (2, 4, 6, 8) else sign
        if n < 9 and not reasons and active_sign * (last_bar.c - p[-1].price) <= 0:
            h.status = "UNRESOLVED"
            h.alternatives.append("PRIOR_LEG_EXTENSION_OR_UNCONFIRMED_TURN")
        if n >= 6:
            h.terminal_fractal = True
            h.alternatives.append("TRUNCATED_W5" if price[5] <= price[3] else "EXTENDED_IMPULSE")
        if n >= 8 and price[7] >= price[5]:
            h.alternatives.append("IRREGULAR_CORRECTION")
        valid_ao = [v for v in osc[-5:] if isfinite(v)]
        h.momentum_shift = (len(valid_ao) == 5 and sign * valid_ao[-1] <
                            sign * sum(valid_ao) / 5 and
                            sign * valid_ao[-1] < sign * valid_ao[-2])
        terminal = p[min(5, n-1)].index
        for j in range(max(1, terminal-1), min(len(bars), terminal+2)):
            a, b = bars[j-1], bars[j]
            if a.v > 0 and b.v > a.v and (b.h-b.l)/b.v < (a.h-a.l)/a.v:
                h.squat = True
        out.append(h)
    return out


def link_wave_hypotheses(scenarios: Sequence[WaveHypothesis]) -> list[dict]:
    links = []
    for child in scenarios:
        if child.status != "PROVISIONAL":
            continue
        parents = [p for p in scenarios if p.status == "PROVISIONAL"
                   and FRAME_MS[p.frame] > FRAME_MS[child.frame]
                   and p.leg_start <= child.anchors[0].extreme_at
                   and child.observed_at <= p.observed_at
                   and all(p.leg_low <= a.price <= p.leg_high for a in child.anchors)]
        if not parents:
            continue
        nearest = min(FRAME_MS[p.frame] for p in parents)
        for parent in parents:
            if FRAME_MS[parent.frame] != nearest:
                continue
            expected = parent.direction
            if parent.label in ("W2", "W4", "A", "C"):
                expected = "DOWN" if expected == "UP" else "UP"
            links.append({"parent": parent.scenario_id, "child": child.scenario_id,
                          "parent_leg": parent.label,
                          "relationship": "ALIGNED" if child.direction == expected else "COUNTERTREND",
                          "proof": "TIME_AND_PRICE_CONTAINMENT", "wave_genealogy_confirmed": False})
    return links


def build_wave_hierarchy(frames: Mapping[str, Sequence[WaveBar]], as_of: int) -> WaveHierarchy:
    valid, errors, scenarios, warnings = {}, {}, [], []
    for frame in sorted(set(frames) | set(REQUIRED_FRAMES), key=lambda f: -FRAME_MS.get(f, 0)):
        try:
            if frame not in FRAME_MS:
                raise ValueError("UNSUPPORTED_FRAME")
            valid[frame] = closed_wave_bars(frames.get(frame, ()), frame, as_of)
        except (ValueError, TypeError, OverflowError) as exc:
            errors[frame] = str(exc)
    for frame, bars in valid.items():
        if frame not in REQUIRED_FRAMES:
            continue  # analytical resolution is not automatic parent genealogy
        hypotheses = hypotheses_from_anchors(frame, bars, confirmed_wave_anchors(bars, FRAME_MS[frame]))
        for h in hypotheses:
            # A daily candle is not fabricated up to the current hour. The
            # already-known daily leg is observed further using genuine closed
            # lower-frame candles, each retaining its own indicator resolution.
            observed = [b for f, series in valid.items() if FRAME_MS[f] <= FRAME_MS[frame]
                        for b in series if h.leg_start <= b.t and b.t + FRAME_MS[f] <= as_of]
            h.observed_at = as_of
            if observed:
                h.leg_low = min(b.l for b in observed)
                h.leg_high = max(b.h for b in observed)
                if len(h.anchors) <= 5 and (
                    h.direction == "UP" and h.leg_low <= h.invalidation or
                    h.direction == "DOWN" and h.leg_high >= h.invalidation
                ):
                    h.status = "INVALIDATED"
                    h.invalid_reasons.append("LOWER_FRAME_OBSERVED_INVALIDATION")
            _rhythm(h, valid)
            if h.status == "PROVISIONAL" and h.momentum_shift and len(h.anchors) >= 4:
                warnings.append({"scenario_id": h.scenario_id, "state": "EXHAUSTION_WARNING",
                                 "can_close": False, "can_reverse": False})
        scenarios.extend(hypotheses)
        osc = _ao(bars)
        if len(osc) >= 40 and all(isfinite(x) for x in osc[-5:]):
            sign = 1 if osc[-1] > 0 else -1
            if sign * osc[-1] < sign * osc[-2] < sign * osc[-3]:
                warnings.append({"scenario_id": frame + ":MOMENTUM_FADE_BEFORE_PIVOT",
                                 "state": "EXHAUSTION_WARNING", "can_close": False,
                                 "can_reverse": False})
    return WaveHierarchy(as_of, scenarios, link_wave_hypotheses(scenarios), errors, warnings,
                         {frame:len(bars) for frame,bars in valid.items()})


def suggested_analytical_frames(report: WaveHierarchy) -> list[str]:
    """At most two real native resolutions; never fabricate 100-140 samples."""
    choices = []
    for frame in ("6h","1d","1h"):
        candidates = [s for s in report.scenarios if s.frame==frame and s.status=="PROVISIONAL"
                      and len(s.anchors)>=4 and s.w4_zero!="MINIMUM_ONLY"]
        if not candidates:
            continue
        h = max(candidates,key=lambda s:len(s.anchors))
        duration = report.as_of-h.anchors[0].extreme_at
        options = [(abs(duration//step-120),name) for name,step in FRAME_MS.items()
                   if 100<=duration//step<=140]
        if options:
            choice=min(options)[1]
            if choice not in choices:
                choices.append(choice)
    return choices[:2]


def correction_entry_context(report: WaveHierarchy, side: str, signal_at: int,
                             signal_type: str) -> dict:
    """Project correction mode: a TC2 H1 Wise Man + structural D1/H6/H1/M15 proof.

    A conservative engineering policy, not a rule claiming the book mandates
    these particular four timeframes. Every ordinary risk/execution gate remains.
    """
    blocked = {"allowed": False, "mode": "CORRECTION", "reason": "NO_CONFIRMED_CORRECTION_PATH"}
    direction = {"LONG": "UP", "SHORT": "DOWN", "BUY": "UP", "SELL": "DOWN"}.get(side)
    if not direction or signal_type not in {"REVERSAL", "SHORT_REVERSAL", "SUPER_AO", "SHORT_SUPER_AO", "FRACTAL", "SHORT_FRACTAL"}:
        return blocked | {"reason": "CORRECTION_REQUIRES_H1_WISE_MAN"}
    if report.frame_errors:
        return blocked | {"reason": "CORRECTION_HISTORY_INCOMPLETE"}
    if signal_at <= 0 or signal_at + FRAME_MS["1h"] > report.as_of or report.as_of - signal_at > 5 * FRAME_MS["1h"]:
        return blocked | {"reason": "CORRECTION_H1_SIGNAL_NOT_CLOSED_OR_FRESH"}
    approvals = {}
    by_id = {s.scenario_id: s for s in report.scenarios if s.status == "PROVISIONAL"}
    for senior in by_id.values():
        if not (senior.frame == "6h" and senior.direction != direction
                and senior.label in {"W4", "A", "C"}
                and senior.w4_zero == "MINIMUM_ONLY" and senior.momentum_shift
                and senior.anchors[3].known_at <= signal_at + FRAME_MS["1h"]):
            continue
        daily = [e for e in report.links if e["child"] == senior.scenario_id
                 and by_id.get(e["parent"]) and by_id[e["parent"]].frame == "1d"]
        h1 = [by_id[e["child"]] for e in report.links if e["parent"] == senior.scenario_id
              and e["relationship"] == "ALIGNED" and by_id[e["child"]].frame == "1h"
              and by_id[e["child"]].direction == direction]
        for decision in h1:
            junior = [e for e in report.links if e["parent"] == decision.scenario_id
                      and e["relationship"] == "ALIGNED" and by_id[e["child"]].frame == "15m"
                      and by_id[e["child"]].direction == direction]
            if not daily or not junior:
                continue
            # An alternative at this same degree still counting W3/W5 in the
            # old direction cannot be silently discarded to approve a reversal.
            conflict = any(s.frame == "6h" and s.direction == senior.direction
                           and s.label in {"W3", "W5"} and s.w4_zero != "MINIMUM_ONLY"
                           and s.anchors[0].extreme_at <= senior.anchors[3].extreme_at
                           for s in by_id.values())
            if conflict:
                return blocked | {"reason": "COMPETING_SENIOR_IMPULSE_UNRESOLVED"}
            if senior.label == "W4":
                bounds = sorted([senior.anchors[1].price, senior.anchors[3].price])
            else:
                terminal = [senior.anchors[5].price]
                if len(senior.anchors) >= 8:
                    terminal.append(senior.anchors[7].price)
                bounds = [0.0, max(terminal)] if senior.direction == "UP" else [min(terminal), 0.0]
            approvals[senior.scenario_id] = {"allowed": True, "mode": "CORRECTION", "reason": "H1_WISE_MAN_IN_CONTAINED_H6_CORRECTION",
                    "parent_scenario_id": senior.scenario_id, "parent_invalidation": senior.invalidation,
                    "parent_floor": bounds[0], "parent_ceiling": bounds[1],
                    "decision_scenario_id": decision.scenario_id, "signal_at": signal_at}
    if len(approvals)==1:
        return next(iter(approvals.values()))
    if len(approvals)>1:
        return blocked | {"reason":"COMPETING_CORRECTION_PARENTS_UNRESOLVED"}
    return blocked


def correction_bounds_valid(context: dict) -> bool:
    try:
        lower = float(context["parent_floor"])
        upper = float(context["parent_ceiling"])
        return (all(isfinite(v) for v in (lower, upper)) and lower >= 0 and upper >= 0
                and (lower == 0 or upper == 0 or lower < upper) and (lower > 0 or upper > 0))
    except (TypeError, ValueError, KeyError):
        return False


def correction_price_allowed(context: dict, mark: float) -> bool:
    try:
        lower = float(context["parent_floor"])
        upper = float(context["parent_ceiling"])
        return (correction_bounds_valid(context) and isfinite(mark) and mark > 0
                and (lower == 0 or mark > lower) and (upper == 0 or mark < upper)
                )
    except (TypeError, ValueError, KeyError):
        return False


def opposing_level_triggered(side: str, trigger: float, signal_closed_at: int,
                             opened_at: int, mark: float, subsequent: Sequence[WaveBar],
                             now: int, expires_at: int) -> bool:
    """Detection is distinct from triggering; an older signal cannot close a new trade."""
    if (side not in {"LONG", "SHORT"} or not all(isfinite(v) and v > 0 for v in (trigger, mark))
            or opened_at <= 0 or not opened_at < signal_closed_at <= now
            or now >= expires_at):
        return False
    if side == "LONG":
        return mark >= trigger or any(b.h >= trigger for b in subsequent if b.t >= signal_closed_at)
    return mark <= trigger or any(b.l <= trigger for b in subsequent if b.t >= signal_closed_at)
