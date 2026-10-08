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
    """Portfolio-level candidate selection and risk allocation."""

    def __init__(self, client, balance_quote: float, symbols=None, interval=None):
        self.client = client
        self.interval = interval or os.getenv("INTERVAL", "1h")
        self.max_open_positions = max(0, int(os.getenv("MAX_OPEN_POSITIONS", "5")))
        self.max_total_risk_pct = min(0.01, max(0.0, float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01"))))
        self.max_risk_per_trade_pct = min(0.005, max(0.0, float(os.getenv("MAX_RISK_PER_TRADE_PCT", os.getenv("RISK_PER_TRADE_PCT", "0.005")))))
        self.min_risk_allocation_pct = min(
            self.max_risk_per_trade_pct,
            max(0.0, float(os.getenv("MIN_RISK_ALLOCATION_PCT", "0.001"))),
        )

        self.scanner = MarketScanner(
            client=client,
            symbols=(symbols if symbols else None),
            interval=self.interval,
        )
        self.risk_engine = RiskEngine(
            balance_quote=float(balance_quote),
            risk_per_trade_pct=self.max_risk_per_trade_pct,
            max_position_fraction=float(os.getenv("POSITION_FRACTION", "0.25")),
            max_daily_loss_pct=float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03")),
            min_rr=float(os.getenv("MIN_RISK_REWARD", "1.5")),
            max_atr_pct=float(os.getenv("MAX_ATR_PCT", "0.08")),
        )

    def _analyse_candidates(self, candidates):
        analysed = []
        for candidate in candidates:
            if not candidate.signal:
                continue
            try:
                campaign_mode = os.getenv("CAMPAIGN_ENGINE", "true").lower() == "true"
                signal_specs = list(getattr(candidate, "campaign_signal_specs", None) or [])
                if campaign_mode and signal_specs:
                    signal = min(
                        signal_specs,
                        key=lambda x: int(x.get("signal_bar_time_ms", 0) or 0),
                    )
                    entry_price = float(signal.get("trigger_price", 0.0) or 0.0)
                    structural_stop = float(signal.get("protective_reference", 0.0) or 0.0)
                else:
                    # Backwards-compatible scanner/risk path. Legacy Candidate
                    # objects may not expose campaign specs.
                    entry_price = float(self.client.ticker_price(candidate.symbol)["price"])
                    structural_stop = float(getattr(candidate, "wave_invalidation_price", 0.0) or 0.0)
                atr = entry_price * candidate.atr_pct
                info = self.client.exchange_info(candidate.symbol)
                filters = {f["filterType"]: f for f in info.get("symbols", [{}])[0].get("filters", [])}
                notional_filter = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
                min_notional = float(notional_filter.get("minNotional", 0) or 0)
                risk = self.risk_engine.analyse(
                    symbol=candidate.symbol,
                    entry_price=entry_price,
                    atr=atr,
                    min_notional=min_notional,
                    signal_strength=candidate.signal_strength,
                    htf_confirmed=candidate.htf_confirmed,
                    spread_pct=candidate.spread_pct,
                    max_spread_pct=self.scanner.max_spread_pct,
                    invalidation_price=structural_stop,
                )
                if risk.allowed:
                    analysed.append(Selection(
                        candidate=candidate,
                        risk=risk,
                        action="BUY_ALLOWED",
                        reason="STRICT_SIGNAL + risk checks passed",
                    ))
            except Exception as exc:
                print(f"[PORTFOLIO] {candidate.symbol}: risk analysis failed: {exc}")
        return analysed

    @staticmethod
    def _rank(selection):
        return (
            selection.candidate.score,
            selection.risk.score,
            selection.risk.risk_reward,
            -selection.risk.stop_distance_pct,
        )

    def select_portfolio(self, open_risk_quote: float = 0.0, open_positions: int = 0):
        """Return candidates for new campaigns and, in campaign mode, later add-ons."""
        campaign_mode = os.getenv("CAMPAIGN_ENGINE", "false").lower() == "true"
        if (
            self.max_open_positions > 0
            and open_positions >= self.max_open_positions
            and not campaign_mode
        ):
            return []
        analysed = self._analyse_candidates(self.scanner.scan())
        if not analysed:
            return []

        analysed.sort(key=self._rank, reverse=True)

        balance = max(self.risk_engine.balance, 0.0)
        used_pct = float(open_risk_quote) / balance if balance > 0 else self.max_total_risk_pct
        remaining_pct = max(0.0, self.max_total_risk_pct - used_pct)
        selections = []
        remaining_slots = (
            len(analysed)
            if campaign_mode
            else (
                max(0, self.max_open_positions - int(open_positions))
                if self.max_open_positions > 0
                else len(analysed)
            )
        )

        for base in analysed:
            if remaining_pct <= 0 or remaining_slots <= 0:
                break

            allocation_pct = min(self.max_risk_per_trade_pct, remaining_pct)
            if allocation_pct < self.min_risk_allocation_pct:
                break

            info = self.client.exchange_info(base.candidate.symbol)
            filters = {f["filterType"]: f for f in info.get("symbols", [{}])[0].get("filters", [])}
            nf = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
            min_notional = float(nf.get("minNotional", 0) or 0)
            r = self.risk_engine.analyse(
                symbol=base.candidate.symbol,
                entry_price=base.risk.entry_price,
                atr=base.risk.entry_price * base.candidate.atr_pct,
                min_notional=min_notional,
                signal_strength=base.candidate.signal_strength,
                htf_confirmed=base.candidate.htf_confirmed,
                spread_pct=base.candidate.spread_pct,
                max_spread_pct=self.scanner.max_spread_pct,
                invalidation_price=float(getattr(base.candidate, "wave_invalidation_price", 0.0) or 0.0),
                risk_pct_override=allocation_pct,
            )
            if not r.allowed:
                continue

            selections.append(Selection(
                candidate=base.candidate,
                risk=r,
                action="BUY_ALLOWED",
                reason=f"STRICT_SIGNAL + portfolio risk allocation {allocation_pct:.2%}",
            ))
            remaining_pct -= allocation_pct
            remaining_slots -= 1

        return selections

    def select(self, has_open_position=False) -> Optional[Selection]:
        """Return the best individual candidate; portfolio selection may return several."""
        open_positions = 1 if has_open_position else 0
        selections = self.select_portfolio(open_risk_quote=0.0, open_positions=open_positions)
        return selections[0] if selections else None
    def dry_run(self, has_open_position=False):
        selections = self.select_portfolio(
            open_risk_quote=0.0,
            open_positions=1 if has_open_position else 0,
        )
        print()
        print("=" * 72)
        print("WILLIAMS PORTFOLIO CONTROLLER — DRY RUN")
        print("NO ORDERS")
        print("=" * 72)
        if not selections:
            print("SELECTION: NONE")
            print("ACTION: WAIT")
            print("REASON: no STRICT_SIGNAL candidate fits portfolio risk")
            return None
        for i, selection in enumerate(selections, 1):
            c = selection.candidate
            r = selection.risk
            print(f"{i}. {c.symbol} score={c.score:.2f} risk={r.risk_pct:.2f}% R:R={r.risk_reward:.2f} position={r.position_quote:.2f}")
        print()
        print(f"TOTAL NEW RISK: {sum(x.risk.risk_pct for x in selections):.2f}%")
        print("ORDERS CREATED: NO")
        print("BUY EXECUTED:   NO")
        print("SELL EXECUTED:  NO")
        return selections
