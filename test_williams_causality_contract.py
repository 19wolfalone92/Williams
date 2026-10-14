"""Causal invariants for the executable Bill Williams indicator layer.

These tests deliberately distinguish retrospective chart annotations from
information available to a trader at candle close. They do not certify that
the implementation is identical to every edition of Williams' books.
"""
import numpy as np
import pandas as pd

from strategy import (
    calculate_indicators,
    canonical_indicator_config_from_env,
)
from trading_config import DEFAULT_FUTURES_SYMBOLS, TradingConfig


def _ohlc(n=100):
    rows = []
    for i in range(n):
        mid = 100.0 + 0.035 * i + 1.7 * np.sin(i / 4.3) + 0.3 * np.sin(i / 1.7)
        spread = 0.7 + 0.15 * (1 + np.sin(i / 3.1))
        rows.append({
            "open": mid - 0.12 * np.cos(i),
            "high": mid + spread,
            "low": mid - spread,
            "close": mid + 0.12 * np.cos(i),
            "volume": 1000.0 + (i % 11) * 17.0,
            "open_time_ms": 1_800_000_000_000 + i * 3_600_000,
        })
    return pd.DataFrame(rows)


def test_executable_indicator_values_are_prefix_causal():
    """Appending future bars cannot alter values already knowable at close."""
    frame = _ohlc()
    cfg = canonical_indicator_config_from_env({})
    full = calculate_indicators(frame, cfg)
    causal_columns = [
        "jaw", "teeth", "lips", "jaw_current", "teeth_current", "lips_current",
        "ao", "ac", "profitunity_macd_signal", "gator_upper", "gator_lower",
        "confirmed_up_level", "confirmed_down_level",
        "confirmed_up_center_index", "confirmed_down_center_index",
        "confirmed_up_group_start_index", "confirmed_down_group_start_index",
        "fractal_up_confirmed", "fractal_down_confirmed",
    ]
    for end in (40, 55, 70, 85, 99):
        prefix = calculate_indicators(frame.iloc[: end + 1].copy(), cfg)
        for column in causal_columns:
            left = prefix[column].reset_index(drop=True)
            right = full[column].iloc[: end + 1].reset_index(drop=True)
            if pd.api.types.is_numeric_dtype(left.dtype):
                np.testing.assert_allclose(
                    left.to_numpy(dtype=float),
                    right.to_numpy(dtype=float),
                    rtol=1e-12,
                    atol=1e-12,
                    equal_nan=True,
                    err_msg=f"future data changed prior {column} values at index {end}",
                )
            else:
                assert left.tolist() == right.tolist(), (
                    f"future data changed prior {column} values at index {end}"
                )


def test_fractal_confirmation_is_not_backdated_to_the_pivot():
    frame = _ohlc(80)
    cfg = canonical_indicator_config_from_env({})
    for end in range(4, len(frame)):
        result = calculate_indicators(frame.iloc[: end + 1].copy(), cfg)
        # A confirmation can only exist at the close where its required
        # right-hand bars are available. Centre markers are retrospective UI.
        for side in ("up", "down"):
            confirmation = result[f"fractal_{side}_confirmation_index"]
            confirmed = result[f"fractal_{side}_confirmed"].astype(bool)
            for idx in confirmed[confirmed].index:
                assert int(confirmation.iloc[int(idx) - 2]) == int(idx) if int(idx) >= 2 else True


def test_canonical_futures_contract_keeps_both_directions_and_exact_universe():
    cfg = TradingConfig.from_env({})
    assert cfg.symbols == DEFAULT_FUTURES_SYMBOLS
    assert len(cfg.symbols) == 10 and len(set(cfg.symbols)) == 10
    assert cfg.allow_long is True
    assert cfg.allow_short is True
    assert cfg.execution_timeframe == "1h"
    assert cfg.structural_timeframes == ("1d", "4h", "1h", "15m")
    assert cfg.max_daily_loss_pct == 0.03
    assert cfg.campaign_risk_limit_pct <= 0.006
    assert cfg.risk_per_trade_pct <= 0.0025
