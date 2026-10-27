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
from williams_wave_subdivisions import enrich_complex_subdivisions, trace_recursive_price_grammar
from williams_wave_momentum import evaluate_third_momentum, ordered_sum, third_momentum_unavailable

FRAME_MS = {"1m":60_000,"3m":180_000,"5m":300_000,"15m": 900_000, "30m": 1_800_000, "1h": 3_600_000,
            "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
            "8h": 28_800_000, "12h": 43_200_000, "1d": 86_400_000}
REQUIRED_FRAMES = ("1d", "6h", "1h", "15m")


def wave_bars_from_dataframe(frame):
    """Use exchange timestamps; never invent timestamps for an analytical scale."""
    import pandas as pd
    bars = []
    for index, row in frame.iterrows():
        stamp = row.get("open_time_ms", row.get("open_time", index))
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
    leg_observation_start: int = 0
    third_momentum: dict | None = None
    observed_close_price: float = 0.0

    @property
    def active_label(self):
        # label describes the LAST CONFIRMED leg. Price observed after the last
        # pivot belongs to the NEXT provisional leg, not that completed leg.
        return {2:"W2",3:"W3",4:"W4",5:"W5",6:"A",7:"B",8:"C"}.get(len(self.anchors),"NEXT_SEQUENCE_UNRESOLVED")

    @property
    def active_direction(self):
        return ("DOWN" if self.direction=="UP" else "UP") if self.active_label in {"W2","W4","A","C"} else self.direction

    def to_dict(self):
        return asdict(self) | {"active_label":self.active_label,"active_direction":self.active_direction,
                              "last_confirmed_leg":self.label,
                              "invalidation_scope":("DEVELOPING_MOTIVE" if len(self.anchors)<6
                                  else "HISTORICAL_REFERENCE_NOT_ACTIVE_INVALIDATION")}


@dataclass
class WaveHierarchy:
    as_of: int
    scenarios: list[WaveHypothesis]
    links: list[dict]
    frame_errors: dict[str, str]
    warnings: list[dict]
    frame_candle_counts: dict[str,int] = field(default_factory=dict)
    complex_structures: list[dict] = field(default_factory=list)
    analysis_frames: tuple[str, ...] = REQUIRED_FRAMES

    def to_dict(self):
        by_id={s.scenario_id:s for s in self.scenarios}
        edges={(e["parent"],e["child"]) for e in self.links}
        def path(node,index):
            if index==len(self.analysis_frames)-1:
                return True
            next_frame=self.analysis_frames[index+1]
            # A real H4/H2 (or other available resolution) may sit between
            # the required frames. Its insertion must not erase the chain.
            return any(p==node and by_id.get(c) and
                       FRAME_MS[by_id[node].frame]>FRAME_MS[by_id[c].frame]>=FRAME_MS[next_frame] and
                       path(c,index+1 if by_id[c].frame==next_frame else index)
                       for p,c in edges)
        contained=bool(self.analysis_frames) and any(s.frame==self.analysis_frames[0] and path(s.scenario_id,0) for s in self.scenarios)
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
        return asdict(self) | {"scenarios":[s.to_dict() for s in self.scenarios],"complex_wave_patterns":(self.complex_structures or complex_patterns)[:48],
                              "schema_version": 1,
                              "wave_labels": "HYPOTHESES_NOT_CERTAINTY",
                              "chain": list(self.analysis_frames),"contained_chain_observed":contained,
                              "analysed_frames":sorted(self.frame_candle_counts,key=lambda f:-FRAME_MS[f]),
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
    # The latest fully closed candle must be present. At a new H1/H4
    # boundary an older close is *already* stale; never infer a wave from a
    # silently missing whole period. Keep parity with Android's closed bars.
    if as_of < closed[-1].t + duration or as_of - (closed[-1].t + duration) >= duration:
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


def wave_leg_observation_start(anchor,frame,frames):
    """First bar boundary definitely after the latest parent extreme.

    A parent's OHLC cannot establish whether its opposite extreme came before
    or after its pivot. Use the final matching extreme in the finest complete
    available subdivision of that parent candle; never guess intrabar order.
    """
    end=anchor.extreme_at+FRAME_MS[frame]
    for f in sorted(frames,key=lambda x:FRAME_MS[x]):
        duration=FRAME_MS[f]
        if duration>FRAME_MS[frame]:continue
        part=[b for b in frames[f] if anchor.extreme_at<=b.t<end]
        if not part or part[0].t!=anchor.extreme_at or part[-1].t+duration!=end:continue
        matches=[b for b in part if abs((b.h if anchor.kind=="HIGH" else b.l)-anchor.price)<=max(1e-9,anchor.price*1e-9)]
        if matches:return matches[-1].t+duration
    return end


def _ao(bars):
    mid = [(b.h + b.l) / 2 for b in bars]
    return [ordered_sum(mid[i-4:i+1]) / 5 - ordered_sum(mid[i-33:i+1]) / 34
            if i >= 33 else float("nan") for i in range(len(mid))]


def classify_fourth_ao(third_peak: float, fourth_minimum: float, scale_bars: int) -> str:
    """Course PDF44 / TC1 PDF63: pass zero; touching is unconfirmed.

    Inputs are direction-normalized: W3 momentum is positive and a real
    W4 crossing is negative. The same rule therefore covers both directions.
    """
    if not 100 <= scale_bars <= 140:
        return "WRONG_SCALE"
    if not (isfinite(third_peak) and third_peak > 0 and isfinite(fourth_minimum)):
        return "MISSING_W3_MOMENTUM"
    if fourth_minimum < 0:
        return "MINIMUM_ONLY"
    # Ten percent is a documented research heuristic, not a book gate.
    if fourth_minimum / third_peak <= .10:
        return "NEAR_ZERO_UNCONFIRMED"
    return "NO_ZERO_CROSS"


def _rhythm(h: WaveHypothesis, frames: Mapping[str, list[WaveBar]]) -> None:
    if len(h.anchors) < 4:
        return
    start = h.anchors[0].extreme_at
    stop = h.observed_at
    # Count actual bars belonging to this proposed sequence. Cached history
    # length and a 140-bar screen window do not determine its wave degree.
    options = []
    for frame, bars in frames.items():
        # The wave's proposed parent degree cannot borrow senior oscillators.
        # TC1 pp.61-63 instead switches to a *finer real resolution* until
        # the SAME price sequence occupies 100-140 genuine bars.
        if FRAME_MS[frame] > FRAME_MS[h.frame]:
            continue
        indexes = [i for i, b in enumerate(bars) if b.t >= start and b.t < stop]
        if (not indexes or indexes[0] < 33 or bars[0].t > start
                or bars[indexes[0]].t - start >= FRAME_MS[frame]):
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

    # Locate the actual price extreme on the chosen genuine scale. The open
    # time of a senior candle is not the time of its intrabar extreme.
    def unique_price_index(anchor):
        candidates=[i for i,b in enumerate(bars)
            if anchor.extreme_at<=b.t<anchor.extreme_at+FRAME_MS[h.frame]
            and abs((b.h if anchor.kind=="HIGH" else b.l)-anchor.price)<=max(1e-9,anchor.price*1e-9)]
        return candidates[0] if len(candidates)==1 and FRAME_MS[frame]<=FRAME_MS[h.frame] else None

    third_index=unique_price_index(p[3])
    fourth_index=unique_price_index(p[4]) if len(p)>=5 else indexes[-1]
    h.third_momentum=(evaluate_third_momentum(bars,osc,indexes[0],third_index,fourth_index,
        h.direction,FRAME_MS[frame]) if third_index is not None and fourth_index is not None else
        third_momentum_unavailable("SCALE_PRICE_EXTREME_MISSING_OR_AMBIGUOUS"))
    # The wrong 100-140 scale is a primary evidence failure, even if the
    # precise price-extreme timestamp is also unknown. No wave-4 minimum.
    if not 100 <= h.scale_bars <= 140:
        h.w4_zero = "WRONG_SCALE"
        return
    # If the parent candle's price extreme has several possible lower-frame
    # timestamps, assigning an AO bar to W3/W4 is only a guess. Keep W4
    # minimum evidence UNKNOWN, never upgrade an ambiguous genealogy.
    if third_index is None or fourth_index is None:
        h.w4_zero = "SCALE_PRICE_EXTREME_MISSING_OR_AMBIGUOUS"
        return

    def peak(a, b):
        values = [sign * osc[i] for i in indexes
                  if a <= bars[i].t <= b and isfinite(osc[i])]
        return max(values) if values else None

    # The oscillator can lag the price extreme by 1-5 bars (TC1 p60).
    third_end = p[3].extreme_at + 5 * FRAME_MS[frame]
    if len(p) >= 5:
        third_end = min(third_end, p[4].extreme_at)
    w3_indexes = [i for i in indexes if p[2].extreme_at <= bars[i].t <= third_end]
    # No NaN skipping or arbitrary winner in a tied global W3 momentum peak.
    # Otherwise a later AO value can turn an unknown degree into a false W4.
    if not w3_indexes or any(not isfinite(osc[i]) for i in w3_indexes):
        h.w4_zero = "MISSING_W3_MOMENTUM"
        return
    third_peak = max(sign * osc[i] for i in w3_indexes)
    winners = [i for i in w3_indexes if sign * osc[i] == third_peak]
    if len(winners) != 1:
        h.w4_zero = "AMBIGUOUS_W3_MOMENTUM_PEAK"
        return
    w3 = winners[0]
    end = p[4].extreme_at if len(p) >= 5 else stop
    w4_indexes = [i for i in indexes if i > w3 and bars[i].t <= end]
    if not w4_indexes or any(not isfinite(osc[i]) for i in w4_indexes):
        h.w4_zero = "MISSING_W4_MOMENTUM"
        return
    fourth_values = [sign * osc[i] for i in w4_indexes]
    h.w4_zero = classify_fourth_ao(third_peak, min(fourth_values),
                                  h.scale_bars)
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


def terminal_squat_volume_proxy(bars, direction, start, end):
    """TC1 PDF53 / Course PDF77: squat among the three extreme W5 bars.

    Base-asset volume is only a declared proxy for the original tick volume.
    Neighbours of the terminal pivot are not necessarily extreme price bars.
    """
    if (direction not in {"UP","DOWN"} or not 0 <= start < len(bars)
            or not 0 <= end < len(bars) or end-start < 2):
        return False
    indexes = list(range(start,end+1))
    for i in indexes:
        b = bars[i]
        if (not all(isfinite(x) for x in (b.o,b.h,b.l,b.c,b.v))
                or b.h < max(b.o,b.l,b.c) or b.l > min(b.o,b.c)
                or b.l <= 0 or b.v < 0
                or i > 0 and (not isfinite(bars[i-1].v) or bars[i-1].v < 0)):
            return False
    def ranked(i):
        return bars[i].h if direction == "UP" else -bars[i].l
    third = sorted((ranked(i) for i in indexes),reverse=True)[2]
    return any(i > 0 and ranked(i) >= third and bars[i-1].v > 0
               and bars[i].v > bars[i-1].v
               and (bars[i].h-bars[i].l)/bars[i].v < (bars[i-1].h-bars[i-1].l)/bars[i-1].v
               for i in indexes)


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
        # Course PDF46/48: W2 may retrace UP TO 100% of W1, never past it.
        # A touch of the exact origin remains an unverified alternative, not
        # an invalidated count nor an executable confirmed Elliott degree.
        w2_origin_touch = n >= 3 and price[2] == price[0]
        if n >= 3 and not price[0] <= price[2] < price[1]:
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
        # A motive sequence has FIVE LEGS but SIX pivot endpoints.
        # Previous mapping treated two pivots as W2 and six as A; that
        # off-by-one silently misclassified late W5 as a correction.
        label = f"W{n-1}" if n <= 6 else {7:"A", 8:"B", 9:"C"}[n]
        if n >= 7 and price[6] >= price[5]:
            reasons.append("A_NOT_CORRECTIVE")
        if n >= 8 and price[7] <= price[6]:
            reasons.append("B_NOT_RETRACING_A")
        if n >= 9 and price[8] >= price[6]:
            reasons.append("C_NOT_EXTENDING_A_COMPLEX_CORRECTION_POSSIBLE")
        inv = p[0].price if n <= 3 else p[2].price if n<=6 and diagonal_overlap else p[1].price if n <= 6 else p[5].price
        last_bar = bars[-1]
        # A/C must remain on the corrective side of the old W5. B may exceed
        # it in an irregular correction: keep that alternative explicitly.
        # A complete five-leg sequence is historical evidence. Its following
        # A-wave may retrace the entire motive without erasing those five legs.
        if n < 6 and (sign * (last_bar.c - inv) < 0 if n <= 3
                          else sign * (last_bar.c - inv) <= 0):
            reasons.append("LIVE_PRICE_INVALIDATED_COUNT")
        leg = [b for b in bars if b.t >= p[-1].extreme_at]
        h = WaveHypothesis(
            f"{frame}:{'UP' if sign == 1 else 'DOWN'}:{p[0].extreme_at}:{n}",
            frame, "UP" if sign == 1 else "DOWN", label, p,
            last_bar.t + duration, p[-1].extreme_at,
            min(b.l for b in leg), max(b.h for b in leg), inv,
            "INVALIDATED" if reasons else ("ALTERNATIVE" if diagonal_overlap or w2_origin_touch else "PROVISIONAL"), reasons,
            observed_close_price=last_bar.c)
        if w2_origin_touch and not reasons:
            h.alternatives.append("W2_ORIGIN_TOUCH_100_PERCENT_UNCONFIRMED")
        if diagonal_overlap and not reasons:
            h.alternatives.append("UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP")
            # Course PDF46 permits shallow overlap in futures. The percentage
            # denominator is not formalized there; expose this explicit W1-range
            # interpretation as an alternative, never an automatic order permit.
            if (price[1]-price[4])/(price[1]-price[0])<=.10:
                h.alternatives.append("FUTURES_SHALLOW_W4_OVERLAP_10_PERCENT_W1_RANGE_POLICY")
        active_sign = -sign if n in (2, 4, 6, 8) else sign
        if n < 9 and h.status == "PROVISIONAL" and active_sign * (last_bar.c - p[-1].price) <= 0:
            h.status = "UNRESOLVED"
            h.alternatives.append("PRIOR_LEG_EXTENSION_OR_UNCONFIRMED_TURN")
        if n >= 6:
            h.terminal_fractal = True
            h.alternatives.append("TRUNCATED_W5" if price[5] <= price[3] else "EXTENDED_IMPULSE")
        if n >= 8 and price[7] >= price[5]:
            h.alternatives.append("IRREGULAR_CORRECTION")
        valid_ao = [v for v in osc[-5:] if isfinite(v)]
        h.momentum_shift = (len(valid_ao) == 5 and sign * valid_ao[-1] <
                            sign * ordered_sum(valid_ao) / 5 and
                            sign * valid_ao[-1] < sign * valid_ao[-2])
        terminal = p[min(5, n-1)].index
        if n >= 6:
            h.squat = terminal_squat_volume_proxy(bars,h.direction,p[4].index,terminal)
        else:
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
            expected = parent.active_direction
            links.append({"parent": parent.scenario_id, "child": child.scenario_id,
                          "parent_leg": parent.active_label,
                          "relationship": "ALIGNED" if child.direction == expected else "COUNTERTREND",
                          "proof": "TIME_AND_PRICE_CONTAINMENT", "wave_genealogy_confirmed": False})
    return links


def build_wave_hierarchy(frames: Mapping[str, Sequence[WaveBar]], as_of: int,
                         *, analysis_frames=REQUIRED_FRAMES) -> WaveHierarchy:
    valid, errors, scenarios, warnings = {}, {}, [], []
    for frame in sorted(set(frames) | set(analysis_frames), key=lambda f: -FRAME_MS.get(f, 0)):
        try:
            if frame not in FRAME_MS:
                raise ValueError("UNSUPPORTED_FRAME")
            valid[frame] = closed_wave_bars(frames.get(frame, ()), frame, as_of)
        except (ValueError, TypeError, OverflowError) as exc:
            errors[frame] = str(exc)
    for frame, bars in valid.items():
        hypotheses = hypotheses_from_anchors(frame, bars, confirmed_wave_anchors(bars, FRAME_MS[frame]))
        for h in hypotheses:
            # A daily candle is not fabricated up to the current hour. The
            # already-known daily leg is observed further using genuine closed
            # lower-frame candles, each retaining its own indicator resolution.
            h.leg_observation_start=wave_leg_observation_start(h.anchors[-1],frame,valid)
            observed = [b for f, series in valid.items() if FRAME_MS[f] <= FRAME_MS[frame]
                        for b in series if h.leg_observation_start <= b.t and b.t + FRAME_MS[f] <= as_of]
            h.observed_at = as_of
            if observed:
                h.leg_low = min(h.anchors[-1].price,min(b.l for b in observed))
                h.leg_high = max(h.anchors[-1].price,max(b.h for b in observed))
                if len(h.anchors) < 6 and (
                    h.direction == "UP" and (
                        min(b.l for b in observed) < h.invalidation if len(h.anchors) <= 3
                        else min(b.l for b in observed) <= h.invalidation
                    ) or h.direction == "DOWN" and (
                        max(b.h for b in observed) > h.invalidation if len(h.anchors) <= 3
                        else max(b.h for b in observed) >= h.invalidation
                    )
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
    structures=[]
    anchors_by_frame={frame:confirmed_wave_anchors(bars,FRAME_MS[frame]) for frame,bars in valid.items()}
    for frame in valid:
        lower=[f for f in valid if FRAME_MS[f]<FRAME_MS[frame]]
        child_frame=max(lower,key=lambda f:FRAME_MS[f]) if lower else ""
        points=anchors_by_frame[frame]
        for direction in ("UP","DOWN"):
            for evidence in detect_complex_wave_patterns(points,direction=direction,as_of=as_of):
                parent=[p for p in points if evidence.start_at<=p.extreme_at<=evidence.end_at]
                proof=enrich_complex_subdivisions(evidence.pattern,parent,anchors_by_frame.get(child_frame,[]),
                    parent_duration=FRAME_MS[frame],as_of=as_of)
                recursive=trace_recursive_price_grammar(
                    evidence.pattern,parent,anchors_by_frame,FRAME_MS,frame,as_of,
                    depth=3,max_nodes=64)
                structures.append(evidence.to_dict()|{"frame":frame,"child_frame":child_frame,
                    "source_scenario_id":f"{frame}:{direction}:{evidence.start_at}:{len(parent)}",
                    "internal_proof":proof.to_dict(),"recursive_proof":recursive,
                    "can_place_orders":False})
    return WaveHierarchy(as_of, scenarios, link_wave_hypotheses(scenarios), errors, warnings,
                         {frame:len(bars) for frame,bars in valid.items()},structures,tuple(analysis_frames))


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
                and senior.active_label in {"W4", "A", "C"}
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
                           and s.active_label in {"W3", "W5"} and s.w4_zero != "MINIMUM_ONLY"
                           and s.anchors[0].extreme_at <= senior.anchors[3].extreme_at
                           for s in by_id.values())
            if conflict:
                return blocked | {"reason": "COMPETING_SENIOR_IMPULSE_UNRESOLVED"}
            if senior.active_label == "W4":
                bounds = sorted([senior.anchors[1].price, senior.anchors[3].price])
            else:
                terminal = [senior.anchors[5].price]
                if len(senior.anchors) >= 8:
                    terminal.append(senior.anchors[7].price)
                bounds = [0.0, max(terminal)] if senior.direction == "UP" else [min(terminal), 0.0]
            approvals[senior.scenario_id] = {"allowed": True, "mode": "CORRECTION", "reason": "H1_WISE_MAN_IN_CONTAINED_H6_CORRECTION",
                    "parent_scenario_id": senior.scenario_id, "parent_invalidation": senior.invalidation,
                    "parent_floor": bounds[0], "parent_ceiling": bounds[1],
                    "decision_scenario_id": decision.scenario_id, "signal_at": signal_at,
                    "parent_active_leg":senior.active_label,"parent_last_confirmed_leg":senior.label}
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
                             now: int, expires_at: int, frame_ms: int = 3_600_000,
                             invalidation: float | None = None) -> bool:
    """Use the same causal event policy as scenario admission and position exits."""
    from williams_scenario_lifecycle import evaluate_level_event
    return evaluate_level_event(side,trigger,signal_closed_at,opened_at,mark,subsequent,
                                now,expires_at,frame_ms,invalidation).state == "TRIGGERED"
