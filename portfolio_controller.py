import os
from dataclasses import dataclass
from typing import Optional

from market_scanner import MarketScanner, Candidate
from risk_engine import RiskEngine, RiskAnalysis
from trading_config import TradingConfig


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
        self.balance_quote = float(balance_quote)
        self.interval = interval or os.getenv("INTERVAL", "1h")
        self.config = TradingConfig.from_env()
        self.max_open_positions = self.config.max_open_positions
        self.max_total_risk_pct = self.config.max_total_risk_pct
        self.max_risk_per_trade_pct = min(0.01, max(0.0, self.config.risk_per_trade_pct))
        self.min_risk_allocation_pct = min(
            self.max_risk_per_trade_pct,
            max(0.0, float(os.getenv("MIN_RISK_ALLOCATION_PCT", "0.001"))),
        )
        # Autonomous entry/exit is the structured Wise-Men campaign pipeline.
        # Legacy fixed-target/R:R checks are an explicit profile overlay, not
        # a hidden requirement of TC2 campaign signals.
        self.campaign_engine_enabled = (
            os.getenv("CAMPAIGN_ENGINE", "true").lower() == "true"
        )

        self.scanner = MarketScanner(
            client=client,
            symbols=(symbols if symbols else None),
            interval=self.interval,
        )
        self.risk_engine = RiskEngine(
            balance_quote=self.balance_quote,
            risk_per_trade_pct=self.max_risk_per_trade_pct,
            max_position_fraction=float(os.getenv("POSITION_FRACTION", "0.25")),
            max_daily_loss_pct=self.config.max_daily_loss_pct,
            min_rr=float(os.getenv("MIN_RISK_REWARD", "1.5")),
            max_atr_pct=float(os.getenv("MAX_ATR_PCT", "0.08")),
        )

    def _candidate_direction(self, candidate) -> str:
        """Resolve direction without silently converting an unknown Futures setup to LONG."""
        raw = str(getattr(candidate, "direction", "") or "").strip().upper()
        if raw in {"LONG", "SHORT"}:
            return raw
        # Legacy Spot scanning is long-only and historically has no direction field.
        # Futures requires an explicit directional Williams signal; unknown is BLOCKED.
        if not bool(getattr(self.client, "is_usdm_futures", False)) and not raw:
            return "LONG"
        return ""

    def _analyse_candidates(self, candidates):
        analysed = []
        for candidate in candidates:
            if not candidate.signal:
                continue
            try:
                entry_price = float(self.client.ticker_price(candidate.symbol)["price"])
                atr = entry_price * candidate.atr_pct
                info = self.client.exchange_info(candidate.symbol)
                filters = {f["filterType"]: f for f in info.get("symbols", [{}])[0].get("filters", [])}
                notional_filter = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
                min_notional = float(notional_filter.get("minNotional", 0) or 0)
                direction = self._candidate_direction(candidate)
                if direction not in {"LONG", "SHORT"}:
                    print(f"[PORTFOLIO] {candidate.symbol}: blocked because directional signal is missing/invalid")
                    continue
                risk = self.risk_engine.analyse(
                    symbol=candidate.symbol,
                    entry_price=entry_price,
                    atr=atr,
                    min_notional=min_notional,
                    signal_strength=candidate.signal_strength,
                    htf_confirmed=candidate.htf_confirmed,
                    spread_pct=candidate.spread_pct,
                    max_spread_pct=self.scanner.max_spread_pct,
                    invalidation_price=float(
                        getattr(candidate, "entry_protective_reference", 0.0) or 0.0
                    ),
                    side=direction,
                    enforce_min_rr=not getattr(
                        self, "campaign_engine_enabled",
                        os.getenv("CAMPAIGN_ENGINE", "true").lower() == "true",
                    ),
                )
                if risk.allowed:
                    analysed.append(Selection(
                        candidate=candidate,
                        risk=risk,
                        action=("BUY_ALLOWED" if direction == "LONG" else "SHORT_ALLOWED"),
                        reason=f"STRICT_SIGNAL + {direction} risk checks passed",
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

    def select_portfolio(
        self,
        open_risk_quote: float = 0.0,
        open_positions: int = 0,
        *,
        include_existing_campaigns: bool = False,
    ):
        """Return new-campaign candidates and optionally candidates for active-campaign add-ons."""
        campaign_mode = getattr(
            self, "campaign_engine_enabled",
            os.getenv("CAMPAIGN_ENGINE", "true").lower() == "true",
        )
        if (
            self.max_open_positions > 0
            and open_positions >= self.max_open_positions
            and not campaign_mode
            and not include_existing_campaigns
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
            if campaign_mode or include_existing_campaigns
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
            direction = self._candidate_direction(base.candidate)
            if direction not in {"LONG", "SHORT"}:
                continue
            r = self.risk_engine.analyse(
                symbol=base.candidate.symbol,
                entry_price=base.risk.entry_price,
                atr=base.risk.entry_price * base.candidate.atr_pct,
                min_notional=min_notional,
                signal_strength=base.candidate.signal_strength,
                htf_confirmed=base.candidate.htf_confirmed,
                spread_pct=base.candidate.spread_pct,
                max_spread_pct=self.scanner.max_spread_pct,
                invalidation_price=float(
                    getattr(base.candidate, "entry_protective_reference", 0.0) or 0.0
                ),
                risk_pct_override=allocation_pct,
                side=direction,
                enforce_min_rr=not campaign_mode,
            )
            if not r.allowed:
                continue

            selections.append(Selection(
                candidate=base.candidate,
                risk=r,
                action=("BUY_ALLOWED" if direction == "LONG" else "SHORT_ALLOWED"),
                reason=f"STRICT_SIGNAL + {direction} portfolio risk allocation {allocation_pct:.2%}",
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
