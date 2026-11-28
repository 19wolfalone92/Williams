"""R49 actual source selector, 500-bar warmup, and negative evidence fixtures."""
import copy
from decimal import Decimal
import pytest
from williams_tc2_admission import verify_tc2_order, HOUR, TC2
from data import _frame
from strategy import (calculate_indicators, canonical_indicator_config_from_env,
    CANONICAL_ALLIGATOR_WARMUP_BARS, CANONICAL_ALLIGATOR_RAW_BARS)
from williams_signals import extract_long_signal_specs, extract_short_signal_specs

NOW = 1_800_000_000_000 // HOUR * HOUR + 1000


def raw_fractal(side, now=NOW, count=CANONICAL_ALLIGATOR_RAW_BARS):
    end = now // HOUR * HOUR
    rows = []
    for i in range(count):
        ts = end - (count-1-i)*HOUR
        h, l = 101, 99
        rows.append([ts,"100",str(h),str(l),"100","100",ts+HOUR-1,"10000",10,"50","5000","0"])
    # Pivot is four rows from the end; two right-hand bars confirm it.
    rows[count-3][2:4] = ["101","99"]
    rows[count-4][2:4] = ["105","99"] if side == "BUY" else ["101","95"]
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
    assert proof["closed_count"] == CANONICAL_ALLIGATOR_WARMUP_BARS
    assert proof["alligator_warmup_bars"] == CANONICAL_ALLIGATOR_WARMUP_BARS
    assert proof["alligator_warmup_required"] == CANONICAL_ALLIGATOR_WARMUP_BARS
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
    elif fault == "missing_pivot": raw[-4][2:4]=["101","99"]
    elif fault == "bad_ohlc": raw[100][2]="98"
    elif fault == "unclosed": raw[-2][6]+=HOUR
    with pytest.raises(RuntimeError, match="TC2_CANONICAL_ADMISSION"):
        verify(p,raw,side)


def test_r49_admission_rejects_less_than_500_closed_h1_bars():
    raw = raw_fractal("BUY")
    payload = candidate("BUY", raw).to_dict()
    # Keep the forming candle and signal bar, but remove one older closed row.
    with pytest.raises(RuntimeError, match="HISTORY_INSUFFICIENT_ALLIGATOR_WARMUP"):
        verify(payload, raw[1:], "BUY")


# R50: the actual last-transport verifier is shared by all NEW-risk intents.
# Synthetic mutations exercise the same function called in _submit, not a
# separate strategy simulator. A valid fractal is reconstructed from OHLC.
@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("role", ["ENTRY", "ADD_ON"])
@pytest.mark.parametrize("fault", [
    "NONE", "MISSING_OWNER_PROFILE", "UNKNOWN_SIGNAL_PROFILE", "OWNER_ID",
    "CAMPAIGN_SYMBOL", "CAMPAIGN_SIDE", "SIGNAL_SIDE", "SIGNAL_ROLE",
    "SIGNAL_FAMILY", "SIGNAL_TIME", "SIGNAL_ID", "PENDING_STATE", "REVISION",
    "RECEIPT_MISSING", "RECEIPT_HASH", "SOURCE_HASH", "SOURCE_COUNT", "CLOCK",
    "TRIGGER", "BOOK_STOP", "PLAN_STOP", "PLAN_QUANTITY", "RISK", "MARGIN",
    "POST_TYPE", "POST_BASIS", "POST_SIDE", "POST_POSITION_SIDE",
    "POST_QUANTITY", "POST_TRIGGER", "POST_CLIENT_ID", "PRICE_PROTECT",
])
def test_r50_final_transport_binding(side, role, fault):
    from types import SimpleNamespace
    from williams_tc2_admission import verify_tc2_transport_receipt, tc2_receipt_digest
    raw = raw_fractal(side)
    p = candidate(side, raw).to_dict()
    p["role"] = role
    purpose = "CAMPAIGN_ENTRY" if role == "ENTRY" else "CAMPAIGN_ADD_ON"
    proof = verify_tc2_order(p, symbol="BTCUSDT", side=side, purpose=purpose,
        raw=raw, now_ms=NOW, tick=.1)
    digest = tc2_receipt_digest(proof)
    stop = p["protective_reference"]
    tags = {"strategy_profile":TC2, "pending_signal":{"state":"VALIDATED","signal":p},
        "pending_order_client_id":"R50_TEST", "pending_order_type":"STOP_MARKET",
        "pending_order_stop":stop, "pending_order_trigger":p["trigger_price"],
        "pending_order_quantity":0.1}
    owner = SimpleNamespace(campaign_id="owner", symbol="BTCUSDT", side=side, tags=tags,
        current_signal_id=p["signal_id"], state_revision=3, initial_stop_price=stop,
        current_stop_price=stop, pending_risk_quote=0.5, capital_reserved_quote=1.0)
    intent = SimpleNamespace(campaign_id="owner", purpose=purpose, symbol="BTCUSDT", side=side,
        order_type="STOP_MARKET", signal_id=p["signal_id"], client_order_id="R50_TEST",
        quantity="0.1", invalidation_level=stop, risk_quote=0.5, capital_reserved_quote=1.0)
    params = {"symbol":"BTCUSDT","side":side,"type":"STOP_MARKET","algoType":"CONDITIONAL",
        "positionSide":"BOTH","workingType":"CONTRACT_PRICE","quantity":"0.1",
        "triggerPrice":str(p["trigger_price"]),"clientAlgoId":"R50_TEST"}
    now = NOW
    if fault == "MISSING_OWNER_PROFILE": tags.pop("strategy_profile")
    elif fault == "UNKNOWN_SIGNAL_PROFILE": p["strategy_profile"] = None
    elif fault == "OWNER_ID": owner.campaign_id = "other"
    elif fault == "CAMPAIGN_SYMBOL": owner.symbol = "ETHUSDT"
    elif fault == "CAMPAIGN_SIDE": owner.side = "SELL" if side == "BUY" else "BUY"
    elif fault == "SIGNAL_SIDE": p["side"] = "SELL" if side == "BUY" else "BUY"
    elif fault == "SIGNAL_ROLE": p["role"] = "ADD_ON" if role == "ENTRY" else "ENTRY"
    elif fault == "SIGNAL_FAMILY": p["signal_type"] = "SUPER_AO"
    elif fault == "SIGNAL_TIME": p["signal_bar_time_ms"] -= HOUR
    elif fault == "SIGNAL_ID": p["signal_id"] = "other"
    elif fault == "PENDING_STATE": tags["pending_signal"]["state"] = "UNKNOWN"
    elif fault == "REVISION": owner.state_revision += 1
    elif fault == "RECEIPT_MISSING": proof = None
    elif fault == "RECEIPT_HASH": digest = "f"*64
    elif fault == "SOURCE_HASH": proof["closed_ohlc_sha256"] = "f"*64
    elif fault == "SOURCE_COUNT": proof["closed_count"] = 499; digest = tc2_receipt_digest(proof)
    elif fault == "CLOCK": now += HOUR
    elif fault == "TRIGGER": tags["pending_order_trigger"] += .1
    elif fault == "BOOK_STOP": p["protective_reference"] += .1
    elif fault == "PLAN_STOP": tags["pending_order_stop"] += .1
    elif fault == "PLAN_QUANTITY": tags["pending_order_quantity"] = 0.2
    elif fault == "RISK": owner.pending_risk_quote = 0.6
    elif fault == "MARGIN": owner.capital_reserved_quote = 1.1
    elif fault == "POST_TYPE": params["type"] = "MARKET"
    elif fault == "POST_BASIS": params["workingType"] = "MARK_PRICE"
    elif fault == "POST_SIDE": params["side"] = "SELL" if side == "BUY" else "BUY"
    elif fault == "POST_POSITION_SIDE": params["positionSide"] = "LONG"
    elif fault == "POST_QUANTITY": params["quantity"] = "0.2"
    elif fault == "POST_TRIGGER": params["triggerPrice"] = "1"
    elif fault == "POST_CLIENT_ID": params["clientAlgoId"] = "other"
    elif fault == "PRICE_PROTECT": params["priceProtect"] = "true"
    def verify():
        verify_tc2_transport_receipt(proof, issued_digest=digest, campaign=owner,
            intent=intent, expected_revision=3, params=params, now_ms=now)
    if fault == "NONE": verify()
    else:
        with pytest.raises(RuntimeError, match="TC2_CANONICAL_ADMISSION:"):
            verify()
