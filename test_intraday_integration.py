import os

from trading_config import TradingConfig
from strategy import calculate_indicators, config_from_env
import pandas as pd


def test_production_defaults_match_intraday_contract():
    cfg = TradingConfig.from_env({})
    assert cfg.decision_timeframe == "1h"
    assert cfg.execution_timeframe == "15m"
    assert cfg.micro_timeframe == "5m"
    assert cfg.structural_timeframes == ("1d", "4h", "1h", "15m", "5m")
    assert cfg.symbols == ("BTCUSDT", "ETHUSDT")
    assert cfg.risk_per_trade_pct == 0.0025
    assert cfg.max_total_risk_pct == 0.006
    assert cfg.max_daily_loss_pct == 0.01
    assert cfg.max_open_positions == 1


def test_wise_men_are_independent_signal_families():
    prices = [100 + (i * 0.05) ** 2 for i in range(90)]
    rows = []
    for p in prices:
        rows.append({"open": p - 0.2, "high": p + 0.5, "low": p - 0.5, "close": p, "volume": 1000.0})
    out = calculate_indicators(pd.DataFrame(rows), config_from_env({}))
    expected = (
        out["long_wise_reversal_entry"]
        | out["long_super_ao_signal"]
        | out["long_fractal_signal"]
    )
    assert (out["long_signal"] == expected).all()
    assert "long_wise_man_count" in out


def test_strategy_does_not_require_two_wise_men_to_create_core_truth():
    cfg = config_from_env({})
    assert cfg["min_wise_men_confirmations"] == 1
