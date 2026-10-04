import os
from dataclasses import dataclass
from typing import Optional

from market_scanner import MarketScanner, Candidate
from risk_engine import RiskEngine, RiskAnalysis


@dataclass
class Selection:
    candidate: Candidate
    risk: RiskAnalysis
    action: str
    reason: str


class PortfolioController:
    """
    Безопасный слой выбора пары.

    ВАЖНО:
    - не создаёт ордера;
    - не вызывает BUY/SELL;
    - не меняет Trader;
    - MAX_OPEN_POSITIONS = 1.
    """

    def __init__(self, client, balance_quote: float, symbols=None, interval=None):
        self.client = client
        self.interval = interval or os.getenv("INTERVAL", "1h")
        self.max_open_positions = 1

        self.scanner = MarketScanner(
            client=client,
            symbols=symbols,
            interval=self.interval,
        )

        self.risk_engine = RiskEngine(
            balance_quote=float(balance_quote),
            risk_per_trade_pct=float(os.getenv("RISK_PER_TRADE_PCT", "0.01")),
            max_position_fraction=float(os.getenv("POSITION_FRACTION", "0.25")),
            max_daily_loss_pct=float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03")),
            min_rr=float(os.getenv("MIN_RISK_REWARD", "1.5")),
            max_atr_pct=float(os.getenv("MAX_ATR_PCT", "0.08")),
        )

    def select(self, has_open_position=False) -> Optional[Selection]:
        # Никогда не выбираем новую позицию поверх существующей.
        if has_open_position:
            return None

        candidates = self.scanner.scan()

        if not candidates:
            return None

        analysed = []

        for candidate in candidates:
            # SETUP_READY / WATCHING только наблюдаем.
            # Для реального входа нужен строгий сигнал.
            if not candidate.signal:
                continue

            try:
                entry_price = float(
                    self.client.ticker_price(candidate.symbol)["price"]
                )

                atr = entry_price * (candidate.atr_pct / 100.0)

                risk = self.risk_engine.analyse(
                    symbol=candidate.symbol,
                    entry_price=entry_price,
                    atr=atr,
                    signal_strength=candidate.signal_strength,
                    htf_confirmed=candidate.htf_confirmed,
                    spread_pct=candidate.spread_pct,
                    max_spread_pct=self.scanner.max_spread_pct,
                )

                if not risk.allowed:
                    continue

                analysed.append(
                    Selection(
                        candidate=candidate,
                        risk=risk,
                        action="BUY_ALLOWED",
                        reason="STRICT_SIGNAL + risk checks passed",
                    )
                )

            except Exception as exc:
                print(
                    f"[PORTFOLIO] {candidate.symbol}: "
                    f"risk analysis failed: {exc}"
                )

        if not analysed:
            return None

        return max(
            analysed,
            key=lambda x: (
                x.candidate.score,
                x.risk.score,
                x.risk.risk_reward,
                -x.risk.stop_distance_pct,
            ),
        )

    def dry_run(self, has_open_position=False):
        selection = self.select(has_open_position)

        print()
        print("=" * 72)
        print("WILLIAMS PORTFOLIO CONTROLLER — DRY RUN")
        print("NO ORDERS")
        print("=" * 72)

        if has_open_position:
            print("POSITION: OPEN")
            print("ACTION: BLOCKED")
            print("REASON: MAX_OPEN_POSITIONS=1")
            return None

        if selection is None:
            print("SELECTION: NONE")
            print("ACTION: WAIT")
            print("REASON: no STRICT_SIGNAL candidate passed risk checks")
            return None

        c = selection.candidate
        r = selection.risk

        print(f"SELECTED SYMBOL: {c.symbol}")
        print(f"STATE:            {c.setup_state}")
        print(f"SCORE:            {c.score:.2f}")
        print(f"SETUP SCORE:      {c.setup_score:.2f}")
        print(f"STRICT SIGNAL:    {c.signal}")
        print(f"HTF CONFIRMED:    {c.htf_confirmed}")
        print(f"RISK:             {r.risk_pct:.2f}%")
        print(f"R:R:              {r.risk_reward:.2f}")
        print(f"ATR:              {c.atr_pct:.3f}%")
        print(f"SPREAD:           {c.spread_pct:.3f}%")
        print(f"ACTION:           {selection.action}")
        print(f"REASON:            {selection.reason}")
        print()
        print("ORDERS CREATED:   NO")
        print("BUY EXECUTED:     NO")
        print("SELL EXECUTED:    NO")

        return selection
