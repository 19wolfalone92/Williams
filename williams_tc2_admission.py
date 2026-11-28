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
