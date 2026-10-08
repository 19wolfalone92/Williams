"""Execution-economics gate for Williams Intraday.

The gate never changes Williams Core truth.  It only answers whether a valid
signal is economically executable under current spread, slippage and fee
conditions.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ExecutionEconomics:
    spread_pct: float = 0.0
    estimated_slippage_pct: float = 0.0
    entry_fee_pct: float = 0.0
    exit_fee_pct: float = 0.0
    expected_move_pct: float = 0.0
    min_edge_multiple: float = 2.0


@dataclass(frozen=True)
class ExecutionEconomicsDecision:
    feasible: bool
    block_reason: str = ""
    round_trip_cost_pct: float = 0.0
    required_edge_pct: float = 0.0


def evaluate_execution_economics(x: ExecutionEconomics) -> ExecutionEconomicsDecision:
    values = (
        x.spread_pct,
        x.estimated_slippage_pct,
        x.entry_fee_pct,
        x.exit_fee_pct,
        x.expected_move_pct,
    )
    if any(v < 0 for v in values):
        return ExecutionEconomicsDecision(False, "NEGATIVE_EXECUTION_INPUTS")
    round_trip = max(0.0, x.spread_pct) + max(0.0, x.estimated_slippage_pct) * 2.0
    round_trip += max(0.0, x.entry_fee_pct) + max(0.0, x.exit_fee_pct)
    required = round_trip * max(1.0, x.min_edge_multiple)
    if x.expected_move_pct <= 0:
        return ExecutionEconomicsDecision(False, "EXPECTED_MOVE_UNAVAILABLE", round_trip, required)
    if x.expected_move_pct <= required:
        return ExecutionEconomicsDecision(False, "BLOCKED_BY_EXECUTION_ECONOMICS", round_trip, required)
    return ExecutionEconomicsDecision(True, "", round_trip, required)
