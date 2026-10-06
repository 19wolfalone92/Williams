"""Depth-based slippage guard for market orders."""
from __future__ import annotations

from decimal import Decimal


def expected_fill_from_depth(levels, required_quote, side: str):
    remaining = Decimal(str(required_quote))
    spent = Decimal("0")
    filled_qty = Decimal("0")
    side_key = "asks" if side.upper() == "BUY" else "bids"
    for level in levels.get(side_key, []) if isinstance(levels, dict) else []:
        price = Decimal(str(level[0]))
        qty = Decimal(str(level[1]))
        if price <= 0 or qty <= 0:
            continue
        level_quote = price * qty
        take_quote = min(remaining, level_quote)
        take_qty = take_quote / price
        spent += take_quote
        filled_qty += take_qty
        remaining -= take_quote
        if remaining <= 1e-12:
            break
    if remaining > 1e-9 or filled_qty <= 0:
        raise ValueError("insufficient depth for requested market order")
    avg_price = spent / filled_qty
    return avg_price, filled_qty


class L2SlippageGuard:
    def __init__(self, max_slippage_pct: float = 0.0015, depth_limit: int = 100):
        self.max_slippage_pct = max(0.0, float(max_slippage_pct))
        self.depth_limit = max(5, int(depth_limit))

    def check_buy_quote(self, client, symbol: str, quote_qty: float) -> dict:
        book = client.book_ticker(symbol)
        ask = Decimal(str(book["askPrice"]))
        if ask <= 0:
            raise ValueError("invalid best ask")
        avg, qty = expected_fill_from_depth(
            client.depth(symbol, limit=self.depth_limit),
            Decimal(str(quote_qty)),
            "BUY",
        )
        slippage = max(0.0, avg / ask - 1.0)
        if slippage > Decimal(str(self.max_slippage_pct)):
            raise ValueError(
                f"L2 slippage {slippage:.4%} exceeds {self.max_slippage_pct:.4%}"
            )
        return {"best_price": float(ask), "avg_price": float(avg), "qty": float(qty), "slippage_pct": float(slippage)}
