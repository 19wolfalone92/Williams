"""R48 actual source selector, fresh data, and negative evidence fixtures."""
import copy
from decimal import Decimal
import pytest
from williams_tc2_admission import verify_tc2_order, HOUR, TC2
from data import _frame
from strategy import calculate_indicators, canonical_indicator_config_from_env
from williams_signals import extract_long_signal_specs, extract_short_signal_specs

NOW = 1_800_000_000_000 // HOUR * HOUR + 1000


def raw_fractal(side, now=NOW):
    end = now // HOUR * HOUR
    rows = []
    for i in range(220):
        ts = end - (219-i)*HOUR
        h, l = 101, 99
        if i == 217:
            h, l = (105,99) if side == "BUY" else (101,95)
        rows.append([ts,"100",str(h),str(l),"100","100",ts+HOUR-1,"10000",10,"50","5000","0"])
    # pivot index 216, confirmation index 218, current index 219.
    rows[217][2:4] = ["101","99"]
    rows[216][2:4] = ["105","99"] if side == "BUY" else ["101","95"]
    return rows


def candidate(side, raw):
    ind = calculate_indicators(_frame(raw[:-1]), canonical_indicator_config_from_env())
    extract = extract_long_signal_specs if side == "BUY" else extract_short_signal_specs
    s = next(s for s in extract("BTCUSDT", ind, timeframe="1h", tick_size=.1)
             if s.signal_type.value == "FRACTAL")
    assert s.trigger_price == (105.1 if side == "BUY" else 94.9)
    assert s.signal_bar_time_ms == raw[-2][0]
    return s


def verify(p, raw, side, now=NOW):
    return verify_tc2_order(p, symbol="BTCUSDT", side=side,
        purpose="CAMPAIGN_ENTRY", raw=raw, now_ms=now, tick=.1)


@pytest.mark.parametrize("side", ["BUY","SELL"])
def test_r48_real_fractal_raw_positive(side):
    raw = raw_fractal(side)
    s = candidate(side, raw)
    proof = verify(s.to_dict(), raw, side)
    assert proof["closed_count"] == 219
    assert proof["signal_id"] == s.signal_id
    assert proof["closed_at_ms"] == NOW//HOUR*HOUR
    assert len(proof["closed_ohlc_sha256"]) == 64


@pytest.mark.parametrize("side", ["BUY","SELL"])
@pytest.mark.parametrize("fault", ["symbol","side","timeframe","role","signal_id","family",
    "timestamp","trigger","stop","nan","infinity","missing","gap","stale","forming_trigger",
    "missing_pivot","bad_ohlc","unclosed"])
def test_r48_real_source_negative(side, fault):
    raw = raw_fractal(side)
    p = candidate(side,raw).to_dict()
    if fault == "symbol": p["symbol"]="ETHUSDT"
    elif fault == "side": p["side"]="SELL" if side=="BUY" else "BUY"
    elif fault == "timeframe": p["timeframe"]="15m"
    elif fault == "role": p["role"]="ADD_ON"
    elif fault == "signal_id": p["signal_id"]="forged"
    elif fault == "family": p["signal_type"]="AO_SAUCER"
    elif fault == "timestamp": p["signal_bar_time_ms"]+=1
    elif fault == "trigger": p["trigger_price"] += .1 if side=="BUY" else -.1
    elif fault == "stop": p["protective_reference"] += -.1 if side=="BUY" else .1
    elif fault == "nan": p["trigger_price"]=float("nan")
    elif fault == "infinity": raw[-5][2]="inf"
    elif fault == "missing": raw=[]
    elif fault == "gap": raw.pop(100)
    elif fault == "stale": raw=raw[:-1]
    elif fault == "forming_trigger": raw[-1][2 if side=="BUY" else 3]=str(p["trigger_price"])
    elif fault == "missing_pivot": raw[216][2:4]=["101","99"]
    elif fault == "bad_ohlc": raw[100][2]="98"
    elif fault == "unclosed": raw[-2][6]+=HOUR
    with pytest.raises(RuntimeError, match="TC2_CANONICAL_ADMISSION"):
        verify(p,raw,side)
