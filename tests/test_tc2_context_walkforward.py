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
