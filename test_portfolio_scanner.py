from types import SimpleNamespace

from portfolio_scanner import PortfolioScanner


def test_best_candidate():

    scanner = object.__new__(PortfolioScanner)

    scanner.scan = lambda: [
        SimpleNamespace(
            final_score=71,
            risk=SimpleNamespace(
                risk_reward=2.0,
                stop_distance_pct=0.04,
            ),
        ),
        SimpleNamespace(
            final_score=91,
            risk=SimpleNamespace(
                risk_reward=2.0,
                stop_distance_pct=0.03,
            ),
        ),
        SimpleNamespace(
            final_score=84,
            risk=SimpleNamespace(
                risk_reward=3.0,
                stop_distance_pct=0.02,
            ),
        ),
    ]

    best = scanner.best()

    assert best.final_score == 91


def test_empty_scan():

    scanner = object.__new__(PortfolioScanner)
    scanner.scan = lambda: []

    assert scanner.best() is None


if __name__ == "__main__":
    test_best_candidate()
    test_empty_scan()

    print("PORTFOLIO SCANNER TESTS: PASS")


def test_campaign_risk_prefilter_does_not_gate_on_synthetic_rr_target(monkeypatch):
    import portfolio_scanner as module

    class _Scanner:
        def __init__(self, **kwargs):
            self.max_spread_pct = 0.01

    class _RiskEngine:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    monkeypatch.setattr(module, "MarketScanner", _Scanner)
    monkeypatch.setattr(module, "RiskEngine", _RiskEngine)
    scanner = module.PortfolioScanner(client=object(), balance_quote=1000.0)
    assert scanner.risk_engine.kwargs["min_rr"] == 0.0


def test_campaign_candidate_rank_is_not_blended_with_synthetic_rr_score():
    scanner = object.__new__(PortfolioScanner)
    scanner.client = SimpleNamespace(ticker_price=lambda symbol: {"price": "100"})
    scanner.scanner = SimpleNamespace(max_spread_pct=0.01)
    scanner.risk_engine = SimpleNamespace(
        analyse=lambda **kwargs: SimpleNamespace(
            allowed=True,
            score=0.01,
            risk_pct=0.5,
        )
    )
    candidate = SimpleNamespace(
        symbol="BTCUSDT",
        atr_pct=0.01,
        htf_confirmed=True,
        spread_pct=0.001,
        score=0.91,
    )
    result = scanner.analyse_candidate(candidate)
    assert result is not None
    assert result.final_score == 0.91
