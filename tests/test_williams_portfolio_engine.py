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


@pytest.mark.parametrize("owner", ["BUY", "SELL"])
@pytest.mark.parametrize("case", ["CONTRACT_ONLY", "MARK_ONLY", "INACTIVE_HISTORICAL", "ACTIVE_LOSS"])
def test_replay_opposite_price_basis_and_user_profit_overlay(owner, case):
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    from williams_edition_rules import TC2
    from campaign_model import SignalType, SignalRole
    now = 100 * H1_MS
    symbol = "BTCUSDT"
    long = owner == "BUY"
    crossed, waiting = (99., 101.) if long else (101., 99.)
    historical = case in ("INACTIVE_HISTORICAL", "ACTIVE_LOSS")
    contract = crossed if case in ("CONTRACT_ONLY", "ACTIVE_LOSS") else waiting
    mark = waiting if case == "CONTRACT_ONLY" else crossed
    entry = (102. if long else 98.) if historical else (80. if long else 120.)
    signal = SimpleNamespace(signal_id="opposite", side="SELL" if long else "BUY",
        strategy_profile=TC2, role=SignalRole.ENTRY, signal_type=SignalType.REVERSAL,
        execution_semantics="STOP_ENTRY", signal_bar_time_ms=now-(2 if historical else 1)*H1_MS,
        trigger_price=100., invalidation_price=110. if long else 90.)
    campaign = {"campaign_id":"owner", "side":owner, "entry_time_ms":now-3*H1_MS,
        "avg_entry":entry, "stop":70. if long else 130., "qty":1.,
        "opposition_watchers":[{"signal":signal, "known_at_ms":now-(H1_MS if historical else 0),
                                "expires_at_ms":now+H1_MS}]}
    sim = PortfolioSimulator.__new__(PortfolioSimulator)
    sim.data = {symbol:{"contract":{"open":[contract]}, "mark":{"mark_open":[mark]}}}
    sim.specs = {symbol:{"tick":Decimal("0.1")}}
    emitted, closes = [], []
    sim._emit = lambda *a, **k: emitted.append(k)
    sim._signal_status = lambda *a, **k: None
    sim._close_campaign = lambda *a, **k: closes.append(a)
    bars = [SimpleNamespace(t=i*H1_MS,o=100.,h=101.,l=99.,c=100.) for i in range(55,100)]
    if historical:
        bars[-1] = SimpleNamespace(t=99*H1_MS,o=crossed,h=102.,l=98.,c=100.)
    assert sim._check_opposition(symbol,campaign,0,now,bars) == (case == "CONTRACT_ONLY")
    if case == "CONTRACT_ONLY":
        assert len(closes) == 1
        assert campaign["opposition_evidence"]["trigger_price_basis"] == "CONTRACT_PRICE"
    elif case == "MARK_ONLY":
        assert not closes and not emitted
    else:
        assert not closes and len(emitted) == 1
        assert emitted[0]["trigger_price_basis"] == "CONTRACT_PRICE"
        assert emitted[0]["current_price_beyond_trigger"] == (case == "ACTIVE_LOSS")
        assert emitted[0]["blocker"] == (
            "TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE" if case == "ACTIVE_LOSS"
            else "CURRENT_PRICE_NOT_BEYOND_TRIGGER")

@pytest.mark.parametrize("side,opening,high,low,stop,expected", [
    ("BUY", 95., 140., 80., 99., (0., 10., 0., 10.)),
    ("SELL", 105., 120., 60., 101., (0., 10., 0., 10.)),
    ("BUY", 102., 140., 80., 99., (4., 2., 80., 40.)),
    ("SELL", 98., 120., 60., 101., (4., 2., 80., 40.)),
])
def test_causal_terminal_mark_excursion_bounds(side, opening, high, low, stop, expected):
    from research.williams_portfolio_engine import PortfolioSimulator, M5_MS
    symbol = "BTCUSDT"
    sim = PortfolioSimulator.__new__(PortfolioSimulator)
    sim.data = {symbol: {"contract": {"open": [opening], "high": [high],
                                    "low": [low], "close": [100.]},
                         "mark": {"mark_open": [opening], "mark_high": [high],
                                  "mark_low": [low], "mark_close": [100.]}}}
    closes = []
    sim._close_campaign = lambda *a: closes.append(a)
    campaign = {"side": side, "qty": 2., "avg_entry": 100., "stop": stop,
                "stop_source": "INITIAL_SIGNAL"}
    sim._process_open_position(symbol, campaign, 0, M5_MS)
    assert len(closes) == 1
    assert tuple(campaign[k] for k in ("mfe_quote", "mae_quote", "mfe_upper_quote",
                                      "mae_upper_quote")) == pytest.approx(expected)


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_causal_full_bar_and_addon_exposure_are_separate(side):
    from research.williams_portfolio_engine import PortfolioSimulator, M5_MS
    symbol = "BTCUSDT"
    sim = PortfolioSimulator.__new__(PortfolioSimulator)
    sim.data = {symbol: {"contract": {"open": [100.], "high": [110.],
                                    "low": [90.], "close": [101.]},
                         "mark": {"mark_open": [100.], "mark_high": [110.],
                                  "mark_low": [90.], "mark_close": [101.]}}}
    base = {"side": side, "qty": 2., "avg_entry": 100.,
            "stop": 80. if side == "BUY" else 120.}
    full = dict(base)
    sim._process_open_position(symbol, full, 0, M5_MS)
    assert full["mfe_quote"] == full["mae_quote"] == 20.
    pending = dict(base, pending={"kind": "ADD_ON",
                     "trigger": 105. if side == "BUY" else 95.})
    sim._process_open_position(symbol, pending, 0, M5_MS)
    assert pending["mfe_quote"] == pending["mae_quote"] == 0.
    assert pending["mfe_upper_quote"] == pending["mae_upper_quote"] == 20.
    pending["qty"] = 6.
    pending["avg_entry"] = 103. if side == "BUY" else 97.
    sim._observe_mark_excursion(pending, (101.,), (90., 110.), ambiguous=True)
    assert pending["excursion_ambiguous_intervals"] == 2
    assert pending["mfe_quote"] <= pending["mfe_upper_quote"]
    assert pending["mae_quote"] <= pending["mae_upper_quote"]


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_causal_initial_fill_excludes_unproven_pre_entry_extrema(side):
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    start = 1730419200000
    symbol = "BTCUSDT"
    prices = {k: np.array([v]) for k, v in
              {"open": 100., "high": 105., "low": 95., "close": 100.}.items()}
    trigger, stop = (101., 90.) if side == "BUY" else (99., 110.)
    signal = SignalSpec.new(symbol=symbol, side=side, signal_type=SignalType.REVERSAL,
                            role=SignalRole.ENTRY, timeframe="1h",
                            signal_bar_time_ms=start-H1_MS, trigger_price=trigger,
                            protective_reference=stop)
    sim = PortfolioSimulator(symbols=[symbol], data={symbol: {"contract": prices,
        "mark": {"mark_"+k: v for k,v in prices.items()}, "h1_close_rows": {}}},
        timeline=np.array([start]), signals_by_index={symbol: {0: [{"signal": signal,
        "known_at_ms": start, "context": {}}]}}, funding_by_index={symbol: {}},
        specs={symbol: {"tick": Decimal("0.1"), "step": Decimal("0.001"),
                       "min_qty": Decimal("0.001"), "max_qty": Decimal("1000"),
                       "min_notional": Decimal("5"), "leverage": 2}}, initial_capital=5000.)
    result = sim.run()
    assert [row["purpose"] for row in result["fills"]] == ["INITIAL"]
    row = result["campaigns"][0]
    assert row["MFE_usdt"] == 0.
    assert row["MFE_upper_usdt"] > 0.
    assert 0. < row["MAE_usdt"] < row["MAE_upper_usdt"]
    assert row["excursion_ambiguous_intervals"] == 1

@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("case", ["SUCCESS", "INVALID_NEW", "EXPIRED_OLD", "OLD_TRIGGER_AT_OPEN", "DAILY_BLOCK", "FILTER_REJECT"])
def test_causal_pending_reanchor_is_confirmed_only_after_new_arm(side, case):
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    start = 1730419200000
    now = start + H1_MS
    symbol = "BTCUSDT"
    long = side == "BUY"
    old_trigger, old_stop = (110., 90.) if long else (90., 110.)
    new_trigger = 105. if long else 95.
    new_stop = (101. if long else 99.) if case in ("INVALID_NEW", "EXPIRED_OLD") else (95. if long else 105.)
    opening = (111. if long else 89.) if case == "OLD_TRIGGER_AT_OPEN" else 100.
    prices = {k: np.array([100., opening]) for k in ("open", "high", "low", "close")}
    old = SignalSpec.new(symbol=symbol, side=side, signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=start-3*H1_MS,
        trigger_price=old_trigger, protective_reference=old_stop,
        expires_at_ms=now if case == "EXPIRED_OLD" else now+H1_MS)
    new = SignalSpec.new(symbol=symbol, side=side, signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=start,
        trigger_price=new_trigger, protective_reference=new_stop)
    sim = PortfolioSimulator(symbols=[symbol], data={symbol: {"contract": prices,
        "mark": {"mark_"+k: v for k,v in prices.items()}, "h1_close_rows": {}}},
        timeline=np.array([start, now]), signals_by_index={symbol: {}}, funding_by_index={symbol: {}},
        specs={symbol: {"tick": Decimal("0.1"), "step": Decimal("0.001"),
                       "min_qty": Decimal("0.001"), "max_qty": Decimal("1000"),
                       "min_notional": Decimal("5"), "leverage": 2}}, initial_capital=5000.)
    sim._new_day(0, start)
    sim._arm_initial(symbol, {"signal": old, "known_at_ms": start, "context": {}}, 0)
    original = sim.active[symbol]
    original_risk = sim._reserved_risk()
    if case == "DAILY_BLOCK":
        sim.daily_entry_blocked = True
        sim.daily_block_reason = "TEST_DAILY_BLOCK"
    if case == "FILTER_REJECT":
        sim.specs[symbol]["min_notional"] = Decimal("100000")
    sim._replace_pending_initial(original, {"signal": new, "known_at_ms": now, "context": {}}, 1)
    names = [e["event"] for e in sim.events]
    if case == "SUCCESS":
        assert sim.active[symbol]["pending"]["signal"].signal_id == new.signal_id
        assert sim.signal_records[(symbol, old.signal_id)]["decision_status"] == "REPLACED"
        assert sim.replaced_count == 1
        assert names.index("PENDING_INITIAL_REPLACEMENT_REQUESTED") < len(names)-1
        assert names[-2:] == ["INITIAL_STOP_ORDER_ARMED", "PENDING_INITIAL_REPLACED"]
    elif case in ("INVALID_NEW", "FILTER_REJECT", "OLD_TRIGGER_AT_OPEN"):
        assert sim.active[symbol] is original
        assert sim._reserved_risk() == original_risk
        assert sim.signal_records[(symbol, old.signal_id)]["decision_status"] == "PENDING_INITIAL"
        assert (symbol, old.signal_id) not in sim.terminal_entry_signals
        assert sim.replaced_count == 0
        assert names[-1] == ("PENDING_INITIAL_REPLACEMENT_DEFERRED_OLD_TRIGGER_AT_OPEN"
                            if case == "OLD_TRIGGER_AT_OPEN" else "PENDING_INITIAL_REPLACEMENT_REJECTED")
    else:
        assert symbol not in sim.active
        assert sim._reserved_risk() == 0.
        assert sim.replaced_count == 0
        assert sim.signal_records[(symbol, old.signal_id)]["decision_status"] == (
            "EXPIRED" if case == "EXPIRED_OLD" else "CANCELLED")
    assert len(sim.active) <= 1

@pytest.mark.parametrize("owner", ["BUY", "SELL"])
@pytest.mark.parametrize("policy", ["U", "T"])
@pytest.mark.parametrize("case", ["ACTIVE_LOSS", "ACTIVE_PROFIT", "WAITING", "WM2"])
def test_dual_policy_uses_source_qualified_trigger_and_confirmed_close(owner, policy, case):
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS
    from williams_edition_rules import TC2
    from campaign_model import SignalType, SignalRole
    now = 100 * H1_MS
    symbol = "BTCUSDT"
    long = owner == "BUY"
    crossed, waiting = (99., 101.) if long else (101., 99.)
    current = waiting if case == "WAITING" else crossed
    entry = (80. if long else 120.) if case == "ACTIVE_PROFIT" else (102. if long else 98.)
    signal = SimpleNamespace(signal_id="opposite", side="SELL" if long else "BUY",
        strategy_profile=TC2, role=SignalRole.ENTRY,
        signal_type=SignalType.SUPER_AO if case == "WM2" else SignalType.REVERSAL,
        execution_semantics="STOP_ENTRY", signal_bar_time_ms=now-H1_MS,
        trigger_price=100., invalidation_price=110. if long else 90.)
    campaign = {"campaign_id":"owner", "side":owner, "entry_time_ms":now-3*H1_MS,
        "avg_entry":entry, "stop":70. if long else 130., "qty":1.,
        "opposition_watchers":[{"signal":signal, "known_at_ms":now,
                                "expires_at_ms":now+H1_MS}]}
    sim = PortfolioSimulator.__new__(PortfolioSimulator)
    sim.opposite_policy = policy
    sim.terminal_entry_signals = set()
    sim.reversal_requests = {}
    sim.active = {symbol: campaign}
    sim.data = {symbol:{"contract":{"open":[current]}, "mark":{"mark_open":[current]}}}
    sim.specs = {symbol:{"tick":Decimal("0.1")}}
    sim._emit = lambda *a, **k: None
    sim._signal_status = lambda *a, **k: None
    def close(*args):
        del sim.active[symbol]
    sim._close_campaign = close
    bars = [SimpleNamespace(t=i*H1_MS,o=100.,h=101.,l=99.,c=100.) for i in range(55,100)]
    expected = case == "ACTIVE_PROFIT" or (policy == "T" and case == "ACTIVE_LOSS")
    assert sim._check_opposition(symbol,campaign,0,now,bars) == expected
    assert (symbol in sim.reversal_requests) == (expected and policy == "T")
    if symbol in sim.reversal_requests:
        assert sim.reversal_requests[symbol]["next_index"] == 1
        assert symbol not in sim.active


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("blocked", [False, True])
def test_reversal_rechecks_risk_and_sizes_at_next_observable_open(side, blocked):
    import numpy as np
    from campaign_model import SignalSpec, SignalRole, SignalType
    from research.williams_portfolio_engine import PortfolioSimulator, H1_MS, M5_MS
    start = 1730419200000
    symbol = "BTCUSDT"
    current = 102. if side == "BUY" else 98.
    prices = {k: np.array([current, current]) for k in ("open","high","low","close")}
    signal = SignalSpec.new(symbol=symbol, side=side, signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=start-H1_MS,
        trigger_price=101. if side=="BUY" else 99.,
        protective_reference=90. if side=="BUY" else 110.)
    sim = PortfolioSimulator(symbols=[symbol], data={symbol:{"contract":prices,
        "mark":{"mark_"+k:v for k,v in prices.items()}, "h1_close_rows":{}}},
        timeline=np.array([start,start+M5_MS]),signals_by_index={symbol:{}},
        funding_by_index={symbol:{}},specs={symbol:{"tick":Decimal("0.1"),
        "step":Decimal("0.001"),"min_qty":Decimal("0.001"),"max_qty":Decimal("1000"),
        "min_notional":Decimal("5"),"leverage":2}},initial_capital=5000.,opposite_policy="T")
    sim._new_day(0,start)
    sim.reversal_requests[symbol] = {"signal":signal,"context":{},"known_at_ms":start,
                                     "next_index":1,"closed_campaign_id":"old"}
    sim._execute_reversal_admissions(0,start)
    assert symbol not in sim.active
    if blocked:
        sim.balance -= 150.
    sim._execute_reversal_admissions(1,start+M5_MS)
    assert not sim.reversal_requests
    if blocked:
        assert symbol not in sim.active
        assert sim.events[-1]["event"] == "RESEARCH_REVERSAL_REJECTED"
    else:
        campaign = sim.active[symbol]
        assert campaign["pending"]["trigger"] == current
        sim._process_pending(symbol,campaign,1,start+2*M5_MS)
        assert campaign["status"] == "OPEN"
        assert sim.reversal_fills == 1
        assert campaign["stop"] == signal.protective_reference
        assert sim.fills[-1]["fee"] > 0
