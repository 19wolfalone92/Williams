"""Canonical Binance Spot symbol rules and Decimal-only order math.

This module is the single validation/normalisation boundary before an order
reaches Binance. Trading quantities, prices and notionals must not depend on
binary floating-point arithmetic.
"""

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP
from typing import Any, Mapping

D0 = Decimal("0")


def D(value: Any) -> Decimal:
    return Decimal(str(value))


def _floor_step(value: Decimal, step: Decimal) -> Decimal:
    if step <= D0:
        return value
    return (value / step).to_integral_value(rounding=ROUND_DOWN) * step


@dataclass(frozen=True)
class SymbolRules:
    symbol: str
    base_asset: str
    quote_asset: str
    min_qty: Decimal = D0
    max_qty: Decimal = D0
    step_size: Decimal = D0
    min_price: Decimal = D0
    max_price: Decimal = D0
    tick_size: Decimal = D0
    min_notional: Decimal = D0

    @classmethod
    def from_exchange_info(cls, payload: Mapping[str, Any], symbol: str | None = None):
        rows = payload.get("symbols", []) if isinstance(payload, Mapping) else []
        selected = next(
            (row for row in rows if not symbol or row.get("symbol") == symbol),
            None,
        )
        if not selected:
            raise ValueError(f"Symbol metadata unavailable: {symbol or 'unknown'}")
        filters = {
            str(item.get("filterType")): item
            for item in selected.get("filters", [])
            if item.get("filterType")
        }
        lot = filters.get("LOT_SIZE") or filters.get("MARKET_LOT_SIZE") or {}
        price = filters.get("PRICE_FILTER") or {}
        notional = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
        return cls(
            symbol=str(selected.get("symbol", symbol or "")).upper(),
            base_asset=str(selected.get("baseAsset", "")),
            quote_asset=str(selected.get("quoteAsset", "")),
            min_qty=D(lot.get("minQty", "0")),
            max_qty=D(lot.get("maxQty", "0")),
            step_size=D(lot.get("stepSize", "0")),
            min_price=D(price.get("minPrice", "0")),
            max_price=D(price.get("maxPrice", "0")),
            tick_size=D(price.get("tickSize", "0")),
            min_notional=D(notional.get("minNotional", "0")),
        )

    def effective_min_notional(self, buffer_pct: Decimal = Decimal("0")) -> Decimal:
        return self.min_notional * (D("1") + D(buffer_pct) / D("100"))


class OrderMath:
    @staticmethod
    def normalize_quantity(qty: Decimal | str | float, rules: SymbolRules) -> Decimal:
        return _floor_step(D(qty), rules.step_size)

    @staticmethod
    def normalize_price(
        price: Decimal | str | float,
        rules: SymbolRules,
        *,
        rounding=ROUND_HALF_UP,
    ) -> Decimal:
        value = D(price)
        if rules.tick_size <= D0:
            return value
        return (value / rules.tick_size).to_integral_value(rounding=rounding) * rules.tick_size

    @staticmethod
    def safe_stop_price(price: Decimal | str | float, rules: SymbolRules) -> Decimal:
        return OrderMath.normalize_price(price, rules, rounding=ROUND_DOWN)

    @staticmethod
    def validate_quantity(qty: Decimal | str | float, rules: SymbolRules) -> bool:
        value = D(qty)
        if value <= D0:
            return False
        if rules.min_qty and value < rules.min_qty:
            return False
        if rules.max_qty and value > rules.max_qty:
            return False
        return not rules.step_size or value == OrderMath.normalize_quantity(value, rules)

    @staticmethod
    def validate_price(price: Decimal | str | float, rules: SymbolRules) -> bool:
        value = D(price)
        if value <= D0:
            return False
        if rules.min_price and value < rules.min_price:
            return False
        if rules.max_price and value > rules.max_price:
            return False
        return not rules.tick_size or value == OrderMath.normalize_price(value, rules)

    @staticmethod
    def validate_notional(qty, price, rules: SymbolRules, buffer_pct=Decimal("0")) -> bool:
        return D(qty) * D(price) >= rules.effective_min_notional(D(buffer_pct))

    @staticmethod
    def validate_order(qty, price, rules: SymbolRules, buffer_pct=Decimal("0")) -> bool:
        return (
            OrderMath.validate_quantity(qty, rules)
            and OrderMath.validate_price(price, rules)
            and OrderMath.validate_notional(qty, price, rules, buffer_pct)
        )

    @staticmethod
    def oco_quantity(filled_qty, free_balance, rules: SymbolRules) -> Decimal:
        return OrderMath.normalize_quantity(min(D(filled_qty), D(free_balance)), rules)
