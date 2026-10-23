"""Independent Python evaluator for the native TC2 two-leg order contract.

TC2 PDF154-156/165: a new opposite campaign follows a verified full close.
This evaluator reads the canonical native ledger; it never submits orders.
The actual mobile executor uses the matching Kotlin door plus fresh H1 and
signed position/order checks. No old position quantity or risk is inherited.
"""
from copy import deepcopy
from decimal import Decimal, InvalidOperation
from hashlib import sha256
from math import isfinite

from williams_edition_rules import TC2

POLICY = "TC2_CONFIRMED_FLAT_REVERSAL_V1"
REASONS = {"OPPOSITE_WILLIAMS_LEVEL_TRIGGERED", "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"}


def reversal_client_id(owner_id, signal_id):
    return "W2V_" + sha256(f"{owner_id}|{signal_id}".encode()).hexdigest()[:28]


def reversal_candidate(ledger, owner_id, now, ignore_client_id=""):
    if not owner_id:
        return None
    try:
        owners = [r for r in ledger if r.get("intent_role", "ENTRY") == "ENTRY" and r.get("client_id") == owner_id]
        if len(owners) != 1:
            return None
        o = owners[0]
        if (not o.get("strategy_ownership_verified") or o.get("strategy_profile") != TC2
                or o.get("campaign_status") != "CLOSED_OR_STOPPED"):
            return None
        proof = o["edition_management"]["exit_evidence"]
        reason = proof["reason"]
        if reason not in REASONS or o.get("close_reason", reason) != reason:
            return None
        c = proof["reverse_candidate"]
        d = proof["opposition_check"]
        opposite = d["opposition"]
        symbol = o["symbol"]
        exits = [r for r in ledger if r.get("intent_role") == "EXIT" and r.get("entry_client_id") == owner_id
                 and r.get("reason") == reason and not r.get("partial_reduction")
                 and r.get("exchange_status") == "EXIT_CONFIRMED" and r.get("order_status") == "FILLED"]
        if len(exits) != 1:
            return None
        x = exits[0]
        flat = int(x["confirmed_flat_at_ms"])
        quantity = Decimal(x["quantity"])
        if (not quantity.is_finite() or quantity <= 0 or Decimal(x["executed_qty"]) != quantity
                or Decimal(x["position_before_qty"]) != quantity or not 0 < flat <= now <= flat+120_000
                or not x["client_id"] or not x["exchange_order_id"]):
            return None
        side = c["direction"]
        trigger, stop = float(c["trigger"]), float(c["protective_stop"])
        signal_id, signal_at = c["signal_id"], int(c["signal_bar_time_ms"])
        family = c["signal_type"].removeprefix("SHORT_").removeprefix("LONG_")
        event = opposite["event"]
        observed, known, opened = int(d["observed_at_ms"]), int(event["known_at_ms"]), int(o["position_open_observed_at_ms"])
        inferred_side = "SHORT" if c["signal_type"].startswith("SHORT_") else "LONG"
        if (c["symbol"] != symbol or c["strategy_profile"] != TC2 or side not in {"LONG", "SHORT"}
                or side == o["direction"] or side != inferred_side or family not in {"REVERSAL", "SUPER_AO", "FRACTAL"}
                or not signal_id or signal_at <= 0 or not all(isfinite(p) and p > 0 for p in (trigger, stop))
                or (stop >= trigger if side == "LONG" else stop <= trigger)
                or c["execution_semantics"] != "STOP_ENTRY" or opposite["signal_id"] != signal_id
                or opposite["direction"] != side or int(opposite["signal_bar_time_ms"]) != signal_at
                or float(opposite["trigger"]) != trigger or proof["signal_id"] != signal_id
                or int(proof["observed_at_ms"]) != observed or d["uncertain"]
                or d["trigger_price_basis"] != "CONTRACT_PRICE" or d["direction"] != o["direction"]
                or int(d["scope_started_at_ms"]) != opened or opened <= 0 or known <= opened
                or known < signal_at+3_600_000 or not known <= observed <= now <= observed+120_000
                or opposite["strategy_profile"] != TC2 or opposite["execution_semantics"] != "STOP_ENTRY"
                or event["state"] != "TRIGGERED"):
            return None
        same = [r for r in ledger if r.get("intent_role", "ENTRY") == "ENTRY" and r.get("symbol") == symbol
                and r.get("client_id") != ignore_client_id]
        if (not same or same[-1]["client_id"] != owner_id
                or any(r.get("reversal_owner_id") == owner_id and r.get("client_id") != ignore_client_id for r in ledger)):
            return None
        return dict(deepcopy(c), policy=POLICY, owner_id=owner_id, exit_client_id=x["client_id"],
                    flat_confirmed_at_ms=flat, client_id=reversal_client_id(owner_id, signal_id))
    except (KeyError, ValueError, TypeError, InvalidOperation, OverflowError):
        return None


def reversal_entry_blocker(ledger, owner_id, symbol, side, signal_id, signal_type,
                           signal_at, client_id, entry, stop, now, contract, mark,
                           tick, allow_persisted_self=False, max_deviation=.0015):
    c = reversal_candidate(ledger, owner_id, now, client_id if allow_persisted_self else "")
    if c is None:
        return "TC2_REVERSAL_FLAT_OWNER_OR_EXIT_PROOF_UNVERIFIED"
    if (c["symbol"], c["direction"], c["signal_id"], c["signal_type"], c["signal_bar_time_ms"], c["client_id"]) != (
            symbol, side, signal_id, signal_type, signal_at, client_id):
        return "TC2_REVERSAL_SIGNAL_OR_OWNER_MISMATCH"
    if not all(isfinite(x) and x > 0 for x in (entry, stop, contract, mark, tick, max_deviation)):
        return "TC2_REVERSAL_PRICE_UNVERIFIED"
    trigger = c["trigger"]
    if abs(stop-c["protective_stop"]) > tick*.11:
        return "TC2_REVERSAL_BOOK_STOP_CHANGED"
    if (contract < trigger if side == "LONG" else contract > trigger):
        return "TC2_REVERSAL_TRIGGER_NO_LONGER_CROSSED"
    if abs(contract/trigger-1) > max_deviation or abs(entry/trigger-1) > max_deviation:
        return "TC2_REVERSAL_TRIGGER_TOO_FAR_NO_CHASING"
    if (entry < contract or stop >= min(mark, contract)) if side == "LONG" else (entry > contract or stop <= max(mark, contract)):
        return "TC2_REVERSAL_EXECUTION_BOUND_OR_PROTECTION_INVALID"
    return None
