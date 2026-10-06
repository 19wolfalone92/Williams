"""No-order execution simulator for Williams AI-shadow research."""
from __future__ import annotations

import random
import time
from dataclasses import asdict, dataclass
from typing import Any

from ai_shadow import AIOrderIntent


@dataclass(frozen=True)
class ShadowFill:
    intent_id: str
    timestamp_ms: int
    symbol: str
    direction: str
    requested_fraction: float
    filled_fraction: float
    reference_price: float
    fill_price: float
    latency_ms: int
    slippage_pct: float
    fee_pct: float
    net_edge_pct: float
    executed: bool

    def to_dict(self) -> dict:
        return asdict(self)


class ShadowExecutionSimulator:
    """Simulates fills without touching a client, socket or exchange API."""

    def __init__(
        self,
        fee_pct: float = 0.001,
        latency_range_ms: tuple[int, int] = (100, 500),
        slippage_ticks: tuple[int, int] = (1, 3),
        tick_pct: float = 0.0001,
        seed: int = 42,
    ):
        self.fee_pct = max(0.0, float(fee_pct))
        self.latency_range_ms = latency_range_ms
        self.slippage_ticks = slippage_ticks
        self.tick_pct = max(0.0, float(tick_pct))
        self.rng = random.Random(seed)

    def simulate(
        self,
        intent: AIOrderIntent,
        *,
        reference_price: float,
        expected_reward_pct: float,
    ) -> ShadowFill:
        price = float(reference_price)
        if price <= 0:
            raise ValueError("reference_price must be positive")
        if intent.direction not in {"LONG", "SHORT"}:
            return ShadowFill(
                intent_id=intent.intent_id,
                timestamp_ms=int(time.time() * 1000),
                symbol=intent.symbol,
                direction=intent.direction,
                requested_fraction=intent.suggested_size_fraction,
                filled_fraction=0.0,
                reference_price=price,
                fill_price=price,
                latency_ms=0,
                slippage_pct=0.0,
                fee_pct=self.fee_pct,
                net_edge_pct=float(expected_reward_pct) - self.fee_pct,
                executed=False,
            )

        latency = self.rng.randint(*self.latency_range_ms)
        ticks = self.rng.randint(*self.slippage_ticks)
        slippage = ticks * self.tick_pct
        adverse = slippage if intent.direction == "LONG" else -slippage
        fill_price = price * (1.0 + adverse)
        net_edge = float(expected_reward_pct) - self.fee_pct - slippage
        executed = net_edge > 0.0
        return ShadowFill(
            intent_id=intent.intent_id,
            timestamp_ms=int(time.time() * 1000),
            symbol=intent.symbol,
            direction=intent.direction,
            requested_fraction=float(intent.suggested_size_fraction),
            filled_fraction=float(intent.suggested_size_fraction) if executed else 0.0,
            reference_price=price,
            fill_price=fill_price,
            latency_ms=latency,
            slippage_pct=slippage,
            fee_pct=self.fee_pct,
            net_edge_pct=net_edge,
            executed=executed,
        )


__all__ = ["ShadowFill", "ShadowExecutionSimulator"]
