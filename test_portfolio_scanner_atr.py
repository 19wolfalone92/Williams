from types import SimpleNamespace

from market_scanner import Candidate
from portfolio_scanner import PortfolioScanner


class FakeClient:
    def ticker_price(self, symbol):
        return {"price": "100.0"}


class FakeRiskEngine:
    def __init__(self):
        self.atr_seen = None

    def analyse(self, **kwargs):
        self.atr_seen = kwargs["atr"]
        return SimpleNamespace(allowed=True, score=100.0)


def test_atr_fraction_is_not_divided_by_100_again():
    scanner = object.__new__(PortfolioScanner)
    scanner.client = FakeClient()
    scanner.scanner = SimpleNamespace(max_spread_pct=0.0015)
    scanner.risk_engine = FakeRiskEngine()

    candidate = Candidate(
        symbol="BTCUSDT",
        score=80.0,
        signal=True,
        setup_score=100.0,
        signal_strength=1.0,
        breakout_distance_pct=0.1,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.03,
        spread_pct=0.0005,
        htf_confirmed=True,
        setup_state="STRONG_SIGNAL",
    )

    result = scanner.analyse_candidate(candidate)
    assert result is not None
    assert scanner.risk_engine.atr_seen == 3.0
