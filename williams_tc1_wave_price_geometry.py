"""Causal, read-only TC1 ch7 wave price geometry, Russian PDF pp52–55.

The author describes TYPICAL ratios, never mandatory TC2 trade rules.
All Elliott degrees/internal A-B-C subdivisions stay unverified. This module
uses only already confirmed alternating pivots and has NO order authority.
Mirrors the bounded Android observer WilliamsTc1WavePriceGeometry.kt.
"""
from __future__ import annotations

from math import isfinite

FRAMES = {
    "1m": 60_000, "3m": 180_000, "5m": 300_000, "15m": 900_000,
    "30m": 1_800_000, "1h": 3_600_000, "2h": 7_200_000,
    "4h": 14_400_000, "6h": 21_600_000, "8h": 28_800_000,
    "12h": 43_200_000, "1d": 86_400_000, "1w": 604_800_000,
}


def _band(value: float, low: float, high: float) -> str:
    if value < low - 1e-9:
        return "BELOW_TYPICAL_BOOK_BAND"
    if value > high + 1e-9:
        return "ABOVE_TYPICAL_BOOK_BAND"
    return "WITHIN_TYPICAL_BOOK_BAND"


def observe_tc1_wave_price_geometry(scenario: dict) -> dict:
    """Read a provisional, causally confirmed wave scenario, never authorize trades."""
    r = {
        "schema": "TC1_CH7_FIBONACCI_WAVE_PRICE_EVIDENCE_V1",
        "reference": "TC1_RU_PDF_52_54_PRINTED_52_54",
        "source_authority": "BOOK_TYPICAL_RANGES_NOT_INVIOLABLE_TRADE_RULES",
        "status": "UNVERIFIED_WAVE_ANCHORS",
        "wave_degree_proven": False, "internal_wave_subdivisions_proven": False,
        "is_tc2_entry_gate": False, "can_place_orders": False,
        "can_move_stop": False, "can_close": False,
        "w2_retracement": {"status": "NOT_OBSERVED"},
        "w3_extension": {"status": "NOT_OBSERVED"},
        "w4_retracement": {"status": "NOT_OBSERVED"},
        "w5_projection": {"status": "W4_NOT_CONFIRMED"},
        "abc_b_retracement": {"status": "NO_CONFIRMED_CORRECTIVE_A_B"},
        "wave_alternation": {"status": "INTERNAL_W2_W4_TYPES_UNVERIFIED"},
    }
    if not isinstance(scenario, dict):
        return r
    frame, direction, status = (
        scenario.get("frame"), scenario.get("direction"), scenario.get("status"))
    anchors = scenario.get("anchors")
    observed = scenario.get("observed_at")
    if (frame not in FRAMES or direction not in {"UP", "DOWN"} or
        status != "PROVISIONAL" or
        type(observed) is not int or observed <= 0 or
        not isinstance(anchors, list) or len(anchors) not in range(3, 9)):
        return r
    points = []
    first = "LOW" if direction == "UP" else "HIGH"
    for i, p in enumerate(anchors):
        if not isinstance(p, dict):
            return r
        expected = first if i % 2 == 0 else (
            "HIGH" if first == "LOW" else "LOW")
        try:
            price = float(p["price"])
        except (TypeError, ValueError, OverflowError, KeyError):
            return r
        t, known = p.get("extreme_at"), p.get("known_at")
        if (p.get("kind") != expected or not isfinite(price) or price <= 0 or
            type(t) is not int or type(known) is not int or
            t < 0 or known < t or known > observed or t % FRAMES[frame]):
            return r
        points.append((price, t, known))
    if any(a[1] >= b[1] or a[2] > b[2]
           for a, b in zip(points, points[1:])):
        return r
    sign = 1.0 if direction == "UP" else -1.0
    values = [sign * p[0] for p in points]
    one, two = values[1] - values[0], values[1] - values[2]
    if one <= 0 or two < 0 or values[2] <= values[0]:
        return r
    r.update(status="BOUNDED_PRICE_EVIDENCE", frame=frame, direction=direction,
             observed_at_ms=observed, last_confirmed_anchor_ms=points[-1][2],
             confirmed_anchor_count=len(points))
    w2 = two / one
    r["w2_retracement"] = dict(
        status=_band(w2, .38, .62), fraction=w2,
        band_min=.38, band_max=.62, first_wave_price_distance=one,
        hard_trade_veto=False,
        explanation_ru=("Волна 2 откатила %.2f%% длины предполагаемой волны 1. "
                        "По TC1 наиболее типична область 38–62%%, но это "
                        "статистическое описание, а не обязательное условие входа.") % (w2*100))
    if len(points) >= 4:
        third = values[3] - values[2]
        if third <= 0 or values[3] <= values[1]:
            r["status"] = "MOTIVE_PRICE_SEQUENCE_CONFLICT"
            return r
        w3 = third / one
        r["w3_extension"] = dict(
            status=_band(w3, 1., 1.62), fraction=w3,
            band_min=1., band_max=1.62,
            projected_low_from_w2=points[2][0] + sign*one,
            projected_high_from_w2=points[2][0] + sign*1.62*one,
            w3_confirmed_price=points[3][0], hard_trade_veto=False,
            explanation_ru=("Предполагаемая волна 3 прошла %.2f длины волны 1. "
                            "В книге описана ориентировочная область 1,00–1,62; "
                            "выход из неё не доказывает ошибочную разметку.") % w3)
        if len(points) >= 5:
            fourth = values[3] - values[4]
            if fourth < 0 or values[4] <= values[2]:
                r["status"] = "MOTIVE_PRICE_SEQUENCE_CONFLICT"
                return r
            w4 = fourth / third
            overlap = values[4] <= values[1]
            r["w4_retracement"] = dict(
                status=_band(w4, .38, .50), fraction=w4,
                band_min=.38, band_max=.50,
                overlaps_w1_price_territory=overlap,
                ordinary_impulse_uncertain_if_overlap=overlap,
                diagonal_or_alternative_count_requires_review=overlap,
                hard_trade_veto=False,
                explanation_ru=(
                    "Предполагаемая волна 4 откатила %.2f%% волны 3; "
                    "типичная область по TC1 — 38–50%%. " % (w4*100) +
                    ("Цена пересекла территорию волны 1: обычный импульс под "
                     "вопросом, возможна диагональ или другая разметка."
                     if overlap else
                     "Перекрытия с территорией волны 1 по этим экстремумам не обнаружено.")))
            r["w5_projection"] = dict(
                status="W4_PIVOT_CONFIRMED_PROJECTION_BASE_ONLY",
                origin_confirmed_at_ms=points[4][2],
                origin_price=points[4][0], price_target_not_invented=True,
                explanation_ru="Точка отсчёта возможной волны 5 известна после подтверждения завершения волны 4. Книга не даёт права выводить универсальную цель по одним пяти экстремумам.")
    if len(points) >= 8:
        a, b = values[5]-values[6], values[7]-values[6]
        if a > 0 and b > 0:
            ba = b / a
            zone = .50-1e-9 <= ba <= .62+1e-9
            r["abc_b_retracement"] = dict(
                status=("ZIGZAG_B_50_62_PRICE_ZONE_ONLY" if zone else
                        "DEEP_B_MAY_BE_FLAT_OR_ALTERNATIVE_COUNT" if ba >= .90 else
                        "OUTSIDE_TYPICAL_ZIGZAG_B_ZONE"),
                b_over_a=ba, source_book_zone_min=.50, source_book_zone_max=.62,
                zigzag_5_3_5_internal_degree_verified=False,
                fractal_and_squat_confirmed_here=False,
                c_wave_confirmed=False, can_enter_c_wave=False,
                explanation_ru=("Предполагаемая B-волна откатила %.2f%% движения A. "
                                "TC1 выделяет 50–62%% для поиска отдельного фрактала "
                                "и «приседающего»; без подтверждения внутренних "
                                "подволн это не сигнал к торговле C.") % (ba*100))
    return r
