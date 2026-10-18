"""Unified Williams orchestration and safety properties."""
from types import SimpleNamespace

import pandas as pd

import williams_profiles as profiles
from williams_edition_rules import (
    TC2, NTD, COURSE, UNIFIED, SOURCE_PROFILES,
    profile_allows_source, unified_direction_conflict,
)


def test_unified_selection_does_not_erase_book_source_or_admit_invalid_sources():
    assert len(SOURCE_PROFILES) == 3
    assert all(profile_allows_source(UNIFIED, source) for source in SOURCE_PROFILES)
    assert all(profile_allows_source(source, source) for source in SOURCE_PROFILES)
    assert not profile_allows_source(TC2, NTD)
    assert not profile_allows_source(COURSE, TC2)
    assert not profile_allows_source(UNIFIED, UNIFIED)
    assert not profile_allows_source(UNIFIED, "FORGED_SOURCE")


def test_conflicting_directions_fail_closed_before_entry():
    assert not unified_direction_conflict(["LONG", "LONG"])
    assert not unified_direction_conflict(["SHORT", "SHORT"])
    assert unified_direction_conflict(["SHORT", "LONG"])
    assert unified_direction_conflict(["LONG", "LONG", "SHORT"])


def test_unified_uses_all_three_sources_and_deduplicates_only_same_signal_id(monkeypatch):
    original = profiles.extract_profile_signals
    seen = []

    def fake_source(symbol, ind, *, profile, side, timeframe, tick_size, **kwargs):
        if profile == UNIFIED:
            return original(symbol, ind, profile=profile, side=side,
                            timeframe=timeframe, tick_size=tick_size, **kwargs)
        seen.append((profile, side))
        return [
            SimpleNamespace(signal_id=f"{profile}:{side}", signal_bar_time_ms=123,
                            strategy_profile=profile),
            SimpleNamespace(signal_id=f"{profile}:{side}", signal_bar_time_ms=123,
                            strategy_profile=profile),
        ]

    monkeypatch.setattr(profiles, "extract_profile_signals", fake_source)
    signals = profiles.extract_profile_signals(
        "BTCUSDT", pd.DataFrame({"stub": [1]}),
        profile=UNIFIED, side="LONG", timeframe="1h", tick_size=0.01
    )
    assert seen == [(TC2, "LONG"), (NTD, "LONG"), (COURSE, "LONG")]
    assert [s.strategy_profile for s in signals] == list(SOURCE_PROFILES)
    assert len({s.signal_id for s in signals}) == 3
