"""Explicit Binance Spot data contract required by Williams Core.

Binance is a data/execution source.  Williams signals are computed locally;
Binance never emits a "Williams signal".
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any


WILLIAMS_TIMEFRAMES = (
    "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
    "6h", "8h", "12h", "1d", "3d", "1w", "1M",
)


@dataclass(frozen=True)
class BinanceDataContract:
    market: tuple[str, ...] = (
        "exchangeInfo",
        "klines",
        "ticker_price",
        "book_ticker",
        "depth",
        "trades_or_aggTrades",
        "ticker_24h",
        "server_time",
    )
    account: tuple[str, ...] = (
        "account",
        "myTrades",
    )
    execution: tuple[str, ...] = (
        "new_order",
        "get_order",
        "cancel_order",
        "cancel_replace",
        "open_orders",
        "all_orders",
        "order_lists",
        "order_execution_reports",
    )
    realtime: tuple[str, ...] = (
        "kline",
        "bookTicker",
        "depth",
        "aggTrade",
        "user_executionReport",
        "user_outboundAccountPosition",
        "user_listStatus",
    )
    authority: tuple[str, ...] = (
        "REST_is_reconciliation_authority",
        "WebSocket_is_low_latency_accelerator",
    )

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["williams_timeframes"] = list(WILLIAMS_TIMEFRAMES)
        data["derived_signals"] = [
            "Alligator", "Angulation", "Fractals",
            "AO", "AC", "MFI/Profitunity Windows",
            "Elliott/MTF structure", "Zone", "Balance Line",
            "Three Wise Men", "Exhaustion",
        ]
        return data
