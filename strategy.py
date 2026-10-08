"""Canonical Williams indicator facade.

All production strategy truth is defined by the williams.* package.  This
legacy facade retains the dataframe columns used by the scanner/UI and
Profitunity diagnostics, but it no longer imposes hidden multi-confirmation
gates on the Core signal.
"""
from __future__ import annotations
import os
import numpy as np
import pandas as pd
from williams.alligator import calculate_alligator
from williams.ao import calculate_ao
from williams.fractals import FractalEngine
from williams.wm1 import evaluate_wm1


def smma(series, period):
    out = pd.Series(index=series.index, dtype=float)
    period = int(period)
    if period <= 0:
        raise ValueError("SMMA period must be positive")
    if len(series) < period:
        return out
    out.iloc[period - 1] = series.iloc[:period].mean()
    for i in range(period, len(series)):
        out.iloc[i] = ((out.iloc[i - 1] * (period - 1) + series.iloc[i]) / period)
    return out


def _streak(mask):
    values = mask.fillna(False).astype(bool).to_numpy()
    out = np.zeros(len(values), dtype=int)
    n = 0
    for i, value in enumerate(values):
        n = n + 1 if value else 0
        out[i] = n
    return pd.Series(out, index=mask.index, dtype=int)


def _profitunity_window(volume_up, mfi_up):
    result = pd.Series("STABLE", index=volume_up.index, dtype=object)
    result.loc[volume_up & mfi_up] = "GREEN"
    result.loc[~volume_up & ~mfi_up] = "FADING"
    result.loc[~volume_up & mfi_up] = "FAKE"
    result.loc[volume_up & ~mfi_up] = "SQUAT"
    return result


def _time_ms(row, index):
    for key in ("open_time_ms", "time_ms", "timestamp", "time", "open_time"):
        value = row.get(key)
        if value is not None and not pd.isna(value):
            try:
                return int(value)
            except (TypeError, ValueError):
                pass
    return int(index)


def calculate_indicators(df, cfg):
    x = df.copy()
    if x.empty:
        return x
    median = (x["high"] + x["low"]) / 2.0

    # Canonical Alligator and AO definitions. The package implementation is
    # the source of truth; the facade exposes the same columns for legacy code.
    x = calculate_alligator(
        x,
        jaw_period=cfg["jaw"],
        teeth_period=cfg["teeth"],
        lips_period=cfg["lips"],
        jaw_shift=cfg["jaw_shift"],
        teeth_shift=cfg["teeth_shift"],
        lips_shift=cfg["lips_shift"],
        min_spread_pct=cfg["min_alligator_spread_pct"],
    )
    x = calculate_ao(x, fast=cfg["ao_fast"], slow=cfg["ao_slow"])
    x["super_ao_long"] = x["ao_green_streak"] >= int(cfg["super_ao_bars"])
    x["super_ao_short"] = x["ao_red_streak"] >= int(cfg["super_ao_bars"])
    x["ao_momentum_rising"] = x["ao_green"].astype(bool)
    x["ao_momentum_falling"] = x["ao_red"].astype(bool)

    # Accelerator/Decelerator and secondary diagnostics.
    x["ac"] = x["ao"] - x["ao"].rolling(cfg["ac_period"]).mean()
    delta = x["close"].diff()
    gain = delta.clip(lower=0.0)
    loss = -delta.clip(upper=0.0)
    avg_gain = gain.ewm(alpha=1.0 / max(int(cfg["rsi_period"]), 1), adjust=False, min_periods=int(cfg["rsi_period"])).mean()
    avg_loss = loss.ewm(alpha=1.0 / max(int(cfg["rsi_period"]), 1), adjust=False, min_periods=int(cfg["rsi_period"])).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    x["rsi"] = (100.0 - (100.0 / (1.0 + rs))).fillna(50.0)

    trade_count = pd.to_numeric(x.get("trades", pd.Series(np.nan, index=x.index)), errors="coerce")
    tick_volume = trade_count.where(trade_count > 0, x["volume"])
    x["tick_volume_proxy"] = tick_volume

    # Complete Williams fractal engine: ties and overlapping/shared bars are
    # legal; the metadata carries 5/6/9-bar extension information.
    fractals = FractalEngine(
        left=max(2, int(cfg["fractal_left"])),
        right=max(2, int(cfg["fractal_right"])),
        max_extension=9,
    ).detect(x)
    x["fractal_up"] = False
    x["fractal_down"] = False
    x["fractal_up_span"] = 0
    x["fractal_down_span"] = 0
    for f in fractals:
        if f.center_index < len(x):
            col = "fractal_up" if f.side == "UP" else "fractal_down"
            span_col = "fractal_up_span" if f.side == "UP" else "fractal_down_span"
            x.iloc[f.center_index, x.columns.get_loc(col)] = True
            x.iloc[f.center_index, x.columns.get_loc(span_col)] = int(f.span)

    x["confirmed_up_level"] = np.nan
    x["confirmed_down_level"] = np.nan
    for f in fractals:
        if f.confirmation_index >= len(x):
            continue
        col = "confirmed_up_level" if f.side == "UP" else "confirmed_down_level"
        value = f.level
        x.iloc[f.confirmation_index, x.columns.get_loc(col)] = float(value)
    x["last_up_level"] = x["confirmed_up_level"].ffill()
    x["last_down_level"] = x["confirmed_down_level"].ffill()

    x["long_fractal_outside"] = x["last_up_level"].notna() & x["teeth_shifted"].notna() & (x["last_up_level"] > x["teeth_shifted"])
    x["short_fractal_outside"] = x["last_down_level"].notna() & x["teeth_shifted"].notna() & (x["last_down_level"] < x["teeth_shifted"])
    x["long_above_fractal"] = x["long_fractal_outside"] & (x["close"] > x["last_up_level"])
    x["long_previous_below_fractal"] = x["long_fractal_outside"] & x["last_up_level"].shift(1).notna() & (x["close"].shift(1) <= x["last_up_level"].shift(1))
    x["short_below_fractal"] = x["short_fractal_outside"] & (x["close"] < x["last_down_level"])
    x["short_previous_above_fractal"] = x["short_fractal_outside"] & x["last_down_level"].shift(1).notna() & (x["close"].shift(1) >= x["last_down_level"].shift(1))
    x["long_fractal_signal"] = x["long_above_fractal"] & x["long_previous_below_fractal"]
    x["short_fractal_signal"] = x["short_below_fractal"] & x["short_previous_above_fractal"]

    # Current-bar WM1 truth comes from the side-specific angulation engine.
    x["bullish_reversal_bar"] = False
    x["bearish_reversal_bar"] = False
    x["wm1_long_valid"] = False
    x["wm1_short_valid"] = False
    x["wm1_angulation_long"] = 0.0
    x["wm1_angulation_short"] = 0.0
    for i in range(len(x)):
        if i < 2:
            continue
        long_r = evaluate_wm1(x, i, "LONG", outside_atr_mult=cfg["wm1_outside_atr_mult"], angulation_window=cfg["wm1_angulation_window"])
        short_r = evaluate_wm1(x, i, "SHORT", outside_atr_mult=cfg["wm1_outside_atr_mult"], angulation_window=cfg["wm1_angulation_window"])
        x.iloc[i, x.columns.get_loc("bullish_reversal_bar")] = bool(long_r.extreme and long_r.upper_half)
        x.iloc[i, x.columns.get_loc("bearish_reversal_bar")] = bool(short_r.extreme and short_r.upper_half)
        x.iloc[i, x.columns.get_loc("wm1_long_valid")] = bool(long_r.valid)
        x.iloc[i, x.columns.get_loc("wm1_short_valid")] = bool(short_r.valid)
        x.iloc[i, x.columns.get_loc("wm1_angulation_long")] = float(long_r.angulation.angular_separation if long_r.angulation else 0.0)
        x.iloc[i, x.columns.get_loc("wm1_angulation_short")] = float(short_r.angulation.angular_separation if short_r.angulation else 0.0)
    x["long_reversal_signal"] = x["wm1_long_valid"]
    x["short_reversal_signal"] = x["wm1_short_valid"]
    x["last_bullish_reversal_high"] = x["high"].where(x["bullish_reversal_bar"]).ffill().shift(1)
    x["last_bearish_reversal_low"] = x["low"].where(x["bearish_reversal_bar"]).ffill().shift(1)

    # The legacy breakout columns remain informational only; WM1 validity does
    # not wait for a fully trending Alligator.
    x["long_wise_reversal_entry"] = x["wm1_long_valid"]
    x["short_wise_reversal_entry"] = x["wm1_short_valid"]
    x["long_super_ao_signal"] = x["ao_green_streak"].eq(3)
    x["short_super_ao_signal"] = x["ao_red_streak"].eq(3)

    # A fractal confirmed on the current bar is a new WM3 observation. Older
    # fractals remain pending in williams_signals.Persisted PendingSignal.
    x["wm3_long_current"] = x["confirmed_up_level"].notna() & x["long_fractal_outside"]
    x["wm3_short_current"] = x["confirmed_down_level"].notna() & x["short_fractal_outside"]
    x["long_wise_man_count"] = x[["wm1_long_valid", "long_super_ao_signal", "wm3_long_current"]].astype(int).sum(axis=1)
    x["short_wise_man_count"] = x[["wm1_short_valid", "short_super_ao_signal", "wm3_short_current"]].astype(int).sum(axis=1)

    # Compatibility fields for UI/legacy scoring. They are not Core gates.
    x["long_bullish"] = x["bullish_alligator"]
    x["long_awake"] = x["alligator_state"].isin(["AWAKENING", "TRENDING"])
    x["alligator_awake"] = x["alligator_state"].isin(["AWAKENING", "TRENDING"])
    x["long_ao_positive"] = x["ao"] > 0
    x["long_ac_positive"] = x["ac"] > 0
    x["long_fractal_ready"] = x["last_up_level"].notna()
    x["short_bearish"] = x["bearish_alligator"]
    x["short_awake"] = x["alligator_state"].isin(["AWAKENING", "TRENDING"])
    x["short_ao_negative"] = x["ao"] < 0
    x["short_ac_negative"] = x["ac"] < 0
    x["short_fractal_ready"] = x["last_down_level"].notna()

    # Core decision is the current H1 observation only. WM2/WM3 remain
    # independent of each other and no minimum count is imposed.
    x["long_signal"] = x[["wm1_long_valid", "long_super_ao_signal", "wm3_long_current"]].any(axis=1)
    x["short_signal"] = x[["wm1_short_valid", "short_super_ao_signal", "wm3_short_current"]].any(axis=1)

    def _family(row, side):
        cols=[f"wm1_{side}_valid",f"{side}_super_ao_signal",f"wm3_{side}_current"]
        names=["REVERSAL","SUPER_AO","FRACTAL"]
        return "+".join(names[i] for i,c in enumerate(cols) if bool(row.get(c,False))) or "NONE"
    x["long_signal_family"] = x.apply(lambda r:_family(r,"long"),axis=1)
    x["short_signal_family"] = x.apply(lambda r:_family(r,"short"),axis=1)
    x["long_setup_score"] = x[["wm1_long_valid","long_super_ao_signal","wm3_long_current"]].astype(int).sum(axis=1) / 3.0 * 100.0
    x["long_wise_man_score"] = x["long_wise_man_count"] / 3.0 * 100.0
    x["long_breakout_distance_pct"] = np.where(x["last_up_level"].notna() & (x["last_up_level"] > 0),(x["close"] / x["last_up_level"] - 1.0) * 100.0,np.nan)

    volume=pd.to_numeric(x.get("volume",pd.Series(np.nan,index=x.index)),errors="coerce")
    x["volume"]=volume
    x["mfi_proxy"]=(x["high"]-x["low"])/x["tick_volume_proxy"].replace(0,np.nan)
    x["volume_up"]=x["tick_volume_proxy"]>x["tick_volume_proxy"].shift(1)
    x["volume_down"]=volume<volume.shift(1)
    x["mfi_up"]=x["mfi_proxy"]>x["mfi_proxy"].shift(1)
    x["mfi_down"]=x["mfi_proxy"]<x["mfi_proxy"].shift(1)
    x["profitunity_window"]=_profitunity_window(x["volume_up"],x["mfi_up"])
    x["squatting_bar"]=x["profitunity_window"]=="SQUAT"
    x["canonical_decision_tf"]="1h"
    return x


def config_from_env(env=os.environ):
    return {
        "jaw": int(env.get("ALLIGATOR_JAW", "13")),
        "teeth": int(env.get("ALLIGATOR_TEETH", "8")),
        "lips": int(env.get("ALLIGATOR_LIPS", "5")),
        "jaw_shift": int(env.get("JAW_SHIFT", "8")),
        "teeth_shift": int(env.get("TEETH_SHIFT", "5")),
        "lips_shift": int(env.get("LIPS_SHIFT", "3")),
        "ao_fast": int(env.get("AO_FAST", "5")),
        "ao_slow": int(env.get("AO_SLOW", "34")),
        "ac_period": int(env.get("AC_PERIOD", "5")),
        "rsi_period": int(env.get("RSI_PERIOD", "14")),
        "fractal_left": max(2, int(env.get("FRACTAL_LEFT", "2"))),
        "fractal_right": max(2, int(env.get("FRACTAL_RIGHT", "2"))),
        "super_ao_bars": 3,
        "min_wise_men_confirmations": 1,
        "allow_countertrend_wise_man": False,
        "min_alligator_spread_pct": float(env.get("MIN_ALLIGATOR_SPREAD_PCT", "0.001")),
        "wm1_outside_atr_mult": float(env.get("WM1_OUTSIDE_ATR_MULT", "0.10")),
        "wm1_angulation_window": max(3, int(env.get("WM1_ANGULATION_WINDOW", "5"))),
    }
