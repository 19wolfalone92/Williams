"""R49 fresh TC2 source admission. No local VALIDATED flag is authority.

This checks the current production selector against new exchange OHLC and
binds its decision to a particular closed H1 and order identity. It is not
an independent expert qualification of the underlying pattern selector.
"""
from __future__ import annotations

import hashlib
import json
from decimal import Decimal
import pandas as pd

from data import _frame
from strategy import (
    calculate_indicators,
    canonical_indicator_config_from_env,
    CANONICAL_ALLIGATOR_WARMUP_BARS,
    CANONICAL_ALLIGATOR_RAW_BARS,
    CANONICAL_ALLIGATOR_ABS_TOLERANCE,
)
from williams_signals import extract_long_signal_specs, extract_short_signal_specs

HOUR = 3_600_000
TC2 = "TC2_THREE_WISE_MEN"
INITIAL = {"REVERSAL", "SUPER_AO", "FRACTAL"}
ADDITIONAL = INITIAL | {"THREE_GREEN_BARS"}


def reject(reason):
    raise RuntimeError("TC2_CANONICAL_ADMISSION:" + reason)


def positive(value):
    d = Decimal(str(value))
    if not d.is_finite() or d <= 0:
        reject("INVALID_PRICE")
    return d


def verify_tc2_order(payload, *, symbol, side, purpose, raw, now_ms, tick):
    """Return a receipt only for a freshly reconstructed, still unconsumed signal."""
    if not isinstance(payload, dict) or purpose not in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}:
        reject("IDENTITY")
    role = "ENTRY" if purpose == "CAMPAIGN_ENTRY" else "ADD_ON"
    family = payload.get("signal_type")
    try:
        start = payload["signal_bar_time_ms"]
        signal_id = payload["signal_id"]
        trigger = positive(payload["trigger_price"])
        stop = positive(payload["protective_reference"])
        step = positive(tick)
        if (type(start) is not int or start <= 0 or start % HOUR or
                start + HOUR > now_ms or side not in {"BUY", "SELL"} or
                payload.get("symbol") != symbol or payload.get("side") != side or
                payload.get("timeframe") != "1h" or payload.get("strategy_profile") != TC2 or
                payload.get("role") != role or payload.get("execution_semantics") != "STOP_ENTRY" or
                family not in (INITIAL if role == "ENTRY" else ADDITIONAL) or
                signal_id != f"{symbol}:1h:{side}:{family}:{start}"):
            reject("IDENTITY")
        if trigger % step or stop % step or (stop >= trigger if side == "BUY" else stop <= trigger):
            reject("PRICE_GRID_OR_STOP")
        if not isinstance(raw, list) or len(raw) < CANONICAL_ALLIGATOR_RAW_BARS:
            reject("HISTORY_INSUFFICIENT_ALLIGATOR_WARMUP")
        expected_open = now_ms // HOUR * HOUR
        opens = []
        prices = []
        for row in raw:
            if not isinstance(row, (list, tuple)) or len(row) != 12:
                reject("HISTORY_ROW")
            if type(row[0]) is not int or type(row[6]) is not int:
                reject("HISTORY_TIMESTAMP")
            stamp = row[0]
            if stamp <= 0 or stamp % HOUR or row[6] != stamp + HOUR - 1:
                reject("HISTORY_TIMESTAMP")
            o, h, l, c = (positive(v) for v in row[1:5])
            if h < max(o, l, c) or l > min(o, c):
                reject("HISTORY_OHLC")
            vol = Decimal(str(row[5]))
            if not vol.is_finite() or vol < 0:
                reject("HISTORY_VOLUME")
            opens.append(stamp)
            prices.append((h, l))
        if opens[-1] != expected_open or any(b-a != HOUR for a,b in zip(opens,opens[1:])):
            reject("HISTORY_STALE_OR_GAP")
        if start not in opens[:-1]:
            reject("FORMATION_OUTSIDE_HISTORY")
        # No subsequent price touch can be retroactively turned into a STOP entry.
        # The forming candle is used ONLY to veto consumed/invalidated orders,
        # never to calculate a new pattern or any indicator.
        for stamp, (h,l) in zip(opens, prices):
            if stamp >= start + HOUR:
                if h >= trigger if side == "BUY" else l <= trigger:
                    reject("TRIGGER_ALREADY_TOUCHED")
                if family in {"REVERSAL", "SUPER_AO"} and (l <= stop if side == "BUY" else h >= stop):
                    reject("STRUCTURAL_INVALIDATION")
        closed = _frame(raw[:-1])
        if len(closed) != len(raw)-1:
            reject("HISTORY_PARSE_LOSS")
        if len(closed) < CANONICAL_ALLIGATOR_WARMUP_BARS:
            reject("HISTORY_INSUFFICIENT_ALLIGATOR_WARMUP")
        ind = calculate_indicators(closed, canonical_indicator_config_from_env())
        extract = extract_long_signal_specs if side == "BUY" else extract_short_signal_specs
        candidates = extract(symbol, ind, timeframe="1h", tick_size=float(step))
        actual = next((s for s in candidates if s.signal_id == signal_id and
                       s.signal_type.value == family and s.side == side), None)
        if actual is None or (role == "ENTRY" and actual.role.value != "ENTRY"):
            reject("FRESH_FORMATION_NOT_PROVEN")
        if positive(actual.trigger_price) != trigger or positive(actual.protective_reference) != stop:
            reject("FRESH_LEVELS_CHANGED")
        if family == "FRACTAL":
            # The displayed Teeth beside the current hour is already known:
            # 8-period SMMA displaced 5; index -5 in CLOSED observations.
            teeth = positive(ind["teeth"].iloc[-5])
            if trigger <= teeth if side == "BUY" else trigger >= teeth:
                reject("ACTIVATION_TEETH")
        else:
            teeth = None
        raw_digest = hashlib.sha256(json.dumps(raw[:-1], separators=(",", ":"), allow_nan=False).encode()).hexdigest()
        return {"schema": "R49_TC2_FRESH_SOURCE_V1", "signal_id":signal_id, "symbol":symbol,
                "side":side, "family":family, "role":role, "signal_bar_time_ms":start,
                "confirmation_close_ms":start+HOUR, "closed_at_ms":expected_open,
                "observed_at_ms":now_ms, "trigger":str(trigger), "book_stop":str(stop),
                "activation_teeth":str(teeth) if teeth is not None else None,
                "closed_count":len(closed), "alligator_warmup_bars":len(closed),
                "alligator_warmup_required":CANONICAL_ALLIGATOR_WARMUP_BARS,
                "alligator_warmup_tolerance":CANONICAL_ALLIGATOR_ABS_TOLERANCE,
                "closed_ohlc_sha256":raw_digest,
                "forming_high":str(prices[-1][0]), "forming_low":str(prices[-1][1]),
                "rule_ids": {"REVERSAL":["TC2.WM1.DIVERGENT_BAR","TC2.WM1.ANGULATION","TC2.WM1.AO_CONTEXT"],
                             "SUPER_AO":["TC2.WM2.THREE_AO_BARS","TC2.WM2.FIRST_STOP"],
                             "FRACTAL":["TC2.FRACTAL.EQUAL_EXTREMES","TC2.WM3.ACTIVATION_TEETH"],
                             "THREE_GREEN_BARS":["TC2.CONTINUATION_EXISTING_POLICY"]}[family]}
    except RuntimeError as exc:
        if str(exc).startswith("TC2_CANONICAL_ADMISSION:"):
            raise
        reject("RECOMPUTE_FAILED")
    except Exception:
        reject("MALFORMED_OR_UNAVAILABLE_EVIDENCE")


def tc2_receipt_digest(receipt):
    """Fingerprint the exact issued receipt, independent of dictionary ordering."""
    return hashlib.sha256(json.dumps(receipt, sort_keys=True, separators=(",", ":"),
        allow_nan=False).encode()).hexdigest()


def verify_tc2_transport_receipt(receipt, *, issued_digest, campaign, intent,
                                 expected_revision, params, now_ms):
    """Last local NEW-risk fence; no network I/O and no authority for exits.

    Bind the fresh source receipt to the durable reservation and the actual
    conditional POST. A missing source or changed plan is not a legacy default.
    This does not claim that a closed exchange candle can never be corrected.
    """
    import re
    try:
        if campaign is None or not isinstance(receipt, dict):
            reject("RECEIPT_OR_OWNER_MISSING")
        tags = campaign.tags
        pending = tags.get("pending_signal") or {}
        signal = pending.get("signal") or {}
        role = {"CAMPAIGN_ENTRY": "ENTRY", "CAMPAIGN_ADD_ON": "ADD_ON"}.get(intent.purpose)
        if (role is None or not intent.campaign_id or campaign.campaign_id != intent.campaign_id
                or campaign.symbol != intent.symbol or campaign.side != intent.side
                or tags.get("strategy_profile") != TC2 or signal.get("strategy_profile") != TC2
                or pending.get("state") != "VALIDATED"
                or tags.get("pending_order_client_id") != intent.client_order_id
                or not intent.client_order_id or not intent.signal_id
                or signal.get("signal_id") != intent.signal_id
                or campaign.current_signal_id != intent.signal_id
                or signal.get("symbol") != intent.symbol or signal.get("side") != intent.side
                or signal.get("role") != role or signal.get("timeframe") != "1h"
                or signal.get("execution_semantics") != "STOP_ENTRY"
                or signal.get("signal_type") not in (INITIAL if role == "ENTRY" else ADDITIONAL)
                or intent.order_type != "STOP_MARKET" or tags.get("pending_order_type") != "STOP_MARKET"):
            reject("TRANSPORT_OWNER_OR_SIGNAL_CHANGED")
        if type(expected_revision) is not int or expected_revision <= 0 or campaign.state_revision != expected_revision:
            reject("STATE_CHANGED_BEFORE_TRANSPORT")
        if (receipt.get("schema") != "R49_TC2_FRESH_SOURCE_V1"
                or not isinstance(issued_digest, str) or tc2_receipt_digest(receipt) != issued_digest
                or not re.fullmatch(r"[a-f0-9]{64}", str(receipt.get("closed_ohlc_sha256", "")))
                or receipt.get("signal_id") != intent.signal_id
                or receipt.get("symbol") != intent.symbol or receipt.get("side") != intent.side
                or receipt.get("family") != signal.get("signal_type") or receipt.get("role") != role
                or receipt.get("signal_bar_time_ms") != signal.get("signal_bar_time_ms")
                or type(receipt.get("closed_count")) is not int
                or receipt["closed_count"] < CANONICAL_ALLIGATOR_WARMUP_BARS):
            reject("TRANSPORT_SOURCE_RECEIPT_CHANGED")
        closed = receipt["closed_at_ms"]
        observed = receipt["observed_at_ms"]
        confirmed = receipt["confirmation_close_ms"]
        if (type(closed) is not int or type(observed) is not int or type(confirmed) is not int
                or type(now_ms) is not int or closed <= 0 or closed % HOUR
                or confirmed != signal["signal_bar_time_ms"] + HOUR
                or not 0 < confirmed <= closed <= observed <= now_ms < closed + HOUR):
            reject("TRANSPORT_SOURCE_CLOCK_OR_CAUSALITY")
        trigger = positive(receipt["trigger"])
        book_stop = positive(receipt["book_stop"])
        planned_stop = positive(tags["pending_order_stop"])
        quantity = positive(intent.quantity)
        if (trigger != positive(signal["trigger_price"]) or trigger != positive(tags["pending_order_trigger"])
                or book_stop != positive(signal["protective_reference"])
                or planned_stop != positive(intent.invalidation_level)
                or quantity != positive(tags["pending_order_quantity"])
                or (planned_stop >= trigger if intent.side == "BUY" else planned_stop <= trigger)
                or (role == "ENTRY" and (planned_stop != book_stop or planned_stop != positive(campaign.initial_stop_price)))
                or (role == "ADD_ON" and planned_stop != positive(campaign.current_stop_price))
                or positive(campaign.pending_risk_quote) != positive(intent.risk_quote)
                or positive(campaign.capital_reserved_quote) != positive(intent.capital_reserved_quote)):
            reject("TRANSPORT_PLAN_OR_RESERVATION_CHANGED")
        if (params.get("symbol") != intent.symbol or params.get("side") != intent.side
                or params.get("type") != "STOP_MARKET" or params.get("algoType") != "CONDITIONAL"
                or params.get("positionSide") != "BOTH" or params.get("workingType") != "CONTRACT_PRICE"
                or str(params.get("priceProtect", "false")).lower() != "false"
                or str(params.get("reduceOnly", "false")).lower() != "false"
                or str(params.get("closePosition", "false")).lower() != "false"
                or params.get("clientAlgoId") != intent.client_order_id
                or positive(params["quantity"]) != quantity or positive(params["triggerPrice"]) != trigger):
            reject("TRANSPORT_CONDITIONAL_POST_CHANGED")
    except RuntimeError as exc:
        if str(exc).startswith("TC2_CANONICAL_ADMISSION:"):
            raise
        reject("TRANSPORT_EVIDENCE_UNAVAILABLE")
    except Exception:
        reject("TRANSPORT_EVIDENCE_UNAVAILABLE")
