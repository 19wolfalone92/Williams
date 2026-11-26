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
    old = SimpleNamespace(symbol="BTCUSDT", signal_id="old", side="BUY", signal_bar_time_ms=1_000, trigger_price=100.0)
    newer = SimpleNamespace(symbol="BTCUSDT", signal_id="new", side="BUY", signal_bar_time_ms=2_000, trigger_price=101.0)
    too_close = SimpleNamespace(symbol="BTCUSDT", signal_id="close", side="BUY", signal_bar_time_ms=2_000, trigger_price=100.5)
    opposite = SimpleNamespace(symbol="BTCUSDT", signal_id="opposite", side="SELL", signal_bar_time_ms=2_000, trigger_price=100.0)
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


@pytest.mark.parametrize("threshold,above,entry,emergency", [
    ("97", "97.0000000000000000000000000000000000000001", False, False),
    ("95", "95.0000000000000000000000000000000000000001", True, False),
])
def test_model_b_extreme_precision_does_not_round_into_threshold(threshold, above, entry, emergency):
    from fractions import Fraction
    result = calculate_daily_risk_model_b("100", above, "0")
    assert Fraction(result["daily_loss"]) == Fraction(100) - Fraction(above)
    assert result["entry_blocked"] is entry
    assert result["emergency_breaker_tripped"] is emergency
    at = calculate_daily_risk_model_b("100", threshold, "0")
    assert at["entry_blocked"]
    assert at["emergency_breaker_tripped"] is (threshold == "95")


def test_portfolio_entry_and_exit_fill_ledger_reconciles_at_expiry_boundary():
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS, M5_MS
    start = 1730419200000
    symbol = "BTCUSDT"
    prices = {"open": np.array([100., 101.]), "high": np.array([102., 102.]),
              "low": np.array([100., 98.]), "close": np.array([101., 98.])}
    mark = {"mark_" + k: v for k, v in prices.items()}
    signal = SignalSpec.new(symbol=symbol, side="BUY", signal_type=SignalType.REVERSAL,
                            role=SignalRole.ENTRY, timeframe="1h",
                            signal_bar_time_ms=start-H1_MS, trigger_price=101.,
                            protective_reference=99., expires_at_ms=start+M5_MS)
    sim = PortfolioSimulator(symbols=[symbol],
        data={symbol: {"contract": prices, "mark": mark, "h1_close_rows": {}}},
        timeline=np.array([start, start+M5_MS]),
        signals_by_index={symbol: {0: [{"signal": signal, "known_at_ms": start, "context": {}}]}},
        funding_by_index={symbol: {}},
        specs={symbol: {"tick": Decimal("0.1"), "step": Decimal("0.001"),
                        "min_qty": Decimal("0.001"), "max_qty": Decimal("1000"),
                        "min_notional": Decimal("5"), "leverage": 2}},
        initial_capital=5000.)
    result = sim.run()
    assert [row["purpose"] for row in result["fills"]] == ["INITIAL", "EXIT"]
    assert result["campaigns"][0]["exit_reason"] == "STOP"
    assert result["metrics"]["entry_fills"] == 1
    assert result["metrics"]["closed_campaigns"] == 1
    assert sum(row["fee"] for row in result["fills"]) == pytest.approx(result["metrics"]["commission_usdt"])
    assert 5000 + sum(row["gross_pnl"] - row["fee"] for row in result["fills"]) == pytest.approx(result["metrics"]["final_equity_usdt"])


@pytest.mark.parametrize("side,trigger,stop,opening,high,low", [
    ("BUY", 101., 99., 100., 102., 98.),
    ("SELL", 99., 101., 100., 102., 98.),
])
def test_same_m5_paths_have_explicit_causal_order(side, trigger, stop, opening, high, low):
    from research.williams_portfolio_engine import pending_initial_path
    assert pending_initial_path(side, trigger, stop, opening, high, low) == "AMBIGUOUS_FILL_FIRST"
    assert pending_initial_path(side, trigger, stop, opening, high, low,
                                policy="INVALIDATION_FIRST") == "AMBIGUOUS_INVALIDATE_FIRST"
    assert pending_initial_path(side, trigger, stop, trigger, high, low,
                                policy="INVALIDATION_FIRST") == "FILL_AT_OPEN"
    assert pending_initial_path(side, trigger, stop, stop, high, low) == "INVALIDATE_AT_OPEN"


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_four_bar_window_uses_same_production_policy_as_three_and_five(side):
    from williams_exit_policy import plan_williams_stop
    start = 1730419200000
    long = side == "BUY"
    bars = [SimpleNamespace(t=start+i*3600000, l=94.+i if long else 103.-i,
                            h=97.+i if long else 106.-i) for i in range(5)]
    proposals = [plan_williams_stop(side, 90. if long else 110., 100.,
                                    90. if long else 110., 110. if long else 90.,
                                    1., start, bars, window=n) for n in (3,4,5)]
    assert [p["reason"] for p in proposals] == [
        "TC2_LAST_3_CLOSED_BARS", "TC2_LAST_4_CLOSED_BARS", "TC2_LAST_5_CLOSED_BARS"]
    assert [p["stop"] for p in proposals] == ([95.,94.,93.] if long else [105.,106.,107.])


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("policy,expected", [("ENTRY_FIRST", ["INITIAL","EXIT"]),
                                               ("INVALIDATION_FIRST", [])])
def test_ambiguous_initial_fill_is_not_always_cancelled(side, policy, expected):
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    start = 1730419200000
    symbol = "BTCUSDT"
    prices = {k: np.array([v]) for k,v in
              {"open":100., "high":102., "low":98., "close":100.}.items()}
    trigger, stop = (101.,99.) if side == "BUY" else (99.,101.)
    signal = SignalSpec.new(symbol=symbol, side=side, signal_type=SignalType.REVERSAL,
                            role=SignalRole.ENTRY, timeframe="1h",
                            signal_bar_time_ms=start-H1_MS, trigger_price=trigger,
                            protective_reference=stop)
    sim = PortfolioSimulator(symbols=[symbol], data={symbol:{"contract":prices,
        "mark":{"mark_"+k:v for k,v in prices.items()}, "h1_close_rows":{}}},
        timeline=np.array([start]), signals_by_index={symbol:{0:[{"signal":signal,
        "known_at_ms":start,"context":{}}]}}, funding_by_index={symbol:{}},
        specs={symbol:{"tick":Decimal("0.1"), "step":Decimal("0.001"),
                       "min_qty":Decimal("0.001"),"max_qty":Decimal("1000"),
                       "min_notional":Decimal("5"),"leverage":2}},
        initial_capital=5000., intrabar_policy=policy)
    result=sim.run()
    assert [row["purpose"] for row in result["fills"]] == expected
    assert result["metrics"]["ambiguous_initial_cases"] == 1
    if expected:
        assert result["metrics"]["total_net_pnl_usdt"] < 0
        assert result["campaigns"][0]["exit_reason"] == "STOP"


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_emergency_latch_closes_owned_position_at_next_observed_open(side):
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS, M5_MS
    start=1730419200000
    symbol="BTCUSDT"
    long=side=="BUY"
    trigger,stop=(101.,90.) if long else (99.,110.)
    prices={k:np.array(v) for k,v in {
        "open":[100.,103. if long else 97.],
        "high":[102.,104.],"low":[98.,96.],"close":[100.,100.]}.items()}
    signal=SignalSpec.new(symbol=symbol,side=side,signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,timeframe="1h",signal_bar_time_ms=start-H1_MS,
        trigger_price=trigger,protective_reference=stop)
    sim=PortfolioSimulator(symbols=[symbol],
        data={symbol:{"contract":prices,"mark":{"mark_"+k:v for k,v in prices.items()},
                      "h1_close_rows":{}}},
        timeline=np.array([start,start+M5_MS]),signals_by_index={symbol:{}},
        funding_by_index={symbol:{}},specs={symbol:{"tick":Decimal("0.1"),
            "step":Decimal("0.001"),"min_qty":Decimal("0.001"),
            "max_qty":Decimal("1000"),"min_notional":Decimal("5"),"leverage":2}},
        initial_capital=5000.)
    sim._new_day(0,start)
    sim._arm_initial(symbol,{"signal":signal,"known_at_ms":start,"context":{}},0)
    sim._process_pending(symbol,sim.active[symbol],0,start+M5_MS)
    # Inject an already-observed portfolio loss, not a claim of a real exchange fill.
    sim.balance -= 250.
    sim._record_equity(0,start+M5_MS)
    assert sim.emergency_latched and symbol in sim.active
    assert sim._entry_block_reason()
    sim._execute_emergency_exits(1,start+M5_MS)
    assert symbol not in sim.active
    assert sim.emergency_exit_count == 1
    assert sim.fills[-1]["reason"] == "EMERGENCY_BREAKER"
    assert sim.fills[-1]["price"] == pytest.approx(
        exit_fill_price(prices["open"][1],side,sim.slippage_rate,Decimal("0.1")))
    sim._execute_emergency_exits(1,start+M5_MS)
    assert sim.emergency_exit_count == 1
    assert sim.emergency_latched
