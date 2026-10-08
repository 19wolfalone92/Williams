import pandas as pd

from williams_campaign_backtester import WilliamsCampaignBacktester


def _bars(start, rows, freq):
    idx = pd.date_range(start, periods=len(rows), freq=freq, tz="UTC")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)


def test_backtester_returns_ok_for_valid_history():
    h1 = _bars("2026-10-08 06:00", [(100, 101, 99, 100)] * 90, "1h")
    m15 = _bars("2026-10-08 08:00", [(100, 101, 99, 100)] * 100, "15min")
    out = WilliamsCampaignBacktester(starting_equity=500).run("BTCUSDT", h1, m15)
    assert out["status"] == "OK"


def test_backtester_honors_existing_stop_before_same_bar_add_on():
    from types import SimpleNamespace
    from campaign_model import SignalRole, SignalSpec, SignalType

    start = pd.Timestamp("2026-10-07 14:15", tz="UTC")
    m15 = _bars(
        start,
        [
            (100.0, 100.5, 99.5, 100.0)
        ] * 90,
        "15min",
    )
    signal_time = m15.index[79]
    m15.iloc[80, m15.columns.get_loc("high")] = 102.0
    m15.iloc[80, m15.columns.get_loc("close")] = 101.5
    m15.iloc[81, m15.columns.get_loc("high")] = 103.0
    m15.iloc[81, m15.columns.get_loc("low")] = 98.0

    initial = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="15m",
        signal_bar_time_ms=int(signal_time.timestamp() * 1000),
        trigger_price=101.0,
        protective_reference=99.5,
        execution_timeframe="5m",
        detected_time_ms=int(signal_time.timestamp() * 1000),
    )
    add_on = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON,
        timeframe="15m",
        signal_bar_time_ms=int(m15.index[80].timestamp() * 1000),
        trigger_price=102.0,
        protective_reference=99.5,
        execution_timeframe="5m",
        detected_time_ms=int(m15.index[80].timestamp() * 1000),
    )

    bt = WilliamsCampaignBacktester(starting_equity=500)
    def fake_evaluate(_symbol, frame, **_kwargs):
        ts = pd.Timestamp(frame.index[-1])
        specs = (initial,) if ts == signal_time else (add_on,) if ts == m15.index[80] else ()
        return SimpleNamespace(signal_specs=specs, decision_time_ms=int(ts.timestamp() * 1000))
    def fake_precomputed(symbol, frame, _ind, **kwargs):
        return fake_evaluate(symbol, frame, **kwargs)

    bt.core.evaluate_precomputed = fake_precomputed

    m5 = _bars(
        m15.index[81],
        [
            (100.0, 100.5, 98.0, 99.0),
            (99.0, 103.0, 99.0, 102.0),
            (102.0, 102.5, 101.0, 102.0),
        ],
        "5min",
    )
    out = bt.run("BTCUSDT", None, m15, m5=m5, tick_size=0.01)
    assert len(out["fills"]) == 1
    assert len(out["trades"]) == 1
    assert out["trades"][0]["reason"] == "STRUCTURAL_STOP"


def test_backtester_does_not_create_new_risk_after_entry_window():
    from types import SimpleNamespace
    from campaign_model import SignalRole, SignalSpec, SignalType

    start = pd.Timestamp("2026-10-07 14:15", tz="UTC")
    m15 = _bars(start, [(100.0, 100.5, 99.5, 100.0)] * 120, "15min")
    signal_time = m15.index[79]
    initial = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="15m",
        signal_bar_time_ms=int(signal_time.timestamp() * 1000),
        trigger_price=101.0,
        protective_reference=99.5,
        execution_timeframe="5m",
    )
    late_add_time = pd.Timestamp("2026-10-08 18:00", tz="UTC")
    late_add = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON,
        timeframe="15m",
        signal_bar_time_ms=int(late_add_time.timestamp() * 1000),
        trigger_price=101.0,
        protective_reference=99.5,
        execution_timeframe="5m",
    )

    bt = WilliamsCampaignBacktester(starting_equity=500)

    def fake_evaluate(_symbol, frame, **_kwargs):
        ts = pd.Timestamp(frame.index[-1])
        if ts == signal_time:
            return SimpleNamespace(signal_specs=(initial,), decision_time_ms=int(ts.timestamp() * 1000))
        if ts == late_add_time:
            return SimpleNamespace(signal_specs=(late_add,), decision_time_ms=int(ts.timestamp() * 1000))
        return SimpleNamespace(signal_specs=(), decision_time_ms=int(ts.timestamp() * 1000))

    def fake_precomputed(symbol, frame, _ind, **kwargs):
        return fake_evaluate(symbol, frame, **kwargs)

    bt.core.evaluate_precomputed = fake_precomputed
    m15.loc[pd.Timestamp("2026-10-08 10:15", tz="UTC"), ["high","close"]] = [101.5, 101.2]
    m15.loc[late_add_time, ["high","close"]] = [102.0, 101.0]

    out = bt.run("BTCUSDT", None, m15, tick_size=0.01)
    assert len(out["fills"]) == 1

def test_backtester_has_no_fixed_take_profit_exit():
    h1 = _bars(
        "2026-10-08 00:00",
        [(100 + i * 0.5, 101 + i * 0.5, 99 + i * 0.5, 100.5 + i * 0.5) for i in range(100)],
        "1h",
    )
    m15 = _bars("2026-10-08 08:00", [(150, 151, 149, 150)] * 200, "15min")
    out = WilliamsCampaignBacktester(starting_equity=500).run("BTCUSDT", h1, m15)
    assert all(t["reason"] != "TARGET" for t in out["trades"])


def test_m5_is_optional_replay_input():
    h1 = _bars("2026-10-08 06:00", [(100, 101, 99, 100)] * 90, "1h")
    m15 = _bars("2026-10-08 08:00", [(100, 101, 99, 100)] * 100, "15min")
    m5 = _bars("2026-10-08 08:00", [(100, 101, 99, 100)] * 300, "5min")
    out = WilliamsCampaignBacktester(starting_equity=500).run("BTCUSDT", h1, m15, m5)
    assert out["status"] == "OK"
