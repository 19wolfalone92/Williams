"""Explicitly opt-in, read-only Binance USDⓈ-M Futures Demo release gate.

The gate never places or cancels orders. State-transition and order-lifecycle
checks are deterministic offline tests; any order-mutating Testnet scenario
must be run separately and explicitly by an operator.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass
from typing import Any, Iterable

from binance_usdm_futures_client import BinanceUsdmFuturesClient


@dataclass(frozen=True)
class FuturesGateStep:
    id: str
    phase: str
    description: str
    mode: str


FUTURES_GATE_STEPS = (
    FuturesGateStep("F01", "environment", "USDⓈ-M Futures Demo endpoint is selected", "OFFLINE"),
    FuturesGateStep("F02", "environment", "Mainnet remains disabled without dual explicit opt-in", "OFFLINE"),
    FuturesGateStep("F03", "auth", "Demo API credentials and signed timestamp are accepted", "TESTNET_READ_ONLY"),
    FuturesGateStep("F04", "account", "Futures account endpoint is reachable", "TESTNET_READ_ONLY"),
    FuturesGateStep("F05", "account", "One-way mode, isolated margin and 1x leverage are confirmed", "TESTNET_READ_ONLY"),
    FuturesGateStep("F06", "account", "Existing positions and open standard orders are enumerated", "TESTNET_READ_ONLY"),
    FuturesGateStep("F07", "account", "Open Algo orders are enumerated for orphan-stop detection", "TESTNET_READ_ONLY"),
    FuturesGateStep("F08", "market", "Every configured symbol is TRADING perpetual with quote/margin metadata", "TESTNET_READ_ONLY"),
    FuturesGateStep("F09", "market", "PRICE_FILTER and LOT_SIZE/MARKET_LOT_SIZE are available", "TESTNET_READ_ONLY"),
    FuturesGateStep("F10", "market", "Mark price is finite and positive", "TESTNET_READ_ONLY"),
    FuturesGateStep("F11", "direction", "LONG entry maps to BUY and its exit maps to SELL", "OFFLINE"),
    FuturesGateStep("F12", "direction", "SHORT entry maps to SELL and its exit maps to BUY", "OFFLINE"),
    FuturesGateStep("F13", "execution", "Stable client IDs prevent duplicate mutation after unknown response", "OFFLINE"),
    FuturesGateStep("F14", "execution", "Partial entry/add-on fills reconcile to position and userTrades", "OFFLINE"),
    FuturesGateStep("F15", "protection", "Stop replacement preserves old protection until new stop is verified", "OFFLINE"),
    FuturesGateStep("F16", "protection", "Flat-position recovery cancels orphaned protective Algo orders", "OFFLINE"),
    FuturesGateStep("F17", "recovery", "Filled pending market exits reconcile after restart without duplicate submit", "OFFLINE"),
    FuturesGateStep("F18", "risk", "Daily loss and kill switch block new exposure but preserve open-position management", "OFFLINE"),
    FuturesGateStep("F19", "recovery", "SQLite state and authoritative exchange state reconcile after restart", "TESTNET_TRADE"),
    FuturesGateStep("F20", "release", "Full LONG/SHORT entry-protection-exit lifecycle is verified on Demo", "TESTNET_TRADE"),
)


def futures_testnet_e2e_enabled() -> bool:
    return os.getenv("WILLIAMS_FUTURES_TESTNET_E2E", "").strip() == "1"


def assert_futures_testnet_opt_in() -> None:
    if not futures_testnet_e2e_enabled():
        raise RuntimeError(
            "Futures Demo checks are disabled. Set WILLIAMS_FUTURES_TESTNET_E2E=1 explicitly."
        )
    if os.getenv("TESTNET", "true").strip().lower() != "true":
        raise RuntimeError("Futures Demo gate refuses to run unless TESTNET=true.")
    if not os.getenv("BINANCE_API_KEY") or not os.getenv("BINANCE_API_SECRET"):
        raise RuntimeError("Futures Demo gate requires BINANCE_API_KEY and BINANCE_API_SECRET.")


def run_futures_testnet_read_only(symbols: Iterable[str] = ("BTCUSDT",)) -> dict[str, Any]:
    """Check authenticated Futures Demo state without submitting/cancelling orders."""
    assert_futures_testnet_opt_in()
    configured = tuple(dict.fromkeys(str(s).strip().upper() for s in symbols if str(s).strip()))
    if not configured:
        raise ValueError("At least one Futures symbol is required")
    client = BinanceUsdmFuturesClient(
        os.environ["BINANCE_API_KEY"],
        os.environ["BINANCE_API_SECRET"],
        testnet=True,
        allow_live=False,
        max_leverage=1,
    )
    if client.base_url != BinanceUsdmFuturesClient.DEMO_BASE_URL:
        raise RuntimeError(f"Refusing Futures Demo gate: unexpected endpoint {client.base_url!r}")

    time_sync = client.sync_time()
    mode = client.ensure_one_way_mode()
    account = client.account()
    if not isinstance(account, dict):
        raise RuntimeError("Futures account endpoint returned an unexpected payload")
    can_trade = account.get("canTrade")
    if can_trade is not True and str(can_trade).strip().lower() != "true":
        raise RuntimeError("Futures Demo account does not confirm canTrade=true")
    positions = client.position_risk()
    if not isinstance(positions, list):
        raise RuntimeError("Futures positionRisk endpoint returned an unexpected payload")

    market_checks = []
    for symbol in configured:
        info = client.exchange_info(symbol)
        rows = info.get("symbols", []) if isinstance(info, dict) else []
        meta = next((row for row in rows if str(row.get("symbol", "")).upper() == symbol), None)
        if not meta:
            raise RuntimeError(f"{symbol}: missing from Futures exchangeInfo")
        if str(meta.get("status", "")).upper() != "TRADING":
            raise RuntimeError(f"{symbol}: exchange status is not TRADING")
        if str(meta.get("contractType", "")).upper() != "PERPETUAL":
            raise RuntimeError(f"{symbol}: expected a perpetual contract")
        if str(meta.get("quoteAsset", "")).upper() != "USDT":
            raise RuntimeError(f"{symbol}: configured Futures quote asset must be USDT")
        if str(meta.get("marginAsset", "")).upper() != "USDT":
            raise RuntimeError(f"{symbol}: configured Futures margin asset must be USDT")
        filters = client.symbol_filters(symbol)
        if not {"PRICE_FILTER", "LOT_SIZE", "MARKET_LOT_SIZE"}.issubset(set(filters)):
            raise RuntimeError(f"{symbol}: PRICE_FILTER, LOT_SIZE and MARKET_LOT_SIZE are required")
        symbol_positions = client.position_risk(symbol)
        if isinstance(symbol_positions, dict):
            position_rows = [symbol_positions] if str(symbol_positions.get("symbol", "")).upper() == symbol else []
        elif isinstance(symbol_positions, list):
            position_rows = [
                row for row in symbol_positions
                if isinstance(row, dict) and str(row.get("symbol", "")).upper() == symbol
            ]
        else:
            raise RuntimeError(f"{symbol}: positionRisk returned an unexpected payload")
        position = next(iter(position_rows), None)
        if position is None:
            raise RuntimeError(f"{symbol}: positionRisk omitted the configured symbol")
        isolated_raw = position.get("isolated")
        isolated = isolated_raw is True or str(isolated_raw).strip().lower() in {"true", "1"}
        try:
            leverage = int(position.get("leverage"))
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"{symbol}: Futures leverage is unavailable") from exc
        if not isolated:
            raise RuntimeError(f"{symbol}: isolated margin is required before trading")
        if leverage != 1:
            raise RuntimeError(f"{symbol}: Futures leverage must be 1x, observed {leverage}x")
        mark_payload = client.mark_price(symbol)
        try:
            mark = float(mark_payload.get("markPrice"))
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"{symbol}: invalid mark price payload") from exc
        if not math.isfinite(mark) or mark <= 0:
            raise RuntimeError(f"{symbol}: mark price is not finite and positive")
        market_checks.append({
            "symbol": symbol,
            "status": meta.get("status"),
            "contract_type": meta.get("contractType"),
            "mark_price": mark,
            "quote_asset": str(meta.get("quoteAsset", "")).upper(),
            "margin_asset": str(meta.get("marginAsset", "")).upper(),
            "isolated_margin": isolated,
            "leverage": leverage,
            "filters": sorted(filters),
        })

    standard_orders = client.open_orders()
    algo_orders = client.open_algo_orders()
    if not isinstance(standard_orders, list) or not isinstance(algo_orders, list):
        raise RuntimeError("Futures open-order endpoints returned unexpected payloads")
    return {
        "status": "PASS_READ_ONLY",
        "endpoint": client.base_url,
        "time_sync": time_sync,
        "one_way_mode": mode,
        "account_can_trade": can_trade,
        "positions_seen": len(positions),
        "open_standard_orders_seen": len(standard_orders),
        "open_algo_orders_seen": len(algo_orders),
        "markets": market_checks,
        "mutations_submitted": 0,
        "warning": "This is read-only preflight, not proof of a full Testnet trade lifecycle.",
    }
