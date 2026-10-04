from dataclasses import dataclass
from typing import Optional

from market_scanner import MarketScanner, Candidate
from risk_engine import RiskEngine, RiskAnalysis


@dataclass
class PortfolioCandidate:
    candidate: Candidate
    risk: RiskAnalysis
    final_score: float


class PortfolioScanner:
    def __init__(
        self,
        client,
        balance_quote: float,
        symbols=None,
        interval: str = "1h",
    ):
        self.client = client

        self.scanner = MarketScanner(
            client=client,
            symbols=symbols,
            interval=interval,
        )

        self.risk_engine = RiskEngine(
            balance_quote=balance_quote,
            risk_per_trade_pct=0.01,
            max_position_fraction=0.25,
            max_daily_loss_pct=0.03,
            min_rr=1.5,
            max_atr_pct=0.08,
        )

    def analyse_candidate(self, candidate: Candidate) -> Optional[PortfolioCandidate]:
        entry_price = float(
            self.client.ticker_price(candidate.symbol)["price"]
        )

        atr = entry_price * (candidate.atr_pct / 100.0)

        risk = self.risk_engine.analyse(
            symbol=candidate.symbol,
            entry_price=entry_price,
            atr=atr,
            signal_strength=1.0,
            htf_confirmed=candidate.htf_confirmed,
            spread_pct=candidate.spread_pct,
            max_spread_pct=self.scanner.max_spread_pct,
        )

        if not risk.allowed:
            return None

        final_score = (
            candidate.score * 0.60
            + risk.score * 0.40
        )

        return PortfolioCandidate(
            candidate=candidate,
            risk=risk,
            final_score=round(final_score, 2),
        )

    def scan(self):
        candidates = self.scanner.scan()
        result = []

        for candidate in candidates:
            try:
                analysed = self.analyse_candidate(candidate)

                if analysed is not None:
                    result.append(analysed)

            except Exception as exc:
                print(
                    f"[SCANNER] {candidate.symbol}: "
                    f"risk analysis failed: {exc}"
                )

        result.sort(
            key=lambda x: (
                x.final_score,
                x.risk.risk_reward,
                -x.risk.stop_distance_pct,
            ),
            reverse=True,
        )

        return result

    def best(self):
        candidates = self.scan()

        if not candidates:
            return None

        return max(
            candidates,
            key=lambda x: (
                x.final_score,
                x.risk.risk_reward,
                -x.risk.stop_distance_pct,
            ),
        )
