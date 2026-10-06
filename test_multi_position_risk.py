import os

from types import SimpleNamespace

from portfolio_controller import PortfolioController


class FakeClient:
    def ticker_price(self, symbol):
        return {"price": "100.0"}

    def exchange_info(self, symbol):
        return {"symbols": [{"filters": [{"filterType": "MIN_NOTIONAL", "minNotional": "10"}]}]}


class FakeScanner:
    max_spread_pct = 0.0015

    def scan(self):
        return [
            SimpleNamespace(symbol="BTCUSDT", signal=True, score=95.0, signal_strength=1.0,
                             htf_confirmed=True, spread_pct=0.0001, atr_pct=0.02,
                             wave_invalidation_price=98.0),
            SimpleNamespace(symbol="ETHUSDT", signal=True, score=90.0, signal_strength=1.0,
                             htf_confirmed=True, spread_pct=0.0001, atr_pct=0.02,
                             wave_invalidation_price=98.0),
            SimpleNamespace(symbol="SOLUSDT", signal=True, score=85.0, signal_strength=1.0,
                             htf_confirmed=True, spread_pct=0.0001, atr_pct=0.02,
                             wave_invalidation_price=98.0),
        ]


def test_multiple_positions_fit_aggregate_risk(monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "0")
    monkeypatch.setenv("MAX_TOTAL_RISK_PCT", "0.01")
    monkeypatch.setenv("MAX_RISK_PER_TRADE_PCT", "0.005")
    monkeypatch.setenv("MIN_RISK_ALLOCATION_PCT", "0.001")

    controller = PortfolioController(FakeClient(), balance_quote=10000.0)
    controller.scanner = FakeScanner()

    # RiskEngine receives an override allocation. Two 0.5% positions fit;
    # the third must not exceed the 1% aggregate budget.
    selections = controller.select_portfolio(open_risk_quote=0.0, open_positions=0)

    assert len(selections) == 2
    assert round(sum(s.risk.risk_pct for s in selections), 6) <= 1.0
    assert all(s.risk.risk_pct <= 0.5 for s in selections)


def test_existing_risk_leaves_only_remaining_budget(monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "0")
    monkeypatch.setenv("MAX_TOTAL_RISK_PCT", "0.01")
    monkeypatch.setenv("MAX_RISK_PER_TRADE_PCT", "0.005")

    controller = PortfolioController(FakeClient(), balance_quote=10000.0)
    controller.scanner = FakeScanner()

    selections = controller.select_portfolio(open_risk_quote=75.0, open_positions=1)

    assert selections
    assert sum(s.risk.risk_pct for s in selections) <= 0.25
