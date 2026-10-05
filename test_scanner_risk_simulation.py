"""Optional Binance Testnet scanner/risk smoke test.

The test is intentionally skipped when credentials or network access are not
available. It never places an order and always uses Binance Spot Testnet.
"""
from pathlib import Path
import os

import pytest
from dotenv import load_dotenv

load_dotenv(Path('.env'))

from binance_client import BinanceSpotClient
from market_scanner import MarketScanner
from risk_engine import RiskEngine


def pct(v):
    return f"{float(v) * 100:.3f}%"


def floor_step(value, step):
    if step <= 0:
        return value
    return int(value / step) * step


def _require_credentials():
    api_key = os.getenv('BINANCE_API_KEY')
    api_secret = os.getenv('BINANCE_API_SECRET')
    if not api_key or not api_secret:
        pytest.skip('BINANCE_API_KEY/BINANCE_API_SECRET not configured')
    return api_key, api_secret


def test_scanner_risk_simulation():
    api_key, api_secret = _require_credentials()

    client = BinanceSpotClient(
        api_key=api_key,
        api_secret=api_secret,
        testnet=True,
    )

    try:
        account = client.account()
    except Exception as exc:
        pytest.skip(f'Binance Spot Testnet unavailable: {type(exc).__name__}: {exc}')

    scanner = MarketScanner(client)
    balance = next(
        (
            float(b['free'])
            for b in account.get('balances', [])
            if b.get('asset') == 'USDT'
        ),
        0.0,
    )
    risk = RiskEngine(balance_quote=balance)

    candidates = scanner.scan()
    if not candidates:
        return

    for candidate in candidates:
        assert candidate.score <= 100.0
        assert candidate.atr_pct <= scanner.max_atr_pct
        assert candidate.spread_pct <= scanner.max_spread_pct
        assert candidate.risk_reward >= scanner.min_risk_reward
        if candidate.signal and scanner.require_htf_confirmation:
            assert candidate.htf_confirmed is True

        if not candidate.signal:
            continue

        try:
            info = client.exchange_info(candidate.symbol)
            ticker = client.ticker_price(candidate.symbol)
        except Exception as exc:
            pytest.skip(
                f'Binance Spot Testnet unavailable during symbol validation: '
                f'{type(exc).__name__}: {exc}'
            )

        symbols = info.get('symbols', [])
        assert symbols
        symbol_info = symbols[0]
        assert symbol_info.get('status') == 'TRADING'

        filters = {f['filterType']: f for f in symbol_info.get('filters', [])}
        assert 'PRICE_FILTER' in filters
        assert 'LOT_SIZE' in filters or 'MARKET_LOT_SIZE' in filters
        assert 'NOTIONAL' in filters or 'MIN_NOTIONAL' in filters

        entry_price = float(ticker['price'])
        assert entry_price > 0

        # Risk calculation is offline math after the read-only market data calls.
        atr = entry_price * candidate.atr_pct
        result = risk.calculate_position(
            entry_price=entry_price,
            atr=atr,
            signal_strength=1.0,
            htf_confirmed=candidate.htf_confirmed,
            spread_pct=candidate.spread_pct,
            max_spread_pct=scanner.max_spread_pct,
        )
        assert result is not None
        assert result.stop_price > 0
        assert result.take_profit_price > result.entry_price
        assert result.score <= 100.0
