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
    commission_base: Decimal = Decimal("0")
    fee_quote_equivalent: Decimal = Decimal("0")

    @property
    def filled(self) -> bool:
        return self.executed_qty > 0 and self.quote_qty > 0

    @property
    def net_base_qty(self) -> Decimal:
        return max(Decimal("0"), self.executed_qty - self.commission_base)

    def as_dict(self) -> dict:
        return {
            "executed_qty": str(self.executed_qty),
            "quote_qty": str(self.quote_qty),
            "avg_price": str(self.avg_price),
            "fee_quote": str(self.fee_quote),
            "commission_base": str(self.commission_base),
            "net_base_qty": str(self.net_base_qty),
            "fee_quote_equivalent": str(self.fee_quote_equivalent),
        }


def accumulate_fills(fills, base_asset=None) -> ExecutionSummary:
    qty = Decimal("0")
    quote = Decimal("0")
    fee_quote = Decimal("0")
    commission_base = Decimal("0")
    fee_quote_equivalent = Decimal("0")
    for fill in fills or []:
        q = Decimal(str(fill.get("qty", "0") or "0"))
        p = Decimal(str(fill.get("price", "0") or "0"))
        commission = Decimal(str(fill.get("commission", "0") or "0"))
        if q <= 0 or p <= 0:
            continue
        qty += q
        quote += q * p
        commission_asset = str(fill.get("commissionAsset", "")).upper()
        if commission_asset in {"USDT", "USDC", "FDUSD", "BUSD"}:
            fee_quote += commission
            fee_quote_equivalent += commission
        if base_asset and commission_asset == str(base_asset).upper():
            commission_base += commission
            fee_quote_equivalent += commission * p
    avg = quote / qty if qty > 0 else Decimal("0")
    return ExecutionSummary(
        qty,
        quote,
        avg,
        fee_quote,
        commission_base,
        fee_quote_equivalent,
    )


def accumulate_order(order: dict, base_asset=None) -> ExecutionSummary:
    fills = order.get("fills") or []
    summary = accumulate_fills(fills, base_asset=base_asset)
    if summary.filled:
        return summary
    qty = Decimal(str(order.get("executedQty", "0") or "0"))
    quote = Decimal(str(order.get("cummulativeQuoteQty", "0") or "0"))
    avg = quote / qty if qty > 0 and quote > 0 else Decimal(str(order.get("price", "0") or "0"))
    return ExecutionSummary(qty, quote, avg)
