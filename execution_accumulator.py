"""Strict execution fill accumulator for authoritative quantity, VWAP and fees.

Missing/invalid exchange fields are not converted to zero and malformed rows are
not silently skipped. Callers must keep trading blocked when this raises.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation


class ExecutionDataIncomplete(ValueError):
    """Authoritative execution data is incomplete or internally inconsistent."""


@dataclass(frozen=True)
class ExecutionSummary:
    executed_qty: Decimal
    quote_qty: Decimal
    avg_price: Decimal
    fee_quote: Decimal = Decimal("0")
    commission_base: Decimal = Decimal("0")
    fee_quote_equivalent: Decimal = Decimal("0")
    financials_complete: bool = True
    trade_ids: tuple[str, ...] = ()

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
            "financials_complete": self.financials_complete,
            "trade_ids": list(self.trade_ids),
        }


def _decimal(fill: dict, key: str, *, positive: bool = False) -> Decimal:
    if key not in fill or fill[key] is None or str(fill[key]).strip() == "":
        raise ExecutionDataIncomplete(f"fill_missing_{key}")
    try:
        value = Decimal(str(fill[key]))
    except (InvalidOperation, ValueError, TypeError):
        raise ExecutionDataIncomplete(f"fill_invalid_{key}") from None
    if not value.is_finite() or (value <= 0 if positive else value < 0):
        raise ExecutionDataIncomplete(f"fill_invalid_{key}")
    return value


def accumulate_fills(fills, base_asset=None) -> ExecutionSummary:
    qty = Decimal("0")
    quote = Decimal("0")
    fee_quote = Decimal("0")
    commission_base = Decimal("0")
    fee_quote_equivalent = Decimal("0")
    seen_ids: dict[str, str] = {}
    trade_ids: list[str] = []
    quote_assets = {"USDT", "USDC", "FDUSD", "BUSD"}
    base = str(base_asset or "").upper()

    for index, fill in enumerate(fills or []):
        if not isinstance(fill, dict):
            raise ExecutionDataIncomplete(f"fill_{index}_not_object")
        trade_id_value = fill.get("tradeId", fill.get("id"))
        if trade_id_value is None or str(trade_id_value).strip() == "":
            raise ExecutionDataIncomplete(f"fill_{index}_missing_trade_id")
        trade_id = str(trade_id_value).strip()
        # A repeated ID inside one response is not silently counted twice.
        signature = repr(sorted((str(k), str(v)) for k, v in fill.items()))
        if trade_id in seen_ids:
            if seen_ids[trade_id] != signature:
                raise ExecutionDataIncomplete("conflicting_duplicate_trade_id")
            raise ExecutionDataIncomplete("duplicate_trade_id")
        seen_ids[trade_id] = signature
        trade_ids.append(trade_id)

        q = _decimal(fill, "qty", positive=True)
        p = _decimal(fill, "price", positive=True)
        commission = _decimal(fill, "commission")
        asset = str(fill.get("commissionAsset") or "").strip().upper()
        if not asset:
            raise ExecutionDataIncomplete("fill_missing_commission_asset")
        # A zero fee is accepted only when the exchange explicitly returned both
        # commission=0 and a non-empty commissionAsset.
        qty += q
        quote += q * p
        if asset in quote_assets:
            fee_quote += commission
            fee_quote_equivalent += commission
        elif base and asset == base:
            commission_base += commission
            fee_quote_equivalent += commission * p
        elif commission != 0:
            # Third-asset valuation must be performed by a caller with a
            # separately verified conversion price; never assume zero value.
            raise ExecutionDataIncomplete(f"unvalued_commission_asset:{asset}")

    avg = quote / qty if qty > 0 else Decimal("0")
    return ExecutionSummary(
        qty, quote, avg, fee_quote, commission_base, fee_quote_equivalent,
        True, tuple(trade_ids),
    )


def accumulate_order(order: dict, base_asset=None) -> ExecutionSummary:
    if not isinstance(order, dict):
        raise ExecutionDataIncomplete("order_not_object")
    fills = order.get("fills")
    qty = _decimal(order, "executedQty") if "executedQty" in order else Decimal("0")
    quote_raw = order.get("cummulativeQuoteQty", order.get("cumulativeQuoteQty", "0"))
    try:
        order_quote = Decimal(str(quote_raw))
    except (InvalidOperation, ValueError, TypeError):
        raise ExecutionDataIncomplete("order_invalid_quote_quantity") from None
    if not order_quote.is_finite() or order_quote < 0:
        raise ExecutionDataIncomplete("order_invalid_quote_quantity")

    if fills:
        summary = accumulate_fills(fills, base_asset=base_asset)
        # Compare exact exchange aggregates against per-trade history. Binance
        # decimal totals can differ only by serialization precision; no tolerance
        # is used here because both fields are exchange-reported decimals.
        if summary.executed_qty != qty or summary.quote_qty != order_quote:
            raise ExecutionDataIncomplete("order_fill_totals_mismatch")
        return summary

    if qty > 0 or order_quote > 0:
        raise ExecutionDataIncomplete("executed_order_missing_authoritative_fills")
    return ExecutionSummary(Decimal("0"), Decimal("0"), Decimal("0"))
