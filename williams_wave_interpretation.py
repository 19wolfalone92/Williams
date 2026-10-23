"""Auditable degree-scoped interpretations, with no execution authority.

TC1 PDF61-65 and Course PDF34-46 distinguish a junior counterwave from
the whole parent movement. These predicates are an engineering interpretation
of those principles, not a unique Elliott count or a price prediction.
"""
from collections import Counter
from math import isfinite

from williams_wave_hierarchy import FRAME_MS

FRAMES = ("1d", "6h", "4h", "1h", "15m")
ACTIVE = {2:"W2",3:"W3",4:"W4",5:"W5",6:"A",7:"B",8:"C"}
CORRECTIVE = {"W2", "W4", "A", "C"}
LIVE_BREAKS = {"LIVE_PRICE_INVALIDATED_COUNT", "LOWER_FRAME_OBSERVED_INVALIDATION"}
EXPLANATIONS = {
    "JUNIOR_CORRECTION_CANDIDATE":"Младшая коррекция внутри действующего старшего импульса; старший разворот не подтверждён.",
    "IMPULSE_CONTINUATION_CANDIDATE":"Младший импульс согласован с действующей старшей волной; продолжение остаётся гипотезой.",
    "PARENT_CORRECTION_IN_PROGRESS_CANDIDATE":"Младший импульс развивается в направлении старшей коррекции; это не доказывает смену старшего тренда.",
    "OPPOSING_IMPULSE_UNRESOLVED":"Младший импульс направлен против старшей волны; её структурная отмена не наблюдается.",
    "SENIOR_REVERSAL_CANDIDATE":"Уровень отмены старшего незавершённого импульса нарушен; встречная младшая структура допускает старший разворот, но не доказывает его.",
    "ALTERNATIVES":"Допустимые разметки дают разные объяснения; однозначного вывода нет.",
    "UNRESOLVED":"Недостаточно совместимой структуры для различения коррекции, продолжения и старшего разворота.",
    "HISTORY_UNVERIFIED":"Нет свежих независимых закрытых свечей обоих масштабов; волновой вывод не подтверждён.",
}


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and isfinite(value)


def _fresh_frame(hierarchy, frame, as_of):
    if frame in hierarchy.get("frame_errors", {}):
        return False
    readings = [r for r in hierarchy.get("frame_readings", []) if r.get("frame") == frame]
    if len(readings) != 1:
        return False
    reading = readings[0]
    closed = reading.get("as_of_close_ms", 0)
    return (reading.get("state") == "OBSERVED" and _number(closed)
            and closed > 0 and 0 <= as_of-closed < FRAME_MS[frame])


def _causal(scenario, as_of):
    anchors = scenario.get("anchors", [])
    frame = scenario.get("frame")
    direction = scenario.get("direction")
    if frame not in FRAME_MS or direction not in {"UP", "DOWN"} or not 2 <= len(anchors) <= 8:
        return False
    active = ACTIVE[len(anchors)]
    expected_direction = ("DOWN" if direction == "UP" else "UP") if active in CORRECTIVE else direction
    if (scenario.get("active_label") != active or scenario.get("active_direction") != expected_direction
            or scenario.get("observed_at") != as_of):
        return False
    for i, anchor in enumerate(anchors):
        extreme, known, price = (anchor.get(k) for k in ("extreme_at", "known_at", "price"))
        kind = "LOW" if (i % 2 == 0) == (direction == "UP") else "HIGH"
        if (not all(_number(x) for x in (extreme, known, price)) or price <= 0 or extreme < 0
                or anchor.get("kind") != kind or known < extreme+FRAME_MS[frame] or known > as_of
                or i and extreme <= anchors[i-1]["extreme_at"]):
            return False
    low, high, invalidation = (scenario.get(k) for k in ("leg_low", "leg_high", "invalidation"))
    return (all(_number(v) and v > 0 for v in (low, high, invalidation)) and low <= high
            and scenario.get("leg_start") == anchors[-1]["extreme_at"])


def _broken(scenario):
    # A completed historical W5 cannot be retroactively invalidated by A.
    if len(scenario["anchors"]) >= 6:
        return False
    return (scenario["leg_low"] <= scenario["invalidation"] if scenario["direction"] == "UP"
            else scenario["leg_high"] >= scenario["invalidation"])


def _state(parent, child):
    parent_leg, child_leg = parent["active_label"], child["active_label"]
    child_impulse = child_leg in {"W3", "W5"} and child["active_direction"] == child["direction"]
    if parent["status"] == "INVALIDATED":
        return ("SENIOR_REVERSAL_CANDIDATE" if _broken(parent) and child_impulse
                and child["direction"] != parent["direction"] else "UNRESOLVED")
    if _broken(parent):
        return "UNRESOLVED"
    if parent_leg in {"W3", "W5"}:
        if child["direction"] == parent["direction"]:
            if child_leg in CORRECTIVE:
                return "JUNIOR_CORRECTION_CANDIDATE"
            if child_impulse:
                return "IMPULSE_CONTINUATION_CANDIDATE"
        if child_impulse and child["direction"] != parent["direction"]:
            return "OPPOSING_IMPULSE_UNRESOLVED"
    elif parent_leg in CORRECTIVE and child_impulse and child["direction"] == parent["active_direction"]:
        return "PARENT_CORRECTION_IN_PROGRESS_CANDIDATE"
    return "UNRESOLVED"


def wave_interpretation(hierarchy):
    """Recheck time AND price containment; never trust a pre-labelled edge.

    Unseen intrabar extreme order, missing/stale readings, duplicate IDs and
    conflicting counts retain uncertainty. No majority vote discards a count.
    """
    as_of = hierarchy.get("as_of", 0)
    all_scenarios = hierarchy.get("scenarios", [])
    ids = Counter(s.get("scenario_id") for s in all_scenarios)
    rejected = []
    usable = []
    if not _number(as_of) or as_of <= 0:
        as_of = 0
    for s in all_scenarios:
        identifier = s.get("scenario_id", "")
        reasons = set(s.get("invalid_reasons", []))
        status = s.get("status")
        if (not identifier or ids[identifier] != 1 or not _causal(s, as_of)
                or status not in {"PROVISIONAL", "INVALIDATED"}
                or status == "INVALIDATED" and (not reasons or not reasons <= LIVE_BREAKS)):
            rejected.append(identifier)
        else:
            usable.append(s)
    scopes = []
    for i, parent_frame in enumerate(FRAMES[:-1]):
        for child_frame in FRAMES[i+1:]:
            fresh = as_of > 0 and all(_fresh_frame(hierarchy, f, as_of) for f in (parent_frame, child_frame))
            relations = []
            if fresh:
                for parent in [s for s in usable if s["frame"] == parent_frame]:
                    boundary = max(parent["leg_start"]+FRAME_MS[parent_frame], parent.get("leg_observation_start", 0))
                    # A finer verified extreme timestamp can reduce the
                    # boundary, but a default zero cannot guess intrabar order.
                    if parent.get("leg_observation_start", 0) > parent["leg_start"]:
                        boundary = parent["leg_observation_start"]
                    for child in [s for s in usable if s["frame"] == child_frame and s["status"] == "PROVISIONAL"]:
                        if (child["anchors"][0]["extreme_at"] < boundary
                                or child["leg_low"] < parent["leg_low"] or child["leg_high"] > parent["leg_high"]
                                or any(not parent["leg_low"] <= a["price"] <= parent["leg_high"] for a in child["anchors"])):
                            continue
                        warnings = []
                        if child.get("divergence") and parent.get("w4_zero") != "MINIMUM_ONLY":
                            warnings.append("JUNIOR_DIVERGENCE_WITHOUT_PARENT_W4_ZERO_PROOF")
                        relations.append({
                            "parent":parent["scenario_id"], "child":child["scenario_id"],
                            "state":_state(parent, child), "parent_active_leg":parent["active_label"],
                            "child_active_leg":child["active_label"], "parent_motive_direction":parent["direction"],
                            "parent_active_direction":parent["active_direction"], "child_motive_direction":child["direction"],
                            "child_active_direction":child["active_direction"],
                            "active_directions_aligned":parent["active_direction"] == child["active_direction"],
                            "parent_invalidation":parent["invalidation"], "parent_count_broken":_broken(parent),
                            "parent_observation_start":boundary, "child_sequence_start":child["anchors"][0]["extreme_at"],
                            "anchors_known_at":max(a["known_at"] for s in (parent, child) for a in s["anchors"]),
                            "proof":"CAUSAL_ANCHORS_AND_FULL_OBSERVED_TIME_PRICE_CONTAINMENT",
                            "parent_w4_minimum_only":parent.get("w4_zero") == "MINIMUM_ONLY",
                            "parent_scale_100_140":100 <= parent.get("scale_bars", 0) <= 140,
                            "degree_warnings":warnings, "wave_genealogy_confirmed":False,
                            "can_place_orders":False, "can_close":False, "can_reverse":False,
                        })
            states = sorted({r["state"] for r in relations})
            relations.sort(key=lambda r:(r["parent"], r["child"]))
            state = ("HISTORY_UNVERIFIED" if not fresh else "UNRESOLVED" if not states
                     else states[0] if len(states) == 1 else "ALTERNATIVES")
            scopes.append({"parent_frame":parent_frame, "child_frame":child_frame, "state":state,
                "candidate_states":states, "relation_count":len(relations), "relations":relations,
                "explanation_ru":EXPLANATIONS[state], "wave_genealogy_confirmed":False})
    return {"schema":"WILLIAMS_WAVE_INTERPRETATION_V1", "as_of":as_of, "scopes":scopes,
        "rejected_scenario_ids":sorted(rejected), "source":"TC1_PDF61_65_COURSE_PDF34_46",
        "method":"EXPLICIT_ENGINEERING_PREDICATES_NOT_UNIQUE_ELLIOTT_COUNT",
        "wave_genealogy_confirmed":False, "forecast_accuracy_certified":False,
        "can_place_orders":False, "can_close":False, "can_reverse":False}
