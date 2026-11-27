"""Causal TC2 signal validity is independent of elapsed H1-bar age."""
from types import SimpleNamespace
import inspect

import pandas as pd
import pytest

from campaign_model import SignalRole, SignalType
from williams_edition_rules import TC2, NTD
from williams_execution_policy import h1_signal_expiry
from williams_scenario_lifecycle import evaluate_level_event
from futures_williams_runtime import _pending_entry_preflight_reason
import williams_signals as signals


H1 = 3_600_000


@pytest.mark.parametrize("family", ["REVERSAL", "SUPER_AO", "FRACTAL", "THREE_GREEN_BARS"])
def test_all_tc2_stop_families_have_no_age_expiry(family):
    assert h1_signal_expiry(100 * H1, family, TC2) == 0


def test_observer_source_policy_is_not_broadened_to_non_fractal_families():
    assert h1_signal_expiry(100 * H1, "FRACTAL", NTD) == 0
    assert h1_signal_expiry(100 * H1, "REVERSAL", NTD) == 105 * H1


def test_wm1_and_wm2_scan_history_older_than_twenty_h1_bars(monkeypatch):
    assert inspect.signature(signals.extract_long_signal_specs).parameters[
        "max_reversal_age_bars"].default is None
    assert inspect.signature(signals.extract_short_signal_specs).parameters[
        "max_reversal_age_bars"].default is None
    rows = 30
    ind = pd.DataFrame({
        "open": [100.0] * rows,
        "high": [101.0] * rows,
        "low": [99.0] * rows,
        "close": [100.0] * rows,
        "ao": [float(i + 1) for i in range(rows)],
        "bullish_reversal_bar": [False] * rows,
        "ao_green_streak": [0] * rows,
    })
    ind.loc[3, "bullish_reversal_bar"] = True
    ind.loc[29, "ao_green_streak"] = 27
    monkeypatch.setattr(signals, "_tc2_reversal_bar_price_proof", lambda *a, **k: True)
    monkeypatch.setattr(signals, "_angulation", lambda *a, **k: (1.0, True))
    monkeypatch.setattr("williams_market_geometry.first_wise_man_ao_blocker", lambda *a, **k: None)
    monkeypatch.setattr(signals, "_tc2_ao_streak_price_proof", lambda *a, **k: True)

    wm1 = signals._latest_reversal(ind, side="LONG")
    wm2 = signals._latest_super_ao(ind, side="LONG")
    assert wm1 == (3, 101.0, 99.0)
    assert wm2 == (5, 101.0, 99.0)


def test_live_preflight_ignores_legacy_tc2_expiry_but_requires_fresh_h1_snapshot():
    now = 2_000_000_000_000
    intent = SimpleNamespace(signal_id="old-wm1", side="BUY")
    signal = {
        "signal_id": "old-wm1", "side": "BUY", "type": "REVERSAL",
        "strategy_profile": TC2, "signal_bar_time_ms": now - 25 * H1,
        "expires_at_ms": now - 1, "trigger_price": 110.0,
        "protective_reference": 90.0,
        "setup_context": {"h1_closed_at_ms": now - 1_000},
    }
    pending = {"state": "VALIDATED", "signal": signal}
    assert _pending_entry_preflight_reason(pending, intent, 100.0, now) == ""

    signal["setup_context"]["h1_closed_at_ms"] = now - H1
    assert _pending_entry_preflight_reason(
        pending, intent, 100.0, now
    ) == "pending_h1_snapshot_stale_or_unverified"


def test_causal_level_event_remains_waiting_after_twenty_five_h1_bars():
    signal_closed = 2 * H1
    bars = [
        SimpleNamespace(t=signal_closed + i * H1, o=100.0, h=101.0,
                        l=99.0, c=100.0, v=1.0)
        for i in range(25)
    ]
    event = evaluate_level_event(
        "LONG", 105.0, signal_closed, H1, 100.0, bars,
        27 * H1, 0, H1, 95.0,
    )
    assert event.state == "WAITING"

    bars[-1] = SimpleNamespace(t=26 * H1, o=100.0, h=106.0,
                               l=99.0, c=105.0, v=1.0)
    event = evaluate_level_event(
        "LONG", 105.0, signal_closed, H1, 100.0, bars,
        27 * H1, 0, H1, 95.0,
    )
    assert event.state == "TRIGGERED"
    assert event.known_at == 27 * H1


def test_replay_opposition_watcher_is_not_expired_after_one_h1_bar():
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    simulator = PortfolioSimulator.__new__(PortfolioSimulator)
    simulator.active = {
        "BTCUSDT": {"status": "OPEN", "side": "BUY", "opposition_watchers": []}
    }
    simulator._signal_status = lambda *args, **kwargs: None
    signal = SimpleNamespace(
        signal_id="old-opposite-wm1", strategy_profile=TC2, side="SELL",
        role=SignalRole.ENTRY, signal_type=SignalType.REVERSAL,
        execution_semantics="STOP_ENTRY", signal_bar_time_ms=10 * H1_MS,
        trigger_price=95.0, invalidation_price=105.0, expires_at_ms=0,
    )
    known_at = 11 * H1_MS
    simulator._refresh_opposition_watchers(
        "BTCUSDT", [{"signal": signal, "known_at_ms": known_at, "context": {}}],
        known_at,
    )
    simulator._refresh_opposition_watchers("BTCUSDT", [], known_at + 25 * H1_MS)
    watchers = simulator.active["BTCUSDT"]["opposition_watchers"]
    assert len(watchers) == 1
    assert watchers[0]["expires_at_ms"] == 0
