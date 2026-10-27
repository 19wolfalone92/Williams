"""Offline tests; NO network and NO signed Binance mutation."""
import importlib.util
from pathlib import Path
from unittest.mock import patch

import pytest

MODULE = Path(__file__).resolve().parent / "tools" / "verify_public_futures_demo.py"
SPEC = importlib.util.spec_from_file_location("williams_public_demo_probe", MODULE)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe)


def contracts():
    return {"symbols": [
        {"symbol": s, "status": "TRADING", "contractType": "PERPETUAL",
         "quoteAsset": "USDT", "filters": [
             {"filterType": "PRICE_FILTER"}, {"filterType": "LOT_SIZE"}]}
        for s in probe.SYMBOLS
    ]}


def candles(start=360_000_000):
    hour = probe.HOUR_MS
    start -= start % hour
    return [[start + i * hour, "100", "101", "99", "100", "10",
             start + (i + 1) * hour - 1] for i in range(3)]


def test_exactly_ten_real_usdt_perpetual_contracts_checked():
    assert len(probe.SYMBOLS) == 10
    assert probe.DEMO == "https://demo-fapi.binance.com"
    probe.validate_contracts(contracts())
    absent = contracts()
    absent["symbols"].pop()
    with pytest.raises(ValueError, match="missing or ambiguous"):
        probe.validate_contracts(absent)
    wrong = contracts()
    wrong["symbols"][1]["contractType"] = "CURRENT_QUARTER"
    with pytest.raises(ValueError, match="not active USDT perpetual"):
        probe.validate_contracts(wrong)


def test_h1_close_boundary_gap_and_price_geometry_fail_closed():
    data = candles()
    assert probe.validate_closed_h1("BTCUSDT", data, int(data[-1][6])+1)["closed_bars"] == 3
    assert probe.validate_closed_h1("BTCUSDT", data, int(data[-1][6])-1)["closed_bars"] == 2
    gap = candles()
    gap[2][0] += probe.HOUR_MS
    with pytest.raises(ValueError, match="H1 history gap"):
        probe.validate_closed_h1("BTCUSDT", gap, int(data[-1][6])+1)
    incomplete = candles()
    incomplete[1][6] += 100
    with pytest.raises(ValueError, match="H1 candle timestamps invalid"):
        probe.validate_closed_h1("BTCUSDT", incomplete, int(data[-1][6])+1)
    invalid = candles()
    invalid[0][2] = "98"
    with pytest.raises(ValueError, match="malformed OHLC"):
        probe.validate_closed_h1("BTCUSDT", invalid, int(data[-1][6])+1)
    with pytest.raises(ValueError, match="stale"):
        probe.validate_closed_h1("BTCUSDT", data, int(data[-1][6])+3*probe.HOUR_MS)


def test_read_only_route_allowlist_prohibits_trading():
    for route in ("/fapi/v1/order", "/fapi/v1/algoOrder",
                  "/api/v3/order", "/fapi/v3/account"):
        with pytest.raises(ValueError, match="only call"):
            probe.read_public(route)


def test_full_public_probe_never_claims_signed_demo_account_or_stop_proof():
    bars = candles()
    clock = int(bars[-1][6]) + 1
    observed = []
    def get(path, params=None):
        observed.append((path, params))
        if path == "/fapi/v1/time":
            return {"serverTime": clock}
        if path == "/fapi/v1/exchangeInfo":
            return contracts()
        assert path == "/fapi/v1/klines"
        assert params["symbol"] in probe.SYMBOLS and params["interval"] == "1h"
        return bars
    with patch.object(probe, "read_public", side_effect=get):
        report = probe.run()
    assert report["result"] == "PUBLIC_DEMO_FUTURES_READ_ONLY_PASS"
    assert report["checked_usdt_perpetuals"] == 10
    assert report["signed_account_verified"] is False
    assert report["orders_executed"] is False
    assert report["protective_stops_verified"] is False
    assert report["physical_android_recovery_verified"] is False
    assert len([x for x in observed if x[0] == "/fapi/v1/klines"]) == 10
    assert set(x[0] for x in observed) == {
        "/fapi/v1/time", "/fapi/v1/exchangeInfo", "/fapi/v1/klines"
    }
