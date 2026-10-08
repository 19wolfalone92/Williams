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
