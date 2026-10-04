import os
from dataclasses import dataclass

from portfolio_controller import PortfolioController


class FakeClient:
    def ticker_price(self, symbol):
        return {"price": "100.0"}


@dataclass
class FakeCandidate:
    symbol: str
    score: float
    signal: bool
    setup_score: float
    signal_strength: float
    breakout_distance_pct: float
    risk_pct: float
    risk_reward: float
    atr_pct: float
    spread_pct: float
    htf_confirmed: bool
    setup_state: str
    reason: str


@dataclass
class FakeRisk:
    allowed: bool
    score: float
    risk_pct: float
    risk_reward: float
    stop_distance_pct: float


def make_controller():
    controller = PortfolioController.__new__(PortfolioController)
    controller.client = FakeClient()
    controller.max_open_positions = 1
    controller.scanner = type("Scanner", (), {
        "max_spread_pct": 0.0015,
        "scan": lambda self: []
    })()

    controller.risk_engine = type("Risk", (), {
        "analyse": lambda self, **kwargs: FakeRisk(
            allowed=True,
            score=90.0,
            risk_pct=1.0,
            risk_reward=2.0,
            stop_distance_pct=2.0,
        )
    })()

    return controller


def test_no_position_selects_best_strict_signal():
    controller = make_controller()

    candidates = [
        FakeCandidate(
            symbol="BTCUSDT",
            score=80.0,
            signal=True,
            setup_score=100.0,
            signal_strength=1.0,
            breakout_distance_pct=0.1,
            risk_pct=2.0,
            risk_reward=2.0,
            atr_pct=0.5,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="STRONG_SIGNAL",
            reason="strict signal",
        ),
        FakeCandidate(
            symbol="ETHUSDT",
            score=90.0,
            signal=True,
            setup_score=100.0,
            signal_strength=1.0,
            breakout_distance_pct=0.2,
            risk_pct=2.0,
            risk_reward=2.0,
            atr_pct=0.5,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="STRONG_SIGNAL",
            reason="strict signal",
        ),
        FakeCandidate(
            symbol="BNBUSDT",
            score=99.0,
            signal=False,
            setup_score=85.7,
            signal_strength=0.8,
            breakout_distance_pct=-0.3,
            risk_pct=2.0,
            risk_reward=2.0,
            atr_pct=0.5,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="SETUP_READY",
            reason="waiting",
        ),
    ]

    controller.scanner.scan = lambda: candidates

    result = controller.select(has_open_position=False)

    assert result is not None
    assert result.candidate.symbol == "ETHUSDT"
    assert result.action == "BUY_ALLOWED"

    print("[PASS] free account selects best STRICT_SIGNAL")


def test_open_position_blocks_entry():
    controller = make_controller()

    controller.scanner.scan = lambda: [
        FakeCandidate(
            symbol="ETHUSDT",
            score=99.0,
            signal=True,
            setup_score=100.0,
            signal_strength=1.0,
            breakout_distance_pct=0.1,
            risk_pct=2.0,
            risk_reward=2.0,
            atr_pct=0.5,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="STRONG_SIGNAL",
            reason="strict signal",
        )
    ]

    result = controller.select(has_open_position=True)

    assert result is None

    print("[PASS] open position blocks new entry")


def test_setup_ready_never_enters():
    controller = make_controller()

    controller.scanner.scan = lambda: [
        FakeCandidate(
            symbol="BNBUSDT",
            score=99.0,
            signal=False,
            setup_score=85.7,
            signal_strength=0.8,
            breakout_distance_pct=-0.2,
            risk_pct=2.0,
            risk_reward=2.0,
            atr_pct=0.5,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="SETUP_READY",
            reason="waiting for breakout",
        )
    ]

    result = controller.select(has_open_position=False)

    assert result is None

    print("[PASS] SETUP_READY cannot trigger entry")


def test_no_candidates_waits():
    controller = make_controller()
    controller.scanner.scan = lambda: []

    result = controller.select(has_open_position=False)

    assert result is None

    print("[PASS] no candidates -> WAIT")


def main():
    print()
    print("=" * 72)
    print("PORTFOLIO CONTROLLER TESTS")
    print("NO ORDERS")
    print("=" * 72)

    test_no_position_selects_best_strict_signal()
    test_open_position_blocks_entry()
    test_setup_ready_never_enters()
    test_no_candidates_waits()

    print()
    print("PORTFOLIO CONTROLLER TESTS: PASS")
    print("BUY EXECUTED: NO")
    print("SELL EXECUTED: NO")


if __name__ == "__main__":
    main()
