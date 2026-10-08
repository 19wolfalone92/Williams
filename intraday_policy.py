"""Operational policy around, but outside, Williams Core truth."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Iterable

from intraday_contract import WilliamsIntradayContract, session_decision


@dataclass(frozen=True)
class IntradayPolicyDecision:
    allow_new_campaign: bool
    cancel_pending: bool
    force_flat: bool
    block_reason: str = ""


def evaluate_intraday_policy(
    now: datetime,
    contract: WilliamsIntradayContract,
    *,
    active_campaigns: int,
    daily_loss_pct: float,
    full_stop_outs: int,
) -> IntradayPolicyDecision:
    session = session_decision(now, contract)
    if daily_loss_pct >= contract.max_daily_loss_pct:
        return IntradayPolicyDecision(False, True, True, "MAX_DAILY_LOSS_REACHED")
    if full_stop_outs >= contract.max_full_stop_outs:
        return IntradayPolicyDecision(False, True, True, "MAX_FULL_STOP_OUTS_REACHED")
    if active_campaigns >= contract.max_open_campaigns:
        return IntradayPolicyDecision(False, False, False, "ACTIVE_CAMPAIGN_LIMIT")
    if session.force_flat:
        return IntradayPolicyDecision(False, True, True, session.reason)
    if not session.new_entries_allowed:
        return IntradayPolicyDecision(False, False, False, session.reason)
    return IntradayPolicyDecision(True, False, False, "")


def campaign_is_stagnant(
    *,
    no_new_confirmation: bool,
    alligator_not_opening: bool,
    no_price_progress: bool,
) -> bool:
    return bool(no_new_confirmation and alligator_not_opening and no_price_progress)
