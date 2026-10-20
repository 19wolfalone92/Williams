"""Release execution policy, separate from book signal detection.

TC2 owns new risk. NTD/Course are independent observations, never votes or
order intents. Previously filled campaigns retain their original exit policy.
"""
from williams_edition_rules import TC2, NTD, COURSE

EXECUTION_PROFILE = TC2
OBSERVER_PROFILES = (NTD, COURSE)
EXECUTION_POLICY = "TC2_LIVE_EDITIONS_OBSERVE_V1"


def new_exposure_blocker(source):
    return None if source == EXECUTION_PROFILE else "WILLIAMS_SOURCE_OBSERVATION_ONLY"


def execution_policy_snapshot():
    return {"mode": EXECUTION_POLICY, "execution_profile": EXECUTION_PROFILE,
            "observer_profiles": list(OBSERVER_PROFILES), "one_campaign_per_symbol": True,
            "legacy_management": "PINNED_SOURCE_NO_NEW_EXPOSURE"}


def structural_fractal(signal_type, source=TC2):
    family = getattr(signal_type, "value", signal_type)
    return source == TC2 and str(family).removeprefix("SHORT_").removeprefix("LONG_") == "FRACTAL"


def h1_signal_expiry(signal_at, signal_type, source=TC2):
    """TC2 ch11 PDF149: unbroken fractal, until replacement, not a 5h TTL.

Zero means structural validity, NOT permission to use stale market data.
Other families retain the explicit project age policy pending source proof.
"""
    return 0 if structural_fractal(signal_type, source) else int(signal_at) + 5 * 3_600_000


def closed_h1_history_blocker(bars, now):
    if len(bars)<45 or any(b.t<=0 or b.t+3_600_000>now for b in bars):
        return "CLOSED_H1_HISTORY_NOT_PROVEN"
    if any(b.t-a.t!=3_600_000 for a,b in zip(bars,bars[1:])):
        return "H1_HISTORY_GAP"
    return None if 0<=now-(bars[-1].t+3_600_000)<3_600_000 else "H1_SNAPSHOT_STALE"


def strategy_market_close_allowed(source):
    """Project SL-only TC2 policy covers opposition AND correction boundaries.

    Manual closure and exchange/account emergency actions have their own gates.
    Filled legacy NTD/Course campaigns retain their pinned management rules.
    """
    return source in (NTD, COURSE)
