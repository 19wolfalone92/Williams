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
