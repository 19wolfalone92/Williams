import math

import pandas as pd
import pytest

from campaign_model import SignalRole, SignalSpec, SignalType
from williams_paper_replay import PaperReplayConfig, run_williams_paper_replay


def signal(side="BUY", *, trigger=101.0, stop=98.0, ts=0, symbol="BTCUSDT"):
    return SignalSpec.new(
        symbol=symbol,
        side=side,
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=ts,
        trigger_price=trigger,
        protective_reference=stop,
    )


def candles(rows):
    return pd.DataFrame(
        rows, columns=["open", "high", "low", "close"],
        index=pd.date_range("2026-01-01", periods=len(rows), freq="h", tz="UTC"),
    )


@pytest.mark.parametrize("short",[False,True])
@pytest.mark.parametrize("crossed",[False,True])
def test_opposite_formation_must_trigger_on_a_later_bar_under_the_owned_source(short,crossed):
    from dataclasses import replace
    frame=candles([(100,100.5,99.5,100),(100.2,101.5,100.1,101.2),
        (101.2,102.5,101,102),(102,104,101.8,103.5),
        (103.5,105,100.6 if crossed else 101.7,101 if crossed else 104.8)])
    if short:
        frame=pd.DataFrame({"open":220-frame.open,"high":220-frame.low,
                            "low":220-frame.high,"close":220-frame.close},index=frame.index)
    initial=signal("SELL" if short else "BUY",trigger=119 if short else 101,stop=122 if short else 98)
    opposite=signal("BUY" if short else "SELL",trigger=119.3 if short else 100.7,stop=110,ts=2)
    _,trades,_=run_williams_paper_replay(frame,{0:[initial],2:[opposite]})
    assert len(trades)==1
    assert trades.iloc[0].exit_reason==("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED" if crossed else "END_OF_DATA")
    assert trades.iloc[0].exit_time==frame.index[-1]
    other=replace(opposite,strategy_profile="NTD_FIVE_DIMENSIONS")
    _,trades,_=run_williams_paper_replay(frame,{0:[initial],2:[other]})
    assert trades.iloc[0].exit_reason=="END_OF_DATA"


def test_long_paper_campaign_uses_conditional_entry_structural_stop_and_real_costs():
    frame = candles([
        (100.0, 100.5, 99.5, 100.0),
        (100.2, 101.5, 100.1, 101.2),
        (101.2, 102.5, 100.5, 102.0),
        (102.0, 104.0, 101.8, 103.5),
        (103.5, 105.0, 103.0, 104.8),
    ])
    cfg = PaperReplayConfig(
        starting_equity_usdt=5000.0, risk_pct_per_entry=0.002,
        fee_rate_per_side=0.001, slippage_rate_per_side=0.001,
        leverage=2.0, tick_size=0.1, trailing_lookback_bars=5,
    )
    equity, trades, metrics = run_williams_paper_replay(
        frame, {0: [signal("BUY", trigger=101.0, stop=98.0, ts=int(frame.index[0].timestamp() * 1000))]}, cfg
    )
    assert len(trades) == 1
    trade = trades.iloc[0]
    assert trade.direction == "LONG"
    assert trade.entry_price > trade.trigger_price  # adverse buy-side slippage
    assert trade.net_pnl_usdt < trade.gross_pnl_usdt
    assert trade.fees_usdt > 0
    assert trade.initial_risk_usdt <= 5000.0 * 0.002 + 1e-7
    assert trade.exit_reason == "END_OF_DATA"
    assert metrics["trades"] == 1
    assert math.isfinite(float(equity.equity_usdt.iloc[-1]))


def test_short_paper_campaign_accounts_for_short_direction_and_exit_slippage():
    frame = candles([
        (100.0, 100.5, 99.5, 100.0),
        (99.8, 99.9, 98.5, 99.0),
        (99.0, 100.0, 97.0, 98.0),
        (98.0, 98.5, 96.5, 97.2),
        (97.2, 97.5, 94.5, 95.0),
    ])
    cfg = PaperReplayConfig(
        starting_equity_usdt=5000.0, risk_pct_per_entry=0.002,
        fee_rate_per_side=0.0004, slippage_rate_per_side=0.0005,
        tick_size=0.1, trailing_lookback_bars=5,
    )
    _, trades, metrics = run_williams_paper_replay(
        frame, {0: [signal("SELL", trigger=99.0, stop=102.0, ts=int(frame.index[0].timestamp() * 1000))]}, cfg
    )
    assert len(trades) == 1
    trade = trades.iloc[0]
    assert trade.direction == "SHORT"
    assert trade.entry_price < trade.trigger_price
    assert trade.exit_price < trade.entry_price
    assert trade.net_pnl_usdt > 0
    assert trade.r_multiple > 0
    assert metrics["wins"] == 1


def test_intrabar_entry_and_stop_collision_is_conservatively_stop_first():
    frame = candles([
        (100.0, 100.5, 99.5, 100.0),
        (100.0, 102.0, 97.5, 100.5),
    ])
    _, trades, _ = run_williams_paper_replay(
        frame, {0: [signal("BUY", trigger=101.0, stop=99.0, ts=1)]},
        PaperReplayConfig(tick_size=0.1, fee_rate_per_side=0.0, slippage_rate_per_side=0.001),
    )
    assert len(trades) == 1
    assert trades.iloc[0].exit_reason == "SAME_BAR_STOP_FIRST"
    assert trades.iloc[0].net_pnl_usdt < 0


def test_no_signal_means_flat_no_trades_and_wrong_side_stop_is_rejected():
    frame = candles([(100, 101, 99, 100), (100, 102, 99, 101), (101, 102, 100, 101)])
    _, no_trades, metrics = run_williams_paper_replay(frame, {})
    _, invalid_trades, _ = run_williams_paper_replay(
        frame, {0: [signal("BUY", trigger=101.0, stop=102.0, ts=1)]}
    )
    assert no_trades.empty
    assert invalid_trades.empty
    assert metrics["ending_equity_usdt"] == metrics["starting_equity_usdt"]


def test_paper_replay_rejects_risk_above_entry_cap_and_bad_candles():
    with pytest.raises(ValueError, match="0.25%"):
        run_williams_paper_replay(
            candles([(100, 101, 99, 100)]), {},
            PaperReplayConfig(risk_pct_per_entry=0.003),
        )
    with pytest.raises(ValueError, match="invalid OHLC"):
        run_williams_paper_replay(candles([(100, 99, 101, 100)]), {})
