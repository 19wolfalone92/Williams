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
            SimpleNamespace(symbol=symbol, signal=True, score=score, signal_strength=1.0,
                             htf_confirmed=True, spread_pct=0.0001, atr_pct=0.02,
                             wave_invalidation_price=98.0)
            for symbol, score in (("BTCUSDT", 95.0), ("ETHUSDT", 90.0), ("SOLUSDT", 85.0))
        ]


def test_selector_caps_new_entries_to_one_campaign(monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "5")
    monkeypatch.setenv("MAX_TOTAL_RISK_PCT", "0.01")
    monkeypatch.setenv("MAX_RISK_PER_TRADE_PCT", "0.005")
    monkeypatch.setenv("MIN_RISK_ALLOCATION_PCT", "0.001")
    controller = PortfolioController(FakeClient(), balance_quote=10_000.0)
    controller.scanner = FakeScanner()
    selections = controller.select_portfolio(open_risk_quote=0.0, open_positions=0)
    assert len(selections) == 1
    assert selections[0].risk.risk_pct <= 0.25
    assert selections[0].risk.risk_quote <= 25.0
    assert controller.max_total_risk_pct <= 0.006


def test_zero_positions_disables_entries_instead_of_meaning_unlimited(monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "0")
    controller = PortfolioController(FakeClient(), balance_quote=10_000.0)
    controller.scanner = FakeScanner()
    assert controller.select_portfolio(open_risk_quote=0.0, open_positions=0) == []


def test_existing_risk_at_or_above_portfolio_limit_blocks_entries(monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "1")
    monkeypatch.setenv("MAX_TOTAL_RISK_PCT", "0.01")
    controller = PortfolioController(FakeClient(), balance_quote=10_000.0)
    controller.scanner = FakeScanner()
    assert controller.select_portfolio(open_risk_quote=60.0, open_positions=0) == []
    assert controller.select_portfolio(open_risk_quote=10.0, open_positions=1) == []
