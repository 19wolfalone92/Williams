import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalState, SignalType
from pending_signal import PendingSignal
from williams.timeframe import CORE_TIMEFRAME_CONTRACT
from williams.ao import calculate_ao
from williams.fractals import FractalEngine, WilliamsFractal
from williams.wm2 import evaluate_wm2
from williams.wm3 import evaluate_wm3
from williams.allocation import size_for_risk
from williams.campaign import step_for_first_signal, next_step, WEIGHTS
from williams.exits import structural_trail_long
from williams.execution_economics import ExecutionFeasibilityGate
from williams.intraday_policy import IntradayPolicy
from williams.risk_policy import SMALL_DEPOSIT_RISK, risk_for_quality


def test_core_timeframe_contract_is_h1_decision():
    assert CORE_TIMEFRAME_CONTRACT.to_dict() == {
        "macro": "1d", "context": "4h", "decision": "1h",
        "execution": "15m", "micro": "5m", "airbag": "1d",
    }


def test_wm2_is_independent_of_fractal_state():
    df = pd.DataFrame({"ao": [0.0, 1.0, 2.0, 3.0],
                       "ao_green_streak": [0, 1, 2, 3],
                       "ao_red_streak": [0, 0, 0, 0],
                       "high": [1, 2, 3, 4], "low": [0, 1, 2, 3]})
    result = evaluate_wm2(df, 3, "LONG")
    assert result.valid is True
    assert result.trigger_price == 4.0


def test_fractal_engine_accepts_equal_extreme_and_shared_bar():
    highs = [10, 12, 12, 11, 9]
    lows = [8, 9, 10, 9, 8]
    df = pd.DataFrame({"high": highs, "low": lows})
    fractals = FractalEngine().detect(df)
    up = [f for f in fractals if f.side == "UP" and f.center_index == 2]
    assert up
    assert up[0].equal_extreme is True
    assert up[0].shared_bars is True


def test_fractal_engine_records_extended_six_and_nine_bar_metadata():
    highs = [10, 9, 12, 8, 7, 12, 6, 12, 5, 12, 4]
    lows = [8, 7, 9, 6, 5, 6, 4, 6, 3, 6, 2]
    df = pd.DataFrame({"high": highs, "low": lows})
    fractals = FractalEngine().detect(df)
    center = [f for f in fractals if f.side == "UP" and f.center_index == 2]
    assert center
    assert center[0].span == 9


def test_wm3_teeth_is_evaluated_at_trigger_time():
    df = pd.DataFrame({
        "high": [10, 9, 12, 9, 8],
        "low": [8, 7, 9, 6, 7],
        "teeth_shifted": [11, 11, 11, 11, 11],
    })
    valid = evaluate_wm3(df, "LONG", teeth_at_trigger=11.0, tick_size=0.01)
    invalid = evaluate_wm3(df, "LONG", teeth_at_trigger=13.0, tick_size=0.01)
    assert valid.valid is True
    assert invalid.valid is False


def test_pending_signal_supports_superseded_terminal_state():
    first = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=100,
        trigger_price=100.0, protective_reference=98.0,
    )
    second = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=200,
        trigger_price=101.0, protective_reference=99.0,
    )
    pending = PendingSignal.from_spec(first, state=SignalState.ARMED)
    superseded = pending.supersede(second)
    assert superseded.state == SignalState.SUPERSEDED
    assert superseded.supersedes_signal_id == second.signal_id
    assert not superseded.transition.__self__.state == SignalState.ARMED


def test_reverse_pyramid_is_a_weight_bias_under_risk_cap():
    assert WEIGHTS == (1, 5, 4, 3, 2)
    assert step_for_first_signal("SUPER_AO").step == 1
    assert step_for_first_signal("SUPER_AO").weight == 1
    assert next_step(1, "SUPER_AO").weight == 5
    allocation = size_for_risk(
        equity=500.0, risk_pct=0.0025, entry=100.0, stop=98.5,
        fee_pct=0.001, slippage_pct=0.0015, max_position_fraction=0.25,
        step=1,
    )
    assert allocation.allowed is True
    assert allocation.risk_quote == 1.25
    assert allocation.quantity > 0


def test_quality_policy_does_not_move_structural_stop():
    assert risk_for_quality("A").risk_pct == SMALL_DEPOSIT_RISK.initial_risk_pct
    trail = structural_trail_long([98.0, 99.0, 100.0], 97.0, 110.0)
    assert trail.moved is True
    assert trail.stop == 100.0
    blocked = structural_trail_long([95.0, 96.0, 96.5], 97.0, 110.0)
    assert blocked.moved is False
    assert blocked.stop == 97.0


def test_execution_economics_separates_cost_block_from_core_truth():
    gate = ExecutionFeasibilityGate(fee_per_side_pct=0.001, slippage_pct=0.0015, max_spread_pct=0.0015)
    result = gate.evaluate(
        entry=100.0, stop=99.8, spread_pct=0.001,
        estimated_slippage_pct=0.0015,
        minimum_notional_ok=True, balance_ok=True, time_to_eod_ok=True,
    )
    assert result.feasible is False
    assert "costs_dominate_structural_room" in result.reasons


def test_intraday_policy_has_open_cutoff_and_force_flat():
    policy = IntradayPolicy()
    before = pd.Timestamp("2026-10-08T07:59:00Z").to_pydatetime()
    active = pd.Timestamp("2026-10-08T17:59:00Z").to_pydatetime()
    cutoff = pd.Timestamp("2026-10-08T18:00:00Z").to_pydatetime()
    flat = pd.Timestamp("2026-10-08T20:00:00Z").to_pydatetime()
    assert policy.state(before) == "CLOSED"
    assert policy.allows_new_campaign(active) is True
    assert policy.state(cutoff) == "NO_NEW_ENTRIES"
    assert policy.pending_action(cutoff) == "CANCEL"
    assert policy.must_flat(flat) is True


def test_ao_canonical_three_bar_green_sequence():
    prices = [100 + (i * 0.05) ** 2 for i in range(80)]
    df = pd.DataFrame({"high": [p + 0.5 for p in prices], "low": [p - 0.5 for p in prices]})
    out = calculate_ao(df)
    assert int(out["ao_green_streak"].iloc[-1]) >= 3


def test_williams_fractal_is_confirmation_delayed_by_two_bars():
    df = pd.DataFrame({"high": [10, 9, 12, 9, 8], "low": [8, 7, 9, 6, 7]})
    f = FractalEngine().latest(df, "LONG")
    assert f is not None
    assert f.center_index == 2
    assert f.confirmation_index == 4
