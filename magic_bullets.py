"""Williams five-magic-bullets diagnostics.

This module deliberately does not authorize orders. The five bullets are a
trend-exhaustion / Point-Zero framework, not a substitute for the Wise-Men
entry sequence.

Canonical book-era bullets:
1. price/AO divergence inside the wave;
2. price in the Elliott target zone;
3. terminal fractal;
4. squat bar among the terminal top/bottom three bars;
5. momentum change (AO colour change).

Modern Profitunity material later describes bullet #2 using a Wise Man
bullish/bearish divergent bar. The caller can therefore supply either the
book target-zone result or the modern replacement explicitly.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class MagicBulletState:
    divergence: bool = False
    target_zone: bool = False
    terminal_fractal: bool = False
    terminal_squat: bool = False
    momentum_change: bool = False
    target_zone_source: str = "BOOK"
    notes: tuple[str, ...] = ()

    @property
    def count(self) -> int:
        return sum(
            (
                self.divergence,
                self.target_zone,
                self.terminal_fractal,
                self.terminal_squat,
                self.momentum_change,
            )
        )

    @property
    def all_five(self) -> bool:
        return self.count == 5


def target_zone(
    *,
    side: str,
    wave1_start: float,
    wave3_end: float,
    wave4_end: float,
    minimum: float = 0.62,
    maximum: float = 1.00,
) -> tuple[float, float]:
    """Return the canonical Wave-5 target-zone bounds."""
    side = str(side).upper()
    distance = abs(float(wave3_end) - float(wave1_start))
    lo = min(float(minimum), float(maximum))
    hi = max(float(minimum), float(maximum))
    if distance <= 0:
        raise ValueError("wave 1→3 distance must be positive")
    if side == "LONG":
        return (
            float(wave4_end) + distance * lo,
            float(wave4_end) + distance * hi,
        )
    if side == "SHORT":
        return (
            float(wave4_end) - distance * hi,
            float(wave4_end) - distance * lo,
        )
    raise ValueError("side must be LONG or SHORT")


def price_in_target_zone(
    price: float,
    *,
    side: str,
    wave1_start: float,
    wave3_end: float,
    wave4_end: float,
) -> bool:
    lo, hi = target_zone(
        side=side,
        wave1_start=wave1_start,
        wave3_end=wave3_end,
        wave4_end=wave4_end,
    )
    return lo <= float(price) <= hi


def ao_price_divergence(
    *,
    side: str,
    wave3_price: float,
    wave5_price: float,
    wave3_ao: float,
    wave5_ao: float,
) -> bool:
    """Williams' Wave-3/Wave-5 price-vs-AO divergence test."""
    side = str(side).upper()
    if side == "LONG":
        return float(wave5_price) > float(wave3_price) and float(wave5_ao) < float(wave3_ao)
    if side == "SHORT":
        return float(wave5_price) < float(wave3_price) and float(wave5_ao) > float(wave3_ao)
    raise ValueError("side must be LONG or SHORT")


def terminal_squat(
    *,
    candidate_index: int,
    terminal_extreme_index: int,
    squat: bool,
    bars_from_terminal: int = 2,
) -> bool:
    """A squat qualifies when it is within the terminal top/bottom three bars."""
    return bool(
        squat
        and 0 <= int(terminal_extreme_index) - int(candidate_index) <= int(bars_from_terminal)
    )


def momentum_change(*, previous_ao_color: str, current_ao_color: str) -> bool:
    previous = str(previous_ao_color).upper()
    current = str(current_ao_color).upper()
    return previous in {"GREEN", "RED"} and current in {"GREEN", "RED"} and previous != current


def evaluate_magic_bullets(
    *,
    divergence: bool,
    target_zone_hit: bool,
    terminal_fractal: bool,
    terminal_squat_hit: bool,
    momentum_change_hit: bool,
    target_zone_source: str = "BOOK",
    notes: Optional[list[str]] = None,
) -> MagicBulletState:
    return MagicBulletState(
        divergence=bool(divergence),
        target_zone=bool(target_zone_hit),
        terminal_fractal=bool(terminal_fractal),
        terminal_squat=bool(terminal_squat_hit),
        momentum_change=bool(momentum_change_hit),
        target_zone_source=str(target_zone_source).upper(),
        notes=tuple(notes or ()),
    )
