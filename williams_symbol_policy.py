"""Separate NEW-risk admission from the existing fifteen-symbol lifecycle.

An already submitted order is an existing liability, including an unknown
POST or an unfilled/triggering/partial order. Revoking admission must not
erase its ledger, free its reservation, retry it, or disable its STOP/exit.
Existing pending orders are reconciled under the original ownership rules;
this policy never cancels them by symbol alone.
"""
from trading_config import DEFAULT_FUTURES_SYMBOLS

SYMBOL_POLICY = "TEN_NEW_RISK_FIFTEEN_MANAGEMENT_V1"
NEW_RISK_SYMBOLS = (
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
)
MANAGEMENT_SYMBOLS = DEFAULT_FUTURES_SYMBOLS


def new_risk_symbol_blocker(symbol):
    # Do not normalize malformed durable identity into a different symbol.
    return None if symbol in NEW_RISK_SYMBOLS else "FUTURES_SYMBOL_MANAGEMENT_ONLY"


def symbol_policy_snapshot():
    return {
        "policy": SYMBOL_POLICY,
        "new_risk_symbols": list(NEW_RISK_SYMBOLS),
        "management_symbols": list(MANAGEMENT_SYMBOLS),
        "existing_pending_policy": "RECONCILE_OWNED_NO_REARM_NO_SYMBOL_ONLY_CANCEL",
        "existing_pending_risk_reserved": True,
    }
