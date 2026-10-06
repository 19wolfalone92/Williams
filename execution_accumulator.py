"""Execution fill accumulator for authoritative quantity and VWAP."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal


@dataclass(frozen=True)
class ExecutionSummary:
    executed_qty: Decimal
    quote_qty: Decimal
    avg_price: Decimal
    fee_quote: Decimal = Decimal("0")

    @property
    def filled(self) -> bool:
        return self.executed_qty > 0 and self.quote_qty > 0

    def as_dict(self) -> dict:
        return {
            "executed_qty": str(self.executed_qty),
            "quote_qty": str(self.quote_qty),
            "avg_price": str(self.avg_price),
            "fee_quote": str(self.fee_quote),
        }


def accumulate_fills(fills) -> ExecutionSummary:
    qty = Decimal("0")
    quote = Decimal("0")
    fee_quote = Decimal("0")
    for fill in fills or []:
        q = Decimal(str(fill.get("qty", "0") or "0"))
        p = Decimal(str(fill.get("price", "0") or "0"))
        commission = Decimal(str(fill.get("commission", "0") or "0"))
        if q <= 0 or p <= 0:
            continue
        qty += q
        quote += q * p
        if str(fill.get("commissionAsset", "")).upper() in {"USDT", "USDC", "FDUSD", "BUSD"}:
            fee_quote += commission
    avg = quote / qty if qty > 0 else Decimal("0")
    return ExecutionSummary(qty, quote, avg, fee_quote)


def accumulate_order(order: dict) -> ExecutionSummary:
    fills = order.get("fills") or []
    summary = accumulate_fills(fills)
    if summary.filled:
        return summary
    qty = Decimal(str(order.get("executedQty", "0") or "0"))
    quote = Decimal(str(order.get("cummulativeQuoteQty", "0") or "0"))
    avg = quote / qty if qty > 0 and quote > 0 else Decimal(str(order.get("price", "0") or "0"))
    return ExecutionSummary(qty, quote, avg)
