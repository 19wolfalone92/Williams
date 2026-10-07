"""Multi-timeframe Williams / Elliott-like structural engine.

Methodology boundary
--------------------
The uploaded *Trading Chaos 2* explicitly links fractals and Elliott waves,
recommends a Level-2 panoramic view of roughly 140+ bars, and states that the
price action outside the Alligator mouth corresponds to an impulsive wave of
some degree while movement around the balance structure is reactive.

The book does not reproduce the complete Elliott counting/trading method; it
points readers to the earlier *Trading Chaos* for that material. Therefore the
numeric W1-W5 labels below are intentionally a *structural estimate* built from
confirmed Williams fractals + Alligator/AO/AC + swing progression. They are not
presented as an exact author-certified Elliott count and they do not use hard
Fibonacci ratios.
"""

from __future__ import annotations

import os
from dataclasses import asdict, dataclass, field
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

import numpy as np
import pandas as pd

from strategy import calculate_indicators, config_from_env

WAVE_STATE_IMPULSE = "IMPULSE"
WAVE_STATE_CORRECTION = "CORRECTION"
WAVE_STATE_TRANSITION = "TRANSITION"
WAVE_STATE_UNKNOWN = "UNKNOWN"

DIRECTION_UP = "UP"
DIRECTION_DOWN = "DOWN"
DIRECTION_NEUTRAL = "NEUTRAL"

MIN_WAVE_BARS_DEFAULT = 140
LOOKBACK_DEFAULT = 220


@dataclass
class Pivot:
    kind: str  # UP / DOWN
    price: float
    center_index: int
    confirmed_index: int
    ao: float = 0.0
    ac: float = 0.0


@dataclass
class WaveSnapshot:
    interval: str
    wave_degree: str
    direction: str = DIRECTION_NEUTRAL
    phase: str = WAVE_STATE_UNKNOWN
    position: int = 0
    wave_label: str = "?"
    confidence: float = 0.0
    structural_confidence: float = 0.0
    impulse_score: float = 0.0
    exhaustion_risk: float = 0.0
    divergence: str = "NONE"
    target_zone: bool = False
    target_zone_low: float = 0.0
    target_zone_high: float = 0.0
    squatting_bar: bool = False
    momentum_fading: bool = False
    magic_bullets: Dict[str, bool] = field(default_factory=dict)
    alligator_state: str = "UNKNOWN"
    alligator_bullish: bool = False
    alligator_bearish: bool = False
    ao: float = 0.0
    ac: float = 0.0
    alligator_spread: float = 0.0
    price: float = 0.0
    last_pivot_kind: str = "NONE"
    last_pivot_price: float = 0.0
    confirmed_fractals: int = 0
    current_leg_pct: float = 0.0
    current_leg_atr: float = 0.0
    pivots: List[dict] = field(default_factory=list)
    structure: Dict[str, object] = field(default_factory=dict)
    primary_count: str = ""
    alternative_count: str = ""
    abc_phase: str = ""
    exhaustion_components: Dict[str, float] = field(default_factory=dict)
    invalidation_price: float = 0.0
    terminal_fractal: bool = False
    magic_bullets_count: int = 0
    scenario_primary: str = ""
    scenario_alternative: str = ""
    reason: str = ""
    data_bars: int = 0
    data_ok: bool = True

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class MultiTimeframeWaveReport:
    frames: Dict[str, WaveSnapshot]
    overall_direction: str = DIRECTION_NEUTRAL
    alignment_score: float = 0.0
    wave_score: float = 50.0
    exhaustion_risk: float = 0.0
    nested_w3: bool = False
    nested_w3_parent_w5: bool = False
    nested_w3_count: int = 0
    nested_w3_parent_positions: List[int] = field(default_factory=list)
    nested_countertrend_impulse: bool = False
    nested_countertrend_count: int = 0
    wave_path: str = ""
    htf_confirmed: bool = False
    setup_position: int = 0
    setup_phase: str = WAVE_STATE_UNKNOWN
    primary_count: str = ""
    alternative_count: str = ""
    reason: str = ""
    entry_interval: str = ""
    entry_position: int = 0
    entry_parent_interval: str = ""
    entry_parent_position: int = 0
    entry_allowed: bool = False
    entry_block_reason: str = ""
    entry_score: float = 0.0
    operative_interval: str = ""
    operative_parent_interval: str = ""

    def to_dict(self) -> dict:
        payload = asdict(self)
        payload["frames"] = {
            key: value.to_dict() if isinstance(value, WaveSnapshot) else value
            for key, value in self.frames.items()
        }
        return payload


class MultiTimeframeWaveEngine:
    """Build a direction-neutral, nested wave context from confirmed fractals."""

    INTERVAL_SECONDS = {
        "1m": 60,
        "3m": 180,
        "5m": 300,
        "15m": 900,
        "30m": 1800,
        "1h": 3600,
        "2h": 7200,
        "4h": 14400,
        "6h": 21600,
        "8h": 28800,
        "12h": 43200,
        "1d": 86400,
        "3d": 259200,
        "1w": 604800,
        "1M": 2592000,
    }

    DEFAULT_CHAINS = {
        "5m": ["1d", "4h", "1h", "15m", "5m"],
        "15m": ["1d", "4h", "1h", "15m", "5m"],
        "30m": ["1d", "4h", "1h", "30m", "15m"],
        "1h": ["1d", "4h", "1h", "15m"],
        "2h": ["1d", "4h", "2h", "1h"],
        "4h": ["1w", "1d", "4h", "1h"],
        "6h": ["1w", "1d", "6h", "4h"],
        "12h": ["1w", "1d", "12h", "4h"],
        "1d": ["1w", "1d", "4h"],
        "3d": ["1w", "3d", "1d"],
        "1w": ["1w", "1d", "4h"],
        "1M": ["1M", "1w", "1d"],
    }

    def __init__(
        self,
        client,
        base_interval: Optional[str] = None,
        intervals: Optional[Sequence[str]] = None,
        lookback: Optional[int] = None,
        min_bars: Optional[int] = None,
        include_micro: Optional[bool] = None,
    ) -> None:
        self.client = client
        self.base_interval = (base_interval or os.getenv("INTERVAL", "1h")).lower()
        self.context_interval = os.getenv("HTF_INTERVAL", "4h").lower()
        self.execution_interval = os.getenv("EXECUTION_TIMEFRAME", "5m").lower()
        self.lookback = int(
            lookback if lookback is not None else os.getenv("WAVE_LOOKBACK", str(LOOKBACK_DEFAULT))
        )
        self.min_bars = int(
            min_bars if min_bars is not None else os.getenv("WAVE_MIN_BARS", str(MIN_WAVE_BARS_DEFAULT))
        )
        self.lookback = max(self.lookback, self.min_bars)
        self.micro_min_bars = max(
            40,
            int(os.getenv("WAVE_MICRO_MIN_BARS", "40")),
        )
        self.include_micro = (
            bool(include_micro)
            if include_micro is not None
            else os.getenv("WAVE_MICRO_ENABLED", "false").lower() == "true"
        )
        self.cfg = config_from_env()

        raw = os.getenv("WAVE_TF_CHAIN", "").strip()
        if intervals is not None:
            wanted = [str(x).lower().strip() for x in intervals if str(x).strip()]
        elif raw:
            wanted = [x.lower().strip() for x in raw.split(",") if x.strip()]
        else:
            wanted = list(self.DEFAULT_CHAINS.get(
                self.base_interval,
                ["1d", self.context_interval, self.base_interval],
            ))

        for interval in (
            self.context_interval,
            self.base_interval,
            self.execution_interval,
        ):
            if interval not in wanted:
                wanted.append(interval)

        # For full historical wave analysis, use Binance-native intervals across
        # the complete hierarchy. A custom WAVE_TF_CHAIN can still narrow this
        # for tests or low-resource deployments.
        if not raw and os.getenv("WAVE_FULL_TF_ALL", "false").lower() == "true":
            wanted = list(self.INTERVAL_SECONDS.keys())

        # Synthetic seconds are never used for structural wave counting.

        # Highest timeframe first; unsupported values are retained so an invalid
        # configuration is visible in the report instead of silently disappearing.
        self.intervals = sorted(
            list(dict.fromkeys(wanted)),
            key=lambda x: self.INTERVAL_SECONDS.get(x, -1),
            reverse=True,
        )

    # ------------------------------------------------------------------
    # Data preparation
    # ------------------------------------------------------------------
    @staticmethod
    def _drop_unfinished(df: pd.DataFrame) -> pd.DataFrame:
        if df is None or df.empty:
            return pd.DataFrame()

        out = df.copy()
        if "close_time" not in out.columns:
            return out

        try:
            close_times = pd.to_datetime(out["close_time"], utc=True, errors="coerce")
            now = pd.Timestamp.now(tz="UTC")
            if bool((close_times.iloc[-1] > now) if pd.notna(close_times.iloc[-1]) else False):
                out = out.iloc[:-1].copy()
        except Exception:
            pass

        return out

    def _fetch(
        self,
        symbol: str,
        interval: str,
        limit: Optional[int] = None,
    ) -> pd.DataFrame:
        from data import fetch_klines, fetch_klines_cached_history

        if os.getenv("WAVE_USE_FULL_HISTORY", "false").lower() == "true":
            return self._drop_unfinished(
                fetch_klines_cached_history(self.client, symbol, interval)
            )

        return self._drop_unfinished(
            fetch_klines(
                self.client,
                symbol,
                interval,
                limit=max(
                    1,
                    int(limit if limit is not None else self.lookback),
                ),
            )
        )

    @staticmethod
    def _aggregate_seconds(
        df: pd.DataFrame,
        seconds: int,
    ) -> pd.DataFrame:
        if df is None or df.empty or seconds <= 1:
            return df

        bucket_ms = int(seconds) * 1000
        open_ms = (
            df.index.astype("int64") // 1_000_000
        )
        bucket = (open_ms // bucket_ms) * bucket_ms

        work = df.copy()
        work["_bucket"] = bucket
        grouped = work.groupby("_bucket", sort=True)

        out = pd.DataFrame({
            "open": grouped["open"].first(),
            "high": grouped["high"].max(),
            "low": grouped["low"].min(),
            "close": grouped["close"].last(),
            "volume": grouped["volume"].sum(),
        })
        out.index = pd.to_datetime(
            out.index,
            unit="ms",
            utc=True,
        )
        out.index.name = df.index.name
        out["close_time"] = (
            out.index +
            pd.to_timedelta(bucket_ms - 1, unit="ms")
        )
        return out.dropna()

    # ------------------------------------------------------------------
    # Confirmed fractal / swing layer
    # ------------------------------------------------------------------
    def _confirmed_pivots(self, ind: pd.DataFrame) -> List[Pivot]:
        """Return confirmed adaptive structural pivots.

        Williams fractals remain the confirmation mechanism, but a fractal is
        promoted to a structural swing according to current volatility. No
        fixed number of bars is imposed on a wave.
        """
        if ind is None or ind.empty:
            return []

        left = int(self.cfg["fractal_left"])
        right = int(self.cfg["fractal_right"])
        atr_period = max(5, int(os.getenv("WAVE_SWING_ATR_PERIOD", os.getenv("ATR_PERIOD", "14"))))
        min_atr = max(0.05, float(os.getenv("WAVE_MIN_SWING_ATR", "0.35")))
        min_pct = max(0.0001, float(os.getenv("WAVE_MIN_SWING_PCT", "0.0015")))

        prev_close = ind["close"].shift(1)
        tr = pd.concat(
            [
                ind["high"] - ind["low"],
                (ind["high"] - prev_close).abs(),
                (ind["low"] - prev_close).abs(),
            ],
            axis=1,
        ).max(axis=1)
        atr_series = tr.rolling(
            atr_period,
            min_periods=max(3, atr_period // 2),
        ).mean()

        raw: List[Pivot] = []
        for i in range(left, len(ind) - right):
            up = bool(ind["fractal_up"].iloc[i])
            down = bool(ind["fractal_down"].iloc[i])
            if up and down:
                continue
            if not (up or down):
                continue

            confirmed = i + right
            if confirmed >= len(ind):
                continue

            row = ind.iloc[i]
            price = self._safe_float(row.get("high" if up else "low"))
            if price <= 0:
                continue

            raw.append(Pivot(
                kind=DIRECTION_UP if up else DIRECTION_DOWN,
                price=price,
                center_index=i,
                confirmed_index=confirmed,
                ao=self._safe_float(row.get("ao")),
                ac=self._safe_float(row.get("ac")),
            ))

        raw.sort(key=lambda p: (p.confirmed_index, p.center_index))

        alternating: List[Pivot] = []
        for pivot in raw:
            if not alternating:
                alternating.append(pivot)
                continue

            last = alternating[-1]

            if pivot.kind == last.kind:
                more_extreme = (
                    pivot.price > last.price
                    if pivot.kind == DIRECTION_UP
                    else pivot.price < last.price
                )
                if more_extreme:
                    alternating[-1] = pivot
                continue

            atr = self._safe_float(atr_series.iloc[pivot.center_index])
            threshold = max(
                atr * min_atr if atr > 0 else 0.0,
                abs(last.price) * min_pct,
            )

            # The threshold is volatility-relative, not time-relative. This
            # allows short waves in fast markets and long waves in quiet markets.
            if abs(pivot.price - last.price) >= threshold:
                alternating.append(pivot)
            elif abs(pivot.price - last.price) >= threshold * 0.65:
                # Keep borderline structure rather than imposing a hard
                # minimum wave length. A later stronger pivot can replace it.
                alternating.append(pivot)

        return alternating

    @staticmethod
    def _pivot_preview(pivots: Iterable[Pivot], max_items: int = 10) -> List[dict]:
        items = list(pivots)[-max_items:]
        return [
            {
                "kind": p.kind,
                "price": round(p.price, 12),
                "center_index": p.center_index,
                "confirmed_index": p.confirmed_index,
                "ao": round(p.ao, 8),
                "ac": round(p.ac, 8),
            }
            for p in items
        ]

    # ------------------------------------------------------------------
    # Structural wave layer
    # ------------------------------------------------------------------
    @staticmethod
    def _expected_kind(direction: str, pivot_index: int) -> str:
        # 0 is the impulse origin. The completed wave sequence for an UP
        # impulse is Down, Up, Down, Up, Down, Up. For DOWN it is mirrored.
        if direction == DIRECTION_UP:
            return DIRECTION_DOWN if pivot_index % 2 == 0 else DIRECTION_UP
        if direction == DIRECTION_DOWN:
            return DIRECTION_UP if pivot_index % 2 == 0 else DIRECTION_DOWN
        return ""

    @staticmethod
    def _leg(a: Pivot, b: Pivot) -> float:
        return abs(float(b.price) - float(a.price))

    def _sequence_fit(
        self,
        seq: Sequence[Pivot],
        direction: str,
        current_position: int,
    ) -> float:
        """Score a candidate wave skeleton using structural constraints.

        This is deliberately a soft Williams/Profitunity-style structure layer:
        fractals provide the swing anchors, while price progression, correction
        depth, momentum and the five-wave geometry decide whether a W1/W3/W5
        interpretation is credible.  It avoids hard Fibonacci rules because
        wave counting is a pattern-recognition task rather than an exact formula.
        """
        if not seq or direction not in (DIRECTION_UP, DIRECTION_DOWN):
            return 0.0

        expected = [self._expected_kind(direction, i) for i in range(len(seq))]
        if any(p.kind != k for p, k in zip(seq, expected)):
            return 0.0

        score = 50.0

        def signed(a: Pivot, b: Pivot) -> float:
            return (b.price - a.price) if direction == DIRECTION_UP else (a.price - b.price)

        def retracement(start_p: Pivot, end_p: Pivot, correction_p: Pivot) -> float:
            impulse = abs(end_p.price - start_p.price)
            if impulse <= 0:
                return 9.0
            return abs(end_p.price - correction_p.price) / impulse

        # W1 must actually travel in the intended direction.
        if len(seq) >= 2:
            w1 = signed(seq[0], seq[1])
            if w1 > 0:
                score += 8.0
            else:
                score -= 18.0

        # W2 should correct W1 without invalidating its origin.  Very deep
        # corrections are penalised, but not treated as impossible because
        # real markets and crypto can produce irregular structures.
        if len(seq) >= 3:
            w2_valid = (
                seq[2].price > seq[0].price
                if direction == DIRECTION_UP
                else seq[2].price < seq[0].price
            )
            if w2_valid:
                score += 10.0
            else:
                score -= 28.0
            r2 = retracement(seq[0], seq[1], seq[2])
            if 0.236 <= r2 <= 0.786:
                score += 7.0
            elif r2 > 1.0:
                score -= 10.0

        # W3 should extend beyond W1 and should normally be a meaningful
        # impulse leg.  It is a high-quality location when momentum agrees.
        if len(seq) >= 4:
            w3_extends = (
                seq[3].price > seq[1].price
                if direction == DIRECTION_UP
                else seq[3].price < seq[1].price
            )
            if w3_extends:
                score += 12.0
            else:
                score -= 25.0

            w1_len = abs(seq[1].price - seq[0].price)
            w3_len = abs(seq[3].price - seq[2].price)
            if w1_len > 0 and w3_len >= w1_len * 0.9:
                score += 8.0
            elif w1_len > 0 and w3_len < w1_len * 0.65:
                score -= 8.0

            if seq[3].ao != 0.0 and seq[3].ao * (1 if direction == DIRECTION_UP else -1) > 0:
                score += 4.0

        # W4 should remain inside the impulse structure and normally must not
        # erase the W1 endpoint.  This is a soft standard-impulse rule; we do
        # not reject diagonal/irregular market structures outright.
        if len(seq) >= 5:
            w4_valid = (
                seq[4].price > seq[1].price
                if direction == DIRECTION_UP
                else seq[4].price < seq[1].price
            )
            w4_before_w3 = (
                seq[4].price < seq[3].price
                if direction == DIRECTION_UP
                else seq[4].price > seq[3].price
            )
            if w4_valid and w4_before_w3:
                score += 10.0
            elif w4_valid:
                score += 2.0
            else:
                score -= 18.0

            w3_len = abs(seq[3].price - seq[2].price)
            w4_len = abs(seq[4].price - seq[3].price)
            if w3_len > 0 and w4_len <= w3_len * 0.95:
                score += 4.0

        # Completed W5 must extend W3 for a normal impulse.  If it does not,
        # downgrade the count rather than forcing a W5 label.
        if len(seq) >= 6:
            w5_extends = (
                seq[5].price > seq[3].price
                if direction == DIRECTION_UP
                else seq[5].price < seq[3].price
            )
            if w5_extends:
                score += 10.0
            else:
                score -= 22.0

            w1_len = abs(seq[1].price - seq[0].price)
            w3_len = abs(seq[3].price - seq[2].price)
            w5_len = abs(seq[5].price - seq[4].price)
            if min(w1_len, w3_len, w5_len) > 0:
                # W3 should not be the shortest of the three impulse legs.
                if w3_len >= min(w1_len, w5_len) * 0.85:
                    score += 5.0
                else:
                    score -= 12.0

        # A candidate W3 gets a small quality bonus when the AO on its endpoint
        # agrees with the impulse direction.  This matches the Profitunity use
        # of AO as momentum confirmation without making AO the wave counter.
        if current_position == 3 and len(seq) >= 4:
            ao = seq[3].ao
            if (direction == DIRECTION_UP and ao > 0) or (direction == DIRECTION_DOWN and ao < 0):
                score += 6.0

        # W5 is never an automatic veto, but a structurally weak W5 is explicitly
        # downgraded so a nested/earlier W3 can win the MTF score.
        if current_position == 5:
            if len(seq) >= 5:
                w3_anchor = seq[-2]
                w5_origin = seq[-1]
                if (direction == DIRECTION_UP and w5_origin.price <= w3_anchor.price) or (
                    direction == DIRECTION_DOWN and w5_origin.price >= w3_anchor.price
                ):
                    score -= 12.0

        return max(0.0, min(100.0, score))
    def _full_impulse_completed(self, pivots: Sequence[Pivot], direction: str) -> bool:
        if len(pivots) < 6:
            return False

        seq = list(pivots[-6:])
        if [p.kind for p in seq] != [
            self._expected_kind(direction, i) for i in range(6)
        ]:
            return False

        if direction == DIRECTION_UP:
            return (
                seq[2].price > seq[0].price
                and seq[3].price > seq[1].price
                and seq[4].price > seq[2].price
                and seq[5].price > seq[3].price
            )

        return (
            seq[2].price < seq[0].price
            and seq[3].price < seq[1].price
            and seq[4].price < seq[2].price
            and seq[5].price < seq[3].price
        )

    def _estimate_position(
        self,
        pivots: Sequence[Pivot],
        direction: str,
        close: float,
        atr: float,
    ) -> Tuple[int, str, float, float, Dict[str, object], str]:
        """Estimate the currently active leg from the confirmed swing skeleton."""
        if direction not in (DIRECTION_UP, DIRECTION_DOWN) or not pivots:
            return 0, "?", 0.0, 0.0, {}, "No coherent directional pivot structure yet."

        last = pivots[-1]
        move_signed = close - last.price
        move = abs(move_signed)
        up_current = direction == DIRECTION_UP and last.kind == DIRECTION_DOWN and move_signed > 0
        down_current = direction == DIRECTION_DOWN and last.kind == DIRECTION_UP and move_signed < 0
        impulse_leg_active = up_current or down_current

        # If a full five-wave impulse has completed and the latest confirmed
        # pivot is the W5 endpoint, a new move in the opposite direction is
        # better represented as an A-wave/post-impulse correction than as a
        # recycled W2/W4 label.
        if (
            not impulse_leg_active
            and len(pivots) >= 6
            and self._full_impulse_completed(pivots, direction)
            and (
                (direction == DIRECTION_UP and last.kind == DIRECTION_UP and move_signed < 0)
                or (direction == DIRECTION_DOWN and last.kind == DIRECTION_DOWN and move_signed > 0)
            )
        ):
            current_leg_pct = move / max(abs(last.price), 1e-12) * 100.0
            current_leg_atr = move / max(atr, 1e-12) if atr > 0 else 0.0
            return (
                0,
                "A",
                75.0,
                current_leg_pct,
                {"post_impulse": True, "active_leg_atr": round(current_leg_atr, 3)},
                "Completed five-wave skeleton detected; price is beginning a post-impulse correction.",
            )

        if not impulse_leg_active:
            # Current leg may be a correction (W2/W4) or a just-confirmed W5
            # endpoint waiting for more price discovery.
            correction_candidates = (2, 4)
            best: Optional[Tuple[int, float]] = None
            for position in correction_candidates:
                need = position
                if len(pivots) < need:
                    continue
                seq = list(pivots[-need:])
                fit = self._sequence_fit(seq, direction, position)
                if fit <= 0:
                    continue

                expected_last = self._expected_kind(direction, position - 1)
                if last.kind != expected_last:
                    continue

                active_sign_ok = (
                    direction == DIRECTION_UP and last.kind == DIRECTION_UP and move_signed < 0
                ) or (
                    direction == DIRECTION_DOWN and last.kind == DIRECTION_DOWN and move_signed > 0
                )
                if not active_sign_ok:
                    continue

                if best is None or fit > best[1] or position > best[0]:
                    best = (position, fit)

            if best is not None:
                position, fit = best
                current_leg_pct = move / max(abs(last.price), 1e-12) * 100.0
                current_leg_atr = move / max(atr, 1e-12) if atr > 0 else 0.0
                structure = {
                    "active_leg_atr": round(current_leg_atr, 3),
                    "completed_pivots": len(pivots),
                    "sequence_fit": round(fit, 2),
                }
                return (
                    position,
                    f"W{position}",
                    fit,
                    current_leg_pct,
                    structure,
                    f"Current corrective leg estimated as W{position} from confirmed fractal sequence.",
                )

            return (
                0,
                "?",
                10.0,
                move / max(abs(last.price), 1e-12) * 100.0,
                {"completed_pivots": len(pivots)},
                "Insufficient coherent completed fractal legs for a numbered wave estimate.",
            )

        # Active impulse leg: W1, W3 or W5. The latest confirmed pivot is the
        # beginning of that leg. Match the longest coherent suffix.
        best_position = 0
        best_fit = 0.0
        for position in (1, 3, 5):
            need = position
            if len(pivots) < need:
                continue
            seq = list(pivots[-need:])
            if seq[-1] is not last:
                continue
            fit = self._sequence_fit(seq, direction, position)
            if fit > 0 and (position > best_position or (position == best_position and fit > best_fit)):
                best_position = position
                best_fit = fit

        current_leg_pct = move / max(abs(last.price), 1e-12) * 100.0
        current_leg_atr = move / max(atr, 1e-12) if atr > 0 else 0.0

        if best_position == 0:
            best_position = 1
            best_fit = 20.0
            structure = {"early_estimate": True, "active_leg_atr": round(current_leg_atr, 3)}
            return (
                best_position,
                "W1",
                best_fit,
                current_leg_pct,
                structure,
                "Early W1 estimate; insufficient completed fractal legs.",
            )

        structure = {
            "active_leg_atr": round(current_leg_atr, 3),
            "completed_pivots": len(pivots),
            "sequence_fit": round(best_fit, 2),
        }
        return (
            best_position,
            f"W{best_position}",
            best_fit,
            current_leg_pct,
            structure,
            f"Current impulse leg estimated as W{best_position} from confirmed fractal sequence.",
        )

    def _abc_state(self, pivots: Sequence[Pivot], direction: str) -> str:
        """Detect a developing A/B/C correction without forcing a textbook count."""
        if direction not in (DIRECTION_UP, DIRECTION_DOWN) or len(pivots) < 4:
            return ""
        seq = list(pivots[-4:])
        expected = ([DIRECTION_UP, DIRECTION_DOWN, DIRECTION_UP, DIRECTION_DOWN]
                    if direction == DIRECTION_UP
                    else [DIRECTION_DOWN, DIRECTION_UP, DIRECTION_DOWN, DIRECTION_UP])
        if [p.kind for p in seq] != expected:
            return ""
        if direction == DIRECTION_UP:
            ok = seq[1].price < seq[0].price and seq[2].price > seq[0].price and seq[3].price < seq[1].price
        else:
            ok = seq[1].price > seq[0].price and seq[2].price < seq[0].price and seq[3].price > seq[1].price
        return "ABC_DEVELOPING" if ok else ""

    @staticmethod
    def _exhaustion_components(position: int, divergence: str, target_zone: bool,
                               squatting_bar: bool, momentum_fading: bool,
                               current_leg_atr: float) -> Dict[str, float]:
        return {
            "wave5_context": 20.0 if position == 5 else 0.0,
            "ao_divergence": 30.0 if divergence != "NONE" else 0.0,
            "target_zone": 20.0 if target_zone and position == 5 else 0.0,
            "squat": 15.0 if squatting_bar else 0.0,
            "momentum_fading": 15.0 if momentum_fading and position in (3, 5) else 0.0,
            "extension": min(20.0, max(0.0, (current_leg_atr - 2.0) * 10.0)),
        }

    @staticmethod
    def _alternative_count(position: int, abc_phase: str, exhaustion: float) -> str:
        if abc_phase == "ABC_DEVELOPING":
            return "ABC"
        if position == 5 and exhaustion >= 55.0:
            return "W3_ALTERNATIVE"
        if position == 3 and exhaustion >= 45.0:
            return "W5_ALTERNATIVE"
        if position in (2, 4):
            return "CORRECTION_ALTERNATIVE"
        return ""

    # ------------------------------------------------------------------
    # Williams context / exhaustion
    # ------------------------------------------------------------------
    @staticmethod
    def _safe_float(value, default: float = 0.0) -> float:
        try:
            if pd.isna(value):
                return default
            return float(value)
        except Exception:
            return default

    def _direction(self, last: pd.Series) -> str:
        if bool(last.get("bullish_alligator", False)):
            return DIRECTION_UP
        if bool(last.get("bearish_alligator", False)):
            return DIRECTION_DOWN

        ao = self._safe_float(last.get("ao"))
        ac = self._safe_float(last.get("ac"))
        if ao > 0 and ac > 0:
            return DIRECTION_UP
        if ao < 0 and ac < 0:
            return DIRECTION_DOWN
        return DIRECTION_NEUTRAL

    def _phase(self, last: pd.Series, direction: str) -> Tuple[str, float, str]:
        price = self._safe_float(last.get("close"))
        jaw = self._safe_float(last.get("jaw_shifted"), np.nan)
        teeth = self._safe_float(last.get("teeth_shifted"), np.nan)
        lips = self._safe_float(last.get("lips_shifted"), np.nan)
        spread = self._safe_float(last.get("alligator_spread_pct"))
        awake = bool(last.get("alligator_awake", False))
        ao = self._safe_float(last.get("ao"))
        ac = self._safe_float(last.get("ac"))

        if price <= 0 or any(np.isnan(v) for v in (jaw, teeth, lips)):
            return WAVE_STATE_UNKNOWN, 0.0, "Insufficient Alligator data."

        above_mouth = price > max(jaw, teeth, lips)
        below_mouth = price < min(jaw, teeth, lips)
        momentum_aligned = (
            direction == DIRECTION_UP and ao > 0 and ac > 0
        ) or (
            direction == DIRECTION_DOWN and ao < 0 and ac < 0
        )

        if direction == DIRECTION_UP and above_mouth and awake:
            score = 75.0 + (15.0 if momentum_aligned else 0.0)
            return WAVE_STATE_IMPULSE, min(score, 100.0), "Price is outside the Alligator mouth on the directional side."
        if direction == DIRECTION_DOWN and below_mouth and awake:
            score = 75.0 + (15.0 if momentum_aligned else 0.0)
            return WAVE_STATE_IMPULSE, min(score, 100.0), "Price is outside the Alligator mouth on the directional side."

        distance_to_jaw = abs(price - jaw) / max(price, 1e-12)
        balance_band = max(0.002, spread * 0.85)
        if distance_to_jaw <= balance_band:
            return WAVE_STATE_CORRECTION, 55.0 if not awake else 45.0, "Price is near the Alligator balance structure."

        if awake and momentum_aligned:
            return WAVE_STATE_TRANSITION, 60.0, "Alligator is awake and momentum is aligned, but price is not clearly outside the mouth."

        if not awake:
            return WAVE_STATE_TRANSITION, 20.0, "Alligator is not clearly awake; directional confidence is low."

        return WAVE_STATE_TRANSITION, 35.0, "Mixed Alligator/AO conditions."

    def _divergence(self, pivots: Sequence[Pivot], direction: str) -> Tuple[str, float]:
        if direction == DIRECTION_UP:
            same = [p for p in pivots if p.kind == DIRECTION_UP]
            if len(same) >= 2 and same[-1].price > same[-2].price and same[-1].ao < same[-2].ao:
                return "BEARISH", 75.0
        elif direction == DIRECTION_DOWN:
            same = [p for p in pivots if p.kind == DIRECTION_DOWN]
            if len(same) >= 2 and same[-1].price < same[-2].price and same[-1].ao > same[-2].ao:
                return "BULLISH", 75.0
        return "NONE", 0.0

    @staticmethod
    def _target_zone(
        pivots: Sequence[Pivot],
        direction: str,
        position: int,
        price: float,
    ) -> Tuple[bool, float, float]:
        """Williams' 62%-100% Wave-5 target-zone approximation.

        The source describes measuring the distance from the start of W1 to the
        end of W3, projecting it from the end of W4, and treating 62%-100% of
        that distance as a likely W5 target zone. We only apply it when the
        structural engine is currently in an active W5.
        """
        if position != 5 or len(pivots) < 5:
            return False, 0.0, 0.0

        start_w1 = float(pivots[-5].price)
        end_w3 = float(pivots[-2].price)
        end_w4 = float(pivots[-1].price)
        distance = abs(end_w3 - start_w1)
        if distance <= 0:
            return False, 0.0, 0.0

        if direction == DIRECTION_UP:
            low = end_w4 + 0.62 * distance
            high = end_w4 + 1.00 * distance
        elif direction == DIRECTION_DOWN:
            low = end_w4 - 1.00 * distance
            high = end_w4 - 0.62 * distance
        else:
            return False, 0.0, 0.0

        return min(low, high) <= price <= max(low, high), min(low, high), max(low, high)

    @staticmethod
    def _exhaustion(
        position: int,
        divergence: str,
        divergence_risk: float,
        phase: str,
        impulse_score: float,
        current_leg_atr: float,
        target_zone: bool = False,
        squatting_bar: bool = False,
        momentum_fading: bool = False,
    ) -> float:
        risk = float(divergence_risk)

        # W5 increases context risk, but never acts as an automatic veto.
        if position == 5:
            risk += 15.0
        if position == 0 and phase == WAVE_STATE_CORRECTION:
            risk += 5.0
        if phase == WAVE_STATE_CORRECTION:
            risk += 8.0
        if impulse_score < 35.0:
            risk += 10.0
        if current_leg_atr >= 3.0 and position in (3, 5):
            risk += 10.0
        if target_zone and position == 5:
            risk += 15.0
        if squatting_bar:
            risk += 10.0
        if momentum_fading and position in (3, 5):
            risk += 10.0

        return max(0.0, min(100.0, risk))

    def build_snapshot(
        self,
        interval: str,
        df: pd.DataFrame,
        degree: str = "MEDIUM",
    ) -> WaveSnapshot:
        clean = self._drop_unfinished(df)
        if clean is None or clean.empty:
            return WaveSnapshot(
                interval=interval,
                wave_degree=degree,
                data_bars=0,
                data_ok=False,
                reason="No market data.",
            )

        required_bars = (
            self.micro_min_bars
            if interval in {"1s", "5s", "25s"}
            else self.min_bars
        )
        if len(clean) < required_bars:
            return WaveSnapshot(
                interval=interval,
                wave_degree=degree,
                data_bars=len(clean),
                data_ok=False,
                reason=f"Need at least {self.min_bars} closed bars; got {len(clean)}.",
            )

        ind = calculate_indicators(clean.copy(), self.cfg)
        last = ind.iloc[-1]
        price = self._safe_float(last.get("close"))

        prev = ind["close"].shift(1)
        tr = pd.concat(
            [
                ind["high"] - ind["low"],
                (ind["high"] - prev).abs(),
                (ind["low"] - prev).abs(),
            ],
            axis=1,
        ).max(axis=1)
        # ATR is a risk/config concept and must not inherit AO periods.
        atr_period = int(os.getenv("ATR_PERIOD", "14"))
        atr = self._safe_float(tr.rolling(atr_period).mean().iloc[-1])

        pivots = self._confirmed_pivots(ind)
        direction = self._direction(last)
        phase, impulse_score, phase_reason = self._phase(last, direction)

        if direction == DIRECTION_NEUTRAL and len(pivots) >= 2:
            direction = (
                DIRECTION_UP
                if pivots[-1].price > pivots[-2].price
                else DIRECTION_DOWN
            )

        (
            position,
            wave_label,
            structural_confidence,
            current_leg_pct,
            structure,
            structure_reason,
        ) = self._estimate_position(
            pivots,
            direction,
            price,
            atr,
        )

        # If a pivot-derived correction/impulse phase is clear, let it refine the
        # indicator-based phase rather than allowing Alligator state to erase the
        # numbered structural context.
        if position in (2, 4) or wave_label == "A":
            phase = WAVE_STATE_CORRECTION
        elif position in (1, 3, 5) and phase == WAVE_STATE_UNKNOWN:
            phase = WAVE_STATE_TRANSITION

        divergence, divergence_risk = self._divergence(pivots, direction)
        target_zone, target_zone_low, target_zone_high = self._target_zone(
            pivots, direction, position, price
        )
        squatting_bar = bool(last.get("squatting_bar", False))
        momentum_fading = (
            (direction == DIRECTION_UP and bool(last.get("ao_red", False)))
            or (direction == DIRECTION_DOWN and bool(last.get("ao_green", False)))
        )
        current_leg_atr = current_leg_pct / 100.0 * price / max(atr, 1e-12) if atr > 0 else 0.0
        exhaustion = self._exhaustion(
            position,
            divergence,
            divergence_risk,
            phase,
            impulse_score,
            current_leg_atr,
            target_zone=target_zone,
            squatting_bar=squatting_bar,
            momentum_fading=momentum_fading,
        )

        alligator_state = (
            "AWAKE" if bool(last.get("alligator_awake", False)) else "SLEEPING_OR_TANGLED"
        )

        abc_phase = self._abc_state(pivots, direction)
        components = self._exhaustion_components(position, divergence, target_zone, squatting_bar, momentum_fading, current_leg_atr)
        primary_count = wave_label if wave_label not in ("?", "") else phase
        alternative_count = self._alternative_count(position, abc_phase, exhaustion)
        invalidation_price = self._invalidation_price(pivots, direction, position)
        terminal_fractal = bool(structure.get("post_impulse") and pivots and pivots[-1].kind == direction)
        magic_bullets_count = self._magic_bullets_count(
            target_zone, divergence, terminal_fractal, squatting_bar, momentum_fading
        )
        scenario_primary, scenario_alternative = self._scenario_labels(
            position, phase, direction, exhaustion, abc_phase
        )

        reason = f"{phase_reason} {structure_reason}"
        if abc_phase:
            reason += " ABC correction structure is developing."
        if divergence != "NONE":
            reason += f" {divergence} AO divergence detected."
        if target_zone:
            reason += f" Price is inside the 62%-100% Wave-5 target zone ({target_zone_low:.8g}-{target_zone_high:.8g})."
        if squatting_bar:
            reason += " A Profitunity SQUAT volume/MFI-proxy condition is present."
        if momentum_fading:
            reason += " Momentum is fading on the current bar."

        last_pivot = pivots[-1] if pivots else None
        confidence = (
            0.45 * structural_confidence
            + 0.35 * impulse_score
            + 0.20 * (100.0 - exhaustion)
        )
        confidence = max(0.0, min(100.0, confidence))

        return WaveSnapshot(
            interval=interval,
            wave_degree=degree,
            direction=direction,
            phase=phase,
            position=int(position),
            wave_label=wave_label,
            confidence=round(confidence, 2),
            structural_confidence=round(structural_confidence, 2),
            impulse_score=round(impulse_score, 2),
            exhaustion_risk=round(exhaustion, 2),
            divergence=divergence,
            target_zone=bool(target_zone),
            target_zone_low=round(target_zone_low, 12),
            target_zone_high=round(target_zone_high, 12),
            squatting_bar=squatting_bar,
            momentum_fading=momentum_fading,
            magic_bullets={
                "target_zone": bool(target_zone),
                "divergence": divergence != "NONE",
                "terminal_fractal": terminal_fractal,
                "squatting_bar": squatting_bar,
                "momentum_fading": momentum_fading,
            },
            primary_count=primary_count,
            alternative_count=alternative_count,
            abc_phase=abc_phase,
            exhaustion_components=components,
            invalidation_price=round(invalidation_price, 12),
            terminal_fractal=terminal_fractal,
            magic_bullets_count=magic_bullets_count,
            scenario_primary=scenario_primary,
            scenario_alternative=scenario_alternative,
            alligator_state=alligator_state,
            alligator_bullish=bool(last.get("bullish_alligator", False)),
            alligator_bearish=bool(last.get("bearish_alligator", False)),
            ao=self._safe_float(last.get("ao")),
            ac=self._safe_float(last.get("ac")),
            alligator_spread=self._safe_float(last.get("alligator_spread_pct")),
            price=price,
            last_pivot_kind=last_pivot.kind if last_pivot else "NONE",
            last_pivot_price=last_pivot.price if last_pivot else 0.0,
            confirmed_fractals=len(pivots),
            current_leg_pct=round(current_leg_pct, 5),
            current_leg_atr=round(current_leg_atr, 3),
            pivots=self._pivot_preview(pivots),
            structure=structure,
            reason=reason,
            data_bars=len(clean),
            data_ok=True,
        )

    @staticmethod
    def _invalidation_price(pivots: Sequence[Pivot], direction: str, position: int) -> float:
        """Return the last confirmed structural invalidation for an active impulse."""
        if not pivots or position not in (1, 3, 5):
            return 0.0
        pivot = pivots[-1]
        if direction == DIRECTION_UP and pivot.kind == DIRECTION_DOWN:
            return float(pivot.price)
        if direction == DIRECTION_DOWN and pivot.kind == DIRECTION_UP:
            return float(pivot.price)
        return 0.0

    @staticmethod
    def _scenario_labels(position: int, phase: str, direction: str, exhaustion: float, abc_phase: str) -> Tuple[str, str]:
        if phase == WAVE_STATE_CORRECTION or abc_phase == "ABC_DEVELOPING":
            primary = "ABC_CORRECTION"
        elif position in (1, 3, 5):
            primary = f"IMPULSE_W{position}_{direction}"
        else:
            primary = "TRANSITION"
        if position == 5 and exhaustion >= 55.0:
            alternative = "W3_CONTINUATION"
        elif position == 3 and exhaustion >= 45.0:
            alternative = "W5_EXHAUSTION"
        elif position in (2, 4):
            alternative = "CORRECTION_CONTINUES"
        else:
            alternative = "NONE"
        return primary, alternative

    @staticmethod
    def _magic_bullets_count(target_zone: bool, divergence: str, terminal_fractal: bool, squatting_bar: bool, momentum_fading: bool) -> int:
        return int(sum(bool(x) for x in (
            target_zone, divergence != "NONE", terminal_fractal,
            squatting_bar, momentum_fading,
        )))

    # ------------------------------------------------------------------
    # MTF hierarchy
    # ------------------------------------------------------------------
    def _nested_relationship(self, parent: WaveSnapshot, child: WaveSnapshot) -> str:
        """Classify the fractal relationship between adjacent timeframes.

        For a bullish parent impulse, W1/W3/W5 contain five-wave bullish
        substructure, while W2/W4 are bearish corrections whose A and C legs
        can themselves be five-wave bearish impulses.  The bearish case is
        mirrored.  This prevents a countertrend five-wave correction from
        being mistaken for a LONG setup.
        """
        if not (parent.data_ok and child.data_ok):
            return "NONE"
        if parent.direction not in (DIRECTION_UP, DIRECTION_DOWN):
            return "NONE"
        if child.position not in (1, 3, 5):
            return "NONE"

        if parent.position in (1, 3, 5):
            return "ALIGNED_IMPULSE" if child.direction == parent.direction else "COUNTERTREND"

        if parent.position in (2, 4):
            expected_correction_direction = (
                DIRECTION_DOWN if parent.direction == DIRECTION_UP else DIRECTION_UP
            )
            if child.direction == expected_correction_direction:
                return "CORRECTION_IMPULSE"
            if child.direction == parent.direction:
                return "COUNTERTREND"
        return "NONE"

    def _build_report(self, snapshots: Mapping[str, WaveSnapshot]) -> MultiTimeframeWaveReport:
        ordered = sorted(
            [
                s for s in snapshots.values()
                if s.data_ok and s.direction in (DIRECTION_UP, DIRECTION_DOWN)
            ],
            key=lambda s: self.INTERVAL_SECONDS.get(s.interval, 0),
            reverse=True,
        )

        setup = snapshots.get(self.base_interval)
        if setup is None and ordered:
            setup = ordered[-1]

        weights = [0.40, 0.30, 0.20, 0.10]
        up = down = total = 0.0
        impulse_sum = confidence_sum = exhaustion_sum = 0.0

        for i, snap in enumerate(ordered):
            weight = weights[i] if i < len(weights) else 0.05
            total += weight
            if snap.direction == DIRECTION_UP:
                up += weight
            elif snap.direction == DIRECTION_DOWN:
                down += weight
            impulse_sum += weight * snap.impulse_score
            confidence_sum += weight * snap.confidence
            exhaustion_sum += weight * snap.exhaustion_risk

        if total <= 0:
            return MultiTimeframeWaveReport(
                frames=dict(snapshots),
                overall_direction=DIRECTION_NEUTRAL,
                alignment_score=0.0,
                wave_score=50.0,
                exhaustion_risk=0.0,
                nested_w3=False,
                nested_w3_parent_w5=False,
                nested_w3_count=0,
                nested_w3_parent_positions=[],
                nested_countertrend_impulse=False,
                nested_countertrend_count=0,
                wave_path="",
                htf_confirmed=False,
                setup_position=int(setup.position if setup else 0),
                setup_phase=setup.phase if setup else WAVE_STATE_UNKNOWN,
                reason="No valid timeframe data for wave scoring; neutral wave score applied.",
            )
        else:
            up_pct = up / total
            down_pct = down / total
            if up_pct > down_pct and up_pct >= 0.45:
                overall = DIRECTION_UP
                alignment = up_pct * 100.0
            elif down_pct > up_pct and down_pct >= 0.45:
                overall = DIRECTION_DOWN
                alignment = down_pct * 100.0
            else:
                overall = DIRECTION_NEUTRAL
                alignment = max(up_pct, down_pct) * 100.0

        nested_w3 = False
        nested_parent_w5 = False
        nested_count = 0
        nested_parent_positions = set()
        nested_countertrend_impulse = False
        nested_countertrend_count = 0

        # W3 may be nested inside any higher-degree impulse W1/W3/W5, not only the adjacent timeframe.
        for parent_index, parent in enumerate(ordered):
            for child in ordered[parent_index + 1:]:
                relation = self._nested_relationship(parent, child)
                child_w3 = child.position == 3 and child.confidence >= 45.0
                if relation == "ALIGNED_IMPULSE" and child_w3:
                    nested_w3 = True
                    nested_count += 1
                    nested_parent_positions.add(int(parent.position))
                    if parent.position == 5:
                        nested_parent_w5 = True
                elif relation == "CORRECTION_IMPULSE" and child.confidence >= 40.0:
                    nested_countertrend_impulse = True
                    nested_countertrend_count += 1

        exhaustion = exhaustion_sum / max(total, 1e-12)
        impulse = impulse_sum / max(total, 1e-12)
        structure = confidence_sum / max(total, 1e-12)
        child_quality = setup.confidence if setup is not None else 0.0

        direction_consistency = 100.0
        if setup is not None and overall in (DIRECTION_UP, DIRECTION_DOWN):
            if setup.direction == DIRECTION_NEUTRAL:
                direction_consistency = 55.0
            elif setup.direction != overall:
                direction_consistency = 30.0

        nested_bonus = 10.0 if nested_w3 else 0.0
        if nested_parent_w5:
            nested_bonus += 10.0

        # Countertrend five-wave structure inside parent W2/W4 is a correction,
        # not a LONG continuation. Do not reward it as bullish alignment.
        if nested_countertrend_impulse and setup is not None:
            nested_bonus -= 12.0

        wave_score = (
            0.28 * alignment
            + 0.18 * impulse
            + 0.17 * structure
            + 0.17 * child_quality
            + 0.20 * direction_consistency
            + nested_bonus
            - 0.24 * exhaustion
        )
        wave_score = max(0.0, min(100.0, wave_score))

        context = snapshots.get(self.context_interval)
        htf_confirmed = bool(
            context
            and context.data_ok
            and context.alligator_bullish
            and context.ao > 0
        )

        # Select the actual execution wave from the nested hierarchy.
        # Prefer W3 over W1, and prefer the lowest timeframe that is still
        # inside an impulse parent. A child below a W2/W4 parent is correction
        # structure (A/C or B), so it can never become a continuation LONG.
        execution_direction = (
            setup.direction
            if setup is not None and setup.direction in (DIRECTION_UP, DIRECTION_DOWN)
            else overall
        )
        entry_interval = ""
        entry_position = 0
        entry_parent_interval = ""
        entry_parent_position = 0
        entry_allowed = False
        entry_block_reason = ""

        ascending = sorted(
            ordered,
            key=lambda s: self.INTERVAL_SECONDS.get(s.interval, 0),
        )
        execution_candidates = sorted(
            [
                snap for snap in ascending
                if (
                    snap.direction == execution_direction
                    and snap.position in (3, 1)
                    and snap.confidence >= 45.0
                    and (
                        snap.alligator_bullish
                        if execution_direction == DIRECTION_UP
                        else snap.alligator_bearish
                    )
                    and (
                        snap.ao > 0
                        if execution_direction == DIRECTION_UP
                        else snap.ao < 0
                    )
                )
            ],
            key=lambda snap: (
                0 if snap.position == 3 else 1,
                self.INTERVAL_SECONDS.get(snap.interval, 0),
            ),
        )

        for child in execution_candidates:
            parent = next(
                (
                    p for p in ordered
                    if self.INTERVAL_SECONDS.get(p.interval, 0)
                    > self.INTERVAL_SECONDS.get(child.interval, 0)
                ),
                None,
            )

            if parent is None:
                entry_interval = child.interval
                entry_position = child.position
                entry_allowed = True
                entry_block_reason = ""
                break

            entry_parent_interval = parent.interval
            entry_parent_position = parent.position

            if parent.position in (2, 4):
                # Any child inside a higher corrective wave is part of the
                # correction tree, even when that child itself has five legs.
                continue

            if parent.position in (1, 3, 5) and parent.direction != execution_direction:
                # Opposite-direction five-wave structure is countertrend.
                continue

            entry_interval = child.interval
            entry_position = child.position
            entry_allowed = True
            entry_block_reason = (
                "W3 inside parent W5 allowed with reduced priority"
                if child.position == 3 and parent.position == 5
                else ""
            )
            break

        if not entry_allowed:
            if setup is not None and setup.position in (2, 4):
                entry_block_reason = "Base timeframe is in corrective W2/W4 structure"
            elif nested_countertrend_impulse:
                entry_block_reason = "Only qualifying five-wave child structures are countertrend corrections"
            elif setup is not None and setup.position == 5:
                entry_block_reason = "Wave 5 has no qualifying lower-timeframe W3/W1 entry structure"
            else:
                entry_block_reason = "No qualifying impulse entry wave across the available timeframes"

        path = " > ".join(
            f"{snap.interval}:{snap.wave_label}"
            for snap in ordered
        )

        reasons: List[str] = []
        if nested_parent_w5:
            reasons.append("nested W3 inside parent W5 detected - W5 context is not a veto")
        elif nested_w3:
            reasons.append("nested W3 alignment detected")
        if nested_countertrend_impulse:
            reasons.append("countertrend 5-wave impulse detected inside parent W2/W4 correction")
        if overall != DIRECTION_NEUTRAL:
            reasons.append(f"multi-timeframe direction={overall}")
        if exhaustion >= 60:
            reasons.append(f"exhaustion risk elevated ({exhaustion:.0f}/100)")
        if not reasons:
            reasons.append("insufficient multi-timeframe agreement")

        primary_count = setup.primary_count if setup is not None else ""
        alternative_count = setup.alternative_count if setup is not None else ""
        entry_score = float(wave_score)
        if nested_parent_w5 and setup is not None and setup.position == 3:
            entry_score += 8.0
        if setup is not None and setup.position == 5:
            entry_score -= min(25.0, float(setup.exhaustion_risk) * 0.25)
        entry_score = max(0.0, min(100.0, entry_score))
        operative_interval = entry_interval
        operative_parent_interval = entry_parent_interval
        return MultiTimeframeWaveReport(
            frames=dict(snapshots),
            overall_direction=overall,
            alignment_score=round(alignment, 2),
            wave_score=round(wave_score, 2),
            exhaustion_risk=round(exhaustion, 2),
            nested_w3=nested_w3,
            nested_w3_parent_w5=nested_parent_w5,
            nested_w3_count=nested_count,
            nested_w3_parent_positions=sorted(nested_parent_positions),
            nested_countertrend_impulse=nested_countertrend_impulse,
            nested_countertrend_count=nested_countertrend_count,
            wave_path=path,
            htf_confirmed=htf_confirmed,
            setup_position=int(setup.position if setup else 0),
            setup_phase=setup.phase if setup else WAVE_STATE_UNKNOWN,
            primary_count=primary_count,
            alternative_count=alternative_count,
            reason="; ".join(reasons),
            entry_interval=entry_interval,
            entry_position=entry_position,
            entry_parent_interval=entry_parent_interval,
            entry_parent_position=entry_parent_position,
            entry_allowed=entry_allowed,
            entry_block_reason=entry_block_reason,
            entry_score=round(entry_score, 2),
            operative_interval=operative_interval,
            operative_parent_interval=operative_parent_interval,
        )

    def analyse(
        self,
        symbol: str,
        cache: Optional[Mapping[str, pd.DataFrame]] = None,
        include_micro: Optional[bool] = None,
    ) -> MultiTimeframeWaveReport:
        cache = cache or {}
        snapshots: Dict[str, WaveSnapshot] = {}

        use_micro = (
            self.include_micro
            if include_micro is None
            else bool(include_micro)
        )
        analysis_intervals = list(self.intervals)
        if use_micro:
            for micro_interval in ("1s", "5s", "25s"):
                if micro_interval not in analysis_intervals:
                    analysis_intervals.append(micro_interval)
            analysis_intervals = sorted(
                analysis_intervals,
                key=lambda value: self.INTERVAL_SECONDS.get(value, 0),
                reverse=True,
            )

        micro_source: Optional[pd.DataFrame] = None

        for index, interval in enumerate(analysis_intervals):
            try:
                frame = cache.get(interval)

                if frame is None and use_micro and interval in {"1s", "5s", "25s"}:
                    if micro_source is None:
                        micro_source = self._fetch(
                            symbol,
                            "1s",
                            limit=1000,
                        )

                    if interval == "1s":
                        frame = micro_source
                    else:
                        frame = self._aggregate_seconds(
                            micro_source,
                            5 if interval == "5s" else 25,
                        )

                if frame is None:
                    frame = self._fetch(symbol, interval)

                degree = (
                    "HIGH" if index == 0 else
                    "MEDIUM_HIGH" if index == 1 else
                    "MEDIUM" if index == 2 else
                    "LOW"
                )
                snapshots[interval] = self.build_snapshot(interval, frame, degree)
            except Exception as exc:
                snapshots[interval] = WaveSnapshot(
                    interval=interval,
                    wave_degree="UNKNOWN",
                    data_ok=False,
                    reason=f"Wave analysis error: {exc}",
                )

        return self._build_report(snapshots)


__all__ = [
    "Pivot",
    "WaveSnapshot",
    "MultiTimeframeWaveReport",
    "MultiTimeframeWaveEngine",
    "WAVE_STATE_IMPULSE",
    "WAVE_STATE_CORRECTION",
    "WAVE_STATE_TRANSITION",
    "WAVE_STATE_UNKNOWN",
    "DIRECTION_UP",
    "DIRECTION_DOWN",
    "DIRECTION_NEUTRAL",
]