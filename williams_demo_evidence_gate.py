"""Evidence validator for a REAL device / Binance USD-M Futures Demo session.

Offline JSON can detect incomplete recovery/SL sequences; it cannot
cryptographically verify that purported exchange receipts or Android events
were genuine. Hence a passing contract ALWAYS needs human device attestation.
Secrets are prohibited in the input. Zero order/network side effects.
"""
from __future__ import annotations
import re
from math import isfinite

TYPES=(
 "APK_INSTALLED","ENGINE_RUNNING","KEYSTORE_SAVED","DEMO_ACCOUNT_READ",
 "ENTRY_FILL_REPORTED","STOP_ACK_REPORTED",
 "PROCESS_KILLED","BOOT_COMPLETED","KEYSTORE_RESTORED","ENGINE_RECOVERED",
 "POSITION_RECONCILED","STOP_RECONFIRMED","POSITION_FLAT"
)
HEX64=re.compile(r"^[a-fA-F0-9]{64}$")
PROHIBITED=("api_key","api_secret","private_key","password","mnemonic","access_token")

def validate_demo_device_evidence(payload:dict)->dict:
    """Return fail-closed reasons and pending physical/exchange attestations."""
    errors=[]
    warnings=[]
    if not isinstance(payload,dict):
        return {"status":"EVIDENCE_MISSING","errors":["NO_DEVICE_EXPORT"],
            "physical_device_verified":False,"exchange_authenticity_verified":False,
            "can_enable_live":False}
    if payload.get("environment")!="BINANCE_USDM_FUTURES_DEMO":
        errors.append("WRONG_OR_UNKNOWN_EXCHANGE_ENVIRONMENT")
    if payload.get("origin")!="PHYSICAL_ANDROID_EXPORT":
        errors.append("NOT_EXPORTED_FROM_PHYSICAL_ANDROID")
    if not isinstance(payload.get("apk_sha256"),str) or not HEX64.fullmatch(payload["apk_sha256"]):
        errors.append("APK_CHECKSUM_MISSING")
    events=payload.get("events")
    if not isinstance(events,list) or not events:
        errors.append("NO_ORDERED_DEVICE_EVENTS")
        events=[]
    times=[];observed=set();stops={}
    for e in events:
        if not isinstance(e,dict):
            errors.append("MALFORMED_DEVICE_EVENT")
            continue
        if any(term in str(k).lower() for k in e for term in PROHIBITED):
            errors.append("SECRET_FIELD_FOUND_IN_DIAGNOSTIC")
        typ=e.get("type")
        at=e.get("at_ms")
        if typ not in TYPES or not isinstance(at,int) or isinstance(at,bool) or at<=0:
            errors.append("MALFORMED_DEVICE_EVENT")
            continue
        if times and at<=times[-1]:
            errors.append("UNSORTED_OR_DUPLICATE_DEVICE_EVENT")
        times.append(at);observed.add(typ)
        if typ in {"ENTRY_FILL_REPORTED","STOP_ACK_REPORTED","POSITION_RECONCILED","STOP_RECONFIRMED"}:
            symbol=e.get("symbol")
            qty=e.get("qty")
            if not isinstance(symbol,str) or not symbol.endswith("USDT"):
                errors.append("UNKNOWN_SYMBOL")
            if not isinstance(qty,(float,int)) or isinstance(qty,bool) or not isfinite(qty) or qty<=0:
                errors.append("INVALID_EXCHANGE_QUANTITY")
            if typ in {"STOP_ACK_REPORTED","STOP_RECONFIRMED"}:
                if not isinstance(e.get("order_id"),str) or len(e["order_id"])<2:
                    errors.append("MISSING_EXCHANGE_STOP_ORDER_ID")
                if e.get("status") not in {"NEW","PARTIALLY_FILLED"}:
                    errors.append("STOP_NOT_EXCHANGE_CONFIRMED_ACTIVE")
                if e.get("reduce_only") is not True and e.get("close_position") is not True:
                    errors.append("STOP_NOT_REDUCE_ONLY")
                stops.setdefault(typ,[]).append(e)
    missing=sorted(set(TYPES)-observed)
    if missing:
        errors.append("MISSING_DEVICE_LIFECYCLE_STEPS")
    def first(type_):
        return next((e for e in events if isinstance(e,dict) and e.get("type")==type_),{})
    def stamp(type_):
        e=first(type_)
        x=e.get("at_ms")
        return x if isinstance(x,int) and not isinstance(x,bool) else -1
    if not all(stamp(x)<stamp(y) for x,y in (
        ("APK_INSTALLED","KEYSTORE_SAVED"),("KEYSTORE_SAVED","ENGINE_RUNNING"),
        ("ENGINE_RUNNING","ENTRY_FILL_REPORTED"),("ENTRY_FILL_REPORTED","STOP_ACK_REPORTED"),
        ("STOP_ACK_REPORTED","PROCESS_KILLED"),("PROCESS_KILLED","BOOT_COMPLETED"),
        ("BOOT_COMPLETED","KEYSTORE_RESTORED"),("KEYSTORE_RESTORED","ENGINE_RECOVERED"),
        ("ENGINE_RECOVERED","POSITION_RECONCILED"),
        ("POSITION_RECONCILED","STOP_RECONFIRMED"),
        ("STOP_RECONFIRMED","POSITION_FLAT"))):
        errors.append("LIFECYCLE_ORDER_OR_REBOOT_RECOVERY_UNPROVED")
    if first("ENTRY_FILL_REPORTED").get("exchange_order_id") in (None,""):
        errors.append("ENTRY_FILL_LACKS_EXCHANGE_ORDER_ID")
    if first("POSITION_FLAT").get("exit_reason") not in {"EXCHANGE_STOP_FILL","USER_MANUAL_EXIT"}:
        errors.append("EXIT_REASON_NOT_VERIFIED_STOP_OR_USER")
    for stop,entry in ((first("STOP_ACK_REPORTED"), first("ENTRY_FILL_REPORTED")),
                      (first("STOP_RECONFIRMED"),first("POSITION_RECONCILED"))):
        if stop and entry:
            if stop.get("symbol")!=entry.get("symbol"):
                errors.append("PROTECTIVE_STOP_WRONG_SYMBOL")
            if isinstance(stop.get("qty"),(float,int)) and isinstance(entry.get("qty"),(float,int)):
                if stop["qty"]+1e-9<entry["qty"]:
                    errors.append("INSUFFICIENT_EXCHANGE_PROTECTIVE_QUANTITY")
    if first("KEYSTORE_RESTORED").get("same_identity") is not True:
        errors.append("API_IDENTITY_NOT_PRESERVED_AFTER_REBOOT")
    if first("ENGINE_RECOVERED").get("state")!="RUNNING":
        errors.append("ENGINE_NOT_RUNNING_AFTER_REBOOT")
    if first("POSITION_RECONCILED").get("source")!="SIGNED_DEMO_ACCOUNT_SNAPSHOT":
        errors.append("NO_AUTHENTICATED_DEMO_POSITION_SNAPSHOT")
    if first("DEMO_ACCOUNT_READ").get("source")!="SIGNED_DEMO_ACCOUNT_SNAPSHOT":
        errors.append("NO_AUTHENTICATED_DEMO_ACCOUNT_READ")
    unique=sorted(set(errors))
    return {"schema":"WILLIAMS_DEVICE_DEMO_EVIDENCE_AUDIT_V1",
        "status":"CONTRACT_FAIL" if unique else "STRUCTURAL_PASS_REQUIRES_PHYSICAL_EXCHANGE_ATTESTATION",
        "errors":unique,"missing_events":missing,
        "physical_device_verified":False,"exchange_authenticity_verified":False,
        "full_trade_lifecycle_certified":False,"can_enable_live":False,
        "warnings":["Offline JSON is not exchange attestation; check genuine device logs and Binance Demo order IDs.",
                    "For unprotected fills, use existing fail-closed emergency stop logic; never wait for wave indicators."],
        "checked_event_count":len(events)}


def _has_sensitive_field(value, depth: int = 0) -> bool:
    """Reject nested credential fields without ever logging their values."""
    if depth > 32:
        return True  # unbounded diagnostic nesting is not safe release proof
    if isinstance(value, dict):
        return any(
            any(secret in str(key).lower() for secret in PROHIBITED)
            or _has_sensitive_field(child, depth+1)
            for key, child in value.items()
        )
    if isinstance(value, list):
        return any(_has_sensitive_field(child, depth+1) for child in value)
    return False


def validate_demo_both_directions(payload: dict) -> dict:
    """Audit two independently signed *claims* of LONG/SHORT Android Demo cycles.

    This is deliberately NOT an exchange verifier. Offline content can be
    forged, so even a structurally valid dual-side export cannot enable
    orders or lift the physical-device RELEASE HOLD.

    Required layout: {"environment": "BINANCE_USDM_FUTURES_DEMO",
    "apk_sha256": "<64 hex>", "runs": [
       {"direction": "LONG", "environment": ..., "apk_sha256": ...,
        "origin": "PHYSICAL_ANDROID_EXPORT", "events": [...]},
       {"direction": "SHORT", ...}]}
    """
    failures = []
    checks = {}
    if _has_sensitive_field(payload):
        failures.append("SECRET_FIELD_FOUND_IN_DIAGNOSTIC")
    if not isinstance(payload, dict):
        return {"status": "CONTRACT_FAIL",
                "errors": ["NO_DUAL_SIDE_DEVICE_EXPORT"],
                "physical_device_verified": False,
                "exchange_authenticity_verified": False,
                "release_approved": False, "can_enable_live": False}
    runs = payload.get("runs")
    if not isinstance(runs, list) or len(runs) != 2:
        failures.append("REQUIRE_EXACTLY_TWO_INDEPENDENT_DEMO_CYCLES")
        runs = []
    if payload.get("environment") != "BINANCE_USDM_FUTURES_DEMO":
        failures.append("INVALID_DUAL_SIDE_DEMO_ENVIRONMENT")
    apk = payload.get("apk_sha256")
    if not isinstance(apk, str) or not HEX64.fullmatch(apk):
        failures.append("INVALID_DUAL_SIDE_APK_SHA256")
    observed_orders = set()
    observed_stops = set()
    observed_campaigns = set()
    sides = set()
    for i, run in enumerate(runs):
        if not isinstance(run, dict):
            failures.append("MALFORMED_DEMO_CYCLE")
            continue
        side = run.get("direction")
        if side not in ("LONG", "SHORT") or side in sides:
            failures.append("DUPLICATE_OR_UNKNOWN_DEMO_DIRECTION")
            continue
        sides.add(side)
        own = validate_demo_device_evidence(run)
        checks[side] = own["status"]
        if own["errors"]:
            failures.append(f"{side}_CYCLE_INCOMPLETE")
        if run.get("environment") != payload.get("environment") or run.get("apk_sha256") != apk:
            failures.append(f"{side}_DIFFERENT_BUILD_OR_EXCHANGE")
        events = run.get("events") or []
        if not isinstance(events, list):
            events = []
        def ev(kind):
            return next((x for x in events
                         if isinstance(x, dict) and x.get("type") == kind), {})
        entry = ev("ENTRY_FILL_REPORTED")
        first_stop = ev("STOP_ACK_REPORTED")
        restored_stop = ev("STOP_RECONFIRMED")
        position = ev("POSITION_RECONCILED")
        flat = ev("POSITION_FLAT")
        expected_entry = "BUY" if side == "LONG" else "SELL"
        expected_exit = "SELL" if side == "LONG" else "BUY"
        if entry.get("side") != expected_entry or first_stop.get("side") != expected_exit:
            failures.append(f"{side}_ENTRY_OR_STOP_DIRECTION_MISMATCH")
        # A stop order can be active on the account yet protect the WRONG
        # side. The original book campaign needs a structural level beyond
        # its entry, independently of the stop after a profitable trail.
        price = entry.get("avg_fill_price")
        initial_stop = first_stop.get("stop_price")
        if (not isinstance(price, (int, float)) or isinstance(price, bool)
                or not isfinite(price) or price <= 0
                or not isinstance(initial_stop, (int, float))
                or isinstance(initial_stop, bool)
                or not isfinite(initial_stop) or initial_stop <= 0
                or (price <= initial_stop if side == "LONG" else price >= initial_stop)):
            failures.append(f"{side}_INITIAL_STRUCTURAL_STOP_WRONG_SIDE")
        if restored_stop.get("side") != expected_exit:
            failures.append(f"{side}_RECOVERED_STOP_DIRECTION_MISMATCH")
        if not all(x.get("symbol") == entry.get("symbol") for x in (
                first_stop, position, restored_stop, flat)):
            failures.append(f"{side}_CROSS_SYMBOL_CAMPAIGN_MISMATCH")
        entry_id = entry.get("exchange_order_id")
        if not isinstance(entry_id, str) or not entry_id.strip() or entry_id in observed_orders:
            failures.append("DUPLICATE_OR_UNKNOWN_DEMO_ENTRY_ORDER")
        else:
            observed_orders.add(entry_id)
        stop_id = first_stop.get("order_id")
        recovery_stop_id = restored_stop.get("order_id")
        if not isinstance(stop_id, str) or not stop_id.strip() or stop_id in observed_stops:
            failures.append("DUPLICATE_OR_UNKNOWN_DEMO_STOP_ORDER")
        else:
            observed_stops.add(stop_id)
        if recovery_stop_id != stop_id:
            # A STOP replacement is independently checkable only if the
            # previous stop is explicitly identified; silent order substitution
            # after reboot must never pass release evidence.
            if (restored_stop.get("replaces_order_id") != stop_id
                    or not restored_stop.get("replacement_receipt_id")):
                failures.append(f"{side}_STOP_REPLACEMENT_UNPROVED")
            if recovery_stop_id in observed_stops or not isinstance(recovery_stop_id, str):
                failures.append(f"{side}_RECOVERY_STOP_ID_COLLISION")
            else:
                observed_stops.add(recovery_stop_id)
        if entry_id == stop_id or entry_id == recovery_stop_id:
            failures.append(f"{side}_ENTRY_AND_STOP_SAME_ORDER")
        if (flat.get("exit_side") != expected_exit
                or flat.get("source") != "SIGNED_DEMO_ACCOUNT_SNAPSHOT"
                or flat.get("position_qty") != 0):
            failures.append(f"{side}_POSITION_FLAT_NOT_SIGNED_OR_DIRECTION_MISSING")
        journal = run.get("journal")
        if not isinstance(journal, dict):
            failures.append(f"{side}_TRADE_JOURNAL_MISSING")
        else:
            # Fees and PnL may be negative, but they must be present and
            # finite, with genuine signed source claimed by the export.
            for name in ("executed_qty", "commission_usdt", "realized_pnl_usdt"):
                value = journal.get(name)
                if (not isinstance(value, (int, float)) or isinstance(value, bool)
                        or not isfinite(value) or (name == "executed_qty" and value <= 0)):
                    failures.append(f"{side}_JOURNAL_{name.upper()}_MISSING")
            if journal.get("source") != "SIGNED_DEMO_TRADE_AND_INCOME_SNAPSHOT":
                failures.append(f"{side}_JOURNAL_SOURCE_NOT_SIGNED")
        # A fresh post-recovery STOP must cover the persisted signed position,
        # and be reported to the same campaign, not a convenient unrelated fill.
        owner = entry.get("campaign_id")
        if isinstance(owner, str) and owner.strip():
            if owner in observed_campaigns:
                failures.append("DUPLICATE_CAMPAIGN_OWNERSHIP_ACROSS_DIRECTIONS")
            observed_campaigns.add(owner)
        if (not isinstance(owner, str) or not owner.strip()
                or position.get("campaign_id") != owner
                or first_stop.get("campaign_id") != owner
                or restored_stop.get("campaign_id") != owner):
            failures.append(f"{side}_POSITION_CAMPAIGN_OWNERSHIP_UNPROVED")
    if sides != {"LONG", "SHORT"}:
        failures.append("LONG_AND_SHORT_BOTH_REQUIRED")
    unique = sorted(set(failures))
    return {
        "schema": "WILLIAMS_DUAL_DIRECTION_DEMO_ACCEPTANCE_V1",
        "status": "CONTRACT_FAIL" if unique else
                  "STRUCTURAL_PASS_REQUIRES_INDEPENDENT_PHYSICAL_BINANCE_ATTESTATION",
        "errors": unique,
        "per_direction": checks,
        "physical_device_verified": False,
        "exchange_authenticity_verified": False,
        "full_trade_lifecycle_certified": False,
        "release_approved": False,
        "can_enable_live": False,
        "warnings": [
            "Two synthetic JSON timelines do not prove any Binance execution.",
            "Review physical device, genuine signed account/orders, and Android reboot on the same APK.",
        ],
    }
