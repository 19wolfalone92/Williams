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
