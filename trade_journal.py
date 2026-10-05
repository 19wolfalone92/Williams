"""Trade intelligence and learning journal for Williams.

Stores why a trade was taken, what happened while it was open, and a
machine-readable diagnosis after exit. This module never changes entry rules.
"""

import json
import time
from datetime import datetime, timezone


def _num(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def entry_context_from_candidate(candidate, rank=None, universe_size=None):
    if candidate is None:
        return {}
    fields = (
        "symbol", "score", "base_score", "setup_score", "signal_strength",
        "breakout_distance_pct", "risk_pct", "risk_reward", "atr_pct",
        "spread_pct", "htf_confirmed", "setup_state", "wise_man_count",
        "signal_family", "wave_score", "wave_position", "wave_phase",
        "wave_confidence", "wave_exhaustion_risk", "nested_w3",
        "nested_w3_parent_w5", "wave_path", "wave_reason",
    )
    result = {k: getattr(candidate, k, None) for k in fields}
    result["scan_rank"] = rank
    result["universe_size"] = universe_size
    result["captured_at"] = datetime.now(timezone.utc).isoformat()
    return result


def observe(db, trade_id, entry_price, current_price, side="LONG"):
    entry = _num(entry_price, 0.0) or 0.0
    price = _num(current_price, 0.0) or 0.0
    if entry <= 0 or price <= 0:
        return None

    key_hi = f"trade:{trade_id}:best_price"
    key_lo = f"trade:{trade_id}:worst_price"
    best = _num(db.state_get(key_hi), entry)
    worst = _num(db.state_get(key_lo), entry)

    side = str(side).upper()
    if side == "SHORT":
        best = min(best, price)
        worst = max(worst, price)
        mfe = (entry - best) / entry
        mae = (worst - entry) / entry
    else:
        best = max(best, price)
        worst = min(worst, price)
        mfe = (best - entry) / entry
        mae = (worst - entry) / entry

    db.state_set(key_hi, best)
    db.state_set(key_lo, worst)
    return {"mfe_pct": mfe * 100.0, "mae_pct": mae * 100.0}


def build_diagnosis(entry_context, pnl, pnl_pct, exit_reason, mfe_pct=None, mae_pct=None):
    ctx = entry_context or {}
    pnl = _num(pnl, 0.0) or 0.0
    pnl_pct = _num(pnl_pct, 0.0) or 0.0
    wave_exhaustion = _num(ctx.get("wave_exhaustion_risk"), 0.0) or 0.0
    wave_position = int(ctx.get("wave_position") or 0)
    htf = bool(ctx.get("htf_confirmed"))
    spread = _num(ctx.get("spread_pct"), 0.0) or 0.0
    atr = _num(ctx.get("atr_pct"), 0.0) or 0.0
    setup = str(ctx.get("setup_state") or "")
    reason = str(exit_reason or "").upper()
    mfe = _num(mfe_pct, 0.0) or 0.0
    mae = abs(_num(mae_pct, 0.0) or 0.0)
    planned_target = abs((_num(ctx.get("risk_pct"), 0.0) or 0.0) * (_num(ctx.get("risk_reward"), 1.0) or 1.0))

    if pnl > 0:
        outcome = "WIN"
    elif pnl < 0:
        outcome = "LOSS"
    else:
        outcome = "BREAKEVEN"

    if outcome != "LOSS":
        diagnosis = "SETUP_WORKED" if outcome == "WIN" else "NO_EDGE"
    elif "STOP" in reason:
        if planned_target > 0 and mfe >= planned_target * 0.60:
            diagnosis = "STOP_TOO_TIGHT"
        elif wave_exhaustion >= 70 or wave_position == 5:
            diagnosis = "WAVE_EXHAUSTION"
        elif not htf:
            diagnosis = "HTF_CONFLICT"
        elif spread > 0.0015:
            diagnosis = "LIQUIDITY_SPREAD"
        elif atr > 0.06:
            diagnosis = "VOLATILITY_SPIKE"
        elif setup == "WATCHING":
            diagnosis = "WEAK_SETUP"
        elif mae >= max(planned_target * 0.75, 0.5):
            diagnosis = "BAD_ENTRY_LOCATION"
        else:
            diagnosis = "FALSE_BREAKOUT"
    elif "OCO" in reason or "RECOVER" in reason:
        diagnosis = "EXECUTION_RECOVERY"
    else:
        diagnosis = "MARKET_REGIME_SHIFT"

    detail = {
        "outcome": outcome,
        "diagnosis": diagnosis,
        "exit_reason": exit_reason,
        "loss_pct": round(pnl_pct * 100.0, 4),
        "wave_position": wave_position,
        "wave_exhaustion_risk": wave_exhaustion,
        "htf_confirmed": htf,
        "setup_state": setup,
        "mfe_pct": mfe,
        "mae_pct": mae,
        "planned_target_pct": planned_target,
    }
    return diagnosis, detail


def record_entry(db, trade_id, candidate, rank=None, universe_size=None):
    context = entry_context_from_candidate(candidate, rank, universe_size)
    db.save_trade_journal(trade_id=trade_id, entry_context=context)
    entry = _num(getattr(candidate, "entry_price", None), None)
    if entry is not None:
        db.state_set(f"trade:{trade_id}:best_price", entry)
        db.state_set(f"trade:{trade_id}:worst_price", entry)
    return context


def record_exit(db, trade, exit_price, pnl, pnl_pct, reason, exit_context=None):
    trade_id = int(trade["id"])
    entry = _num(trade.get("entry_price"), 0.0) or 0.0
    journal = db.get_trade_journal(trade_id) or {}
    entry_context = journal.get("entry_context") or {}
    extrema = observe(db, trade_id, entry, exit_price, trade.get("side", "LONG")) or {}

    opened = trade.get("entry_time")
    closed = trade.get("exit_time")
    duration = None
    try:
        if opened and closed:
            duration = max(
                0.0,
                (datetime.fromisoformat(closed.replace("Z", "+00:00"))
                 - datetime.fromisoformat(opened.replace("Z", "+00:00"))).total_seconds(),
            )
    except Exception:
        duration = None

    diagnosis, detail = build_diagnosis(entry_context, pnl, pnl_pct, reason, extrema.get('mfe_pct'), extrema.get('mae_pct'))
    db.save_trade_journal(
        trade_id=trade_id,
        exit_context=exit_context or {},
        mfe_pct=extrema.get("mfe_pct"),
        mae_pct=extrema.get("mae_pct"),
        duration_seconds=duration,
        diagnosis=diagnosis,
        diagnosis_detail=detail,
    )
    db.log_event("INFO", "trade_learning", "Trade outcome classified", {
        "trade_id": trade_id, "diagnosis": diagnosis,
        "mfe_pct": extrema.get("mfe_pct"),
        "mae_pct": extrema.get("mae_pct"),
    })
    for suffix in ("best_price", "worst_price"):
        db.state_delete(f"trade:{trade_id}:{suffix}")
    return diagnosis, detail
