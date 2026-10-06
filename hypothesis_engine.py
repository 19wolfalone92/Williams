"""Rule-based competing wave hypotheses.

These scores are relative evidence scores, not calibrated probabilities. They become
true probabilities only after supervised out-of-sample calibration on labelled data.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable

from market_context import WaveHypothesis


@dataclass(frozen=True)
class HypothesisSummary:
    hypotheses: tuple[WaveHypothesis, ...]
    primary: WaveHypothesis
    margin: float
    entropy: float
    decision: str


def _label_kind(label: str) -> str:
    value = str(label or "?").upper()
    for kind in ("W1", "W2", "W3", "W4", "W5"):
        if value.startswith(kind) or value.startswith(kind[1:]):
            return kind
    return value


def _softmax(scores: Iterable[float]) -> list[float]:
    values = [float(x) for x in scores]
    if not values:
        return []
    peak = max(values)
    exps = [math.exp(max(-20.0, min(20.0, x - peak))) for x in values]
    total = sum(exps)
    return [x / total for x in exps] if total > 0 else [1.0 / len(exps)] * len(exps)


def build_hypotheses(symbol: str, interval: str, snapshot, *, bullish: bool, bearish: bool) -> HypothesisSummary:
    direction = "LONG" if bullish else "SHORT" if bearish else "NEUTRAL"
    primary = str(getattr(snapshot, "primary_count", "") or getattr(snapshot, "wave_label", "?") or "?")
    alternative = str(getattr(snapshot, "alternative_count", "") or "").strip()
    abc = str(getattr(snapshot, "abc_phase", "") or "").strip()

    labels = []
    for label in (primary, alternative, abc):
        if label and label not in labels and label not in {"?", "NONE"}:
            labels.append(label)
    if not labels:
        labels = [primary or "?"]

    confidence = float(getattr(snapshot, "confidence", 0.0) or 0.0)
    structural = float(getattr(snapshot, "structural_confidence", 0.0) or 0.0)
    exhaustion = float(getattr(snapshot, "exhaustion_risk", 0.0) or 0.0)
    phase = str(getattr(snapshot, "phase", "") or "").upper()
    nested = bool(getattr(snapshot, "nested_w3", False))

    raw_scores = []
    for index, label in enumerate(labels):
        kind = _label_kind(label)
        evidence = 0.15 + 0.45 * min(1.0, confidence / 100.0)
        evidence += (0.25 if index == 0 else 0.12 if index == 1 else 0.05) * min(1.0, structural / 100.0)
        if kind == "W3":
            evidence += 0.18 if phase == "IMPULSE" else 0.08
            if nested:
                evidence += 0.08
        elif kind == "W5":
            evidence += 0.08 - 0.18 * min(1.0, exhaustion)
        elif label.upper().startswith(("A", "B", "C")) and phase == "CORRECTION":
            evidence += 0.04
        evidence -= 0.03 * index
        raw_scores.append(evidence)

    probs = _softmax(raw_scores)
    hypotheses = [
        WaveHypothesis(
            hypothesis_id=f"{symbol.upper()}:{interval.lower()}:{i}:{label}",
            label=label,
            direction=direction,
            probability=round(probability, 8),
            secondary_probability=0.0,
            invalidation_level=float(getattr(snapshot, "invalidation_price", 0.0) or 0.0),
            confidence=round(probability, 8),
            exhaustion_risk=exhaustion,
            source="rule_relative_uncalibrated",
        )
        for i, (label, probability) in enumerate(zip(labels, probs))
    ]

    ordered = sorted(hypotheses, key=lambda x: x.probability, reverse=True)
    second_prob = ordered[1].probability if len(ordered) > 1 else 0.0
    adjusted = [
        WaveHypothesis(
            hypothesis_id=item.hypothesis_id,
            label=item.label,
            direction=item.direction,
            probability=item.probability,
            secondary_probability=second_prob,
            invalidation_level=item.invalidation_level,
            target_level=item.target_level,
            confidence=item.confidence,
            exhaustion_risk=item.exhaustion_risk,
            source=item.source,
        )
        for item in hypotheses
    ]
    ordered = sorted(adjusted, key=lambda x: x.probability, reverse=True)

    entropy = 0.0
    if len(ordered) > 1:
        entropy = -sum(
            max(1e-12, h.probability) * math.log(max(1e-12, h.probability))
            for h in ordered
        ) / math.log(len(ordered))

    primary_h = ordered[0]
    margin = primary_h.probability - second_prob
    decision = (
        "UNCERTAIN"
        if primary_h.probability < 0.60 or margin < 0.15 or entropy > 0.80
        else direction if direction != "NEUTRAL" else "NO_TRADE"
    )
    return HypothesisSummary(tuple(ordered), primary_h, margin, entropy, decision)
