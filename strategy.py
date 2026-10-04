import os
import numpy as np
import pandas as pd


def smma(series, period):
    out = pd.Series(index=series.index, dtype=float)

    if len(series) < period:
        return out

    out.iloc[period - 1] = series.iloc[:period].mean()

    for i in range(period, len(series)):
        out.iloc[i] = (
            (out.iloc[i - 1] * (period - 1) + series.iloc[i]) / period
        )

    return out


def calculate_indicators(df, cfg):
    x = df.copy()

    median = (x["high"] + x["low"]) / 2

    # Williams Alligator
    x["jaw"] = smma(median, cfg["jaw"])
    x["teeth"] = smma(median, cfg["teeth"])
    x["lips"] = smma(median, cfg["lips"])

    x["jaw_shifted"] = x["jaw"].shift(cfg["jaw_shift"])
    x["teeth_shifted"] = x["teeth"].shift(cfg["teeth_shift"])
    x["lips_shifted"] = x["lips"].shift(cfg["lips_shift"])

    # Awesome Oscillator
    x["ao"] = (
        median.rolling(cfg["ao_fast"]).mean()
        - median.rolling(cfg["ao_slow"]).mean()
    )

    # Accelerator Oscillator
    x["ac"] = x["ao"] - x["ao"].rolling(cfg["ac_period"]).mean()

    # Fractals
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
            x.iloc[
                i,
                x.columns.get_loc("confirmed_up_level"),
            ] = x["high"].iloc[fi]

        if bool(x["fractal_down"].iloc[fi]):
            x.iloc[
                i,
                x.columns.get_loc("confirmed_down_level"),
            ] = x["low"].iloc[fi]

    x["last_up_level"] = x["confirmed_up_level"].ffill()
    x["last_down_level"] = x["confirmed_down_level"].ffill()

    # Alligator direction
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

    # Alligator spread / awake state
    spread = (
        x[
            ["jaw_shifted", "teeth_shifted", "lips_shifted"]
        ].max(axis=1)
        - x[
            ["jaw_shifted", "teeth_shifted", "lips_shifted"]
        ].min(axis=1)
    ) / x["close"].replace(0, np.nan)

    x["alligator_spread_pct"] = spread
    x["alligator_awake"] = (
        spread >= cfg["min_alligator_spread_pct"]
    )

    # Individual long conditions.
    # These are intentionally separate so the scanner can understand
    # why a setup is close to a valid signal.
    x["long_bullish"] = x["bullish_alligator"]
    x["long_awake"] = x["alligator_awake"]
    x["long_ao_positive"] = x["ao"] > 0
    x["long_ac_positive"] = x["ac"] > 0
    x["long_fractal_ready"] = x["last_up_level"].notna()
    x["long_above_fractal"] = (
        x["last_up_level"].notna()
        & (x["close"] > x["last_up_level"])
    )
    x["long_previous_below_fractal"] = (
        x["last_up_level"].notna()
        & x["last_up_level"].shift(1).notna()
        & (
            x["close"].shift(1)
            <= x["last_up_level"].shift(1)
        )
    )

    # Strict entry signal.
    #
    # IMPORTANT:
    # This remains the actual BUY signal.
    # We are NOT weakening it.
    x["long_signal"] = (
        x["long_bullish"]
        & x["long_awake"]
        & x["long_ao_positive"]
        & x["long_ac_positive"]
        & x["long_fractal_ready"]
        & x["long_above_fractal"]
        & x["long_previous_below_fractal"]
    )

    # Short-side diagnostics.
    x["short_bearish"] = x["bearish_alligator"]
    x["short_awake"] = x["alligator_awake"]
    x["short_ao_negative"] = x["ao"] < 0
    x["short_ac_negative"] = x["ac"] < 0
    x["short_fractal_ready"] = x["last_down_level"].notna()
    x["short_below_fractal"] = (
        x["last_down_level"].notna()
        & (x["close"] < x["last_down_level"])
    )
    x["short_previous_above_fractal"] = (
        x["last_down_level"].notna()
        & x["last_down_level"].shift(1).notna()
        & (
            x["close"].shift(1)
            >= x["last_down_level"].shift(1)
        )
    )

    x["short_signal"] = (
        x["short_bearish"]
        & x["short_awake"]
        & x["short_ao_negative"]
        & x["short_ac_negative"]
        & x["short_fractal_ready"]
        & x["short_below_fractal"]
        & x["short_previous_above_fractal"]
    )

    # Long setup strength.
    #
    # This is NOT an entry signal.
    # It is a normalized measure used only for ranking markets.
    long_components = [
        "long_bullish",
        "long_awake",
        "long_ao_positive",
        "long_ac_positive",
        "long_fractal_ready",
        "long_above_fractal",
        "long_previous_below_fractal",
    ]

    x["long_setup_score"] = (
        x[long_components]
        .astype(int)
        .sum(axis=1)
        / len(long_components)
        * 100.0
    )

    # Distance to the confirmed breakout level.
    x["long_breakout_distance_pct"] = np.where(
        x["last_up_level"].notna()
        & (x["last_up_level"] > 0),
        (x["close"] / x["last_up_level"] - 1.0) * 100.0,
        np.nan,
    )

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
        "min_alligator_spread_pct": float(
            env.get("MIN_ALLIGATOR_SPREAD_PCT", "0.001")
        ),
    }
