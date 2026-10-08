from dataclasses import dataclass
from typing import Optional
import os

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
            risk_per_trade_pct=min(0.005, max(0.0, float(os.getenv("WILLIAMS_INITIAL_RISK_PCT", "0.0025")))),
            max_position_fraction=0.25,
            max_daily_loss_pct=0.01,
            min_rr=1.5,
            max_atr_pct=0.08,
        )

    def analyse_candidate(self, candidate: Candidate) -> Optional[PortfolioCandidate]:
        entry_price = float(
            self.client.ticker_price(candidate.symbol)["price"]
        )

        # MarketScanner.atr_pct is already a fraction (ATR / price), not a
        # percentage in whole-number units.
        atr = entry_price * candidate.atr_pct

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


    def allocate(self, open_risk_quote=0.0, open_positions=0, max_open_positions=1):
        """Allocate candidates from the remaining portfolio risk budget.
        
        Position count is telemetry/backward compatibility only. Capacity is
        controlled by MAX_TOTAL_RISK_PCT and MAX_RISK_PER_TRADE_PCT.
        """
        candidates = self.scan()
        balance = max(float(self.risk_engine.balance), 0.0)
        max_total_risk_pct = min(
            0.01,
            max(0.0, float(os.getenv("WILLIAMS_MAX_CAMPAIGN_RISK_PCT", "0.006"))),
        )
        max_risk_per_trade_pct = min(
            0.005,
            max(0.0, float(os.getenv("WILLIAMS_INITIAL_RISK_PCT", "0.0025"))),
        )
        used_pct = float(open_risk_quote) / balance if balance > 0 else max_total_risk_pct
        remaining_pct = max(0.0, max_total_risk_pct - used_pct)
        result = []

        for item in candidates:
            if remaining_pct <= 0:
                break
            allocation_pct = min(max_risk_per_trade_pct, remaining_pct)
            if allocation_pct <= 0:
                break

            entry = item.risk.entry_price
            atr = entry * item.candidate.atr_pct
            risk = self.risk_engine.analyse(
                symbol=item.candidate.symbol,
                entry_price=entry,
                atr=atr,
                signal_strength=1.0,
                htf_confirmed=item.candidate.htf_confirmed,
                spread_pct=item.candidate.spread_pct,
                max_spread_pct=self.scanner.max_spread_pct,
                risk_pct_override=allocation_pct,
            )
            if not risk.allowed:
                continue

            result.append(
                PortfolioCandidate(
                    candidate=item.candidate,
                    risk=risk,
                    final_score=item.final_score,
                )
            )
            remaining_pct -= allocation_pct

        return result
