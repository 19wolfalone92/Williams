"""Regression tests for the canonical Futures timeframe contract."""

from futures_williams_runtime import FuturesWilliamsScanner


class NoNetworkClient:
    pass


def test_scanner_defaults_to_h1_decisions_and_h4_context():
    scanner = FuturesWilliamsScanner(NoNetworkClient())
    assert scanner.interval == "1h"
    assert scanner.htf_interval == "4h"


def test_scanner_accepts_explicit_canonical_timeframes():
    scanner = FuturesWilliamsScanner(
        NoNetworkClient(),
        interval="1h",
        htf_interval="4h",
    )
    assert scanner.interval == "1h"
    assert scanner.htf_interval == "4h"
