"""Course PDF80-84 terminal and failed-count rules, without order authority.

Numerical conditions and provisional volume/oscillator evidence are not a
certificate of the unique Elliott count. Only the owning execution door can
act, after signed position/quantity/protection checks and fresh rederivation.
"""
from math import isfinite

from williams_course_location import _anchors, course_four_sign_evidence, H1

TERMINAL_REASONS = frozenset({"COURSE_W5_TERMINAL_CONFIRMED", "COURSE_C_TERMINAL_CONFIRMED"})
OPPOSITE_REASONS = frozenset({"COURSE_OPPOSITE_FORMATION_TRIGGERED", "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
                              "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"})
FAILURE_REASONS = frozenset({"COURSE_W3_STALL_INVALIDATED", "COURSE_W5_STALL_INVALIDATED",
                             "COURSE_CORRECTION_INVALIDATED"})
BOOK_EXIT_REASONS = TERMINAL_REASONS | OPPOSITE_REASONS | FAILURE_REASONS | {
    "COURSE_STAGE_TRIM", "COURSE_HIGHER_FRAME_AIRBAG_TRIGGERED"}


def _source_anchors(s, as_of):
    # A real boundary breach can invalidate the *current* count. Its earlier
    # confirmed pivots remain evidence for the source's failed-count exit.
    if (s.get("status") == "INVALIDATED" and s.get("invalid_reasons") and
            set(s["invalid_reasons"]) <= {"LIVE_PRICE_INVALIDATED_COUNT"}):
        return _anchors(dict(s, status="PROVISIONAL"), as_of)
    return _anchors(s, as_of)


def _terminal(s, as_of):
    a = _source_anchors(s, as_of)
    if not a or len(a) < 6:
        return None
    frozen = dict(s, anchors=a[:6], label="W5", status="PROVISIONAL")
    return a[:6] if course_four_sign_evidence(frozen, as_of)["status"] == "FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED" else None


def course_exit_plan(hierarchy, side, origin_at, stage, opened_at, now_ms, bars, contract, correction=None):
    """Read-only exact-source terminal/failure candidate; never a bare label.

    The five-hour observation-age bound is project policy. Junior evidence may
    use any available actual frame; M15 confirmation is not required.
    """
    out = {"status": "BLOCKED", "blocker": "COURSE_EXIT_OWNER_OR_HISTORY_UNVERIFIED",
           "can_place_orders": False, "can_close": False, "can_reverse": False,
           "wave_degree_confirmed": False}
    as_of = hierarchy.get("as_of", 0)
    errors = hierarchy.get("frame_errors", {})
    sign = 1 if side in ("LONG", "BUY") else -1 if side in ("SHORT", "SELL") else 0
    if (not sign or not 0 < origin_at < opened_at <= now_ms or not 0 < as_of <= now_ms or
            now_ms-as_of >= H1 or "1h" in errors or not isfinite(contract) or contract <= 0):
        return out
    bars = [b for b in bars if b.t+H1 <= as_of]
    if (len(bars)<45 or now_ms-(bars[-1].t+H1) not in range(H1) or
            any(b.t<=0 or not all(isfinite(v) and v>0 for v in (b.o,b.h,b.l,b.c)) or
                b.h<max(b.o,b.c,b.l) or b.l>min(b.o,b.c) for b in bars) or
            any(y.t-x.t!=H1 for x,y in zip(bars,bars[1:]))):
        return dict(out,blocker="COURSE_EXIT_CLOSED_H1_HISTORY_UNVERIFIED")
    correction = correction or {}
    corrective = stage in {"A_ENTRY", "A_REDUCE", "C_ADD"}
    motive_direction = "DOWN" if sign == 1 else "UP"
    direction = "UP" if sign == 1 else "DOWN"
    wanted = motive_direction if corrective else direction
    candidates = []
    for s in hierarchy.get("scenarios", []):
        a = _source_anchors(s, as_of)
        if (a and s.get("frame") == "1h" and s.get("direction") == wanted and
                a[0]["extreme_at"] == origin_at):
            candidates.append((s, a))
    if len(candidates) != 1:
        out["blocker"] = "COURSE_EXIT_PINNED_PARENT_AMBIGUOUS_OR_MISSING"
        return out
    parent, a = candidates[0]

    def observed(reason, known, end, *, child=None, boundary=None):
        if (not opened_at < known <= as_of or end["extreme_at"] < opened_at or
                (reason in TERMINAL_REASONS and now_ms-known > 18_000_000)):
            return dict(out, blocker="COURSE_EXIT_PREENTRY_OR_STALE_EVIDENCE")
        proof = dict(out, status="OBSERVED_COURSE_SOURCE_RULE", blocker=None, reason=reason,
                     origin_at=origin_at, signal_close_ms=known, parent_id=parent.get("scenario_id", ""),
                     terminal_id=(child or {}).get("scenario_id", ""),
                     source="COURSE_PDF80_84", trigger_price_basis="CONTRACT_PRICE")
        proof["rule_id"] = f"COURSE:{reason}:{origin_at}:{end['extreme_at']}:{known}"
        if boundary is not None:proof["boundary"] = boundary
        return proof

    if corrective:
        if (not correction.get("motive_campaign_id") or
                not 0 < correction.get("flat_confirmed_at_ms", 0) < opened_at or len(a) < 6):
            return dict(out, blocker="COURSE_CORRECTION_FLAT_HANDOFF_UNVERIFIED")
        extreme = a[5]["price"]
        tick = correction.get("tick", 0)
        if not isinstance(tick, (int, float)) or not isfinite(tick) or tick <= 0:
            return dict(out, blocker="COURSE_CORRECTION_TICK_UNVERIFIED")
        boundary = extreme-sign*tick
        if sign*(contract-boundary) <= 0:
            # This is the specifically pinned original motive extreme, not
            # a generic correction target range or a changed wave label.
            proof = dict(out, status="OBSERVED_COURSE_SOURCE_RULE", blocker=None,
                         reason="COURSE_CORRECTION_INVALIDATED", origin_at=origin_at,
                         source_known_at_ms=a[5]["known_at"], boundary=boundary, source="COURSE_PDF83_84",
                         trigger_price_basis="CONTRACT_PRICE",
                         rule_id=f"COURSE:COURSE_CORRECTION_INVALIDATED:{origin_at}:{a[5]['extreme_at']}")
            return proof
    # Course PDF80-81: a young W3 must first reach W1's length, then stall
    # before the 110% expansion and actually fall through the old W1 top.
    if stage == "W3" and len(a) == 3:
        rows = [b for b in bars if b.t+H1 <= as_of and b.t >= a[2]["extreme_at"]]
        w1 = sign*(a[1]["price"]-a[0]["price"])
        if rows and w1 > 0:
            peak = max(sign*(b.h if sign == 1 else b.l) for b in rows)
            ratio = (peak-sign*a[2]["price"])/w1
            reached = next((b for b in rows if sign*((b.h if sign == 1 else b.l)-a[2]["price"]) >= w1), None)
            if reached is not None and 1 <= ratio < 1.1 and sign*(contract-a[1]["price"]) <= 0:
                return observed("COURSE_W3_STALL_INVALIDATED", reached.t+H1,
                                {"extreme_at":reached.t}, boundary=a[1]["price"])
    # Course PDF82: internal W3-of-W5 reached, then W1-of-W5 is broken.
    if stage == "W5" and len(a) >= 5:
        from williams_wave_hierarchy import FRAME_MS
        children = []
        for s in hierarchy.get("scenarios", []):
            c = _source_anchors(s, as_of)
            if (c and len(c) == 4 and s.get("frame") not in errors and FRAME_MS.get(s.get("frame"),H1+1)<=H1 and s.get("direction") == direction and
                    c[0]["extreme_at"] == a[4]["extreme_at"] and c[0]["price"] == a[4]["price"] and
                    sign*(contract-c[1]["price"]) <= 0):
                children.append((s,c))
        if len(children) == 1:
            s,c = children[0]
            return observed("COURSE_W5_STALL_INVALIDATED", c[3]["known_at"], c[3], child=s, boundary=c[1]["price"])
        if len(children) > 1:return dict(out, blocker="COURSE_EXIT_INTERNAL_DEGREE_AMBIGUOUS")
    # Full motive ends at W5; a corrective owner ends at C's internal fifth.
    if stage == "W5" and len(a) >= 6:
        if _terminal(parent, as_of) is None:
            return dict(out, blocker="COURSE_W5_TERMINAL_SIGNS_INCOMPLETE")
        start, end = a[4:6]
        reason = "COURSE_W5_TERMINAL_CONFIRMED"
    elif stage == "C_ADD" and len(a) >= 9:
        start, end = a[7:9]
        reason = "COURSE_C_TERMINAL_CONFIRMED"
    else:
        return dict(out, blocker="COURSE_TERMINAL_STAGE_NOT_OBSERVED")
    from williams_wave_hierarchy import FRAME_MS
    children = []
    for s in hierarchy.get("scenarios", []):
        c = _terminal(s, as_of)
        if (c and s.get("frame") not in errors and FRAME_MS.get(s.get("frame"), H1+1) <= H1 and
                s.get("direction") == direction and c[0]["extreme_at"] == start["extreme_at"] and
                c[-1]["extreme_at"] == end["extreme_at"] and c[0]["price"] == start["price"] and c[-1]["price"] == end["price"]):
            children.append((s,c))
    if len(children) != 1:return dict(out, blocker="COURSE_TERMINAL_SUBDIVISION_AMBIGUOUS_OR_MISSING")
    child,c = children[0]
    return observed(reason, max(end["known_at"],c[-1]["known_at"]), end, child=child)


def course_exit_proof_blocker(proof, owner_id, symbol, side, reason, opened_at, now_ms):
    """Bind durable evidence to the exact owner before fresh source rederivation."""
    if (not isinstance(proof, dict) or not owner_id or proof.get("owner_id") != owner_id or
            proof.get("symbol") != symbol or proof.get("side") != side or proof.get("reason") != reason or
            reason not in BOOK_EXIT_REASONS or proof.get("trigger_price_basis") != "CONTRACT_PRICE"):
        return "COURSE_EXIT_DURABLE_OWNER_OR_RULE_UNVERIFIED"
    observed = proof.get("observed_at_ms", 0)
    if not 0 < opened_at <= observed <= now_ms or now_ms-observed > 120_000:
        return "COURSE_EXIT_DURABLE_EVIDENCE_STALE"
    return None


def course_closed_motive_handoff(owner_id, side, origin_at, reason, flat_confirmed_at, *,
                                  signed_exit_filled, signed_flat, orders_clean):
    """A separate corrective owner may be proposed only AFTER verified FLAT.

    A valid handoff never bypasses a new source/risk/precision/protection gate.
    It is not a permission to reverse through a single oversized market order.
    """
    if (not owner_id or side not in ("LONG", "SHORT", "BUY", "SELL") or
            not 0 < origin_at < flat_confirmed_at or reason != "COURSE_W5_TERMINAL_CONFIRMED" or
            not signed_exit_filled or not signed_flat or not orders_clean):
        return None
    return {"motive_campaign_id":owner_id,"motive_side":side,"motive_origin_at":origin_at,
            "flat_confirmed_at_ms":flat_confirmed_at,"source":"SIGNED_FLAT_COURSE_MOTIVE",
            "side":"SHORT" if side in ("BUY","LONG") else "LONG", "can_place_orders":False}
