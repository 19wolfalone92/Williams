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


def test_short_backtest_flag_is_not_silently_ignored():
    with pytest.raises(NotImplementedError):
        Backtester(allow_shorts=True)


def test_invalid_ohlc_and_parameters_fail_closed():
    bad = candles([(100, 99, 98, 100, True)])
    with pytest.raises(ValueError, match="OHLC invariant"):
        Backtester().run(bad)
    with pytest.raises(ValueError, match="finite"):
        Backtester(fee_rate=float("nan"))
    with pytest.raises(ValueError, match="position_fraction"):
        Backtester(position_fraction=1.1)
