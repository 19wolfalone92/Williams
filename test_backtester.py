import numpy as np
import pandas as pd
import pytest

from backtester import Backtester, calculate_metrics


def candles(rows):
    return pd.DataFrame(rows, columns=["open", "high", "low", "close", "long_signal"],
                        index=pd.date_range("2025-01-01", periods=len(rows), freq="1h", tz="UTC"))


def test_signal_at_close_enters_only_at_next_open_and_charges_both_side_fees():
    data = candles([
        (100, 101, 99, 100, True),
        (110, 111, 109, 110, False),
        (112, 113, 111, 112, False),
    ])
    bt = Backtester(starting_capital=1000, fee_rate=0.01, slippage_rate=0,
                    position_fraction=0.5, stop_loss_pct=0.5, take_profit_pct=0.5)
    equity, trades = bt.run(data)
    assert len(trades) == 1
    trade = trades.iloc[0]
    assert trade.entry_time == data.index[1]
    assert trade.entry_price == 110
    assert trade.reason == "END"
    assert trade.fees > 0
    assert equity.attrs["starting_capital"] == 1000


def test_stop_gap_fills_at_open_not_unreachable_stop():
    data = candles([
        (100, 101, 99, 100, True),
        (100, 101, 99, 100, False),
        (90, 92, 85, 88, False),
    ])
    bt = Backtester(starting_capital=1000, fee_rate=0, slippage_rate=0,
                    position_fraction=1, stop_loss_pct=0.05, take_profit_pct=0.5)
    _, trades = bt.run(data)
    assert trades.iloc[0].reason == "STOP"
    assert trades.iloc[0].exit_price == 90


def test_stop_target_collision_is_stop_first_by_default():
    data = candles([
        (100, 101, 99, 100, True),
        (100, 110, 90, 100, False),
    ])
    bt = Backtester(starting_capital=1000, fee_rate=0, slippage_rate=0,
                    stop_loss_pct=0.05, take_profit_pct=0.05)
    _, trades = bt.run(data)
    assert trades.iloc[0].reason == "STOP"


def test_metrics_use_initial_capital_and_handle_no_trades_and_zero_losses():
    equity = pd.DataFrame(
        {"equity": [1100.0, 1200.0]},
        index=pd.date_range("2025-01-01", periods=2, freq="1D", tz="UTC"),
    )
    metrics = calculate_metrics(equity, pd.DataFrame(columns=["pnl"]), "1d", starting_capital=1000)
    assert metrics["total_return_pct"] == pytest.approx(20.0)
    assert metrics["starting_capital"] == 1000
    assert metrics["profit_factor"] is None
    assert metrics["trades"] == 0
    wins = pd.DataFrame({"pnl": [10.0], "pnl_pct": [1.0], "fees": [2.0]})
    metrics = calculate_metrics(equity, wins, "1d", starting_capital=1000)
    assert np.isinf(metrics["profit_factor"])
    assert metrics["expectancy_usd"] == 10.0
    assert metrics["expectancy_pct"] == pytest.approx(1.0)


def test_short_signal_enters_at_next_open_and_covers_target_with_fees():
    data = candles([
        (100, 101, 99, 100, False),
        (100, 101, 99, 100, False),
        (90, 92, 85, 88, False),
    ])
    data["short_signal"] = [True, False, False]
    bt = Backtester(
        starting_capital=1000,
        fee_rate=0.01,
        slippage_rate=0,
        position_fraction=0.5,
        stop_loss_pct=0.05,
        take_profit_pct=0.05,
        allow_shorts=True,
    )
    equity, trades = bt.run(data)
    assert len(trades) == 1
    trade = trades.iloc[0]
    assert trade.side == "SHORT"
    assert trade.entry_time == data.index[1]
    assert trade.entry_price == 100
    assert trade.exit_time == data.index[2]
    assert trade.exit_price == pytest.approx(95)
    assert trade.reason == "TARGET"
    assert trade.pnl > 0
    assert trade.fees > 0
    assert equity.iloc[-1, 0] == pytest.approx(1000 + trade.pnl)


def test_short_gap_through_stop_is_adverse_and_stop_first_by_default():
    data = candles([
        (100, 101, 99, 100, False),
        (100, 101, 99, 100, False),
        (110, 112, 90, 110, False),
    ])
    data["short_signal"] = [True, False, False]
    bt = Backtester(
        starting_capital=1000,
        fee_rate=0,
        slippage_rate=0,
        position_fraction=1,
        stop_loss_pct=0.05,
        take_profit_pct=0.05,
        allow_shorts=True,
        intrabar_exit_policy="stop_first",
    )
    equity, trades = bt.run(data)
    assert len(trades) == 1
    assert trades.iloc[0].side == "SHORT"
    assert trades.iloc[0].reason == "STOP"
    assert trades.iloc[0].exit_price == 110
    assert trades.iloc[0].pnl == pytest.approx(-100)
    assert equity.iloc[-1, 0] == pytest.approx(900)


def test_short_replay_requires_explicit_short_signal_and_rejects_ambiguous_double_signal():
    with pytest.raises(ValueError, match="short_signal"):
        Backtester(allow_shorts=True).run(candles([(100, 101, 99, 100, False)]))

    data = candles([
        (100, 101, 99, 100, False),
        (100, 101, 99, 100, False),
    ])
    data["short_signal"] = [True, False]
    data.loc[data.index[0], "long_signal"] = True
    _, trades = Backtester(
        allow_shorts=True,
        stop_loss_pct=0.2,
        take_profit_pct=0.2,
    ).run(data)
    assert trades.empty


def test_invalid_ohlc_and_parameters_fail_closed():
    bad = candles([(100, 99, 98, 100, True)])
    with pytest.raises(ValueError, match="OHLC invariant"):
        Backtester().run(bad)
    with pytest.raises(ValueError, match="finite"):
        Backtester(fee_rate=float("nan"))
    with pytest.raises(ValueError, match="position_fraction"):
        Backtester(position_fraction=1.1)


def test_gap_through_stop_remains_adverse_when_target_is_also_touched():
    data = candles([
        (100, 101, 99, 100, True),
        (100, 101, 99, 100, False),
        (90, 110, 85, 100, False),
    ])
    bt = Backtester(starting_capital=1000, fee_rate=0, slippage_rate=0,
                    position_fraction=1, stop_loss_pct=0.05, take_profit_pct=0.05,
                    intrabar_exit_policy="stop_first")
    _, trades = bt.run(data)
    assert trades.iloc[0].reason == "STOP"
    assert trades.iloc[0].exit_price == 90


def test_backtester_rejects_missing_or_ambiguous_signal_values():
    base = candles([
        (100, 101, 99, 100, False),
        (100, 101, 99, 100, False),
    ])
    missing = base.copy()
    missing["long_signal"] = missing["long_signal"].astype(object)
    missing.iloc[0, missing.columns.get_loc("long_signal")] = np.nan
    with pytest.raises(ValueError, match="missing values"):
        Backtester().run(missing)
    ambiguous = base.copy()
    ambiguous["long_signal"] = ["false", "true"]
    with pytest.raises(ValueError, match="booleans or 0/1"):
        Backtester().run(ambiguous)
