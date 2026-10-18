from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path

import pandas as pd
import pytest

from williams_exit_policy import plan_williams_stop, zone_trailing_activation
from williams_wave_hierarchy import (
    FRAME_MS, WaveAnchor, WaveBar, WaveHierarchy, WaveHypothesis,
    build_wave_hierarchy, confirmed_wave_anchors, correction_entry_context,
    correction_price_allowed, link_wave_hypotheses, opposing_level_triggered,
)

HOUR = FRAME_MS["1h"]


def rhythm_bars(mirror=False):
    """Synthetic causal price trajectory, not a claimed historical book replay."""
    knots = [(0,120),(50,100),(65,110),(78,106),(110,150),(145,118),(160,156),(170,154)]
    prices = []
    for (a,p),(b,q) in zip(knots,knots[1:]):
        prices += [p+(q-p)*(i-a)/(b-a) for i in range(a,b)]
    prices.append(knots[-1][1])
    if mirror:
        prices = [300-p for p in prices]
    return [WaveBar(i*HOUR,p,p+.1,p-.1,p,1000) for i,p in enumerate(prices)]


@pytest.mark.parametrize("mirror,direction", [(False,"UP"),(True,"DOWN")])
def test_real_sequence_bars_not_cached_bar_count_control_the_w4_scale(mirror,direction):
    bars = rhythm_bars(mirror)
    report = build_wave_hierarchy({"1h":bars},len(bars)*HOUR)
    senior = next(s for s in report.scenarios if s.label == "W5" and s.direction == direction)
    assert senior.status == "PROVISIONAL"
    assert senior.w4_zero == "MINIMUM_ONLY"
    assert senior.scale_bars == 121  # origin at bar 50, observed through bar 170
    assert senior.anchors[0].extreme_at == 50*HOUR
    assert senior.anchors[0].known_at == 53*HOUR
    assert senior.terminal_fractal is True
    # Another valid suffix is a competing smaller count, not erased by a winner.
    assert any(s.label == "W1" and s.status == "PROVISIONAL" for s in report.scenarios)
    assert not correction_entry_context(report,"LONG",169*HOUR,"REVERSAL")["allowed"]


def test_every_prefix_is_identical_with_or_without_all_future_candles():
    bars = rhythm_bars()
    for count in range(40,len(bars)+1,3):
        full = build_wave_hierarchy({"1h":bars},count*HOUR)
        prefix = build_wave_hierarchy({"1h":bars[:count]},count*HOUR)
        assert full.to_dict() == prefix.to_dict()
    # Even absurdly malformed FUTURE prices cannot affect a previous decision.
    future = bars[:120] + [replace(b,h=float("nan"),l=-99) for b in bars[120:]]
    assert build_wave_hierarchy({"1h":future},120*HOUR).to_dict() == build_wave_hierarchy({"1h":bars[:120]},120*HOUR).to_dict()


@pytest.mark.parametrize("defect", ["gap","duplicate","nan","stale","bad_ohlc"])
def test_unreliable_candle_history_does_not_create_a_wave_permission(defect):
    bars = rhythm_bars()
    now = len(bars)*HOUR
    if defect == "gap": del bars[60]
    if defect == "duplicate": bars[60] = replace(bars[60],t=bars[59].t)
    if defect == "nan": bars[60] = replace(bars[60],l=float("nan"))
    if defect == "bad_ohlc": bars[60] = replace(bars[60],c=10000)
    if defect == "stale": now += 2*HOUR
    report = build_wave_hierarchy({"1h":bars},now)
    assert "1h" in report.frame_errors
    assert not report.scenarios


def test_plateau_pivot_has_a_separate_real_confirmation_time():
    lows = [105,104,100,100,100,102,103]
    bars = [WaveBar(i*HOUR,l+1,l+2,l,l+1) for i,l in enumerate(lows)]
    pivots = confirmed_wave_anchors(bars,HOUR)
    assert [(p.kind,p.extreme_at,p.known_at) for p in pivots] == [("LOW",4*HOUR,7*HOUR)]
    assert confirmed_wave_anchors(bars[:-1],HOUR) == []


def test_equal_separate_pivots_do_not_break_long_short_reflection_symmetry():
    lows=[103,102,100,102,103,102,100,102,103]
    bars=[WaveBar(i*HOUR,105,110,l,105) for i,l in enumerate(lows)]
    mirrored=[replace(b,o=220-b.o,h=220-b.l,l=220-b.h,c=220-b.c) for b in bars]
    assert [p.index for p in confirmed_wave_anchors(bars,HOUR)]==[2]
    assert [p.index for p in confirmed_wave_anchors(mirrored,HOUR)]==[2]


def hypothesis(frame,direction,label,prices,times,leg_start,low,high):
    anchors = [WaveAnchor("LOW" if (i%2==0)==(direction=="UP") else "HIGH",p,i,t*HOUR,(t+3)*HOUR)
               for i,(p,t) in enumerate(zip(prices,times))]
    return WaveHypothesis(frame+":"+label,frame,direction,label,anchors,340*HOUR,
                          leg_start*HOUR,low,high,prices[1] if len(prices)>=4 else prices[0])


def correction_report(mirror=False):
    d = hypothesis("1d","DOWN","W3",[260,210,240],[5,20,50],50,50,280)
    senior = hypothesis("6h","DOWN","W4",[220,180,200,100,130],[80,100,200,300,320],300,95,180)
    senior.w4_zero = "MINIMUM_ONLY"
    senior.momentum_shift = True
    decision = hypothesis("1h","UP","W3",[110,145,130],[310,320,330],330,125,165)
    junior = hypothesis("15m","UP","W3",[131,143,137],[331,334,336],336,136,150)
    scenarios = [d,senior,decision,junior]
    if mirror:
        for h in scenarios:
            h.direction = "DOWN" if h.direction=="UP" else "UP"
            h.anchors = [replace(a,price=400-a.price,kind="HIGH" if a.kind=="LOW" else "LOW") for a in h.anchors]
            h.leg_low,h.leg_high = 400-h.leg_high,400-h.leg_low
            h.invalidation = 400-h.invalidation
    return WaveHierarchy(340*HOUR,scenarios,link_wave_hypotheses(scenarios),{},[])


def test_parent_requires_time_and_price_containment_and_nearest_degree():
    report = correction_report()
    links = {(e["parent"],e["child"]) for e in report.links}
    assert links == {("1d:W3","6h:W4"),("6h:W4","1h:W3"),("1h:W3","15m:W3")}
    # A stale child beginning before the active parent leg is not its subwave.
    report.scenarios[2].anchors[0] = replace(report.scenarios[2].anchors[0],extreme_at=290*HOUR)
    assert not any(e["parent"]=="6h:W4" and e["child"]=="1h:W3" for e in link_wave_hypotheses(report.scenarios))
    # Same timeframe numbers and direction cannot override a price contradiction.
    report = correction_report()
    report.scenarios[2].anchors[1] = replace(report.scenarios[2].anchors[1],price=300)
    assert not any(e["parent"]=="6h:W4" and e["child"]=="1h:W3" for e in link_wave_hypotheses(report.scenarios))


@pytest.mark.parametrize("mirror,side,mark", [(False,"LONG",150),(True,"SHORT",250)])
def test_contained_correction_mode_is_bidirectional_and_has_structural_price_bounds(mirror,side,mark):
    proof = correction_entry_context(correction_report(mirror),side,339*HOUR,"REVERSAL")
    assert proof["allowed"] is True
    assert proof["mode"] == "CORRECTION"
    assert proof["parent_scenario_id"] == "6h:W4"
    assert correction_price_allowed(proof,mark)
    assert not correction_price_allowed(proof,proof["parent_floor"])
    assert not correction_price_allowed(proof,proof["parent_ceiling"])


def test_post_w5_long_correction_uses_a_floor_instead_of_an_inverted_ceiling():
    r=correction_report()
    h=hypothesis("6h","DOWN","A",[220,200,210,150,180,120],[80,100,200,250,280,300],300,110,180)
    h.w4_zero="MINIMUM_ONLY";h.momentum_shift=True
    r.scenarios[1]=h;r.links=link_wave_hypotheses(r.scenarios)
    proof=correction_entry_context(r,"LONG",339*HOUR,"REVERSAL")
    assert proof["allowed"]
    assert proof["parent_floor"]==120 and proof["parent_ceiling"]==0
    assert correction_price_allowed(proof,150)
    assert not correction_price_allowed(proof,119)


def test_different_viable_correction_parents_are_not_silently_resolved_by_list_order():
    r=correction_report()
    other=deepcopy(r.scenarios[1]);other.scenario_id="another-parent"
    r.scenarios.append(other);r.links=link_wave_hypotheses(r.scenarios)
    result=correction_entry_context(r,"LONG",339*HOUR,"REVERSAL")
    assert not result["allowed"]
    assert result["reason"]=="COMPETING_CORRECTION_PARENTS_UNRESOLVED"


@pytest.mark.parametrize("defect", ["zero","history","conflict","future_signal","stale_signal","no_signal","no_junior"])
def test_wave_number_or_zero_cross_alone_never_authorizes_a_correction(defect):
    r = correction_report()
    signal_at,kind = 339*HOUR,"REVERSAL"
    if defect=="zero": r.scenarios[1].w4_zero="NEAR_ZERO_UNCONFIRMED"
    if defect=="history": r.frame_errors["15m"]="STALE"
    if defect=="conflict":
        h = deepcopy(r.scenarios[1]);h.scenario_id="competing";h.label="W3";h.w4_zero="NO_ZERO_CROSS";r.scenarios.append(h)
    if defect=="future_signal": signal_at=340*HOUR
    if defect=="stale_signal": signal_at=300*HOUR
    if defect=="no_signal": kind="AO_DIVERGENCE"
    if defect=="no_junior": r.links=[e for e in r.links if e["child"]!="15m:W3"]
    assert not correction_entry_context(r,"LONG",signal_at,kind)["allowed"]


@pytest.mark.parametrize("side,level,mark,triggered", [("LONG",110,109,False),("LONG",110,110,True),
                                                       ("SHORT",90,91,False),("SHORT",90,90,True)])
def test_opposite_signal_is_not_an_exit_until_the_level_is_triggered(side,level,mark,triggered):
    assert opposing_level_triggered(side,level,20,10,mark,[],30,50) is triggered
    assert not opposing_level_triggered(side,level,20,25,mark,[],30,50)
    assert not opposing_level_triggered(side,level,20,10,mark,[],50,50)


def test_a_trigger_crossed_during_scanner_downtime_remains_observable():
    closed = [WaveBar(20,100,111,99,105)]
    assert opposing_level_triggered("LONG",110,20,10,105,closed,30,50)


@pytest.mark.parametrize("side", ["LONG","SHORT"])
def test_tc2_five_bar_stop_moves_before_one_r_without_a_new_fractal(side):
    lows = [95,96,97,98,99]
    bars = [WaveBar(i*HOUR,l+1,l+2,l,l+1) for i,l in enumerate(lows)]
    if side=="SHORT":
        bars = [replace(b,o=200-b.o,h=200-b.l,l=200-b.h,c=200-b.c) for b in bars]
    got = plan_williams_stop(side,90 if side=="LONG" else 110,100,90 if side=="LONG" else 110,
                             104 if side=="LONG" else 96,.1,1,bars)
    assert got["reason"]=="TC2_LAST_5_CLOSED_BARS"
    assert got["stop"]==pytest.approx(94.9 if side=="LONG" else 105.1)
    unknown_r = plan_williams_stop(side,90 if side=="LONG" else 110,100,None,
                                   104 if side=="LONG" else 96,.1,1,bars)
    assert unknown_r==got


def test_ntd_zone_is_joint_ao_ac_colour_not_price_candle_colour_and_can_activate_after_gap():
    ind = pd.DataFrame({"open":[100]*10,"close":[101]*10,"ao":list(range(10)),"ac":list(range(10,0,-1))})
    assert zone_trailing_activation(ind,"BUY") is None
    # Red bodies do not prevent green ZONE activation.
    ind["open"],ind["close"],ind["ac"] = 102,100,list(range(10))
    assert zone_trailing_activation(ind,"BUY")["index"]==5
    # The fifth happened before the latest five bars: restart still finds it.
    ind.loc[6:,"ac"]=[-1,-2,-3,-4]
    assert zone_trailing_activation(ind,"BUY")["index"]==5


def test_shared_android_wave_contract_fixture():
    payload = json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_wave_contract.json").read_text())
    for case in payload["cases"]:
        bars = [WaveBar(*row) for row in case["bars"]]
        report = build_wave_hierarchy({"1h":bars},case["as_of"])
        actual = [{"label":s.label,"direction":s.direction,"status":s.status,
                   "w4_zero":s.w4_zero,"scale_bars":s.scale_bars} for s in report.scenarios]
        assert actual == case["expected"]


@pytest.mark.parametrize("mirror,side", [(False,"LONG"),(True,"SHORT")])
def test_four_frame_history_does_not_promote_w3_to_w4_and_false_approve_correction(mirror,side):
    """No hand-constructed scenarios/links or mocked indicator/signal detectors.

    The same nested synthetic OHLC history is consumed by Android. Higher
    candles were aggregated from a single M15 path, not independently invented.
    """
    from strategy import calculate_indicators, canonical_indicator_config_from_env
    from williams_signals import extract_long_signal_specs, extract_short_signal_specs

    payload=json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_correction_contract.json").read_text())
    frames={f:[WaveBar(*row) for row in rows] for f,rows in payload["frames"].items()}
    if mirror:
        total=payload["mirror_sum"]
        frames={f:[replace(b,o=total-b.o,h=total-b.l,l=total-b.h,c=total-b.c) for b in bars]
                for f,bars in frames.items()}
    report=build_wave_hierarchy(frames,payload["as_of"])
    assert not report.frame_errors
    assert report.to_dict()["contained_chain_observed"]
    h1=pd.DataFrame([{"open":b.o,"high":b.h,"low":b.l,"close":b.c,"volume":b.v} for b in frames["1h"]],
                    index=pd.to_datetime([b.t for b in frames["1h"]],unit="ms",utc=True))
    indicators=calculate_indicators(h1,canonical_indicator_config_from_env())
    detector=extract_short_signal_specs if mirror else extract_long_signal_specs
    signal=next(s for s in detector("BTCUSDT",indicators,timeframe="1h",tick_size=.1)
                if s.signal_type.value=="FRACTAL")
    assert signal.signal_bar_time_ms==payload["signal_at"]
    expected_trigger=payload["mirror_sum"]-payload["long_trigger"] if mirror else payload["long_trigger"]
    assert signal.trigger_price==pytest.approx(expected_trigger)
    proof=correction_entry_context(report,side,signal.signal_bar_time_ms,signal.signal_type.value)
    # Correct counting is not optional: the former 4-pivot "W4" was only
    # W3, so a countertrend approval here had been a false positive.
    assert not proof["allowed"]
    assert not any(s.frame=="6h" and s.label=="W4" and len(s.anchors)<5
                   for s in report.scenarios if s.status=="PROVISIONAL")
    assert any(s.frame=="6h" and s.label=="W3" for s in report.scenarios)
    # Missing junior evidence must revoke the very same real candle permission.
    missing=build_wave_hierarchy({f:bars for f,bars in frames.items() if f!="15m"},payload["as_of"])
    assert not correction_entry_context(missing,side,signal.signal_bar_time_ms,signal.signal_type.value)["allowed"]
    # Future higher and lower candles cannot change this already-known path.
    future={f:bars+[replace(bars[-1],t=bars[-1].t+FRAME_MS[f],h=float("nan"))] for f,bars in frames.items()}
    assert build_wave_hierarchy(future,payload["as_of"]).to_dict()==report.to_dict()


def test_overlapping_fourth_keeps_non_executable_diagonal_hypothesis():
    """An overlap is not an ordinary impulse, but a diagonal is possible."""
    from williams_wave_hierarchy import WaveAnchor, WaveBar, hypotheses_from_anchors

    hour = 3_600_000
    candles = [WaveBar(i*hour, 130.0,131.0,129.0,130.0,1000)
               for i in range(180)]
    points = [("LOW",100.,10),("HIGH",120.,30),("LOW",110.,50),
              ("HIGH",140.,75),("LOW",115.,100),("HIGH",150.,130)]
    def count(points):
        pivots = [WaveAnchor(k,p,i,i*hour,(i+3)*hour)
                  for k,p,i in points]
        return [h for h in hypotheses_from_anchors("1h",candles,pivots)
                if h.label=="W5"][0]
    diag=count(points)
    assert diag.status=="ALTERNATIVE"
    assert diag.invalid_reasons==[]
    assert "UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP" in diag.alternatives
    assert diag.status!="PROVISIONAL"
    invalid=count(points[:4]+[("LOW",109.,100)]+points[5:])
    assert invalid.status=="INVALIDATED"
    assert "W4_BREACHES_W3_OR_W4_END" in invalid.invalid_reasons
    ordinary=count(points[:4]+[("LOW",125.,100)]+points[5:])
    assert ordinary.status=="PROVISIONAL"
