"""Offline contract tests: all fifteen configured Futures Demo instruments are probed."""

import pytest
from trading_config import DEFAULT_FUTURES_SYMBOLS
from tools.verify_public_futures_demo import SYMBOLS, validate_contracts


def fake_row(symbol: str) -> dict:
    return {
        "symbol": symbol, "status": "TRADING", "contractType": "PERPETUAL",
        "quoteAsset": "USDT",
        "filters": [{"filterType": "PRICE_FILTER"}, {"filterType": "LOT_SIZE"}],
    }


def test_demo_public_acceptance_has_the_exact_trading_universe():
    assert SYMBOLS == DEFAULT_FUTURES_SYMBOLS
    assert len(SYMBOLS) == len(set(SYMBOLS)) == 15
    validate_contracts({"symbols": [fake_row(s) for s in SYMBOLS]})


@pytest.mark.parametrize("missing", DEFAULT_FUTURES_SYMBOLS)
def test_one_absent_demo_contract_never_yields_an_all_green_probe(missing):
    rows = [fake_row(s) for s in SYMBOLS if s != missing]
    with pytest.raises(ValueError, match=missing):
        validate_contracts({"symbols": rows})


def test_duplicate_or_inactive_demo_contract_never_passes():
    rows = [fake_row(s) for s in SYMBOLS]
    rows.append(fake_row("SUIUSDT"))
    with pytest.raises(ValueError, match="SUIUSDT"):
        validate_contracts({"symbols": rows})
    rows.pop()
    rows[-1]["status"] = "BREAK"
    with pytest.raises(ValueError, match=SYMBOLS[-1]):
        validate_contracts({"symbols": rows})
