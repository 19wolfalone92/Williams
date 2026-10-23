"""Read-only TC1 five Magic Bullets at a *specific* verified wave degree.

Source: Trading Chaos 1st ed. Russian PDF53-54, PDF82-83.
Five signs: MFI divergence, wave price target zone, terminal fractal,
Squat in three actual extreme bars and Profitunity 5/34/5 momentum turn.
MFI (market facilitation index) must not silently become Course AO.
Binance base volume is a proxy, not source tick-volume MFI.

No action is authorized. Historical wave label remains a hypothesis.
"""

from __future__ import annotations

_ALLOWED_MFI_PROVENANCE = {
    "EXCHANGE_VERIFIED_CANONICAL_MFI",
    "MANUALLY_VERIFIED_TC1_CHART",
}

def tc1_five_bullet_evidence(scenario: dict, verified_mfi_divergence=None, mfi_source=None) -> dict:
    w5 = scenario.get("label") == "W5" and scenario.get("status") == "PROVISIONAL"
    verified = verified_mfi_divergence is True and mfi_source in _ALLOWED_MFI_PROVENANCE
    def check(key: str, positive: str) -> str:
        return positive if scenario.get(key) is True else "NOT_OBSERVED"

    checks = {
        "mfi_divergence": "VERIFIED_EXTERNAL_MFI" if verified else "NOT_VERIFIED",
        "target_zone": check("target_zone", "NUMERICAL_PROXY"),
        "terminal_fractal": check("terminal_fractal", "CONFIRMED_PRICE_PIVOT"),
        "extreme_squat": check("squat", "BASE_VOLUME_PROXY_ONLY"),
        "profitunity_macd_momentum_turn": check("momentum_shift", "AO_SIGNAL_PROXY_ONLY"),
    }
    observed = sum(value not in ("NOT_VERIFIED", "NOT_OBSERVED") for value in checks.values())
    try:
        scale_bars = int(scenario.get("scale_bars", 0))
        observed_at = int(scenario.get("observed_at", 0))
        anchors = scenario.get("anchors")
        known_at = int(anchors[5].get("known_at", 0)) if isinstance(anchors, list) and len(anchors) >= 6 else 0
    except (TypeError, ValueError, OverflowError, AttributeError, IndexError):
        scale_bars, observed_at, known_at = 0, 0, 0
    chronological = known_at > 0 and observed_at >= known_at
    return {
        "source":"TC1_CH7_PDF53_AND_CH9_PDF82_83",
        "status": ("NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT" if not w5
                   else "MFI_DIVERGENCE_UNVERIFIED" if not verified
                   else "TC1_FIVE_BULLETS_INCOMPLETE" if observed < 5
                   else "FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE"),
        "checks":checks,
        "observed_or_proxy_count":observed,
        "required_count":5,
        "mfi_source":mfi_source if verified else None,
        "tc1_mfi_distinct_from_course_ao":True,
        "wave_scale_100_140_observed":100 <= scale_bars <= 140,
        "wave_scale_bars":scale_bars,
        "last_w5_pivot_causally_known":chronological,
        "w5_known_at":known_at if chronological else None,
        "point_zero_certified":False,
        "book_bullets_vs_proxies":"TC1_MFI_AND_PROFITUNITY_MACD_NOT_AUTO_EQUALS_AO_AND_BASE_VOLUME",
        "this_is_not_course_four_sign_check":True,
        "wave_degree_confirmed":False,
        "can_place_orders":False,"can_close":False,"can_reverse":False,
    }
