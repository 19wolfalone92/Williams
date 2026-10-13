import os
import numpy as np
import pandas as pd


def smma(series, period):
    """Wilder-style recursive SMMA with an explicit SMA seed.

    Missing/non-finite seed values block the entire series. Once a gap occurs
    after the seed, the recursive line remains unavailable; silently reseeding
    later would create cross-runtime disagreement about the Alligator state.
    """
    out = pd.Series(np.nan, index=series.index, dtype=float)
    period = int(period)
    if period <= 0:
        raise ValueError("SMMA period must be positive")
    if len(series) < period:
        return out
    values = pd.to_numeric(series, errors="coerce").to_numpy(dtype=float)
    seed = values[:period]
    if not np.isfinite(seed).all():
        return out
    out.iloc[period - 1] = float(seed.mean())
    for i in range(period, len(series)):
        previous = float(out.iloc[i - 1])
        current = float(values[i])
        if not np.isfinite(previous) or not np.isfinite(current):
            out.iloc[i] = np.nan
        else:
            out.iloc[i] = (previous * (period - 1) + current) / period
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

    # Normalize raw candle numerics before indicator math. Invalid high/low
    # bars must not become artificial pivots or zero-valued median prices.
    for column in ("high", "low", "close"):
        if column not in x.columns:
            raise ValueError(f"required OHLC column missing: {column}")
        x[column] = pd.to_numeric(x[column], errors="coerce")
    high_values = x["high"].to_numpy(dtype=float)
    low_values = x["low"].to_numpy(dtype=float)
    valid_range = (
        np.isfinite(high_values)
        & np.isfinite(low_values)
        & (high_values > 0.0)
        & (low_values > 0.0)
        & (high_values >= low_values)
    )
    x.loc[~valid_range, ["high", "low"]] = np.nan
    close_values = x["close"].to_numpy(dtype=float)
    x.loc[~np.isfinite(close_values) | (close_values <= 0.0), "close"] = np.nan

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
    # The Wise-Man event is the third bar in the same-colour run, not
    # every subsequent bar while momentum keeps rising/falling.
    x["super_ao_long"] = x["ao_green_streak"].eq(int(cfg["super_ao_bars"]))
    x["super_ao_short"] = x["ao_red_streak"].eq(int(cfg["super_ao_bars"]))
    x["ao_momentum_rising"] = x["ao_green"]
    x["ao_momentum_falling"] = x["ao_red"]

    # Accelerator/Decelerator: AO minus its 5-period SMA.
    x["ac"] = x["ao"] - x["ao"].rolling(cfg["ac_period"]).mean()

    # Fractals use a centre plus two strictly qualifying bars on each side.
    # Equal right-side extremes do not count as lower/higher bars; they extend
    # confirmation. A strict new extreme cancels the candidate. The centre flag
    # is structural metadata only: actionability begins at the recorded
    # confirmation row. Consumers must never backdate confirmation to center+2.
    left = int(cfg["fractal_left"])
    right = int(cfg["fractal_right"])
    if left < 1 or right < 1:
        raise ValueError("Fractal left/right confirmation windows must be positive")
    x["fractal_right_bars"] = right
    x["fractal_up"] = False
    x["fractal_down"] = False
    x["confirmed_up_level"] = np.nan
    x["confirmed_down_level"] = np.nan
    x["confirmed_up_center_index"] = -1
    x["confirmed_down_center_index"] = -1

    highs = pd.to_numeric(x["high"], errors="coerce").to_numpy(dtype=float)
    lows = pd.to_numeric(x["low"], errors="coerce").to_numpy(dtype=float)
    up_flag_col = x.columns.get_loc("fractal_up")
    down_flag_col = x.columns.get_loc("fractal_down")
    up_level_col = x.columns.get_loc("confirmed_up_level")
    down_level_col = x.columns.get_loc("confirmed_down_level")
    up_center_col = x.columns.get_loc("confirmed_up_center_index")
    down_center_col = x.columns.get_loc("confirmed_down_center_index")

    for center_i in range(left, len(x)):
        center_high = highs[center_i]
        prior_highs = highs[center_i-left:center_i]
        if (
            np.isfinite(center_high)
            and np.isfinite(prior_highs).all()
            and center_high > prior_highs.max()
        ):
            lower_count = 0
            for confirmation_i in range(center_i + 1, len(x)):
                right_high = highs[confirmation_i]
                if not np.isfinite(right_high) or right_high > center_high:
                    break
                if right_high < center_high:
                    lower_count += 1
                if lower_count == right:
                    # Event time is the confirmation row, never the
                    # historical center row; the latter would leak future data.
                    x.iloc[confirmation_i, up_flag_col] = True
                    x.iloc[confirmation_i, up_level_col] = center_high
                    x.iloc[confirmation_i, up_center_col] = center_i
                    break

        center_low = lows[center_i]
        prior_lows = lows[center_i-left:center_i]
        if (
            np.isfinite(center_low)
            and np.isfinite(prior_lows).all()
            and center_low < prior_lows.min()
        ):
            higher_count = 0
            for confirmation_i in range(center_i + 1, len(x)):
                right_low = lows[confirmation_i]
                if not np.isfinite(right_low) or right_low < center_low:
                    break
                if right_low > center_low:
                    higher_count += 1
                if higher_count == right:
                    x.iloc[confirmation_i, down_flag_col] = True
                    x.iloc[confirmation_i, down_level_col] = center_low
                    x.iloc[confirmation_i, down_center_col] = center_i
                    break

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

    # Second Wise Man: the third same-colour AO histogram bar identifies
    # WM2 independently. The conditional price trigger and wider context are
    # handled by the campaign-entry path; a prior WM3 fractal is not a WM2 gate.
    x["long_super_ao_signal"] = x["super_ao_long"].fillna(False)
    x["short_super_ao_signal"] = x["super_ao_short"].fillna(False)

    # Conservative execution overlay. The counter-trend Wise-Man signals from
    # the book are retained as diagnostics, but disabled for the long-only
    # autonomous entry path unless explicitly enabled.
    countertrend = bool(cfg["allow_countertrend_wise_man"])
    # A reversal bar can be the first Wise Man; requiring the Alligator
    # already to be aligned suppresses the very reversal it is meant to detect.
    # These booleans are candidate diagnostics only, not permission for a
    # MARKET order; live entries must use a causal conditional SignalSpec.
    x["long_wise_reversal_entry"] = (
        x["last_bullish_reversal_high"].notna()
        & (x["close"] > x["last_bullish_reversal_high"])
    )
    x["short_wise_reversal_entry"] = (
        x["last_bearish_reversal_low"].notna()
        & (x["close"] < x["last_bearish_reversal_low"])
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

    # One independently qualified Wise-Man may be the first event in a
    # flat campaign. Do not impose a synthetic "two Wise Men plus WM3" AND gate.
    # The column is a strategy-candidate diagnostic. Live order creation is
    # disabled in the legacy Trader path and remains the campaign engine's
    # responsibility because the book's triggers are conditional price levels.
    x["long_signal"] = (
        x["long_wise_reversal_entry"]
        | x["long_super_ao_signal"]
        | x["long_fractal_signal"]
    )
    x["short_signal"] = (
        x["short_wise_reversal_entry"]
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


def canonical_williams_parameter_blockers(cfg):
    """Return exact-profile blockers for live TC2 execution.

    Research/backtest helpers may explore another configuration, but a live
    canonical engine must not silently call changed indicator constants
    "Williams". Unresolved profile constants are blocked.
    """
    expected = {
        "jaw": 13, "teeth": 8, "lips": 5,
        "jaw_shift": 8, "teeth_shift": 5, "lips_shift": 3,
        "ao_fast": 5, "ao_slow": 34, "ac_period": 5,
        "fractal_left": 2, "fractal_right": 2, "super_ao_bars": 3,
    }
    blockers = []
    for key, value in expected.items():
        try:
            actual = int(cfg[key])
        except (KeyError, TypeError, ValueError, OverflowError):
            blockers.append(f"invalid {key}: expected {value}")
            continue
        if actual != value:
            blockers.append(f"noncanonical {key}={actual}; expected {value}")
    return blockers


def canonical_williams_environment_blockers(env=None):
    """Validate raw live environment values without crashing on malformed input."""
    source = os.environ if env is None else env
    names = {
        "ALLIGATOR_JAW": ("jaw", 13), "ALLIGATOR_TEETH": ("teeth", 8),
        "ALLIGATOR_LIPS": ("lips", 5), "JAW_SHIFT": ("jaw_shift", 8),
        "TEETH_SHIFT": ("teeth_shift", 5), "LIPS_SHIFT": ("lips_shift", 3),
        "AO_FAST": ("ao_fast", 5), "AO_SLOW": ("ao_slow", 34),
        "AC_PERIOD": ("ac_period", 5), "FRACTAL_LEFT": ("fractal_left", 2),
        "FRACTAL_RIGHT": ("fractal_right", 2), "SUPER_AO_BARS": ("super_ao_bars", 3),
    }
    blockers = []
    requested_timeframe = str(source.get("EXECUTION_TIMEFRAME", "1h")).strip().lower()
    if requested_timeframe != "1h":
        blockers.append(
            f"EXECUTION_TIMEFRAME={requested_timeframe or '<empty>'}; canonical Williams signals require H1 (1h)"
        )
    for name, (key, expected) in names.items():
        if name not in source or str(source.get(name, "")).strip() == "":
            continue
        try:
            actual = int(source[name])
        except (TypeError, ValueError, OverflowError):
            blockers.append(f"invalid {name}={source.get(name)!r}; expected {expected}")
            continue
        if actual != expected:
            blockers.append(f"noncanonical {name}={actual}; expected {expected}")
    return blockers


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
