"""Live monitoring for an active Williams trading campaign.

Primary exit protection is structural: a stop below the recent 3/5-bar low.
The monitor never loosens a protective stop.  Fixed take-profit is not used as
the campaign's primary exit rule.

Wave/exhaustion context is sampled less frequently than price/bar state so the
mobile/runtime API budget is not consumed by repeated deep MTF requests.
"""

from __future__ import annotations

import os
import time

from campaign_model import CampaignState, structural_stop_for_long
from campaign_execution import CampaignExecutionError, CampaignExecutionService
from strategy import calculate_indicators, config_from_env
from data import fetch_klines
from williams_intraday_spec import IntradayPolicy


class CampaignMonitor:
    def __init__(self, client, db, execution_service: CampaignExecutionService):
        self.client = client
        self.db = db
        self.execution = execution_service
        self.intraday_policy = IntradayPolicy.from_env()
        self.wave_recheck_seconds = max(
            30,
            int(os.getenv("CAMPAIGN_WAVE_RECHECK_SECONDS", "60")),
        )

    @staticmethod
    def _tick(client, symbol: str) -> float:
        info = client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            return 0.0
        for f in rows[0].get("filters", []):
            if str(f.get("filterType")) == "PRICE_FILTER":
                try:
                    return float(f.get("tickSize", 0) or 0)
                except (TypeError, ValueError):
                    return 0.0
        return 0.0

    @staticmethod
    def _closed_candles(client, symbol: str, timeframe: str, limit: int = 80):
        raw = fetch_klines(client, symbol, timeframe, limit=limit)
        if len(raw) >= 2:
            return raw.iloc[:-1].copy()
        return raw.copy()

    @staticmethod
    def _zone_snapshot(indicators) -> tuple[str, int]:
        if indicators is None or len(indicators) == 0:
            return "UNKNOWN", 0
        row = indicators.iloc[-1]
        color = str(row.get("zone_color", "UNKNOWN") or "UNKNOWN").upper()
        streak = int(row.get("zone_streak", 0) or 0) if color == "GREEN" else int(row.get("zone_red_streak", 0) or 0) if color == "RED" else 0
        return color, streak

    def _wave_snapshot(self, campaign, candles):
        now = int(time.time() * 1000)
        last_sample = int(campaign.tags.get("wave_last_sample_ms", 0) or 0)
        if now - last_sample < self.wave_recheck_seconds * 1000:
            return None

        try:
            from wave_engine import MultiTimeframeWaveEngine

            engine = MultiTimeframeWaveEngine(
                self.client,
                base_interval=campaign.decision_timeframe,
                include_micro=False,
            )
            report = engine.analyse(
                campaign.symbol,
                cache={campaign.decision_timeframe: candles},
            )
            campaign.tags["wave_last_sample_ms"] = now
            campaign.wave_position = int(
                getattr(
                    report.frames.get(campaign.decision_timeframe),
                    "position",
                    getattr(report, "setup_position", 0),
                )
                or 0
            )
            campaign.wave_confidence = float(
                getattr(report, "wave_score", 0.0) or 0.0
            )
            campaign.wave_exhaustion_risk = float(
                getattr(report, "exhaustion_risk", 0.0) or 0.0
            )
            self.db.save_campaign(campaign)
            return report
        except Exception as exc:
            campaign.tags["wave_last_error"] = str(exc)
            self.db.save_campaign(campaign)
            return None

    def manage_campaign(self, campaign) -> dict:
        if campaign is None or campaign.position_qty <= 0:
            return {"state": "SKIP", "reason": "no active quantity"}
        if campaign.side != "BUY":
            return {"state": "SKIP", "reason": "current Spot campaign monitor is LONG-only"}

        symbol = campaign.symbol.upper()
        candles = self._closed_candles(
            self.client,
            symbol,
            campaign.decision_timeframe,
            limit=max(40, int(os.getenv("CAMPAIGN_TRAIL_CANDLES", "80"))),
        )
        if len(candles) < 20:
            return {
                "symbol": symbol,
                "state": campaign.state.value,
                "action": "WAIT_HISTORY",
                "reason": "insufficient closed candles",
            }

        last = candles.iloc[-1]
        current_price = float(
            self.client.ticker_price(symbol).get("price", 0) or 0
        )
        if current_price <= 0:
            return {"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "invalid current price"}

        indicators = calculate_indicators(candles, config_from_env())
        last_ind = indicators.iloc[-1]
        prev_ind = indicators.iloc[-2] if len(indicators) > 1 else last_ind
        teeth = float(last_ind.get("teeth_shifted", 0.0) or 0.0)
        zone_color, zone_streak = self._zone_snapshot(indicators)

        # Stagnation is an operational diagnosis only. It never widens or
        # tightens the stop by itself and never replaces a Williams exit.
        current_spread = float(last_ind.get("alligator_spread_pct", 0.0) or 0.0)
        previous_spread = float(prev_ind.get("alligator_spread_pct", 0.0) or 0.0)


        tick = self._tick(self.client, symbol)
        if tick <= 0:
            raise CampaignExecutionError(f"{symbol}: PRICE_FILTER.tickSize unavailable")

        trail_window = max(
            3,
            min(
                5,
                int(os.getenv("CAMPAIGN_TRAIL_BARS", "5")),
            ),
        )
        recent_lows = [float(x) for x in candles["low"].tail(trail_window).tolist() if float(x) > 0]
        proposed, source = structural_stop_for_long(
            signal_type=__import__("campaign_model").SignalType(
                str(campaign.current_signal_type or "FRACTAL")
                if str(campaign.current_signal_type or "FRACTAL") in {"REVERSAL", "SUPER_AO", "FRACTAL"}
                else "FRACTAL"
            ),
            signal_bar_low=float(campaign.initial_stop_price or 0.0) + tick,
            recent_lows=recent_lows,
            teeth=0.0,
            wave_invalidation=0.0,
            buffer=tick,
        )

        # Structural 3/5-bar protection is the primary Williams trail.
        # Williams' Zone is a separate profit-extraction layer.
        zone_trail_armed = bool(campaign.tags.get("zone_trail_armed", False))
        if zone_color == "GREEN" and zone_streak >= 5:
            zone_trail_armed = True
        campaign.tags["zone_trail_armed"] = zone_trail_armed
        if zone_trail_armed and len(candles):
            zone_stop = float(candles.iloc[-1]["low"]) - tick
            if zone_stop > proposed:
                proposed = zone_stop
                source = "ZONE_5_GREEN"
        # Teeth tightening is optional and OFF by default to avoid turning a
        # context line into an implicit fixed exit rule.
        if os.getenv("CAMPAIGN_TRAIL_TO_TEETH", "false").lower() == "true" and teeth > 0:
            proposed = max(proposed, teeth - tick)
            source = "TEETH"

        current_stop = float(campaign.current_stop_price or 0.0)
        stop_moved = False

        stagnation_bars = int(campaign.tags.get("stagnation_bars", 0) or 0)
        prior_spread = float(campaign.tags.get("last_alligator_spread", current_spread) or current_spread)
        progress = proposed > current_stop + tick or current_price > float(campaign.tags.get("last_progress_price", campaign.average_entry_price or current_price) or current_price)
        if not progress and current_spread <= prior_spread:
            stagnation_bars += 1
        else:
            stagnation_bars = 0
        campaign.tags["stagnation_bars"] = stagnation_bars
        campaign.tags["last_alligator_spread"] = current_spread
        campaign.tags["last_progress_price"] = current_price
        if stagnation_bars >= max(3, int(os.getenv("CAMPAIGN_STAGNATION_BARS", "3"))):
            campaign.tags["stagnant"] = True
            campaign.tags["stagnation_reason"] = "no_structural_progress_and_no_alligator_expansion"
            campaign.next_action = "CAMPAIGN_STAGNANT"
            self.db.log_campaign_event(
                campaign.campaign_id,
                "CAMPAIGN_STAGNANT",
                level="INFO",
                reason="no structural progress and no Alligator expansion",
                payload={"stagnation_bars": stagnation_bars},
            )
        else:
            campaign.tags["stagnant"] = False
        if (
            proposed > current_stop + tick
            and proposed < current_price
        ):
            result = self.execution.replace_structural_stop(
                campaign,
                existing_order_id=int(
                    campaign.tags.get("protective_order_id", "0") or 0
                ),
                quantity=float(campaign.position_qty),
                proposed_stop=proposed,
            )
            new_id = str(
                (result.get("newOrderResponse") or {}).get("orderId", "")
            )
            if new_id:
                campaign.tags["protective_order_id"] = new_id
            campaign.current_stop_price = proposed
            campaign.structural_stop_source = source
            campaign.state = CampaignState.TRAILING
            campaign.next_action = "MONITOR_CAMPAIGN"
            self.db.save_campaign(campaign)
            self.db.log_campaign_event(
                campaign.campaign_id,
                "STOP_MOVED",
                reason=source,
                payload={
                    "old_stop": current_stop,
                    "new_stop": proposed,
                    "trail_bars": trail_window,
                    "zone_color": zone_color,
                    "zone_streak": zone_streak,
                },
            )
            stop_moved = True

        report = self._wave_snapshot(campaign, candles)
        exhaustion_reasons = []
        if report is not None:
            threshold = max(
                0.0,
                min(
                    100.0,
                    float(os.getenv("CAMPAIGN_EXHAUSTION_WARNING", "75")),
                ),
            )
            if campaign.wave_exhaustion_risk >= threshold:
                exhaustion_reasons.append(
                    f"wave_exhaustion={campaign.wave_exhaustion_risk:.1f}"
                )
            setup = report.frames.get(campaign.decision_timeframe)
            if setup is not None and bool(getattr(setup, "terminal_fractal", False)):
                exhaustion_reasons.append("terminal_fractal")
            if setup is not None and bool(getattr(setup, "ao_bearish_divergence", False)):
                exhaustion_reasons.append("ao_bearish_divergence")

        # Exhaustion is a watch state by default. Automatic market exit requires
        # multiple independent warnings, preventing a single noisy indicator
        # from ending a campaign prematurely.
        if len(exhaustion_reasons) >= 2:
            self.execution.engine.enter_exhaustion_watch(
                campaign,
                exhaustion_reasons,
            )

        # The hard exchange stop remains the primary immediate exit. The
        # optional close-below-Teeth rule is explicitly disabled by default.
        if (
            os.getenv("CAMPAIGN_TEETH_EXIT", "false").lower() == "true"
            and teeth > 0
            and float(last["close"]) < teeth
        ):
            try:
                return {
                    "symbol": symbol,
                    "state": "EXIT_SIGNALLED",
                    "exit": self.execution.exit_market(
                        campaign,
                        reason="CLOSE_BELOW_TEETH",
                    ),
                }
            except Exception as exc:
                raise CampaignExecutionError(
                    f"{symbol}: close-below-Teeth exit failed: {exc}"
                ) from exc

        return {
            "symbol": symbol,
            "state": campaign.state.value,
            "current_price": current_price,
            "current_stop": campaign.current_stop_price,
            "proposed_stop": proposed,
            "stop_source": source,
            "stop_moved": stop_moved,
            "zone_color": zone_color,
            "zone_streak": zone_streak,
            "teeth": teeth,
            "wave_exhaustion_risk": campaign.wave_exhaustion_risk,
            "exhaustion_reasons": exhaustion_reasons,
            "stagnation_bars": int(campaign.tags.get("stagnation_bars", 0) or 0),
            "stagnant": bool(campaign.tags.get("stagnant", False)),
            "next_action": campaign.next_action,
        }

    def enforce_session(self) -> list[dict]:
        now = __import__("datetime").datetime.now(__import__("datetime").timezone.utc)
        state = self.intraday_policy.session.state(now)
        if state in {"PRE_SESSION", "ENTRY_WINDOW"}:
            return []
        if state == "MANAGE_ONLY":
            # Pending entries are not allowed after the 18:00 UTC cutoff.
            return self.execution.cancel_pending_for_eod("NO_NEW_ENTRIES_AFTER_CUTOFF")
        results = self.execution.cancel_pending_for_eod("EOD_PENDING_CANCELLED")
        for row in list(self.db.open_campaigns()):
            campaign = self.execution.engine.load_campaign(str(row["campaign_id"]))
            if campaign is None or campaign.position_qty <= 0:
                continue
            if campaign.state in {CampaignState.EXIT_SIGNALLED, CampaignState.EXIT_PENDING, CampaignState.RECONCILE_REQUIRED}:
                continue
            try:
                results.append({
                    "campaign_id": campaign.campaign_id,
                    "symbol": campaign.symbol,
                    "state": "EOD_FLAT",
                    "exit": self.execution.exit_market(campaign, reason="EOD_FLAT"),
                })
            except Exception as exc:
                self.execution.engine.mark_reconcile_required(campaign, f"EOD_FLAT failed: {exc}")
                results.append({
                    "campaign_id": campaign.campaign_id,
                    "symbol": campaign.symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def monitor_all(self) -> list[dict]:
        results = []
        for row in self.db.open_campaigns():
            try:
                campaign = self.execution.engine.load_campaign(row["campaign_id"])
                if campaign is None:
                    continue
                if campaign.state in {
                    CampaignState.ENTRY_PENDING,
                    CampaignState.ENTRY_ARMING,
                    CampaignState.ADD_ON_PENDING,
                    CampaignState.ADD_ON_ARMING,
                    CampaignState.SIGNAL_DETECTED,
                    CampaignState.EXIT_SIGNALLED,
                    CampaignState.EXIT_PENDING,
                    CampaignState.RECONCILE_REQUIRED,
                }:
                    continue
                results.append(self.manage_campaign(campaign))
            except Exception as exc:
                campaign = self.execution.engine.load_campaign(row["campaign_id"])
                if campaign is not None:
                    self.execution.engine.mark_reconcile_required(campaign, str(exc))
                results.append({
                    "campaign_id": row["campaign_id"],
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results
