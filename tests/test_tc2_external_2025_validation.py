"""2025 cross-year robustness: frozen public Futures H1/H4/D1, never live orders."""
from __future__ import annotations
import copy
import json
from pathlib import Path
import pytest
from research import tc2_context_walkforward as source
from research import tc2_external_2025_validation as ext

ROOT = Path(__file__).resolve().parents[1]/"research"/"data_2025"

def test_all_ten_2025_sources_are_complete_and_future_closed():
    assert len(source.SYMBOLS)==10
    total=0
    for symbol in source.SYMBOLS:
        frames=ext.load_2025(symbol,ROOT)
        assert {k:len(v) for k,v in frames.items()}==ext.FRAME_COUNTS
        for name,rows in frames.items():
            dt=source.FRAMES[name]
            assert rows[0][0]==ext.START_2025
            assert rows[-1][0]+dt==ext.END_2025
            assert all(rows[i][0]+dt==rows[i+1][0] for i in range(len(rows)-1))
        total+=sum(map(len,frames.values()))
    assert total==113150

def test_bad_source_or_missing_candles_are_rejected(tmp_path):
    sample=json.loads((ROOT/"usdm_closed_2025_BTCUSDT.json").read_text())
    for damage in ("wrong_year","spot","future","duplicate","missing","nan",
                   "negative","wrong_columns","fake_execution"):
        obj=copy.deepcopy(sample)
        if damage=="wrong_year": obj["requested_from_utc"]="2025-02-01T00:00:00Z"
        elif damage=="spot": obj["source_endpoint"]="/api/v3/klines"
        elif damage=="future": obj["frames"]["1d"][-1][0]+=86400000
        elif damage=="duplicate": obj["frames"]["1h"][1][0]=obj["frames"]["1h"][0][0]
        elif damage=="missing": obj["frames"]["4h"].pop()
        elif damage=="nan": obj["frames"]["1d"][50][4]="NaN"
        elif damage=="negative": obj["frames"]["1h"][100][3]=-1
        elif damage=="wrong_columns": obj["columns"]=["a","b"]
        else: obj["execution_authority"]=True
        (tmp_path/"usdm_closed_2025_BTCUSDT.json").write_text(json.dumps(obj))
        with pytest.raises(ValueError):
            ext.load_2025("BTCUSDT",tmp_path)

def test_2025_closed_h4_d1_indicators_are_unchanged_by_future_bars():
    bars=ext.load_2025("BTCUSDT",ROOT)
    for frame in ("4h","1d"):
        x=bars[frame]
        prefix=source.indicators(x[:100],frame)
        longer=source.indicators(x[:120],frame)
        for k in ("ao","jaw","teeth","lips","bias",
                  "last_high","prev_high","last_low","prev_low"):
            assert prefix[k][55:100]==longer[k][55:100]
        now=x[100][0]+source.FRAMES[frame]
        assert source._frame_at(longer,now)==100
        assert source._frame_at(longer,now-1)==99

def test_2025_calendar_quarters_are_complete_exclusive_and_stable():
    q=ext.QUARTERS_2025
    assert [x[0] for x in q]==["2025-Q1","2025-Q2","2025-Q3","2025-Q4"]
    assert q[0][1]==ext.START_2025 and q[-1][2]==ext.END_2025
    assert all(q[i][2]==q[i+1][1] for i in range(len(q)-1))
    assert sum((hi-lo)//source.HOUR for _,lo,hi in q)==8760

def test_2025_research_summary_has_no_broker_mutations():
    f=ext.load_2025("ETHUSDT",ROOT)
    h1=source.indicators(f["1h"],"1h")
    h4=source.indicators(f["4h"],"4h")
    d1=source.indicators(f["1d"],"1d")
    available=list(source.signals(f["1h"],h1,source.TICK_ASSUMED["ETHUSDT"]))
    assert available, "Research archive should contain some source candidates"
    e=available[len(available)//2]
    c=source.regime(e,h1,h4,d1,f["4h"],f["1d"])
    assert c["wave_degree_certified"] is False
    assert all(isinstance(c[k],bool) for k in (
        "hold","hold_structure","hold_h4_and_d1_structure"))
    assert not c["hold_h4_and_d1_structure"] or c["hold_structure"]
    assert not c["hold_structure"] or c["hold"]
    for trailing in (False,True):
        result=source.replay(e,f["1h"],24,5,trailing=trailing)
        assert result["valid"] is False or result["no_intrabar_sequence_or_bid_ask"]
