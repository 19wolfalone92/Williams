import os
import numpy as np
import pandas as pd


def smma(series, period):
    out = pd.Series(index=series.index, dtype=float)
    period = int(period)
    if period <= 0:
        raise ValueError("SMMA period must be positive")
    if len(series) < period:
        return out
    out.iloc[period - 1] = series.iloc[:period].mean()
    for i in range(period, len(series)):
        out.iloc[i] = (
            (out.iloc[i - 1] * (period - 1) + series.iloc[i]) / period
        )
    return out


def _streak(mask):
    """Return the current consecutive True-run length for every row."""
    values = mask.fillna(False).astype(bool).to_numpy()
    out = np.zeros(len(values), dtype=int)
    n = 0
    for i, value in enumerate(values):
        n = n + 1 if value else 0
        out[i] = n
    return pd.Series(out, index=mask.index, dtype=int)


def _profitunity_window(volume_up, mfi_up, valid=None):
    """Classify relative MFI/volume states without inventing a warm-up state.

    UNKNOWN means at least one current/previous input is unavailable. STABLE
    is reserved for valid equal-direction comparisons, not NaN comparisons.
    """
    if valid is None:
        valid = volume_up.notna() & mfi_up.notna()
    else:
        valid = valid.astype(bool)
    volume_up = volume_up.fillna(False).astype(bool)
    mfi_up = mfi_up.fillna(False).astype(bool)
    result = pd.Series("UNKNOWN", index=volume_up.index, dtype=object)
    result.loc[valid] = "STABLE"
    result.loc[valid & volume_up & mfi_up] = "GREEN"
    result.loc[valid & ~volume_up & ~mfi_up] = "FADING"
    result.loc[valid & ~volume_up & mfi_up] = "FAKE"
    result.loc[valid & volume_up & ~mfi_up] = "SQUAT"
    return result


CANONICAL_WILLIAMS_INDICATOR_PARAMS = {
    "jaw": 13,
    "teeth": 8,
    "lips": 5,
    "jaw_shift": 8,
    "teeth_shift": 5,
    "lips_shift": 3,
    "ao_fast": 5,
    "ao_slow": 34,
    "ac_period": 5,
    "fractal_left": 2,
    "fractal_right": 2,
    "super_ao_bars": 3,
}


def canonical_indicator_config_from_env(env=os.environ):
    """Return the canonical production indicator config or fail closed.

    Research callers may still use config_from_env() to explore variants.
    The Futures execution path must not silently trade a changed indicator
    period/shift while still labelling the profile as Williams TC2/NTD.
    """
    cfg = config_from_env(env)
    mismatches = {
        key: (cfg[key], expected)
        for key, expected in CANONICAL_WILLIAMS_INDICATOR_PARAMS.items()
        if cfg[key] != expected
    }
    if mismatches:
        detail = ", ".join(
            f"{key}={actual!r} (required {expected!r})"
            for key, (actual, expected) in sorted(mismatches.items())
        )
        raise ValueError(
            "Non-canonical Williams indicator parameters are blocked in the "
            f"Futures execution path: {detail}"
        )
    return cfg


def calculate_indicators(df, cfg):
    x = df.copy()
    if x.empty:
        return x

    # Binance kline volume/trade-count fields are not automatically identical
    # to the historical tick-volume input in Williams' original MFI. The
    # market-facilitation field below is therefore explicitly a proxy and is
    # never an execution requirement by itself.
    median = (x["high"] + x["low"]) / 2

    # Williams Alligator: 13/8, 8/5, 5/3 smoothed displaced lines.
    x["jaw"] = smma(median, cfg["jaw"])
    x["teeth"] = smma(median, cfg["teeth"])
    x["lips"] = smma(median, cfg["lips"])
    x["jaw_shifted"] = x["jaw"].shift(cfg["jaw_shift"])
    x["teeth_shifted"] = x["teeth"].shift(cfg["teeth_shift"])
    x["lips_shifted"] = x["lips"].shift(cfg["lips_shift"])
    # Keep both semantic domains explicit: *_shifted is a legacy/display
    # alignment column; executable decisions use the current-time SMMA values.
    # The Alligator's forward chart displacement is not a licence to compare
    # today's price against an eight/five/three-bar-old line.
    x["jaw_current"] = x["jaw"]
    x["teeth_current"] = x["teeth"]
    x["lips_current"] = x["lips"]

    # Gator Oscillator: display-only convergence/divergence of displaced
    # Alligator lines. Its bars are diagnostics, never an extra entry gate.
    x["gator_upper"] = (x["jaw_shifted"] - x["teeth_shifted"]).abs()
    x["gator_lower"] = -(x["teeth_shifted"] - x["lips_shifted"]).abs()
    x["gator_upper_expanding"] = (
        x["gator_upper"] > x["gator_upper"].shift(1)
    ).fillna(False).astype(bool)
    x["gator_lower_expanding"] = (
        x["gator_lower"].abs() > x["gator_lower"].shift(1).abs()
    ).fillna(False).astype(bool)
    x["gator_state"] = "UNKNOWN"
    gator_state = "UNKNOWN"
    for i in range(len(x)):
        upper = x["gator_upper"].iloc[i]
        lower = x["gator_lower"].iloc[i]
        if i == 0 or not (
            np.isfinite(upper) and np.isfinite(lower)
            and np.isfinite(x["gator_upper"].iloc[i - 1])
            and np.isfinite(x["gator_lower"].iloc[i - 1])
        ):
            gator_state = "UNKNOWN"
        else:
            upper_expanding = bool(x["gator_upper_expanding"].iloc[i])
            lower_expanding = bool(x["gator_lower_expanding"].iloc[i])
            if not upper_expanding and not lower_expanding:
                gator_state = "SLEEPING"
            elif upper_expanding and lower_expanding:
                gator_state = "EATING"
            elif gator_state in {"EATING", "SATIATED"}:
                gator_state = "SATIATED"
            else:
                gator_state = "AWAKENING"
        x.iloc[i, x.columns.get_loc("gator_state")] = gator_state

    # NTD names the current-timeframe blue Jaw line the Balance Line. Teeth
    # remains the red Balance Line one degree lower when used as a fractal gate.
    x["balance_line"] = x["jaw_current"]
    x["balance_line_distance_pct"] = (
        (x["close"] - x["balance_line"]) / x["close"].replace(0, np.nan)
    )
    x["balance_line_side"] = "UNKNOWN"
    x.loc[x["close"] > x["balance_line"], "balance_line_side"] = "ABOVE"
    x.loc[x["close"] < x["balance_line"], "balance_line_side"] = "BELOW"
    x.loc[
        x["close"].notna() & x["balance_line"].notna()
        & (x["close"] == x["balance_line"]),
        "balance_line_side",
    ] = "ON"

    # Awesome Oscillator: SMA(5, median) - SMA(34, median).
    x["ao"] = median.rolling(cfg["ao_fast"]).mean() - median.rolling(cfg["ao_slow"]).mean()
    x["ao_green"] = x["ao"] > x["ao"].shift(1)
    x["ao_red"] = x["ao"] < x["ao"].shift(1)
    x["ao_green_streak"] = _streak(x["ao_green"])
    x["ao_red_streak"] = _streak(x["ao_red"])
    x["super_ao_long"] = x["ao_green_streak"] >= int(cfg["super_ao_bars"])
    x["super_ao_short"] = x["ao_red_streak"] >= int(cfg["super_ao_bars"])
    x["ao_momentum_rising"] = x["ao_green"]
    x["ao_momentum_falling"] = x["ao_red"]

    # Profitunity MACD 5/34/5 is the same 5/34 momentum line plus its
    # five-period signal average. Expose the pieces explicitly so the old
    # Course model can be audited without conflating AO, signal line and AC.
    x["profitunity_macd_line"] = x["ao"]
    x["profitunity_macd_signal"] = x["ao"].rolling(cfg["ac_period"]).mean()
    x["profitunity_macd_histogram"] = (
        x["profitunity_macd_line"] - x["profitunity_macd_signal"]
    )

    # Accelerator/Decelerator is the MACD histogram: AO minus its 5-period SMA.
    x["ac"] = x["profitunity_macd_histogram"]
    x["ac_green"] = x["ac"] > x["ac"].shift(1)
    x["ac_red"] = x["ac"] < x["ac"].shift(1)

    # New Trading Dimensions zones: AO and AC must accelerate in the same
    # direction. Zone is context/position-management evidence, not a standalone
    # entry trigger.
    x["williams_zone"] = "GRAY"
    x.loc[x["ao_green"] & x["ac_green"], "williams_zone"] = "GREEN"
    x.loc[x["ao_red"] & x["ac_red"], "williams_zone"] = "RED"

    # Williams fractals: the centre may be a plateau of adjacent equal
    # extremes. Two bars on each side of the whole plateau must be strictly
    # lower (up fractal) / higher (down fractal). The anchor is the LAST bar
    # of the equal-extreme group; confirmation is the close of the second
    # right-hand bar. Centre markers are retrospective diagnostics only;
    # consumers must use the explicit confirmation columns for decisions.
    left = max(2, int(cfg["fractal_left"]))
    right = max(2, int(cfg["fractal_right"]))
    highs = pd.to_numeric(x["high"], errors="coerce").to_numpy(dtype=float)
    lows = pd.to_numeric(x["low"], errors="coerce").to_numpy(dtype=float)
    n = len(x)

    x["fractal_up"] = False
    x["fractal_down"] = False
    x["fractal_up_group_start_index"] = np.nan
    x["fractal_down_group_start_index"] = np.nan
    x["fractal_up_confirmation_index"] = np.nan
    x["fractal_down_confirmation_index"] = np.nan
    x["fractal_up_confirmed"] = False
    x["fractal_down_confirmed"] = False
    x["confirmed_up_level"] = np.nan
    x["confirmed_down_level"] = np.nan
    x["confirmed_up_center_index"] = np.nan
    x["confirmed_down_center_index"] = np.nan
    x["confirmed_up_group_start_index"] = np.nan
    x["confirmed_down_group_start_index"] = np.nan
    x["confirmed_up_confirmation_index"] = np.nan
    x["confirmed_down_confirmation_index"] = np.nan

    for anchor in range(left, n):
        # Only the last bar of an adjacent equal-extreme group can anchor it.
        high_value = highs[anchor]
        low_value = lows[anchor]
        if not (np.isfinite(high_value) and np.isfinite(low_value)):
            continue

        high_start = anchor
        while high_start > 0 and highs[high_start - 1] == high_value:
            high_start -= 1
        low_start = anchor
        while low_start > 0 and lows[low_start - 1] == low_value:
            low_start -= 1

        # If the equal-extreme group continues to the next bar, defer it until
        # the group's last bar; this avoids duplicate pivots for one plateau.
        high_is_group_end = anchor + 1 >= n or highs[anchor + 1] != high_value
        low_is_group_end = anchor + 1 >= n or lows[anchor + 1] != low_value
        confirmation = anchor + right
        if confirmation >= n:
            continue

        if (
            high_is_group_end
            and high_start >= left
            and np.isfinite(highs[high_start-left:high_start]).all()
            and np.isfinite(highs[anchor+1:confirmation+1]).all()
            and (highs[high_start-left:high_start] < high_value).all()
            and (highs[anchor+1:confirmation+1] < high_value).all()
        ):
            x.iloc[anchor, x.columns.get_loc("fractal_up")] = True
            x.iloc[anchor, x.columns.get_loc("fractal_up_group_start_index")] = high_start
            x.iloc[anchor, x.columns.get_loc("fractal_up_confirmation_index")] = confirmation
            x.iloc[confirmation, x.columns.get_loc("fractal_up_confirmed")] = True
            x.iloc[confirmation, x.columns.get_loc("confirmed_up_level")] = high_value
            x.iloc[confirmation, x.columns.get_loc("confirmed_up_center_index")] = anchor
            x.iloc[confirmation, x.columns.get_loc("confirmed_up_group_start_index")] = high_start
            x.iloc[confirmation, x.columns.get_loc("confirmed_up_confirmation_index")] = confirmation

        if (
            low_is_group_end
            and low_start >= left
            and np.isfinite(lows[low_start-left:low_start]).all()
            and np.isfinite(lows[anchor+1:confirmation+1]).all()
            and (lows[low_start-left:low_start] > low_value).all()
            and (lows[anchor+1:confirmation+1] > low_value).all()
        ):
            x.iloc[anchor, x.columns.get_loc("fractal_down")] = True
            x.iloc[anchor, x.columns.get_loc("fractal_down_group_start_index")] = low_start
            x.iloc[anchor, x.columns.get_loc("fractal_down_confirmation_index")] = confirmation
            x.iloc[confirmation, x.columns.get_loc("fractal_down_confirmed")] = True
            x.iloc[confirmation, x.columns.get_loc("confirmed_down_level")] = low_value
            x.iloc[confirmation, x.columns.get_loc("confirmed_down_center_index")] = anchor
            x.iloc[confirmation, x.columns.get_loc("confirmed_down_group_start_index")] = low_start
            x.iloc[confirmation, x.columns.get_loc("confirmed_down_confirmation_index")] = confirmation

    x["last_up_level"] = x["confirmed_up_level"].ffill()
    x["last_down_level"] = x["confirmed_down_level"].ffill()

    # Alligator direction / awake state.
    mouth_values = x[["jaw_current", "teeth_current", "lips_current"]]
    x["bullish_alligator"] = (
        (x["lips_current"] > x["teeth_current"])
        & (x["teeth_current"] > x["jaw_current"])
        & (x["close"] > x["lips_current"])
    )
    x["bearish_alligator"] = (
        (x["lips_current"] < x["teeth_current"])
        & (x["teeth_current"] < x["jaw_current"])
        & (x["close"] < x["lips_current"])
    )
    spread = (
        mouth_values.max(axis=1) - mouth_values.min(axis=1)
    ) / x["close"].replace(0, np.nan)
    x["alligator_spread_pct"] = spread
    x["alligator_awake"] = spread >= cfg["min_alligator_spread_pct"]

    # Williams' fractal/Balance-Line gate: a buy fractal is only actionable
    # when its peak is above the red Balance Line (Teeth); sells are mirrored.
    x["long_fractal_outside"] = (
        x["last_up_level"].notna()
        & x["teeth_current"].notna()
        & (x["last_up_level"] > x["teeth_current"])
    )
    x["short_fractal_outside"] = (
        x["last_down_level"].notna()
        & x["teeth_current"].notna()
        & (x["last_down_level"] < x["teeth_current"])
    )

    # Fractal breakout triggers.
    x["long_above_fractal"] = x["long_fractal_outside"] & (x["close"] > x["last_up_level"])
    x["long_previous_below_fractal"] = (
        x["long_fractal_outside"]
        & x["last_up_level"].shift(1).notna()
        & (x["close"].shift(1) <= x["last_up_level"].shift(1))
    )
    x["short_below_fractal"] = x["short_fractal_outside"] & (x["close"] < x["last_down_level"])
    x["short_previous_above_fractal"] = (
        x["short_fractal_outside"]
        & x["last_down_level"].shift(1).notna()
        & (x["close"].shift(1) >= x["last_down_level"].shift(1))
    )
    x["long_fractal_signal"] = x["long_above_fractal"] & x["long_previous_below_fractal"]
    x["short_fractal_signal"] = x["short_below_fractal"] & x["short_previous_above_fractal"]

    # First Wise Man: divergent/reversal bar. The source rule requires a new
    # extreme and a close in the signal half of the bar. The extra mouth gate
    # is the conservative spot-market overlay used by this bot.
    bar_range = (x["high"] - x["low"]).replace(0, np.nan)
    close_location = (x["close"] - x["low"]) / bar_range
    prior_low = x["low"].shift(1).rolling(2).min()
    prior_high = x["high"].shift(1).rolling(2).max()
    x["bullish_reversal_bar"] = (
        (x["low"] < prior_low)
        & (close_location >= 0.50)
        & x["jaw_current"].notna()
        & (x["low"] < x[["jaw_current", "teeth_current", "lips_current"]].min(axis=1))
    )
    x["bearish_reversal_bar"] = (
        (x["high"] > prior_high)
        & (close_location <= 0.50)
        & x["jaw_current"].notna()
        & (x["high"] > x[["jaw_current", "teeth_current", "lips_current"]].max(axis=1))
    )
    x["long_reversal_signal"] = x["bullish_reversal_bar"]
    x["short_reversal_signal"] = x["bearish_reversal_bar"]

    # The book places a buy/sell stop beyond the reversal bar. Therefore the
    # reversal bar itself is context; the actionable trigger is a later break
    # of that signal bar's extreme.
    x["last_bullish_reversal_high"] = (
        x["high"].where(x["bullish_reversal_bar"]).ffill().shift(1)
    )
    x["last_bearish_reversal_low"] = (
        x["low"].where(x["bearish_reversal_bar"]).ffill().shift(1)
    )

    # Second Wise Man: Super AO. The third same-colour AO bar is the signal.
    # A fractal prerequisite is deliberately absent: Williams also describes
    # Super AO as a possible first presenting signal.
    x["long_super_ao_signal"] = x["super_ao_long"]
    x["short_super_ao_signal"] = x["super_ao_short"]

    # Legacy diagnostic overlay. Exact armability and trigger semantics are
    # owned by williams_signals.py; these fields remain only for compatibility
    # with indicator/status consumers and never authorize an order.
    countertrend = bool(cfg["allow_countertrend_wise_man"])
    x["long_wise_reversal_entry"] = (
        x["last_bullish_reversal_high"].notna()
        & (x["close"] > x["last_bullish_reversal_high"])
        & (x["bullish_alligator"] | countertrend)
    )
    x["short_wise_reversal_entry"] = (
        x["last_bearish_reversal_low"].notna()
        & (x["close"] < x["last_bearish_reversal_low"])
        & (x["bearish_alligator"] | countertrend)
    )

    # Canonical Wise-Men count. This is confirmation/ranking information in our
    # one-position system; no pyramiding order is ever emitted by this layer.
    x["long_wise_man_count"] = (
        x[["long_wise_reversal_entry", "long_super_ao_signal", "long_fractal_signal"]]
        .astype(int).sum(axis=1)
    )
    x["short_wise_man_count"] = (
        x[["short_wise_reversal_entry", "short_super_ao_signal", "short_fractal_signal"]]
        .astype(int).sum(axis=1)
    )

    # Legacy diagnostics retained for compatibility.
    x["long_bullish"] = x["bullish_alligator"]
    x["long_awake"] = x["alligator_awake"]
    x["long_ao_positive"] = x["ao"] > 0
    x["long_ac_positive"] = x["ac"] > 0
    x["long_fractal_ready"] = x["last_up_level"].notna()
    x["short_bearish"] = x["bearish_alligator"]
    x["short_awake"] = x["alligator_awake"]
    x["short_ao_negative"] = x["ao"] < 0
    x["short_ac_negative"] = x["ac"] < 0
    x["short_fractal_ready"] = x["last_down_level"].notna()

    # Indicator-level campaign candidates. This is deliberately not the
    # authoritative execution decision: williams_signals.py applies trigger
    # prices, angulation, Teeth validity and signal chronology.
    x["long_signal"] = (
        x["long_reversal_signal"]
        | x["long_super_ao_signal"]
        | x["long_fractal_signal"]
    )
    x["short_signal"] = (
        x["short_reversal_signal"]
        | x["short_super_ao_signal"]
        | x["short_fractal_signal"]
    )

    def _family(row, side):
        cols = [
            f"{side}_wise_reversal_entry",
            f"{side}_super_ao_signal",
            f"{side}_fractal_signal",
        ]
        names = ["REVERSAL", "SUPER_AO", "FRACTAL"]
        hit = [names[i] for i, c in enumerate(cols) if bool(row.get(c, False))]
        return "+".join(hit) if hit else "NONE"

    x["long_signal_family"] = x.apply(lambda r: _family(r, "long"), axis=1)
    x["short_signal_family"] = x.apply(lambda r: _family(r, "short"), axis=1)

    # Legacy setup score stays available, but it now includes canonical
    # Williams trigger readiness rather than pretending AO>0/AC>0 equals all
    # three Wise Men.
    long_components = [
        "long_bullish",
        "long_awake",
        "long_fractal_outside",
        "long_super_ao_signal",
        "long_fractal_signal",
        "long_reversal_signal",
    ]
    x["long_setup_score"] = (
        x[long_components].astype(int).sum(axis=1) / len(long_components) * 100.0
    )
    x["long_wise_man_score"] = x["long_wise_man_count"] / 3.0 * 100.0

    # Distance to the confirmed breakout level.
    x["long_breakout_distance_pct"] = np.where(
        x["last_up_level"].notna() & (x["last_up_level"] > 0),
        (x["close"] / x["last_up_level"] - 1.0) * 100.0,
        np.nan,
    )

    # Williams Market Facilitation Index proxy and four Profitunity windows.
    # Original MFI uses tick volume. Binance candles expose trade count, not
    # true tick count, so use trade count when present and explicitly label the
    # result as a proxy; otherwise fall back to base-asset volume.
    volume = pd.to_numeric(x.get("volume", pd.Series(np.nan, index=x.index)), errors="coerce")
    trades = pd.to_numeric(x.get("trades", pd.Series(np.nan, index=x.index)), errors="coerce")
    x["volume"] = volume
    if trades.notna().all() and (trades > 0).any():
        mfi_volume = trades
        x["mfi_proxy_source"] = "BINANCE_TRADE_COUNT_PROXY"
    else:
        mfi_volume = volume
        x["mfi_proxy_source"] = "BASE_VOLUME_PROXY"
    x["mfi_volume_proxy"] = mfi_volume
    x["mfi_proxy"] = (x["high"] - x["low"]) / mfi_volume.replace(0, np.nan)
    x["volume_up"] = (mfi_volume > mfi_volume.shift(1)).fillna(False).astype(bool)
    x["volume_down"] = (mfi_volume < mfi_volume.shift(1)).fillna(False).astype(bool)
    x["mfi_up"] = (x["mfi_proxy"] > x["mfi_proxy"].shift(1)).fillna(False).astype(bool)
    x["mfi_down"] = (x["mfi_proxy"] < x["mfi_proxy"].shift(1)).fillna(False).astype(bool)
    mfi_comparison_valid = (
        mfi_volume.notna() & mfi_volume.shift(1).notna()
        & x["mfi_proxy"].notna() & x["mfi_proxy"].shift(1).notna()
    )
    x["mfi_proxy_valid"] = mfi_comparison_valid.astype(bool)
    x["profitunity_window"] = _profitunity_window(
        x["volume_up"], x["mfi_up"], valid=mfi_comparison_valid
    )
    x["squatting_bar"] = (x["profitunity_window"] == "SQUAT").astype(bool)

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
        "fractal_left": int(env.get("FRACTAL_LEFT", "2")),
        "fractal_right": int(env.get("FRACTAL_RIGHT", "2")),
        "super_ao_bars": int(env.get("SUPER_AO_BARS", "3")),
        "min_wise_men_confirmations": int(env.get("MIN_WISE_MEN_CONFIRMATIONS", "2")),
        "allow_countertrend_wise_man": env.get("ALLOW_COUNTERTREND_WISE_MAN", "false").lower() == "true",
        "min_alligator_spread_pct": float(env.get("MIN_ALLIGATOR_SPREAD_PCT", "0.001")),
    }
