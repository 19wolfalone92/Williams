import os
from dataclasses import dataclass

from portfolio_controller import PortfolioController
from risk_engine import RiskEngine


class FakeClient:
    def ticker_price(self, symbol):
        return {"price": "100.0"}

    def exchange_info(self, symbol):
        return {
            "symbols": [{
                "symbol": symbol,
                "filters": [
                    {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                    {"filterType": "LOT_SIZE", "minQty": "0.000001", "stepSize": "0.000001"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "5"},
                ],
            }]
        }


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
    controller.max_open_positions = 3
    controller.max_total_risk_pct = 0.01
    controller.max_risk_per_trade_pct = 0.005
    controller.min_risk_allocation_pct = 0.001
    controller.scanner = type("Scanner", (), {
        "max_spread_pct": 0.0015,
        "scan": lambda self: []
    })()

    controller.risk_engine = RiskEngine(
        balance_quote=10000,
        risk_per_trade_pct=0.005,
        max_position_fraction=0.25,
        min_rr=1.5,
        max_atr_pct=0.08,
    )

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
            atr_pct=0.005,
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
            atr_pct=0.005,
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
            atr_pct=0.005,
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


def test_open_position_does_not_block_entry_when_risk_budget_allows():
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
            atr_pct=0.005,
            spread_pct=0.001,
            htf_confirmed=True,
            setup_state="STRONG_SIGNAL",
            reason="strict signal",
        )
    ]

    result = controller.select(has_open_position=True)

    assert result is not None

    print("[PASS] open position does not block entry when risk budget allows")


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
            atr_pct=0.005,
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


def test_portfolio_risk_caps_total_at_one_percent():
    controller = make_controller()

    candidates = []
    for symbol, score in [("BTCUSDT", 99.0), ("ETHUSDT", 98.0), ("SOLUSDT", 97.0)]:
        candidates.append(FakeCandidate(
            symbol=symbol, score=score, signal=True, setup_score=100.0,
            signal_strength=1.0, breakout_distance_pct=0.1, risk_pct=1.0,
            risk_reward=2.0, atr_pct=0.005, spread_pct=0.001,
            htf_confirmed=True, setup_state="STRONG_SIGNAL",
            reason="strict signal",
        ))
    controller.scanner.scan = lambda: candidates

    selections = controller.select_portfolio(open_risk_quote=0.0, open_positions=0)

    assert len(selections) == 2
    assert all(s.risk.risk_pct <= 0.5 for s in selections)
    assert abs(sum(s.risk.risk_pct for s in selections) - 1.0) < 1e-9
    assert [s.candidate.symbol for s in selections] == ["BTCUSDT", "ETHUSDT"]
    print("[PASS] portfolio total risk <= 1%, per trade <= 0.5%")


def test_portfolio_respects_existing_risk():
    controller = make_controller()

    candidates = [
        FakeCandidate(
            symbol="SOLUSDT", score=99.0, signal=True, setup_score=100.0,
            signal_strength=1.0, breakout_distance_pct=0.1, risk_pct=1.0,
            risk_reward=2.0, atr_pct=0.005, spread_pct=0.001,
            htf_confirmed=True, setup_state="STRONG_SIGNAL",
            reason="strict signal",
        )
    ]
    controller.scanner.scan = lambda: candidates

    selections = controller.select_portfolio(open_risk_quote=80.0, open_positions=1)

    assert len(selections) == 1
    assert abs(selections[0].risk.risk_pct - 0.2) < 1e-9
    assert selections[0].risk.risk_quote == 20.0
    print("[PASS] existing 0.8% risk leaves only 0.2%")


if __name__ == "__main__":
    main()
    test_portfolio_risk_caps_total_at_one_percent()
    test_portfolio_respects_existing_risk()
