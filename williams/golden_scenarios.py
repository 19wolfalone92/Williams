"""Canonical golden-scenario registry for the Williams Intraday contract."""
from __future__ import annotations
from dataclasses import dataclass

@dataclass(frozen=True)
class GoldenScenario:
    id: int
    name: str
    expected: str

GOLDEN_SCENARIOS = (
    GoldenScenario(1, "WM1 -> WM2 -> WM3", "campaign steps 1,2,3"),
    GoldenScenario(2, "WM1 -> WM3", "campaign skips absent WM2 without inventing it"),
    GoldenScenario(3, "WM2 first", "WM2 becomes campaign step 1"),
    GoldenScenario(4, "WM3 first", "WM3 may become Core step 1"),
    GoldenScenario(5, "WM1 without angulation", "Core invalid"),
    GoldenScenario(6, "WM1 inside Alligator mouth", "Core invalid"),
    GoldenScenario(7, "Fractal initially invalid", "pending and not triggerable"),
    GoldenScenario(8, "Fractal -> Teeth becomes valid", "same pending signal can become triggerable"),
    GoldenScenario(9, "New fractal supersedes pending", "old PendingSignal -> SUPERSEDED"),
    GoldenScenario(10, "Equal highs/lows", "fractal detector remains valid when equality rules permit"),
    GoldenScenario(11, "Shared bars", "shared-bar fractal metadata retained"),
    GoldenScenario(12, "6-bar fractal", "extension metadata records 6"),
    GoldenScenario(13, "9-bar fractal", "extension metadata records 9"),
    GoldenScenario(14, "Overlapping fractals", "multiple live structures may coexist"),
    GoldenScenario(15, "Sleeping Alligator", "monitor WM1; no aggressive trend add-on"),
    GoldenScenario(16, "Awakening Alligator", "quality/context may improve; Core truth unchanged"),
    GoldenScenario(17, "Gap through trigger", "fill at executable open, not stale trigger"),
    GoldenScenario(18, "Gap through stop", "protective exit records gap path"),
    GoldenScenario(19, "Trigger + stop same candle", "ambiguous event is explicitly recorded"),
    GoldenScenario(20, "Campaign reaches EOD", "pending cancelled and position force-flat"),
    GoldenScenario(21, "H4 adverse + H1 pristine WM1", "Core remains valid in base profile"),
    GoldenScenario(22, "H4 supportive + H1 valid WM1", "Core valid; context may improve risk quality"),
    GoldenScenario(23, "Restart with pending WM1", "state restored and trigger remains durable"),
    GoldenScenario(24, "Restart with active campaign", "campaign restored before new mutations"),
    GoldenScenario(25, "Spot bearish signal while LONG", "no short; evaluate reduction/exit only"),
)

def validate_registry() -> None:
    ids = [x.id for x in GOLDEN_SCENARIOS]
    if ids != list(range(1, 26)):
        raise AssertionError("golden scenario registry must contain exactly 1..25")

validate_registry()