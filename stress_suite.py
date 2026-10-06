"""Deterministic latency/slippage stress utilities for Williams research."""
from __future__ import annotations

import random
import time
from dataclasses import asdict, dataclass
from typing import Iterable


@dataclass(frozen=True)
class StressCase:
    latency_ms: int
    adverse_ticks: int
    expected_reward_pct: float
    fee_pct: float
    slippage_pct: float
    net_edge_pct: float
    passed: bool


@dataclass(frozen=True)
class StressSummary:
    scenario: str
    cases: int
    pass_rate: float
    worst_net_edge_pct: float
    p05_net_edge_pct: float
    passed: bool

    def to_dict(self):
        return asdict(self)


def run_latency_slippage_stress(
    *,
    expected_reward_pct: float,
    fee_pct: float,
    tick_pct: float = 0.0001,
    latency_range_ms: tuple[int, int] = (100, 500),
    adverse_ticks_range: tuple[int, int] = (1, 3),
    cases: int = 100,
    seed: int = 42,
    slippage_ceiling_pct: float = 0.0015,
) -> StressSummary:
    if expected_reward_pct < 0 or fee_pct < 0:
        raise ValueError("reward and fee must be non-negative")
    if tick_pct <= 0:
        raise ValueError("tick_pct must be positive")
    rng = random.Random(seed)
    rows: list[StressCase] = []
    for _ in range(max(1, int(cases))):
        latency = rng.randint(*latency_range_ms)
        ticks = rng.randint(*adverse_ticks_range)
        slippage = ticks * tick_pct
        net = expected_reward_pct - fee_pct - slippage
        # Latency is recorded/stressed, but it is not converted into an
        # invented slippage formula. Actual exchange data must calibrate that.
        passed = net > 0 and slippage <= slippage_ceiling_pct
        rows.append(StressCase(latency, ticks, expected_reward_pct, fee_pct, slippage, net, passed))

    values = sorted(x.net_edge_pct for x in rows)
    p05 = values[max(0, int(len(values) * 0.05) - 1)]
    return StressSummary(
        scenario="latency_slippage",
        cases=len(rows),
        pass_rate=sum(1 for x in rows if x.passed) / len(rows),
        worst_net_edge_pct=min(values),
        p05_net_edge_pct=p05,
        passed=all(x.passed for x in rows),
    )


__all__ = ["StressCase", "StressSummary", "run_latency_slippage_stress"]
