from datetime import datetime, timezone

import pytest

from execution_economics import ExecutionEconomics, evaluate_execution_economics
from intraday_contract import (
    WilliamsIntradayContract,
    position_size_from_structural_stop,
    session_decision,
)
from intraday_policy import campaign_is_stagnant, evaluate_intraday_policy


def test_canonical_contract():
    c = WilliamsIntradayContract()
    c.validate()
    assert (c.macro_tf, c.context_tf, c.decision_tf, c.execution_tf, c.micro_tf) == (
        "1d", "4h", "1h", "15m", "5m"
    )
    assert c.symbols == ("BTCUSDT", "ETHUSDT")
    assert c.initial_risk_pct == 0.0025
    assert c.max_campaign_risk_pct == 0.006
    assert c.max_daily_loss_pct == 0.01
    assert c.max_open_campaigns == 1
    assert c.max_full_stop_outs == 2
    assert c.reverse_pyramid_weights == (1, 5, 4, 3, 2)
    assert not c.averaging_down
    assert not c.fixed_take_profit


def test_position_size_uses_market_stop():
    qty = position_size_from_structural_stop(
        equity_quote=500.0,
        entry_price=100.0,
        structural_stop=98.5,
        risk_pct=0.0025,
    )
    assert qty == pytest.approx(0.8333333333)


def test_session_policy():
    c = WilliamsIntradayContract()
    assert session_decision(datetime(2026, 10, 8, 7, 59, tzinfo=timezone.utc), c).new_entries_allowed is False
    assert session_decision(datetime(2026, 10, 8, 10, 0, tzinfo=timezone.utc), c).new_entries_allowed is True
    assert session_decision(datetime(2026, 10, 8, 18, 1, tzinfo=timezone.utc), c).new_entries_allowed is False
    assert session_decision(datetime(2026, 10, 8, 20, 0, tzinfo=timezone.utc), c).force_flat is True


def test_economics_gate():
    ok = evaluate_execution_economics(
        ExecutionEconomics(
            spread_pct=0.0005,
            estimated_slippage_pct=0.0003,
            entry_fee_pct=0.0004,
            exit_fee_pct=0.0004,
            expected_move_pct=0.01,
        )
    )
    assert ok.feasible
    blocked = evaluate_execution_economics(
        ExecutionEconomics(
            spread_pct=0.005,
            estimated_slippage_pct=0.002,
            entry_fee_pct=0.001,
            exit_fee_pct=0.001,
            expected_move_pct=0.005,
        )
    )
    assert not blocked.feasible
    assert blocked.block_reason == "BLOCKED_BY_EXECUTION_ECONOMICS"


def test_intraday_policy_guards():
    c = WilliamsIntradayContract()
    d = evaluate_intraday_policy(
        datetime(2026, 10, 8, 19, 0, tzinfo=timezone.utc),
        c,
        active_campaigns=0,
        daily_loss_pct=0.0,
        full_stop_outs=0,
    )
    assert not d.allow_new_campaign
    assert not d.force_flat
    assert d.block_reason == "NO_NEW_CAMPAIGNS"


def test_stagnation_is_not_an_immediate_time_stop():
    assert campaign_is_stagnant(
        no_new_confirmation=True,
        alligator_not_opening=True,
        no_price_progress=True,
    )
