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
    """TC2 ch11-12 authorizes EXIT on a real opposite Wise-Man breakout.

    NTD/Course retain source-specific rules. Reopening the reverse side needs
    a fresh entry/risk/exchange gate after exchange-confirmed FLAT.
    """
    return source in (TC2, NTD, COURSE)


def tc2_opposite_exit_profit_blocker(side, entry_price, current_mark,
                                     cost_buffer=0.0035, tick=0.0):
    """Opposite Wise-Man setup is not an instruction to realize a loss.

    TC2 ch12 mainly exits using trailing structural STOP; NTD ch9 warns that
    opposite-direction signals are its LEAST favored profit-taking method.
    Source policy: a TC2 contrary signal may authorize optional reduce-only
    MARKET exit only if gross mark-to-entry return exceeds a *conservative*
    fee+slippage reserve. It does not constrain the existing protective SL,
    an operator close, or confirmed liquidation remediation. A positive mark
    is not a guaranteed positive fill (latency, funding, actual commission).
    """
    from math import isfinite
    try:
        entry,mark,cost,minimum_tick=(float(v) for v in
            (entry_price,current_mark,cost_buffer,tick))
    except (TypeError,ValueError,OverflowError):
        return "TC2_OPPOSITE_EXIT_PROFIT_EVIDENCE_UNVERIFIED"
    if (side not in ("BUY","SELL") or
            not all(isfinite(v) for v in (entry,mark,cost,minimum_tick)) or
            entry<=0 or mark<=0 or cost<0 or minimum_tick<0):
        return "TC2_OPPOSITE_EXIT_PROFIT_EVIDENCE_UNVERIFIED"
    gross=((mark-entry) if side=="BUY" else (entry-mark))/entry
    return (None if gross>cost+minimum_tick/entry
            else "TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE")


def tc2_opposite_exit_evidence_blocker(tags, reason, now, current_side, symbol, *, live_mark=None):
    """Final reason+evidence fence, prior to any mutation/entry cancellation.

    Trading Chaos 2 ch11-12: only a causally triggered opposite Wise-Man
    STOP may close TC2. A generic contrary hypothesis or risk-mode flag cannot.
    """
    if market_exit_blocker(TC2, reason):
        return "TC2_EXIT_REASON_NOT_BOOK_APPROVED"
    proof = (tags or {}).get("tc2_book_exit_proof") or {}
    try:
        observed = int(proof.get("observed_at_ms", 0))
        opened = int(proof.get("opened_at_ms", 0))
        known = int((proof.get("event") or {}).get("known_at_ms", 0))
        trigger = float(proof.get("trigger", 0))
        mark = float(proof.get("mark", 0))
        checked_now = int(now)
        direction = str(proof.get("direction", ""))
        signal_id = str(proof.get("signal_id", ""))
    except (TypeError, ValueError, OverflowError):
        return "TC2_EXIT_BOOK_EVIDENCE_MALFORMED"
    from math import isfinite
    last = (tags or {}).get("last_opposition_evidence") or {}
    if (proof.get("reason") != reason
            or proof.get("symbol") != symbol
            or last.get("signal_id") != signal_id
            or (last.get("event") or {}) != (proof.get("event") or {})
            or proof.get("strategy_profile") != TC2
            or proof.get("event", {}).get("state") != "TRIGGERED"
            or not signal_id or opened <= 0 or known <= opened
            or observed < known or checked_now < observed
            or checked_now-observed > 120_000
            or not isfinite(trigger) or trigger <= 0
            or not isfinite(mark) or mark <= 0
            or not ((current_side == "BUY" and direction == "SHORT" and mark <= trigger)
                or (current_side == "SELL" and direction == "LONG" and mark >= trigger))):
        return "TC2_EXIT_BOOK_OPPOSITE_TRIGGER_UNVERIFIED"
    # Trading signals are detected from CONTRACT candle extrema, while the
    # broker's protective STOP_MARKET is MARK_PRICE-based. The initial signal
    # mark is only an observation. Just before admitting a reduce-only exit,
    # require a fresh Binance Futures MARK still beyond the opposite trigger.
    # A failed/malformed mark read is UNKNOWN, never authority to flatten.
    try:
        actual_mark = float(live_mark) if live_mark is not None else mark
    except (TypeError, ValueError, OverflowError):
        return "TC2_EXIT_LIVE_MARK_UNVERIFIED"
    if not isfinite(actual_mark) or actual_mark <= 0:
        return "TC2_EXIT_LIVE_MARK_UNVERIFIED"
    if not ((current_side == "BUY" and direction == "SHORT" and actual_mark <= trigger)
            or (current_side == "SELL" and direction == "LONG" and actual_mark >= trigger)):
        return "TC2_EXIT_LIVE_MARK_NO_LONGER_TRIGGERED"
    return None


def market_exit_blocker(source, reason, *, risk_emergency=False):
    """Final TC2 close gate, independent of the scanner's current hypothesis.

    A risk emergency is an explicit call from the revalidated safety path; the
    live ownership/side/quantity checks in _exit_market remain mandatory.
    """
    if reason in ("MANUAL_EXIT", "KILL_SWITCH") or risk_emergency:
        return None
    if source == TC2:
        return (None if reason in {
            "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
            "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION",
        } else "TC2_EXIT_REQUIRES_BOOK_OPPOSITE_TRIGGER")
    return None if source in (NTD, COURSE) else "EXIT_STRATEGY_OWNER_UNVERIFIED"
