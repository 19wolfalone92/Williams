"""Canonical H1 Williams signal adapter.

All truth is delegated to williams.core.WilliamsCore. This module only
translates deterministic Core signals into the durable SignalSpec contract
used by campaign execution. M15/M5 are never consulted here.
"""
from __future__ import annotations
import pandas as pd
from campaign_model import SignalRole, SignalSpec, SignalType
from williams.core import WilliamsCore
from williams.wm1 import measure_angulation


def _tick_buffer(tick_size: float, ticks: int = 1) -> float:
    return max(float(tick_size), 0.0) * max(int(ticks), 1)


def _row_time_ms(row: pd.Series) -> int:
    for key in ("open_time_ms", "time_ms", "timestamp", "time", "open_time"):
        value = row.get(key)
        if value is not None and not pd.isna(value):
            try:
                return int(value)
            except (TypeError, ValueError):
                pass
    idx = getattr(row, "name", 0)
    try:
        return int(idx.value // 1_000_000)
    except Exception:
        return int(idx) if isinstance(idx, (int, float)) else 0


def _angulation(ind: pd.DataFrame, index: int, *, window: int = 5) -> tuple[float, bool]:
    a = measure_angulation(ind, index, "LONG", window)
    return max(0.0, float(a.angular_separation)), bool(a.valid)


def extract_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    timeframe: str = "1h",
    tick_size: float = 0.0,
    h4_context: str = "NEUTRAL",
    htf_confirmed: bool = False,
    wave_confidence: float = 0.0,
    wave_exhaustion_risk: float = 0.0,
    wave_invalidation_price: float = 0.0,
    context_versions: dict[str, int] | None = None,
    campaign_id: str = "",
    max_reversal_age_bars: int = 20,
) -> list[SignalSpec]:
    if str(timeframe).lower() != "1h":
        return []
    core = WilliamsCore(outside_atr_mult=0.10, angulation_window=5)
    evaluation = core.evaluate(
        ind,
        symbol=symbol,
        tick_size=tick_size,
        h4_context=h4_context,
        campaign_id=campaign_id,
    )
    role = SignalRole.ADD_ON if campaign_id else SignalRole.ENTRY
    out: list[SignalSpec] = []
    for base in evaluation.signals:
        out.append(
            SignalSpec.new(
                symbol=base.symbol,
                side=base.side,
                signal_type=base.signal_type,
                role=role,
                timeframe="1h",
                signal_bar_time_ms=base.signal_bar_time_ms,
                trigger_price=base.trigger_price,
                protective_reference=base.protective_reference,
                trigger_buffer_ticks=base.trigger_buffer_ticks,
                invalidation_price=float(wave_invalidation_price or base.invalidation_price or base.protective_reference),
                teeth_at_detection=base.teeth_at_detection,
                alligator_bullish=base.alligator_bullish,
                alligator_awake=base.alligator_awake,
                angulation_score=base.angulation_score,
                wave_confidence=float(wave_confidence),
                wave_exhaustion_risk=float(wave_exhaustion_risk),
                htf_confirmed=bool(htf_confirmed),
                context_versions=dict(context_versions or base.context_versions),
                reason=f"H1 Williams Core {base.signal_type.value}; H4={str(h4_context).upper()}",
                source_candle_index=base.source_candle_index,
            )
        )
    unique = {(x.signal_type.value, x.signal_bar_time_ms): x for x in out}
    priority = {SignalType.REVERSAL.value: 0, SignalType.SUPER_AO.value: 1, SignalType.FRACTAL.value: 2}
    return sorted(unique.values(), key=lambda x: (x.signal_bar_time_ms, priority.get(x.signal_type.value, 9)))


def extract_long_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    timeframe: str,
    tick_size: float,
    htf_confirmed: bool = False,
    wave_confidence: float = 0.0,
    wave_exhaustion_risk: float = 0.0,
    wave_invalidation_price: float = 0.0,
    context_versions: dict[str, int] | None = None,
    max_reversal_age_bars: int = 20,
    campaign_id: str = "",
    h4_context: str = "NEUTRAL",
) -> list[SignalSpec]:
    return extract_signal_specs(
        symbol,
        ind,
        timeframe=timeframe,
        tick_size=tick_size,
        h4_context=h4_context,
        htf_confirmed=htf_confirmed,
        wave_confidence=wave_confidence,
        wave_exhaustion_risk=wave_exhaustion_risk,
        wave_invalidation_price=wave_invalidation_price,
        context_versions=context_versions,
        campaign_id=campaign_id,
        max_reversal_age_bars=max_reversal_age_bars,
    )


def extract_short_signal_specs(symbol: str, ind: pd.DataFrame, *, timeframe: str, tick_size: float, context_versions: dict[str, int] | None = None) -> list[SignalSpec]:
    """Mirrored diagnostics are intentionally not armable in Spot production."""
    return []
