import os
from dataclasses import dataclass
from typing import Optional

from market_scanner import MarketScanner, Candidate
from risk_engine import RiskEngine, RiskAnalysis
from williams_intraday_spec import IntradayPolicy


@dataclass
class Selection:
    candidate: Candidate
    risk: RiskAnalysis
    action: str
    reason: str


class PortfolioController:
    """Portfolio-level candidate selection and risk allocation."""

    def __init__(self, client, balance_quote: float, symbols=None, interval=None, db=None, strategy_profile=None):
        self.client = client
        self.db = db
        self.strategy_profile = str(strategy_profile or os.getenv("WILLIAMS_STRATEGY_PROFILE", "")).strip().upper()
        self.policy = IntradayPolicy.from_env()
        self.intraday_core_enabled = self.strategy_profile in {"WILLIAMS_INTRADAY_CORE", "WILLIAMS_INTRADAY_CONSERVATIVE", "WILLIAMS_CORE_INTRADAY", "WILLIAMS_CORE_INTRADAY_CONSERVATIVE"} or self.policy.profile in {"WILLIAMS_CORE_INTRADAY", "WILLIAMS_CORE_INTRADAY_CONSERVATIVE"}
        self.interval = self.policy.timeframes.decision_tf if getattr(self, "intraday_core_enabled", False) else (interval or os.getenv("INTERVAL", "1h"))
        self.max_open_positions = max(0, int(os.getenv("MAX_OPEN_POSITIONS", str(self.policy.risk.max_campaigns if self.intraday_core_enabled else 5))))
        self.max_total_risk_pct = min(0.006 if self.intraday_core_enabled else 0.01, max(0.0, float(os.getenv("MAX_TOTAL_RISK_PCT", str(self.policy.risk.campaign_risk_pct if self.intraday_core_enabled else 0.01)))))
        self.max_risk_per_trade_pct = min(0.0025 if self.intraday_core_enabled else 0.005, max(0.0, float(os.getenv("MAX_RISK_PER_TRADE_PCT", os.getenv("RISK_PER_TRADE_PCT", str(self.policy.risk.initial_risk_pct if self.intraday_core_enabled else 0.005))))))
        self.min_risk_allocation_pct = min(
            self.max_risk_per_trade_pct,
            max(0.0, float(os.getenv("MIN_RISK_ALLOCATION_PCT", "0.001"))),
        )

        self.scanner = MarketScanner(
            client=client,
            symbols=(symbols if symbols else None),
            interval=self.interval,
            strategy_profile=self.strategy_profile if self.intraday_core_enabled else None,
        )
        self.risk_engine = RiskEngine(
            balance_quote=float(balance_quote),
            risk_per_trade_pct=self.max_risk_per_trade_pct,
            max_position_fraction=float(os.getenv("POSITION_FRACTION", "0.25")),
            max_daily_loss_pct=float(os.getenv("MAX_DAILY_LOSS_PCT", str(self.policy.risk.daily_loss_pct if self.intraday_core_enabled else 0.03))),
            min_rr=float(os.getenv("MIN_RISK_REWARD", "1.5")),
            max_atr_pct=float(os.getenv("MAX_ATR_PCT", "0.08")),
        )

    def _analyse_candidates(self, candidates):
        # Some isolated regression tests construct this class via __new__.
        # Treat missing optional runtime attributes as legacy/disabled rather
        # than converting an otherwise valid candidate into a silent exception.
        self.intraday_core_enabled = bool(getattr(self, "intraday_core_enabled", False))
        self.db = getattr(self, "db", None)
        analysed = []
        for candidate in candidates:
            if not candidate.signal:
                continue
            try:
                market_price = float(self.client.ticker_price(candidate.symbol)["price"])
                entry_price = (
                    float(getattr(candidate, "entry_trigger_price", 0.0) or 0.0)
                    if self.intraday_core_enabled and float(getattr(candidate, "entry_trigger_price", 0.0) or 0.0) > 0
                    else market_price
                )
                atr = market_price * candidate.atr_pct
                info = self.client.exchange_info(candidate.symbol)
                filters = {f["filterType"]: f for f in info.get("symbols", [{}])[0].get("filters", [])}
                notional_filter = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
                min_notional = float(notional_filter.get("minNotional", 0) or 0)
                if self.intraday_core_enabled:
                    risk = self.risk_engine.analyse_structural_stop(
                        symbol=candidate.symbol,
                        entry_price=entry_price,
                        stop_price=float(getattr(candidate, "entry_protective_reference", 0.0) or 0.0),
                        risk_pct_override=min(self.max_risk_per_trade_pct, max(0.0, float(candidate.risk_pct) / 100.0)),
                        spread_pct=candidate.spread_pct,
                        max_spread_pct=self.scanner.max_spread_pct,
                        min_notional=min_notional,
                    )
                else:
                    risk = self.risk_engine.analyse(
                        symbol=candidate.symbol, entry_price=entry_price, atr=atr,
                        min_notional=min_notional, signal_strength=candidate.signal_strength,
                        htf_confirmed=candidate.htf_confirmed, spread_pct=candidate.spread_pct,
                        max_spread_pct=self.scanner.max_spread_pct,
                        invalidation_price=float(getattr(candidate, "wave_invalidation_price", 0.0) or 0.0),
                    )
                trace = dict(getattr(candidate, "decision_trace", {}) or {})
                if trace:
                    trace["risk_feasible"] = bool(getattr(risk, "allowed", False))
                    trace["block_reason"] = "" if risk.allowed else str(risk.reason)
                    if self.db is not None and hasattr(self.db, "save_decision_trace"):
                        try:
                            self.db.save_decision_trace(trace)
                        except Exception:
                            pass
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
            if self.intraday_core_enabled:
                r = self.risk_engine.analyse_structural_stop(
                    symbol=base.candidate.symbol,
                    entry_price=base.risk.entry_price,
                    stop_price=float(getattr(base.candidate, "entry_protective_reference", 0.0) or 0.0),
                    spread_pct=base.candidate.spread_pct,
                    max_spread_pct=self.scanner.max_spread_pct,
                    min_notional=min_notional,
                    risk_pct_override=allocation_pct,
                )
            else:
                r = self.risk_engine.analyse(
                    symbol=base.candidate.symbol, entry_price=base.risk.entry_price,
                    atr=base.risk.entry_price * base.candidate.atr_pct, min_notional=min_notional,
                    signal_strength=base.candidate.signal_strength, htf_confirmed=base.candidate.htf_confirmed,
                    spread_pct=base.candidate.spread_pct, max_spread_pct=self.scanner.max_spread_pct,
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
