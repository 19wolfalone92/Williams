"""Safety regressions for causal TC2 context and hypothetical exits."""
from __future__ import annotations

import copy
import json

import pytest

from research import tc2_context_walkforward as w


def bars(n=90, frame="1h", direction=1):
    ms=w.FRAMES[frame]
    arr=[]
    for i in range(n):
        px=100+direction*i*.1 + (i%7)*.2
        arr.append((w.START+i*ms, px-.1,px+1,px-1,px,3.0))
    return arr


def test_historical_input_is_really_complete_and_no_future_source():
    root=__import__("pathlib").Path(__file__).resolve().parents[1]/"research"/"data"
    for symbol in w.SYMBOLS:
        inp=w.load(symbol,root)
        assert len(inp["1h"])==7398
        assert len(inp["4h"])==1849
        assert len(inp["1d"])==308
        assert inp["1h"][-1][0]+w.HOUR<=w.END
        assert inp["1d"][-1][0]+w.FRAMES["1d"]<=w.END


def test_no_repainting_ao_alligator_or_confirmed_pivots():
    early=bars(80)
    late=early+bars(6)  # replace later timestamps with strictly future opens
    late=early+[(early[-1][0]+(i+1)*w.HOUR,*x[1:])
                for i,x in enumerate(bars(6))]
    a=w.indicators(early,"1h")
    b=w.indicators(late,"1h")
    for name in ("ao","jaw","teeth","lips","bias","last_high","prev_high",
                 "last_low","prev_low"):
        assert a[name][50:]==b[name][50:80]


def test_h4_d1_candle_close_is_always_before_signal_context():
    candles=bars(100,"4h")
    indicator=w.indicators(candles,"4h")
    at=candles[75][0]+w.HOUR
    k=w._frame_at(indicator,at)
    assert k==74
    assert indicator["close_times"][k]<=at
    assert indicator["close_times"][k+1]>at


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_stop_gap_worse_than_level_but_no_fictional_fill_guarantee(side):
    src=bars(75)
    ref=100
    src[50]=(src[50][0],101,102,99,101,4)
    event={"i":50,"owner":side,"exit_ref":ref}
    # Make the 5-bar historic stop unambiguously beyond the reference price.
    for j in range(46,51):
        src[j]=(src[j][0],100,102,90 if side=="LONG" else 99,101,5)
        if side=="SHORT":
            src[j]=(src[j][0],100,110,98,101,5)
    at=52
    if side=="LONG":
        src[at]=(src[at][0],80,82,79,81,5)
    else:
        src[at]=(src[at][0],120,122,118,119,5)
    out=w.replay(event,src,6,stop_bars=5)
    assert out["valid"]
    assert out["stop_hit"]
    assert out["delta_bps"]<0
    assert out["adverse_bps"]>0


def test_senior_lookahead_is_independent_of_unfinished_d1():
    h1=w.indicators(bars(150),"1h")
    h4=w.indicators(bars(150,"4h"),"4h")
    d1=w.indicators(bars(150,"1d"),"1d")
    event={"i":120,"owner":"LONG","decision_at":w.START+121*w.HOUR}
    base=w.regime(event,h1,h4,d1,bars(150,"4h"),bars(150,"1d"))
    assert base["label"] in {"NO_SENIOR_CAUSAL_HISTORY",
                             "SENIOR_ALIGNED","CONFLICTING","JUNIOR_COUNTERTREND_CANDIDATE"}
    assert not base.get("wave_degree_certified",False)


def test_invalid_gapped_or_future_data_must_be_rejected(tmp_path):
    root=__import__("pathlib").Path(__file__).resolve().parents[1]/"research"/"data"
    symbol="BTCUSDT"
    payload=json.loads((root/f"usdm_closed_2026_{symbol}.json").read_text())
    for damage in ("duplicate", "nan", "future", "wrong_venue"):
        data=copy.deepcopy(payload)
        if damage=="duplicate":
            data["frames"]["1h"][20][0]=data["frames"]["1h"][19][0]
        elif damage=="nan":
            data["frames"]["4h"][20][2]="NaN"
        elif damage=="future":
            data["frames"]["1d"][-1][0]+=w.FRAMES["1d"]
        else:
            data["source_endpoint"]="/api/v3/klines"
        (tmp_path/f"usdm_closed_2026_{symbol}.json").write_text(json.dumps(data))
        with pytest.raises(ValueError):
            w.load(symbol,tmp_path)

def test_wm3_confirmed_fractal_can_trigger_two_hours_later_but_prior_stop_may_already_be_hit():
    rows=[(w.START+i*w.HOUR,100.0,101.0,99.0,100.0,1.0)
          for i in range(90)]
    rows[48]=(rows[48][0],100.0,110.0,99.0,100.0,1.0)
    rows[52]=(rows[52][0],100.0,112.0,99.0,111.0,1.0)
    fake_indicators={"ao":[0.0]*90,
        "teeth":[100.0]*90,"jaw":[100.0]*90,"lips":[100.0]*90}
    observed=list(w.signals(rows,fake_indicators,.01))
    wm3=[e for e in observed if e["source"]=="WM3_FRACTAL"]
    assert len(wm3)==1
    assert wm3[0]["owner"]=="SHORT"
    assert wm3[0]["decision_at"]==rows[52][0]
    assert not wm3[0]["prior_5bar_stop_behind_trigger"]
    assert not w.replay(wm3[0],rows,6,stop_bars=5)["valid"]


def test_both_sides_in_single_hour_have_no_intrabar_ordering_authority():
    rows=[(w.START+i*w.HOUR,100.0,101.0,99.0,100.0,1.0)
          for i in range(90)]
    # Bearish and bullish WM3 fractals can coexist from preceding closed
    # bars, but both stops in same future hour are not serializable from OHLC.
    rows[46]=(rows[46][0],100.0,108.0,99.0,100.0,1.0)
    rows[48]=(rows[48][0],100.0,110.0,90.0,100.0,1.0)
    rows[52]=(rows[52][0],100.0,112.0,85.0,100.0,1.0)
    inds={"ao":[0.0]*90,"teeth":[100.0]*90,
          "jaw":[100.0]*90,"lips":[100.0]*90}
    events=list(w.signals(rows,inds,.01))
    assert all(e["decision_at"]!=rows[52][0] for e in events)


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_causal_trailing_stop_cannot_widen_and_is_not_static(side):
    sign=1 if side=="LONG" else -1
    rows=[]
    for i in range(28):
        close=100+sign*i*.6
        rows.append((w.START+i*w.HOUR,close-.2,close+2,close-2,close,3.0))
    idx=10
    reference=100+sign*idx*.6
    original=w.replay({"i":idx,"owner":side,"exit_ref":reference},
                      rows,6,stop_bars=5,trailing=False)
    trailed=w.replay({"i":idx,"owner":side,"exit_ref":reference},
                     rows,6,stop_bars=5,trailing=True)
    assert original["valid"] and trailed["valid"]
    assert not original["stop_hit"] and not trailed["stop_hit"]
    assert trailed["trailing_tightening_events"]>0
    assert (trailed["final_stop_reference"]>trailed["initial_stop_reference"]
            if side=="LONG" else
            trailed["final_stop_reference"]<trailed["initial_stop_reference"])
    assert trailed["stop_model"]=="TRAILING_3_OR_5_PREVIOUS_CLOSED_H1"
    assert not trailed["no_intrabar_sequence_or_bid_ask"] is False


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_dynamic_stop_uses_prebar_candles_and_gap_worse_than_trigger(side):
    sign=1 if side=="LONG" else -1
    rows=[]
    for i in range(25):
        close=100+sign*i*.6
        rows.append((w.START+i*w.HOUR,close-.2,close+2,close-2,close,3.0))
    idx=10
    reference=100+sign*idx*.6
    bad=15
    bad_open=100+sign*3.1
    bad_hi=bad_open+1.3
    bad_lo=bad_open-1.3
    rows[bad]=(rows[bad][0],bad_open,bad_hi,bad_lo,bad_open,3.0)
    event={"i":idx,"owner":side,"exit_ref":reference}
    original=w.replay(event,rows,6,stop_bars=5,trailing=False)
    trailing=w.replay(event,rows,6,stop_bars=5,trailing=True)
    assert original["valid"] and trailing["valid"]
    assert not original["stop_hit"]
    assert trailing["stop_hit"]
    assert trailing["exit_bar_open_ms"]==rows[bad][0]
    # Gap beyond tightened protective STOP must not be priced at ideal stop.
    assert trailing["exit_reference"]==bad_open
    assert (trailing["exit_reference"]<trailing["final_stop_reference"]
            if side=="LONG" else
            trailing["exit_reference"]>trailing["final_stop_reference"])


def test_trailing_replay_future_perturbation_cannot_change_past_closed_horizon():
    rows=bars(40)
    # An unambiguous original stop on the correct side of the LONG ref.
    event={"i":10,"owner":"LONG","exit_ref":105.0}
    known=w.replay(event,rows,3,stop_bars=3,trailing=True)
    altered=list(rows)
    k=20
    altered[k]=(rows[k][0],999.0,1000.0,998.0,999.0,3.0)
    assert w.replay(event,altered,3,stop_bars=3,trailing=True)==known


def test_senior_h4_and_d1_independent_confirmed_swings_are_required_for_strict_shadow():
    # Construct a historical H1 decision at UTC day 60. The latest D1
    # candle allowed is day 59; anything from day 60 must not be read.
    at=w.START+60*w.FRAMES["1d"]
    i=60*24-1
    event={"i":i,"owner":"LONG","decision_at":at}
    def frame(frame, n):
        return {"close_times":[w.START+(k+1)*w.FRAMES[frame] for k in range(n)],
            "bias":["UP"]*n,"ao":[1.0]*n,
            "last_high":[90.0]*n,"prev_high":[80.0]*n,
            "last_low":[70.0]*n,"prev_low":[60.0]*n}
    h1=frame("1h",1500);h1["bias"][i]="DOWN"
    h4=frame("4h",400);d1=frame("1d",100)
    candles4=[(w.START+k*w.FRAMES["4h"],99.0,101.0,98.0,100.0,2.0)
              for k in range(400)]
    candles1=[(w.START+k*w.FRAMES["1d"],99.0,101.0,98.0,100.0,2.0)
              for k in range(100)]
    observed=w.regime(event,h1,h4,d1,candles4,candles1)
    assert observed["hold"]
    assert observed["hold_structure"]
    assert observed["hold_h4_and_d1_structure"]
    assert observed["h4_swing_pattern_confirmed"] and observed["d1_swing_pattern_confirmed"]
    # A D1 sequence that is unverified at cutoff must NOT masquerade as
    # independently confirmed senior trend. The permissive baseline is
    # only a hypothesis; the STRICT research variant has no hold.
    d1["prev_low"][59]=72.0
    observed=w.regime(event,h1,h4,d1,candles4,candles1)
    assert observed["hold"]
    assert observed["hold_structure"]
    assert not observed["hold_h4_and_d1_structure"]
    assert not observed["d1_swing_pattern_confirmed"]


def test_all_three_shadow_cohorts_are_monotone_subsets_by_definition():
    root=__import__("pathlib").Path(__file__).resolve().parents[1]/"research"/"data"
    report=w.evaluate(root)
    assert report["instrument_count"]==10
    assert report["holdout"]["all_candidates"]>0
    for group in ("development","holdout"):
        baseline=report[group]["h4_d1_only"]["proposed_holds"]
        h4=report[group]["h4_swing_confirmed"]["proposed_holds"]
        senior=report[group]["both_h4_d1_swings_confirmed"]["proposed_holds"]
        assert 0<=senior<=h4<=baseline
        for name in ("h4_d1_only","h4_swing_confirmed","both_h4_d1_swings_confirmed"):
            metrics=report[group][name]
            for horizon in (6,24):
                for n in (3,5):
                    static=metrics[f"{horizon}h_stop{n}"]
                    dynamic=metrics[f"{horizon}h_trail{n}"]
                    # Both compare the SAME eligible events; no selective
                    # deletion of losing historical STOP outcomes is allowed.
                    assert static["n"]==dynamic["n"]
                    assert static["ineligible_historic_stop"]==dynamic["ineligible_historic_stop"]
                    assert dynamic["trailing_stop_tightening_events"]>=0
    assert not report["execution_authority"]
    assert not report["profitability_certified"]
