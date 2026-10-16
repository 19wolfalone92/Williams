"""Causal directional evidence for Binance USD-M Williams Futures candidates.

This is a bounded *ranking* overlay, not Bill Williams' original entry rule.
It cannot fabricate a Wise-Man signal, override exchange/risk gates, or close
positions. Percent retracement levels are observations, never mandatory gates.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Mapping


def _finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) else None


@dataclass(frozen=True)
class DirectionAssessment:
    side: str
    bias: str
    alignment: str
    score_adjustment: float
    active_wave: str
    wave_phase: str
    wave_alternative: str
    impulse_model: str
    correction_stage: str
    ao_divergence: str
    alligator_context: str
    ao_context: str
    ac_context: str
    htf_confirmed: bool
    reasons: tuple[str, ...]
    calibration_status: str = "HEURISTIC_UNCALIBRATED"
    can_create_entry_signal: bool = False
    can_place_orders: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


def _neutral(side: str, htf_confirmed: bool, reason: str) -> DirectionAssessment:
    return DirectionAssessment(
        side=side, bias="WAIT", alignment="UNKNOWN",
        score_adjustment=0.0, active_wave="?", wave_phase="UNKNOWN",
        wave_alternative="", impulse_model="UNCLASSIFIED",
        correction_stage="UNKNOWN", ao_divergence="NONE",
        alligator_context="UNKNOWN", ao_context="UNKNOWN",
        ac_context="UNKNOWN", htf_confirmed=bool(htf_confirmed),
        reasons=(reason,),
    )


def _recent_divergence(visual: dict, key: str, candle_count: int) -> bool:
    item = visual.get(key)
    if not isinstance(item, dict) or item.get("status") != "DETECTED":
        return False
    at = item.get("confirmed_through_index")
    if not isinstance(at, int) or at < 0 or at >= candle_count:
        return False
    # The screenshot-like divergence is near a trade decision. Ancient
    # divergences must not be treated as fresh reverse-order permission.
    return (candle_count - 1 - at) <= 24


def evaluate_direction(
    *,
    side: str,
    snapshot: Any,
    last_indicator: Mapping[str, Any],
    closed_bar_count: int,
    htf_confirmed: bool,
) -> DirectionAssessment:
    """Rate an existing LONG/SHORT *candidate*, not the possibility of entry.

    No signal type or trade execution action is accepted as an input: context
    alone must never manufacture an entry or trigger an opposing campaign.
    """
    side = str(side or "").upper()
    if side not in {"LONG", "SHORT"}:
        raise ValueError("side must be LONG or SHORT")
    if (snapshot is None or not bool(getattr(snapshot, "data_ok", False))
            or closed_bar_count < 140):
        return _neutral(side, htf_confirmed, "INSUFFICIENT_CONFIRMED_H1_STRUCTURE")

    direction = "UP" if side == "LONG" else "DOWN"
    opposite = "DOWN" if direction == "UP" else "UP"
    visual = getattr(snapshot, "structure", {}) or {}
    visual = visual.get("visual_wave_evidence") if isinstance(visual, dict) else None
    if not isinstance(visual, dict):
        return _neutral(side, htf_confirmed, "VISUAL_WAVE_EVIDENCE_UNAVAILABLE")

    indicator = last_indicator
    bullish = bool(indicator.get("bullish_alligator", False))
    bearish = bool(indicator.get("bearish_alligator", False))
    alligator = "BULLISH" if bullish and not bearish else (
        "BEARISH" if bearish and not bullish else "MIXED")
    ao = _finite(indicator.get("ao"))
    ac = _finite(indicator.get("ac"))
    ao_state = "POSITIVE" if ao is not None and ao > 0 else (
        "NEGATIVE" if ao is not None and ao < 0 else "NEUTRAL_OR_UNAVAILABLE")
    ac_state = "POSITIVE" if ac is not None and ac > 0 else (
        "NEGATIVE" if ac is not None and ac < 0 else "NEUTRAL_OR_UNAVAILABLE")

    score = 0.0
    reasons: list[str] = []

    if alligator == ("BULLISH" if direction == "UP" else "BEARISH"):
        score += 3.0
        reasons.append("H1_ALLIGATOR_SUPPORTS_SIDE")
    elif alligator in {"BULLISH", "BEARISH"}:
        score -= 3.0
        reasons.append("H1_ALLIGATOR_OPPOSES_SIDE")
    else:
        reasons.append("H1_ALLIGATOR_MIXED")
    if ao is not None and ao != 0:
        if (ao > 0) == (direction == "UP"):
            score += 1.0
            reasons.append("H1_AO_SIGN_SUPPORTS_SIDE")
        else:
            score -= 1.0
            reasons.append("H1_AO_SIGN_OPPOSES_SIDE")
    if ac is not None and ac != 0:
        if (ac > 0) == (direction == "UP"):
            score += 0.5
            reasons.append("H1_AC_SUPPORTS_SIDE")
        else:
            score -= 0.5
            reasons.append("H1_AC_OPPOSES_SIDE")

    phase = str(getattr(snapshot, "phase", "UNKNOWN") or "UNKNOWN")
    wave_direction = str(getattr(snapshot, "direction", "NEUTRAL") or "NEUTRAL")
    if phase == "IMPULSE" and wave_direction == direction:
        score += 1.0
        reasons.append("ESTIMATED_H1_IMPULSE_SUPPORTS_SIDE")
    elif phase == "IMPULSE" and wave_direction == opposite:
        score -= 1.0
        reasons.append("ESTIMATED_H1_IMPULSE_OPPOSES_SIDE")
    elif phase == "CORRECTION":
        reasons.append("ESTIMATED_H1_CORRECTION_NOT_A_TREND_REVERSAL")

    own_divergence_key = ("ao_divergence_bullish" if side == "LONG"
                          else "ao_divergence_bearish")
    contrary_key = ("ao_divergence_bearish" if side == "LONG"
                    else "ao_divergence_bullish")
    own_div = _recent_divergence(visual, own_divergence_key, closed_bar_count)
    contrary_div = _recent_divergence(visual, contrary_key, closed_bar_count)
    if own_div:
        score += 2.0
        reasons.append("RECENT_AO_DIVERGENCE_FAVORS_REVERSAL_SIDE_NOT_AN_ENTRY")
    if contrary_div:
        score -= 2.0
        reasons.append("RECENT_AO_DIVERGENCE_OPPOSES_SIDE_NOT_A_VETO")

    direction_key = "impulse_up" if side == "LONG" else "impulse_down"
    impulse = visual.get(direction_key) or {}
    if not isinstance(impulse, dict):
        impulse = {}
    model = str(impulse.get("model", "UNCLASSIFIED"))
    # Wave label 5 is structurally uncertain, so its exhaustion changes
    # rank slightly, never a guaranteed reversal or sell signal.
    if (wave_direction == direction
            and int(getattr(snapshot, "position", 0) or 0) == 5
            and (_finite(getattr(snapshot, "exhaustion_risk", 0)) or 0) >= 60):
        score -= 1.0
        reasons.append("ESTIMATED_W5_EXHAUSTION_CAUTION")

    correction = visual.get("impulse_correction_up" if side == "LONG"
                            else "impulse_correction_down") or {}
    if not isinstance(correction, dict):
        correction = {}
    correction_stage = str(correction.get("stage", "UNKNOWN"))
    if correction.get("status") == "STRUCTURAL_HYPOTHESIS":
        reasons.append("POST_IMPULSE_ABC_STAGE_IS_HYPOTHESIS_NOT_FORECAST")

    if htf_confirmed:
        score += 1.0
        reasons.append("D1_H4_DIRECTIONAL_CONTEXT_SUPPORTS_SIDE")
    else:
        reasons.append("D1_H4_CONTEXT_NOT_DIRECTIONALLY_CONFIRMED")

    score = round(max(-8.0, min(8.0, score)), 2)
    bias = "LONG" if score >= 2 else "SHORT" if score <= -2 and side == "LONG" else (
        "SHORT" if side == "SHORT" and score >= 2 else (
            "LONG" if side == "SHORT" and score <= -2 else "WAIT"
        )
    )
    alignment = "SUPPORTED" if score >= 2 else (
        "CONTRADICTED" if score <= -2 else "MIXED")
    return DirectionAssessment(
        side=side, bias=bias, alignment=alignment,
        score_adjustment=score,
        active_wave=str(getattr(snapshot, "wave_label", "?") or "?"),
        wave_phase=phase,
        wave_alternative=str(getattr(snapshot, "alternative_count", "") or ""),
        impulse_model=model, correction_stage=correction_stage,
        ao_divergence=("ALIGNED" if own_div else "OPPOSING" if contrary_div else "NONE"),
        alligator_context=alligator, ao_context=ao_state, ac_context=ac_state,
        htf_confirmed=bool(htf_confirmed), reasons=tuple(reasons),
    )
