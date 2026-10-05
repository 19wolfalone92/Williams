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


def _profitunity_window(volume_up, mfi_up):
    """Classify Williams' four volume/MFI states."""
    result = pd.Series("STABLE", index=volume_up.index, dtype=object)
    result.loc[volume_up & mfi_up] = "GREEN"
    result.loc[~volume_up & ~mfi_up] = "FADING"
    result.loc[~volume_up & mfi_up] = "FAKE"
    result.loc[volume_up & ~mfi_up] = "SQUAT"
    return result


def calculate_indicators(df, cfg):
    x = df.copy()
    if x.empty:
        return x

    # Binance Spot exposes traded volume rather than the historical
    # tick-count volume used in Williams' original MFI. Therefore the
    # market-facilitation field below is explicitly a proxy and is never an
    # execution requirement by itself.
    median = (x["high"] + x["low"]) / 2

    # Williams Alligator: 13/8, 8/5, 5/3 smoothed displaced lines.
    x["jaw"] = smma(median, cfg["jaw"])
    x["teeth"] = smma(median, cfg["teeth"])
    x["lips"] = smma(median, cfg["lips"])
    x["jaw_shifted"] = x["jaw"].shift(cfg["jaw_shift"])
    x["teeth_shifted"] = x["teeth"].shift(cfg["teeth_shift"])
    x["lips_shifted"] = x["lips"].shift(cfg["lips_shift"])

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

    # Accelerator/Decelerator: AO minus its 5-period SMA.
    x["ac"] = x["ao"] - x["ao"].rolling(cfg["ac_period"]).mean()

    # Fractals. The center must be strictly higher/lower than the two bars
    # on each side; equality therefore does not create a false fractal.
    left = cfg["fractal_left"]
    right = cfg["fractal_right"]
    x["fractal_up"] = False
    x["fractal_down"] = False
    for i in range(left, len(x) - right):
        if (
            x["high"].iloc[i] > x["high"].iloc[i-left:i].max()
            and x["high"].iloc[i] > x["high"].iloc[i+1:i+right+1].max()
        ):
            x.iloc[i, x.columns.get_loc("fractal_up")] = True
        if (
            x["low"].iloc[i] < x["low"].iloc[i-left:i].min()
            and x["low"].iloc[i] < x["low"].iloc[i+1:i+right+1].min()
        ):
            x.iloc[i, x.columns.get_loc("fractal_down")] = True

    x["confirmed_up_level"] = np.nan
    x["confirmed_down_level"] = np.nan
    for i in range(left + right, len(x)):
        fi = i - right
        if bool(x["fractal_up"].iloc[fi]):
            x.iloc[i, x.columns.get_loc("confirmed_up_level")] = x["high"].iloc[fi]
        if bool(x["fractal_down"].iloc[fi]):
            x.iloc[i, x.columns.get_loc("confirmed_down_level")] = x["low"].iloc[fi]

    x["last_up_level"] = x["confirmed_up_level"].ffill()
    x["last_down_level"] = x["confirmed_down_level"].ffill()

    # Alligator direction / awake state.
    mouth_values = x[["jaw_shifted", "teeth_shifted", "lips_shifted"]]
    x["bullish_alligator"] = (
        (x["lips_shifted"] > x["teeth_shifted"])
        & (x["teeth_shifted"] > x["jaw_shifted"])
        & (x["close"] > x["lips_shifted"])
    )
    x["bearish_alligator"] = (
        (x["lips_shifted"] < x["teeth_shifted"])
        & (x["teeth_shifted"] < x["jaw_shifted"])
        & (x["close"] < x["lips_shifted"])
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
        & x["teeth_shifted"].notna()
        & (x["last_up_level"] > x["teeth_shifted"])
    )
    x["short_fractal_outside"] = (
        x["last_down_level"].notna()
        & x["teeth_shifted"].notna()
        & (x["last_down_level"] < x["teeth_shifted"])
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
        & x["jaw_shifted"].notna()
        & (x["low"] < x[["jaw_shifted", "teeth_shifted", "lips_shifted"]].min(axis=1))
    )
    x["bearish_reversal_bar"] = (
        (x["high"] > prior_high)
        & (close_location <= 0.50)
        & x["jaw_shifted"].notna()
        & (x["high"] > x[["jaw_shifted", "teeth_shifted", "lips_shifted"]].max(axis=1))
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

    # Second Wise Man: Super AO. We use Williams' histogram color definition
    # (green = current AO above previous AO, red = below), not merely AO > 0.
    x["long_super_ao_signal"] = (
        x["super_ao_long"]
        & x["long_fractal_outside"].shift(1).eq(True)
    )
    x["short_super_ao_signal"] = (
        x["super_ao_short"]
        & x["short_fractal_outside"].shift(1).eq(True)
    )

    # Conservative execution overlay. The counter-trend Wise-Man signals from
    # the book are retained as diagnostics, but disabled for the long-only
    # autonomous entry path unless explicitly enabled.
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

    # Strict entry: conservative Williams gate + at least one valid Wise-Man
    # trigger. Fractal breakouts, Super AO continuation and reversal bars are
    # separate triggers; they are not incorrectly ANDed together.
    min_wise = int(cfg["min_wise_men_confirmations"])
    x["long_signal"] = (
        # Williams' first gate: no downstream Wise-Man signal is actionable
        # until a confirmed fractal has formed outside the Teeth/balance line.
        x["long_fractal_outside"]
        & x["long_bullish"]
        & x["long_awake"]
        & x["long_wise_man_count"].ge(min_wise)
    )
    x["short_signal"] = (
        x["short_bearish"]
        & x["short_awake"]
        & x["short_wise_man_count"].ge(min_wise)
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
    # On Binance Spot, this uses traded base volume rather than tick count, so
    # it is deliberately diagnostic only.
    volume = pd.to_numeric(x.get("volume", pd.Series(np.nan, index=x.index)), errors="coerce")
    x["volume"] = volume
    x["mfi_proxy"] = (x["high"] - x["low"]) / volume.replace(0, np.nan)
    x["volume_up"] = volume > volume.shift(1)
    x["volume_down"] = volume < volume.shift(1)
    x["mfi_up"] = x["mfi_proxy"] > x["mfi_proxy"].shift(1)
    x["mfi_down"] = x["mfi_proxy"] < x["mfi_proxy"].shift(1)
    x["profitunity_window"] = _profitunity_window(x["volume_up"], x["mfi_up"])
    x["squatting_bar"] = x["profitunity_window"] == "SQUAT"

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
