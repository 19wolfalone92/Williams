"""Exercise real runtime management: a TC2 hypothesis cannot liquidate a campaign."""
from types import SimpleNamespace
import pandas as pd
import pytest
import futures_williams_runtime as module
from futures_williams_runtime import FuturesWilliamsRuntime
from williams_edition_rules import TC2, NTD, COURSE

H = 3_600_000


def runtime_fixture(monkeypatch, side, profile=TC2, *, boundary=False, opposite=False, advisory_error=False):
    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.client = SimpleNamespace(time_offset_ms=0, ticker_price=lambda symbol: {"price": "100"})
    runtime._tick = lambda symbol: .1
    exits, trails, saves = [], [], []
    runtime.db = SimpleNamespace(save_campaign=lambda campaign: saves.append(campaign.tags.copy()),
                                 log_event=lambda *args, **kwargs: None)
    runtime._set_state = lambda *args: None
    runtime._exit_market = lambda campaign, reason: exits.append(reason) or {"action": "EXIT", "reason": reason}
    runtime._replace_protection = lambda campaign, stop: trails.append(stop)
    def observe(*args):
        if advisory_error:
            raise ValueError("observer failed")
        return SimpleNamespace(signal_id="opposite", side="SELL" if side=="BUY" else "BUY") if opposite else None
    runtime._triggered_opposite_from_history = observe
    start = 1_700_002_800_000 // H * H
    frame = pd.DataFrame(dict(open_time_ms=[start+i*H for i in range(45)],
                              open=[100]*45, high=[101]*45, low=[99]*45,
                              close=[100]*45, volume=[10]*45))
    monkeypatch.setattr(module, "fetch_klines", lambda *args, **kwargs: frame)
    monkeypatch.setattr(module, "closed_strategy_frame", lambda data, *args: data)
    monkeypatch.setattr(module, "correction_bounds_valid", lambda proof: True)
    monkeypatch.setattr(module, "correction_price_allowed", lambda proof, mark: not boundary)
    monkeypatch.setattr(module, "plan_williams_stop", lambda *args, **kwargs:
                        {"stop": 96 if side=="BUY" else 104, "reason": "CONFIRMED_H1_FRACTAL"})
    campaign = SimpleNamespace(symbol="BTCUSDT", execution_timeframe="1h",
        state=SimpleNamespace(value="OPEN_PROTECTED"), side=side,
        current_stop_price=95 if side=="BUY" else 105, average_entry_price=100,
        initial_stop_price=95 if side=="BUY" else 105,
        tags={"strategy_profile":profile, "trade_mode":"CORRECTION", "correction_context":{},
              "position_open_observed_at_ms":start})
    return runtime, campaign, exits, trails, saves


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("profile", [TC2, NTD, COURSE])
def test_correction_boundary_obeys_the_owning_strategy(monkeypatch, side, profile):
    runtime, campaign, exits, trails, _ = runtime_fixture(monkeypatch, side, profile, boundary=True)
    runtime._trail(campaign)
    if profile==TC2:
        assert exits == []
        assert trails == [96 if side=="BUY" else 104]
        assert campaign.tags["tc2_correction_boundary_advisory"]["market_close_authorized"] is False
    else:
        assert exits == ["CORRECTION_PARENT_PRICE_INVALIDATED"]
        assert trails == []


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_tc2_opposition_is_recorded_while_the_confirmed_stop_keeps_trailing(monkeypatch, side):
    runtime, campaign, exits, trails, saves = runtime_fixture(monkeypatch, side, opposite=True)
    runtime._trail(campaign)
    assert exits == []
    assert trails == [96 if side=="BUY" else 104]
    assert campaign.tags["tc2_opposing_signal_advisory"]["signal_id"] == "opposite"
    assert campaign.tags["tc2_opposing_signal_advisory"]["market_close_authorized"] is False
    assert saves


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_optional_opposition_analysis_failure_does_not_interrupt_tc2_stop_management(monkeypatch, side):
    runtime, campaign, exits, trails, _ = runtime_fixture(monkeypatch, side, advisory_error=True)
    runtime._trail(campaign)
    assert exits == []
    assert trails == [96 if side=="BUY" else 104]
    assert campaign.tags["tc2_opposition_advisory_error"] == "ValueError"
