from decimal import Decimal
from types import SimpleNamespace

import pytest

from campaign_engine import CampaignEngine
from daily_risk_accounting import calculate_daily_risk_model_b
from research.williams_portfolio_engine import (
    active_campaign_slot_count,
    entry_fill_price,
    exit_fill_price,
    initial_unit_risk_pct,
    stop_fill_raw,
    stop_triggered,
)
from williams_exit_policy import tc2_exact_structural_stop
from williams_tc2_pyramid import POLICY, next_tranche


def test_pending_initial_reserves_one_slot_and_pending_addon_does_not_add_slot():
    assert active_campaign_slot_count({"BTCUSDT", "ETHUSDT"}, {"ETHUSDT", "SOLUSDT"}) == 3


def test_initial_unit_risk_matches_production_tc2_reverse_pyramid_contract():
    value = initial_unit_risk_pct(0.002, 0.006, 0.0025)
    assert value == pytest.approx(0.0004, abs=1e-12)


def test_tc2_reverse_pyramid_is_exact_1_5_4_3_2():
    completed = []
    owned = Decimal("1")
    expected = [Decimal("5"), Decimal("4"), Decimal("3"), Decimal("2")]
    for addition, tranche in enumerate(expected):
        plan = {
            "policy": POLICY,
            "unit_quantity": "1",
            "completed_quantities": [str(x) for x in completed],
        }
        actual = next_tranche(plan, owned_quantity=owned, additions=addition)
        assert Decimal(str(actual)) == tranche
        completed.append(tranche)
        owned += tranche


def test_entry_stop_market_gap_fills_are_adverse_and_tick_aligned():
    long_fill = entry_fill_price(101.0, 100.0, "BUY", 0.001, Decimal("0.1"))
    short_fill = entry_fill_price(99.0, 100.0, "SELL", 0.001, Decimal("0.1"))
    assert long_fill == pytest.approx(101.2)
    assert short_fill == pytest.approx(98.9)


def test_exit_market_fills_round_adversely():
    assert exit_fill_price(100.0, "BUY", 0.001, Decimal("0.1")) == pytest.approx(99.9)
    assert exit_fill_price(100.0, "SELL", 0.001, Decimal("0.1")) == pytest.approx(100.1)


def test_protective_stop_triggers_on_mark_price_not_contract_price():
    assert stop_triggered("BUY", 100.0, 101.0, 102.0, 99.0)
    assert stop_triggered("SELL", 100.0, 99.0, 101.0, 98.0)
    assert not stop_triggered("BUY", 100.0, 101.0, 102.0, 100.1)


def test_mark_stop_uses_worst_observed_contract_ohlc_for_fill():
    long_raw = stop_fill_raw("BUY", 101.0, 103.0, 98.0, 99.0, 101.0, 100.0)
    short_raw = stop_fill_raw("SELL", 99.0, 102.0, 97.0, 101.0, 99.0, 100.0)
    assert long_raw == 98.0
    assert short_raw == 102.0


def test_tc2_structural_stop_is_exactly_one_exchange_tick_beyond_source():
    assert tc2_exact_structural_stop("BUY", 100.0, 0.1) == pytest.approx(99.9)
    assert tc2_exact_structural_stop("SELL", 100.0, 0.1) == pytest.approx(100.1)
    # Off-grid source extrema fail closed instead of being rounded heuristically.
    assert tc2_exact_structural_stop("BUY", 100.05, 0.1) is None


def test_pending_stop_replacement_requires_newer_signal_and_tick_distance():
    old = SimpleNamespace(side="BUY", signal_bar_time_ms=1_000, trigger_price=100.0)
    newer = SimpleNamespace(side="BUY", signal_bar_time_ms=2_000, trigger_price=101.0)
    too_close = SimpleNamespace(side="BUY", signal_bar_time_ms=2_000, trigger_price=100.5)
    opposite = SimpleNamespace(side="SELL", signal_bar_time_ms=2_000, trigger_price=100.0)
    assert CampaignEngine.should_replace_pending(old, newer, tick_size=1.0)
    assert not CampaignEngine.should_replace_pending(old, too_close, tick_size=1.0)
    assert CampaignEngine.should_replace_pending(old, opposite, tick_size=1.0)


def test_model_b_daily_limit_and_emergency_boundaries_are_inclusive():
    below = calculate_daily_risk_model_b(Decimal("100"), Decimal("97.01"), Decimal("0"))
    at_3 = calculate_daily_risk_model_b(Decimal("100"), Decimal("97"), Decimal("0"))
    at_5 = calculate_daily_risk_model_b(Decimal("100"), Decimal("95"), Decimal("0"))
    assert below["entry_blocked"] is False
    assert at_3["entry_blocked"] is True
    assert at_3["emergency_breaker_tripped"] is False
    assert at_5["entry_blocked"] is True
    assert at_5["emergency_breaker_tripped"] is True
