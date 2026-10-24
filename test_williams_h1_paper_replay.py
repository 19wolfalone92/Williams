import pandas as pd
import pytest

import run_williams_h1_paper_replay as replay


def _kline(open_time, close_time, close="100"):
    return [
        open_time, "99", "101", "98", close, "1000", close_time,
        "100000", 100, "500", "50000", "0",
    ]


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeSession:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, dict(params or {}), timeout))
        return FakeResponse(self.payload)


def test_history_reader_uses_usdm_futures_endpoint_and_ignores_open_candle(monkeypatch):
    now_ms = 7_500_000
    monkeypatch.setattr(replay.time, "time", lambda: now_ms / 1000.0)
    session = FakeSession([
        _kline(1_000, 3_600_999),
        _kline(3_601_000, 7_200_999, "101"),
        _kline(7_201_000, 10_800_999, "102"),
    ])
    frame = replay.fetch_usdm_h1_history(
        "BTCUSDT", 0, 12_000_000, session=session, base_url="https://example.test"
    )
    assert len(frame) == 2
    assert list(frame.close) == [100.0, 101.0]
    assert session.calls[0][0] == "https://example.test/fapi/v1/klines"
    assert session.calls[0][1]["interval"] == "1h"
    assert session.calls[0][1]["symbol"] == "BTCUSDT"
    assert "/api/v3/" not in session.calls[0][0]


def test_exchange_tick_is_read_from_usdm_price_filter():
    response = {
        "symbols": [{
            "symbol": "BTCUSDT",
            "status": "TRADING",
            "filters": [{"filterType": "PRICE_FILTER", "tickSize": "0.10"}],
        }]
    }
    session = FakeSession(response)
    assert replay.futures_tick_size("BTCUSDT", session=session, base_url="https://example.test") == 0.1
    assert session.calls[0][0] == "https://example.test/fapi/v1/exchangeInfo"


def test_causal_signal_extraction_never_sees_bars_after_its_current_index(monkeypatch):
    seen = []
    def fake_long(symbol, frame, *, timeframe, tick_size):
        seen.append(("LONG", len(frame), float(frame.close.iloc[-1]), float(frame.center_index.iloc[-1])))
        return []
    def fake_short(symbol, frame, *, timeframe, tick_size):
        seen.append(("SHORT", len(frame), float(frame.close.iloc[-1]), float(frame.center_index.iloc[-1])))
        return []
    monkeypatch.setattr(replay, "extract_long_signal_specs", fake_long)
    monkeypatch.setattr(replay, "extract_short_signal_specs", fake_short)

    candles = pd.DataFrame({
        "close": [float(i + 100) for i in range(65)],
        "center_index": [float(i) for i in range(65)],
    }, index=pd.date_range("2026-01-01", periods=65, freq="h", tz="UTC"))

    assert replay.extract_causal_h1_signals(
        candles, symbol="BTCUSDT", tick_size=0.1, window_bars=60
    ) == {}
    # Each bar produces two extractor calls. The newest visible close/index is
    # exactly the current bar, and the rolling window is never longer than 60.
    assert len(seen) == 130
    for bar in range(65):
        pair = seen[bar * 2: bar * 2 + 2]
        assert [x[0] for x in pair] == ["LONG", "SHORT"]
        assert pair[0][1] <= 60
        assert pair[0][2] == 100.0 + bar
        assert pair[0][3] == min(bar, 59)


def test_market_regimes_use_trailing_trend_and_volatility_only():
    index = pd.date_range("2025-01-01", periods=900, freq="h", tz="UTC")
    candles = pd.DataFrame({
        "close": [100.0 + (i * 0.1) for i in range(len(index))],
        "open": [100.0] * len(index),
        "high": [101.0] * len(index),
        "low": [99.0] * len(index),
    }, index=index)
    regimes = replay._market_regime_frame(candles)
    assert list(regimes.columns) == [
        "trend_30d_pct", "realized_vol_14d_annualized", "trend_regime", "volatility_regime"
    ]
    assert regimes.iloc[-1]["trend_regime"] == "TREND_UP_30D"

def test_replay_keeps_each_symbol_tick_through_execution_and_stress_audit(monkeypatch,tmp_path):
    from williams_paper_replay import PaperReplayConfig
    idx=pd.date_range("2024-11-09",periods=80,freq="h",tz="UTC")
    candles=pd.DataFrame({"open":[100.0]*80,"high":[100.2]*80,
        "low":[99.8]*80,"close":[100.0]*80,"volume":[1000.0]*80},index=idx)
    seen=[]
    monkeypatch.setattr(replay,"calculate_indicators",lambda c,cfg:c.copy())
    monkeypatch.setattr(replay,"extract_causal_h1_signals",lambda *a,**k:{})
    original=replay.run_williams_paper_replay
    def spy(c,s,cfg):
        seen.append(cfg.tick_size)
        return original(c,s,cfg)
    monkeypatch.setattr(replay,"run_williams_paper_replay",spy)
    config=PaperReplayConfig(tick_size=0.1)
    xrp=replay.run_symbol_replay("XRPUSDT",candles,tick_size=0.0001,
        output_dir=tmp_path/"xrp",config=config)
    btc=replay.run_symbol_replay("BTCUSDT",candles,tick_size=0.1,
        output_dir=tmp_path/"btc",config=config)
    assert seen==[0.0001,0.1]
    assert xrp["tick_size"]==0.0001 and btc["tick_size"]==0.1
    assert xrp["read_only_chronological_quality"]["execution_authority"] is False
    assert config.tick_size==0.1
    with pytest.raises(ValueError,match="tick size"):
        replay.run_symbol_replay("XRPUSDT",candles,tick_size=float("nan"),
            output_dir=tmp_path/"bad",config=config)
