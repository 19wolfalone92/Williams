"""Execution adapter for Williams conditional campaign entries and stops."""

from __future__ import annotations

from datetime import datetime, timezone
import math
from typing import Any
import json
from decimal import Decimal, ROUND_DOWN, ROUND_CEILING
import os
import uuid

from binance_client import BinanceAPIError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingOrderRecord, SignalSpec, SignalState, SignalType
from decision_trace import DecisionTrace
from execution_barrier import ExecutionBarrier, OrderIntent


class CampaignExecutionError(RuntimeError):
    pass


class CampaignExecutionService:
    ENTRY_PREFIX = "WILLV5_ENTRY_"
    STOP_PREFIX = "WILLV5_STOP_"
    EXIT_PREFIX = "WILLV5_EXIT_"

    def __init__(self, client, db, execution_barrier: ExecutionBarrier | None = None):
        self.client = client
        self.db = db
        self.barrier = execution_barrier
        self.engine = CampaignEngine(
            db,
            portfolio_risk_limit_pct=min(0.006, float(os.getenv("MAX_TOTAL_RISK_PCT", "0.006"))),
            campaign_risk_limit_pct=min(0.006, float(os.getenv("MAX_CAMPAIGN_RISK_PCT", "0.006"))),
            initial_risk_fraction_of_campaign=float(
                os.getenv("CAMPAIGN_INITIAL_RISK_FRACTION", "0.40")
            ),
        )

    @staticmethod
    def _symbols_rows(info: dict[str, Any]) -> dict[str, dict]:
        return {
            str(row.get("symbol", "")).upper(): row
            for row in info.get("symbols", [])
            if isinstance(row, dict)
        }

    def _rules(self, symbol: str) -> dict[str, Any]:
        info = self.client.exchange_info(symbol)
        row = self._symbols_rows(info).get(symbol.upper())
        if row is None:
            raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable")
        return {
            f.get("filterType"): f
            for f in row.get("filters", [])
            if isinstance(f, dict)
        }

    @staticmethod
    def _floor(value: float, step: float) -> float:
        import math
        try:
            value_d, step_d = Decimal(str(value)), Decimal(str(step))
        except Exception as exc:
            raise CampaignExecutionError("quantity/price and exchange step must be decimal numbers") from exc
        if not value_d.is_finite() or value_d <= 0:
            raise CampaignExecutionError("quantity/price must be finite and positive")
        if not step_d.is_finite() or step_d <= 0:
            raise CampaignExecutionError("exchange step/tick filter is missing or invalid")
        result_d = (value_d / step_d).to_integral_value(rounding=ROUND_DOWN) * step_d
        result = float(result_d)
        if not math.isfinite(result) or result <= 0:
            raise CampaignExecutionError("normalized quantity/price is invalid")
        return result

    def _normalize_qty(self, symbol: str, quantity: float) -> float:
        filters = self._rules(symbol)
        lot = filters.get("MARKET_LOT_SIZE") if "MARKET_LOT_SIZE" in filters else filters.get("LOT_SIZE")
        if not isinstance(lot, dict) or not {"stepSize", "minQty", "maxQty"}.issubset(lot):
            raise CampaignExecutionError(f"{symbol}: required quantity filter is missing")
        import math
        try:
            step, minimum, maximum = (float(lot[k]) for k in ("stepSize", "minQty", "maxQty"))
            quantity = float(quantity)
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: quantity/filter values are invalid") from exc
        if not all(math.isfinite(v) for v in (step, minimum, maximum, quantity)) or step <= 0 or minimum < 0 or maximum <= 0 or minimum > maximum or quantity <= 0:
            raise CampaignExecutionError(f"{symbol}: quantity/filter values are invalid")
        qty = self._floor(quantity, step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty < minimum or qty > maximum:
            raise CampaignExecutionError(f"{symbol}: normalized quantity violates LOT_SIZE")
        return qty

    def _normalized_entry_qty(self, symbol: str, notional: float, trigger: float, *, filters=None) -> float:
        filters = filters if filters is not None else self._rules(symbol)
        lot = filters.get("LOT_SIZE")
        if not isinstance(lot, dict) or not {"stepSize", "minQty", "maxQty"}.issubset(lot):
            raise CampaignExecutionError(f"{symbol}: LOT_SIZE filter unavailable")
        import math
        try:
            notional, trigger = float(notional), float(trigger)
            step, minimum, maximum = (float(lot[k]) for k in ("stepSize", "minQty", "maxQty"))
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: invalid entry/filter values") from exc
        if not all(math.isfinite(v) for v in (notional, trigger, step, minimum, maximum)) or notional <= 0 or trigger <= 0 or step <= 0 or minimum < 0 or maximum <= 0 or minimum > maximum:
            raise CampaignExecutionError(f"{symbol}: invalid entry/filter values")
        qty = self._floor(notional / trigger, step)
        market_lot = filters.get("MARKET_LOT_SIZE")
        if not isinstance(market_lot, dict) or not {"stepSize", "minQty", "maxQty"}.issubset(market_lot):
            raise CampaignExecutionError(f"{symbol}: MARKET_LOT_SIZE filter unavailable for conditional market execution")
        try:
            market_step, market_min, market_max = (
                float(market_lot[k]) for k in ("stepSize", "minQty", "maxQty")
            )
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: MARKET_LOT_SIZE values invalid") from exc
        if not all(math.isfinite(v) for v in (market_step, market_min, market_max)) or market_step <= 0 or market_min < 0 or market_max <= 0 or market_min > market_max:
            raise CampaignExecutionError(f"{symbol}: MARKET_LOT_SIZE values invalid")
        qty = self._floor(qty, market_step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty > market_max:
            qty = self._floor(market_max, market_step)
        if qty < minimum or qty > maximum or qty < market_min or qty > market_max:
            raise CampaignExecutionError(f"{symbol}: normalized entry quantity violates LOT_SIZE/MARKET_LOT_SIZE")
        # Binance requires every applicable quantity grid to be satisfied.
        # Sequential flooring alone is not sufficient when the steps differ.
        try:
            qty_d = Decimal(str(qty))
            lot_step_d = Decimal(str(lot["stepSize"]))
            market_step_d = Decimal(str(market_lot["stepSize"]))
        except Exception as exc:
            raise CampaignExecutionError(f"{symbol}: quantity-step decimal conversion failed") from exc
        if (
            not qty_d.is_finite()
            or not lot_step_d.is_finite()
            or not market_step_d.is_finite()
            or qty_d % lot_step_d != 0
            or qty_d % market_step_d != 0
        ):
            raise CampaignExecutionError(
                f"{symbol}: rounded quantity is not an exact multiple of every applicable lot step"
            )
        nf = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL")
        if not isinstance(nf, dict) or "minNotional" not in nf:
            raise CampaignExecutionError(f"{symbol}: NOTIONAL/MIN_NOTIONAL filter unavailable")
        try:
            min_notional = float(nf["minNotional"])
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: invalid minNotional filter") from exc
        if not math.isfinite(min_notional) or min_notional < 0:
            raise CampaignExecutionError(f"{symbol}: invalid minNotional filter")
        rounded_notional = qty * trigger
        if rounded_notional < min_notional:
            raise CampaignExecutionError(
                f"{symbol}: rounded entry notional {rounded_notional:.8f} below minimum {min_notional:.8f}"
            )
        max_notional = nf.get("maxNotional")
        if max_notional not in (None, ""):
            try:
                max_notional = float(max_notional)
            except (TypeError, ValueError, OverflowError) as exc:
                raise CampaignExecutionError(f"{symbol}: invalid maxNotional filter") from exc
            if not math.isfinite(max_notional) or max_notional <= 0 or rounded_notional > max_notional:
                raise CampaignExecutionError(f"{symbol}: rounded entry notional exceeds NOTIONAL maximum")
        return qty

    def _normalize_price(self, symbol: str, price: float, *, filters=None) -> float:
        filters = filters if filters is not None else self._rules(symbol)
        pf = filters.get("PRICE_FILTER")
        if not isinstance(pf, dict) or "tickSize" not in pf:
            raise CampaignExecutionError(f"{symbol}: PRICE_FILTER.tickSize unavailable")
        try:
            tick = float(pf["tickSize"])
            min_price = float(pf.get("minPrice", "0") or 0)
            max_price = float(pf.get("maxPrice", "0") or 0)
            price = float(price)
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: invalid PRICE_FILTER values") from exc
        import math
        if not all(math.isfinite(v) for v in (price, tick, min_price, max_price)) or price <= 0 or tick <= 0 or min_price < 0 or max_price < 0:
            raise CampaignExecutionError(f"{symbol}: invalid PRICE_FILTER values")
        result = self._floor(price, tick)
        if min_price > 0 and result < min_price:
            raise CampaignExecutionError(f"{symbol}: price below PRICE_FILTER.minPrice")
        if max_price > 0 and result > max_price:
            raise CampaignExecutionError(f"{symbol}: price above PRICE_FILTER.maxPrice")
        return result

    def _normalize_trigger_price(self, symbol: str, price: float, *, filters=None) -> float:
        filters = filters if filters is not None else self._rules(symbol)
        pf = filters.get("PRICE_FILTER")
        if not isinstance(pf, dict):
            raise CampaignExecutionError(f"{symbol}: PRICE_FILTER unavailable")
        try:
            tick, value = Decimal(str(pf["tickSize"])), Decimal(str(price))
        except Exception as exc:
            raise CampaignExecutionError(f"{symbol}: invalid trigger/tick decimal") from exc
        if not tick.is_finite() or tick <= 0 or not value.is_finite() or value <= 0:
            raise CampaignExecutionError(f"{symbol}: trigger/tick must be finite and positive")
        result = float((value / tick).to_integral_value(rounding=ROUND_CEILING) * tick)
        self._normalize_price(symbol, result, filters=filters)
        return result

    def _check_buy_position_capacity(self, symbol: str, quantity: float) -> None:
        filters = self._rules(symbol)
        max_position_filter = filters.get("MAX_POSITION") or {}
        max_position = float(
            max_position_filter.get("maxPosition", "inf") or "inf"
        )
        if not max_position < float("inf"):
            return

        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise CampaignExecutionError(
                f"{symbol}: exchangeInfo unavailable for MAX_POSITION check"
            )
        asset = str(rows[0].get("baseAsset", "")).upper()
        account = self.client.account()
        base_total = 0.0
        for balance in account.get("balances", []):
            if str(balance.get("asset", "")).upper() == asset:
                base_total = (
                    float(balance.get("free", 0) or 0)
                    + float(balance.get("locked", 0) or 0)
                )
                break

        open_orders = self.client.open_orders(symbol)
        pending_buy_qty = sum(
            max(
                0.0,
                float(o.get("origQty", 0) or 0)
                - float(o.get("executedQty", 0) or 0),
            )
            for o in open_orders
            if str(o.get("side", "")).upper() == "BUY"
        )
        tolerance = max(1e-12, max_position * 1e-9)
        if base_total + pending_buy_qty + float(quantity) > max_position + tolerance:
            raise CampaignExecutionError(
                f"{symbol}: MAX_POSITION would be exceeded "
                f"(base={base_total:.12g}, pending_buy={pending_buy_qty:.12g}, "
                f"new={float(quantity):.12g}, max={max_position:.12g})"
            )

    def _current_price(self, symbol: str) -> float:
        row = self.client.ticker_price(symbol)
        price = float(row.get("price", 0) or 0)
        import math
        if not math.isfinite(price) or price <= 0:
            raise CampaignExecutionError(f"{symbol}: invalid current market price")
        return price

    def _check_algo_capacity(self, symbol: str, additional: int = 1) -> None:
        filters = self._rules(symbol)
        open_orders = self.client.open_orders(symbol)
        max_orders = int((filters.get("MAX_NUM_ORDERS") or {}).get("maxNumOrders", 10**9))
        max_algo = int((filters.get("MAX_NUM_ALGO_ORDERS") or {}).get("maxNumAlgoOrders", 10**9))
        if len(open_orders) + additional > max_orders:
            raise CampaignExecutionError(f"{symbol}: MAX_NUM_ORDERS would be exceeded")
        algo_open = sum(
            1
            for row in open_orders
            if str(row.get("type", "")).upper()
            in {"STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"}
        )
        if algo_open + additional > max_algo:
            raise CampaignExecutionError(f"{symbol}: MAX_NUM_ALGO_ORDERS would be exceeded")

    def _submit(self, intent: OrderIntent, submit, pre_submit_checks):
        if self.barrier is None:
            raise CampaignExecutionError(
                "Campaign execution requires the canonical ExecutionBarrier"
            )
        result = self.barrier.execute(
            intent,
            submit,
            pre_submit_checks=pre_submit_checks,
        )
        if not result.accepted:
            raise CampaignExecutionError(
                f"ExecutionBarrier blocked {intent.purpose}: {result.reason}"
            )
        return result.response

    def arm_initial_entry(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
        capital_fraction: float = 0.25,
    ) -> dict[str, Any]:
        if signal.side != "BUY":
            raise CampaignExecutionError("Current Spot campaign executor only arms LONG entries")

        import math
        try:
            equity_quote = float(equity_quote)
            candidate_risk_pct = float(candidate_risk_pct)
            capital_fraction = float(capital_fraction)
            structural_reference = float(signal.protective_reference)
            raw_trigger = float(signal.trigger_price)
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError("entry sizing inputs must be numeric") from exc
        if not all(math.isfinite(v) for v in (equity_quote, candidate_risk_pct, capital_fraction, structural_reference, raw_trigger)):
            raise CampaignExecutionError("entry sizing inputs must be finite")
        if equity_quote <= 0 or candidate_risk_pct <= 0 or not 0 < capital_fraction <= 0.25:
            raise CampaignExecutionError("equity/risk/capital fraction is outside the canonical range")

        filters = self._rules(signal.symbol)
        if not isinstance(filters.get("PRICE_FILTER"), dict) or not isinstance(filters.get("LOT_SIZE"), dict) or not (isinstance(filters.get("NOTIONAL"), dict) or isinstance(filters.get("MIN_NOTIONAL"), dict)):
            raise CampaignExecutionError(f"{signal.symbol}: required exchange price/lot/notional filters are missing")
        current = self._current_price(signal.symbol)
        trigger = self._normalize_trigger_price(signal.symbol, raw_trigger, filters=filters)
        stop = self._normalize_price(signal.symbol, structural_reference, filters=filters)
        account = self.client.account()
        if not isinstance(account, dict) or not isinstance(account.get("balances"), list):
            raise CampaignExecutionError("fresh account balances unavailable; entry blocked")
        try:
            free_usdt = float(next(
                (b.get("free") for b in account["balances"] if str(b.get("asset", "")).upper() == "USDT"),
                None,
            ))
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError("free USDT balance unavailable; entry blocked") from exc
        if not math.isfinite(free_usdt) or free_usdt <= 0:
            raise CampaignExecutionError("free USDT balance is invalid or insufficient; entry blocked")

        if trigger <= current:
            raise CampaignExecutionError(
                f"{signal.symbol}: signal trigger {trigger:.12g} is not above current price {current:.12g}; "
                "trigger was missed, no market-order substitution is allowed"
            )
        if stop <= 0 or stop >= trigger:
            raise CampaignExecutionError(
                f"{signal.symbol}: invalid campaign structural stop {stop:.12g}"
            )

        stop_fraction = (trigger - stop) / trigger
        fee_per_side = float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001"))
        slippage = float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        if not math.isfinite(fee_per_side) or not math.isfinite(slippage) or fee_per_side < 0 or slippage < 0:
            raise CampaignExecutionError("fee/slippage buffers must be finite and non-negative")
        effective_loss_fraction = stop_fraction + 2.0 * fee_per_side + slippage
        if not math.isfinite(effective_loss_fraction) or effective_loss_fraction <= 0:
            raise CampaignExecutionError("effective stop loss fraction is invalid")
        client_id = f"{self.ENTRY_PREFIX}{uuid.uuid4().hex[:20]}"

        # Check capacity and persist the reservation in one serialized SQLite
        # transaction before the first possible Binance order mutation.
        with self.db.transaction():
            if self.db.state_get("risk_entry_reservation") is not None:
                raise CampaignExecutionError("another risk reservation is already pending")
            if self.db.active_campaign_count() >= 1:
                raise CampaignExecutionError("maximum one active campaign already reserved")
            reserved = self.engine.portfolio_reserved_risk_quote()
            capacity = equity_quote * self.engine.portfolio_risk_limit_pct
            remaining_risk = max(0.0, capacity - reserved)
            requested_risk = min(
                candidate_risk_pct,
                self.engine.initial_risk_pct(),
                remaining_risk / equity_quote,
            )
            if not math.isfinite(requested_risk) or requested_risk <= 0:
                raise CampaignExecutionError("No portfolio risk capacity for campaign entry")
            risk_quote = equity_quote * requested_risk
            notional = min(
                equity_quote * capital_fraction,
                risk_quote / effective_loss_fraction,
                free_usdt * 0.95,
            )
            qty = self._normalized_entry_qty(signal.symbol, notional, trigger, filters=filters)
            if qty * trigger > notional + max(1e-10, notional * 1e-10):
                raise CampaignExecutionError("quantity rounding would exceed approved notional")
            realized_risk_quote = qty * trigger * effective_loss_fraction
            if realized_risk_quote > risk_quote + max(1e-8, risk_quote * 1e-9):
                raise CampaignExecutionError("rounded quantity would exceed approved risk")

            if not self.db.try_claim_state("risk_entry_reservation", client_id):
                raise CampaignExecutionError("another risk reservation is already pending")
            if not self.db.try_claim_state(f"entry_client_order_id:{signal.symbol}", client_id):
                raise CampaignExecutionError(f"{signal.symbol}: another conditional entry is already reserved")

            campaign = self.engine.create_campaign(signal, initial_risk_pct=requested_risk)
            campaign.tags["signal_role"] = signal.role.value
            campaign.tags["decision_trace"] = DecisionTrace.from_signal(signal).to_dict()
            campaign.tags["pending_signal_expires_at_ms"] = int(signal.expires_at_ms or 0)
            campaign.tags["initial_stop_price"] = stop
            campaign.tags["pending_order_client_id"] = client_id
            campaign.tags["pending_order_quantity"] = qty
            campaign.tags["pending_order_trigger"] = trigger
            campaign.initial_stop_price = stop
            campaign.current_stop_price = stop
            # Reserve at the maximum approved loss, not just the idealized fill.
            campaign.pending_risk_quote = risk_quote
            campaign.capital_reserved_quote = qty * trigger
            self.db.save_campaign(campaign)
            # Persist ENTRY_PENDING and its risk reservation before Binance.
            self.engine.arm_entry(campaign, signal)
            self.db.state_set(f"campaign_state:{campaign.campaign_id}", campaign.state.value)

        intent = OrderIntent.new(
            signal.symbol,
            "BUY",
            "STOP_LOSS",
            required_context_versions=dict(signal.context_versions),
            hypothesis_id=f"WILLIAMS_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=client_id,
            purpose="CAMPAIGN_ENTRY",
            permission_interval=signal.timeframe,
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=risk_quote,
            capital_reserved_quote=qty * trigger,
        )

        def check(snapshot):
            # Last-mile market condition: this is a conditional order, never a
            # substitute for a missed trigger. If price already crossed the
            # trigger, abort rather than turn it into a MARKET BUY.
            now_price = self._current_price(signal.symbol)
            if now_price >= trigger:
                raise CampaignExecutionError(
                    f"{signal.symbol}: trigger already crossed at {now_price:.12g}"
                )
            latest_account = self.client.account()
            latest_balances = latest_account.get("balances") if isinstance(latest_account, dict) else None
            if not isinstance(latest_balances, list):
                raise CampaignExecutionError(f"{signal.symbol}: last-mile account balance unavailable")
            try:
                latest_free_usdt = float(next(
                    (b.get("free") for b in latest_balances if str(b.get("asset", "")).upper() == "USDT"),
                    None,
                ))
            except (TypeError, ValueError, OverflowError) as exc:
                raise CampaignExecutionError(f"{signal.symbol}: last-mile free USDT invalid") from exc
            if not math.isfinite(latest_free_usdt) or latest_free_usdt + 1e-9 < qty * trigger:
                raise CampaignExecutionError(
                    f"{signal.symbol}: fresh free USDT is insufficient for reserved order quantity"
                )

            # For the fractal signal, validity is specifically evaluated at
            # trigger time relative to Teeth. At arm time we require trigger
            # to remain above the latest Teeth as an early sanity check.
            if signal.signal_type == SignalType.FRACTAL:
                try:
                    from data import fetch_klines
                    from strategy import calculate_indicators, config_from_env
                    df = fetch_klines(
                        self.client,
                        signal.symbol,
                        signal.timeframe,
                        limit=160,
                    )
                    closed = df.iloc[:-1].copy() if len(df) > 1 else df
                    ind = calculate_indicators(closed, config_from_env())
                    teeth = float(ind.iloc[-1].get("teeth_shifted", 0.0) or 0.0)
                    if teeth > 0 and trigger <= teeth:
                        raise CampaignExecutionError(
                            f"{signal.symbol}: fractal trigger {trigger:.12g} is now not above Teeth {teeth:.12g}"
                        )
                except CampaignExecutionError:
                    raise
                except Exception as exc:
                    raise CampaignExecutionError(
                        f"{signal.symbol}: fractal last-mile validation failed: {exc}"
                    ) from exc

            self._check_buy_position_capacity(
                signal.symbol,
                qty,
            )
            self._check_algo_capacity(signal.symbol, additional=1)

        try:
            order = self._submit(
                intent,
                lambda: self.client.order_safe(
                    signal.symbol,
                    "BUY",
                    "STOP_LOSS",
                    quantity=self.client.decimal_format(qty),
                    stop_price=self.client.decimal_format(trigger),
                    new_client_order_id=client_id,
                ),
                check,
            )
            self.db.save_campaign_order(
                PendingOrderRecord(
                    order_id=str(order.get("orderId", "") or ""),
                    client_order_id=client_id,
                    symbol=signal.symbol,
                    side="BUY",
                    order_type="STOP_LOSS",
                    purpose="ENTRY",
                    status=str(order.get("status", "NEW")),
                    stop_price=trigger,
                    quantity=qty,
                    risk_quote=risk_quote,
                    capital_reserved_quote=qty * trigger,
                    signal_id=signal.signal_id,
                    campaign_id=campaign.campaign_id,
                )
            )
            campaign.tags["pending_order_id"] = str(order.get("orderId", "") or "")
            self.db.save_campaign(campaign)
            self.db.state_delete("risk_entry_reservation")
            return {
                "campaign_id": campaign.campaign_id,
                "signal_id": signal.signal_id,
                "order_id": order.get("orderId"),
                "client_order_id": client_id,
                "trigger_price": trigger,
                "structural_stop": stop,
                "quantity": qty,
                "risk_quote": risk_quote,
            }
        except Exception as exc:
            # A pre-submit barrier rejection is safe to clear. Any exchange
            # mutation ambiguity remains fail-closed and recoverable by CID.
            message = str(exc)
            if "ExecutionBarrier blocked" in message:
                campaign.state = CampaignState.CLOSED
                campaign.pending_risk_quote = 0.0
                campaign.capital_reserved_quote = 0.0
                self.db.set_campaign_signal_state(
                    signal.signal_id,
                    SignalState.INVALIDATED.value,
                )
                self.db.state_delete(f"entry_client_order_id:{signal.symbol}")
                self.db.state_delete("risk_entry_reservation")
                self.db.save_campaign(campaign)
            else:
                self.engine.mark_reconcile_required(campaign, message)
            raise

    def _find_campaign_by_pending_client_id(self, client_id: str):
        row = self.db.conn.execute(
            "SELECT campaign_id FROM campaign_orders WHERE client_order_id=? "
            "ORDER BY id DESC LIMIT 1",
            (str(client_id),),
        ).fetchone()
        if row:
            return self.engine.load_campaign(str(row["campaign_id"]))

        # Recovery race guard: Binance may have accepted the conditional order
        # before campaign_orders was persisted. Find the durable campaign by
        # its persisted pending clientOrderId tag.
        for item in self.db.open_campaigns():
            try:
                tags = json.loads(item.get("tags_json") or "{}")
            except Exception:
                tags = {}
            if str(tags.get("pending_order_client_id", "")) == str(client_id):
                return self.engine.load_campaign(str(item["campaign_id"]))
        return None


    def _historical_fee_quote(self, asset: str, quote_asset: str, amount: float, timestamp_ms: int) -> float:
        """Value a third-asset commission using the 1-minute candle containing the fill.

        This is a documented valuation convention, not a claim of exact intraminute
        execution value. If historical conversion is unavailable, accounting fails closed.
        """
        if amount == 0:
            return 0.0
        minute_start = int(timestamp_ms) - (int(timestamp_ms) % 60_000)
        for pair, inverse in ((f"{asset}{quote_asset}", False), (f"{quote_asset}{asset}", True)):
            try:
                candles = self.client.klines(
                    pair, "1m", limit=1,
                    start_time=minute_start, end_time=minute_start + 60_000,
                )
                if not isinstance(candles, list) or not candles:
                    continue
                close = float(candles[0][4])
                if not math.isfinite(close) or close <= 0:
                    continue
                return float(amount) / close if inverse else float(amount) * close
            except Exception:
                continue
        raise CampaignExecutionError(
            f"cannot value {asset} commission in {quote_asset} at fill time; reconciliation required"
        )

    def _account_order_fills(self, symbol: str, campaign_id: str, order: dict[str, Any]) -> dict[str, float]:
        """Persist authoritative per-trade fills once and derive net Spot base inventory."""
        order_id = order.get("orderId")
        if order_id is None:
            raise CampaignExecutionError(f"{symbol}: executed order has no authoritative orderId")
        executed = float(order.get("executedQty", 0) or 0)
        if not math.isfinite(executed) or executed <= 0:
            raise CampaignExecutionError(f"{symbol}: invalid executed quantity")
        history = getattr(self.client, "my_trades", None)
        if not callable(history):
            raise CampaignExecutionError(f"{symbol}: authoritative trade history unavailable")
        try:
            fills = history(symbol, order_id=order_id, limit=1000)
        except Exception as exc:
            raise CampaignExecutionError(
                f"{symbol}: trade history unavailable for order {order_id}; reconciliation required"
            ) from exc
        if not isinstance(fills, list) or not fills:
            raise CampaignExecutionError(
                f"{symbol}: executed order {order_id} has no authoritative trade fills"
            )

        rows = self._symbols_rows(self.client.exchange_info(symbol))
        row = rows.get(symbol.upper())
        if not row:
            raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable for fee accounting")
        base_asset = str(row.get("baseAsset", "")).upper()
        quote_asset = str(row.get("quoteAsset", "")).upper()
        if not base_asset or not quote_asset:
            raise CampaignExecutionError(f"{symbol}: base/quote assets unavailable for fee accounting")

        with self.db.transaction():
            for fill in fills:
                if str(fill.get("orderId", "")) != str(order_id):
                    continue
                if not bool(fill.get("isBuyer", True)):
                    raise CampaignExecutionError(f"{symbol}: non-BUY fill returned for entry order {order_id}")
                trade_id = fill.get("id")
                if trade_id is None or str(trade_id).strip() == "":
                    raise CampaignExecutionError(f"{symbol}: trade history row lacks exchange trade ID")
                try:
                    qty = float(fill["qty"])
                    price = float(fill["price"])
                    quote_qty = float(fill.get("quoteQty", qty * price))
                    commission = float(fill["commission"])
                    timestamp_ms = int(fill["time"])
                    fee_asset = str(fill["commissionAsset"]).upper()
                except (KeyError, TypeError, ValueError, OverflowError) as exc:
                    raise CampaignExecutionError(
                        f"{symbol}: incomplete fee/fill fields for trade {trade_id}"
                    ) from exc
                if (
                    not all(math.isfinite(v) for v in (qty, price, quote_qty, commission))
                    or qty <= 0 or price <= 0 or quote_qty <= 0 or commission < 0
                    or timestamp_ms <= 0 or not fee_asset
                ):
                    raise CampaignExecutionError(
                        f"{symbol}: invalid quantity, price or commission for trade {trade_id}"
                    )
                commission_base = commission if fee_asset == base_asset else 0.0
                if fee_asset == quote_asset:
                    fee_quote = commission
                elif fee_asset == base_asset:
                    fee_quote = commission * price
                elif commission == 0:
                    fee_quote = 0.0
                else:
                    fee_quote = self._historical_fee_quote(
                        fee_asset, quote_asset, commission, timestamp_ms
                    )
                self.db.save_campaign_fill({
                    "campaign_id": campaign_id,
                    "order_id": str(order_id),
                    "trade_id": str(trade_id),
                    "symbol": symbol.upper(),
                    "side": "BUY" if bool(fill.get("isBuyer", True)) else "SELL",
                    "quantity": qty,
                    "price": price,
                    "quote_quantity": quote_qty,
                    "fee_quote": fee_quote,
                    "commission_base": commission_base,
                    "commission_asset": fee_asset,
                    "raw_json": fill,
                })

        stored = self.db.campaign_fills_for_order(symbol, order_id)
        if not stored:
            raise CampaignExecutionError(f"{symbol}: no durable fill ledger after commit")
        gross_qty = sum(float(x["quantity"]) for x in stored)
        quote_qty = sum(float(x["quote_quantity"]) for x in stored)
        fee_quote = sum(float(x["fee_quote"]) for x in stored)
        commission_base = sum(float(x["commission_base"]) for x in stored)
        tolerance = max(1e-12, executed * 1e-8)
        if abs(gross_qty - executed) > tolerance:
            raise CampaignExecutionError(
                f"{symbol}: durable fills ({gross_qty:.12g}) do not reconcile to exchange executedQty ({executed:.12g})"
            )
        exchange_quote = float(order.get("cummulativeQuoteQty", 0) or 0)
        if exchange_quote > 0 and abs(quote_qty - exchange_quote) > max(1e-8, exchange_quote * 1e-8):
            raise CampaignExecutionError(
                f"{symbol}: durable fill quote ({quote_qty:.12g}) does not reconcile to exchange quote ({exchange_quote:.12g})"
            )
        net_qty = gross_qty - commission_base
        if not math.isfinite(net_qty) or net_qty <= 0:
            raise CampaignExecutionError(f"{symbol}: net Spot inventory is non-positive after base-asset commission")
        return {
            "gross_quantity": gross_qty,
            "net_quantity": net_qty,
            "quote_quantity": quote_qty,
            "average_entry_price": quote_qty / net_qty,
            "fee_quote": fee_quote,
            "commission_base": commission_base,
        }

    def reconcile_pending_entries(self) -> list[dict[str, Any]]:
        """Adopt conditional BUYs after triggers, partial fills, restart or crash."""
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state "
            "WHERE key LIKE 'entry_client_order_id:%' "
            "AND ("
            "value LIKE 'WILLV5_ENTRY_%' OR "
            "value LIKE 'WILLV4_ENTRY_%' OR "
            "value LIKE 'W5E_%' OR "
            "value LIKE 'W4B_%'"
            ")"
        ).fetchall()
        results: list[dict[str, Any]] = []

        for row in rows:
            symbol = str(row["key"]).split(":", 1)[1].upper()
            client_id = str(row["value"])
            campaign = None
            try:
                order = self.client.get_order(
                    symbol,
                    orig_client_order_id=client_id,
                )
                self.db.save_order(order)
                status = str(order.get("status", "")).upper()
                campaign = self._find_campaign_by_pending_client_id(client_id)

                if campaign is None:
                    self.db.state_set(
                        f"position_state:{symbol}",
                        "RECONCILE_REQUIRED",
                    )
                    results.append({
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "reason": "conditional order has no persisted campaign",
                    })
                    continue

                order_row = self.db.conn.execute(
                    "SELECT purpose,signal_id FROM campaign_orders "
                    "WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
                    (client_id,),
                ).fetchone()
                purpose = str(order_row["purpose"] if order_row else "ENTRY").upper()
                signal_id = str(
                    order_row["signal_id"]
                    if order_row
                    else campaign.current_signal_id
                )

                gross_executed = float(order.get("executedQty", 0) or 0)
                fill_accounting = None
                executed = gross_executed
                quote = float(order.get("cummulativeQuoteQty", 0) or 0)
                fee_quote = 0.0
                if gross_executed > 0:
                    fill_accounting = self._account_order_fills(
                        symbol, campaign.campaign_id, order
                    )
                    executed = fill_accounting["net_quantity"]
                    quote = fill_accounting["quote_quantity"]
                    fee_quote = fill_accounting["fee_quote"]

                if status in {"NEW", "PENDING_NEW"} and executed <= 0:
                    # Still waiting for the conditional trigger.
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "ENTRY_PENDING",
                        "order_status": status,
                    })
                    continue

                if status == "PARTIALLY_FILLED" and order.get("orderId") is not None:
                    cancel = getattr(self.client, "cancel_order", None)
                    if cancel is None:
                        raise CampaignExecutionError(
                            f"{symbol}: partial conditional BUY cannot be safely cancelled"
                        )
                    try:
                        cancel(symbol, order_id=order.get("orderId"))
                        # A fill can race with cancellation. Re-read authoritative
                        # order state and trade history before sizing protection.
                        order = self.client.get_order(symbol, order_id=order.get("orderId"))
                        status = str(order.get("status", "")).upper()
                        gross_executed = float(order.get("executedQty", 0) or 0)
                        executed = gross_executed
                        if gross_executed > 0:
                            fill_accounting = self._account_order_fills(
                                symbol, campaign.campaign_id, order
                            )
                            executed = fill_accounting["net_quantity"]
                            quote = fill_accounting["quote_quantity"]
                            fee_quote = fill_accounting["fee_quote"]
                    except Exception as exc:
                        self.engine.mark_reconcile_required(
                            campaign,
                            f"partial conditional BUY cancel/re-read ambiguous: {exc}",
                        )
                        raise

                if executed > 0:
                    if quote <= 0:
                        raise CampaignExecutionError(
                            f"{symbol}: executed conditional BUY has no authoritative quote quantity"
                        )
                    avg = quote / executed

                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        if campaign.state == CampaignState.ADD_ON_PENDING:
                            campaign.transition(
                                CampaignState.POSITION_EXPANDING,
                                reason="conditional add-on triggered",
                            )

                        old_qty = float(campaign.position_qty)
                        total_qty = old_qty + executed
                        current_stop = float(campaign.current_stop_price or 0.0)
                        if current_stop <= 0 or current_stop >= avg:
                            raise CampaignExecutionError(
                                f"{symbol}: existing structural stop {current_stop:.12g} "
                                f"is invalid for add-on average {avg:.12g}"
                            )

                        protective_id = str(
                            campaign.tags.get("protective_order_id", "") or ""
                        )
                        if protective_id:
                            protection = self.replace_structural_stop(
                                campaign,
                                existing_order_id=int(protective_id),
                                quantity=total_qty,
                                proposed_stop=current_stop,
                            )
                        else:
                            protection = self.create_hard_stop(
                                campaign,
                                quantity=total_qty,
                                stop_price=current_stop,
                            )
                        campaign.tags["protective_order_id"] = protection.get(
                            "order_id", protection.get("newOrderResponse", {}).get("orderId", "")
                        )
                        protected_qty = float(protection.get("quantity", 0) or 0)
                        protection_gap = max(0.0, total_qty - protected_qty)
                        if protection_gap > max(1e-12, total_qty * 1e-12):
                            campaign.tags["protection_state"] = "PARTIAL"
                            campaign.tags["unprotected_exposure_qty"] = repr(protection_gap)
                        else:
                            campaign.tags["protection_state"] = "FULL"
                            campaign.tags.pop("unprotected_exposure_qty", None)

                        self.engine.record_add_on_fill(
                            campaign,
                            quantity=executed,
                            average_entry_price=avg,
                            fill_order_id=str(order.get("orderId", "")),
                            risk_quote=float(campaign.pending_risk_quote),
                            fee_quote=fee_quote,
                        )
                        if protection_gap > max(1e-12, total_qty * 1e-12):
                            self.engine.mark_reconcile_required(
                                campaign,
                                f"protective quantity shortfall {protection_gap:.12g} {symbol}; exposure remains",
                            )
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.FILLED.value,
                        )
                        trade = self.db.open_trade(symbol)
                        if trade is not None:
                            self.db.update_trade_quantity(
                                trade["id"],
                                total_qty,
                            )
                            self.db.conn.execute(
                                "UPDATE trades SET entry_price=?, stop_price=?, "
                                "risk_pct=?, fees=COALESCE(fees,0)+?, updated_at=CURRENT_TIMESTAMP "
                                "WHERE id=? AND exit_time IS NULL",
                                (
                                    campaign.average_entry_price,
                                    campaign.current_stop_price,
                                    float(
                                        campaign.open_risk_quote /
                                        max(self._current_equity_quote(), 1e-12)
                                    ) * 100.0,
                                    fee_quote,
                                    int(trade["id"]),
                                ),
                            )
                            self.db.conn.commit()

                        self.db.state_delete(f"entry_client_order_id:{symbol}")
                        self.db.state_set(
                            f"position_state:{symbol}",
                            "RECONCILE_REQUIRED" if campaign.state == CampaignState.RECONCILE_REQUIRED else "OPEN",
                        )
                        campaign.tags.pop("pending_order_id", None)
                        self.db.save_campaign(campaign)
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": campaign.state.value,
                            "action": "ADD_ON_FILLED",
                            "filled_quantity": executed,
                        })
                        continue

                    if campaign.state == CampaignState.ENTRY_PENDING:
                        self.engine.mark_triggered(
                            campaign,
                            signal_id,
                            str(order.get("orderId", "")),
                        )
                    elif campaign.state == CampaignState.SIGNAL_DETECTED:
                        # Defensive recovery for a crash between state creation
                        # and campaign arming. The durable Binance order is proof
                        # that entry was already submitted; recover it, don't arm twice.
                        campaign.transition(
                            CampaignState.ENTRY_PENDING,
                            reason="recovered submitted conditional entry",
                        )
                        self.engine.mark_triggered(
                            campaign,
                            signal_id,
                            str(order.get("orderId", "")),
                        )
                    else:
                        raise CampaignExecutionError(
                            f"{symbol}: unexpected campaign state {campaign.state.value} for initial fill"
                        )

                    stop = float(
                        campaign.initial_stop_price or
                        campaign.tags.get("initial_stop_price", 0.0) or
                        0.0
                    )
                    if stop <= 0:
                        raise CampaignExecutionError(
                            f"{symbol}: campaign has no initial structural stop"
                        )

                    protection = self.create_hard_stop(
                        campaign,
                        quantity=executed,
                        stop_price=stop,
                    )
                    campaign.tags["protective_order_id"] = protection.get(
                        "order_id", ""
                    )
                    protected_qty = float(protection.get("quantity", 0) or 0)
                    protection_gap = max(0.0, executed - protected_qty)
                    if protection_gap > max(1e-12, executed * 1e-12):
                        campaign.tags["protection_state"] = "PARTIAL"
                        campaign.tags["unprotected_exposure_qty"] = repr(protection_gap)
                    else:
                        campaign.tags["protection_state"] = "FULL"
                        campaign.tags.pop("unprotected_exposure_qty", None)

                    self.engine.record_initial_fill(
                        campaign,
                        quantity=executed,
                        average_entry_price=avg,
                        initial_stop_price=stop,
                        fill_order_id=str(order.get("orderId", "")),
                        risk_quote=float(campaign.pending_risk_quote),
                        fee_quote=fee_quote,
                    )
                    if protection_gap > max(1e-12, executed * 1e-12):
                        self.engine.mark_reconcile_required(
                            campaign,
                            f"protective quantity shortfall {protection_gap:.12g} {symbol}; exposure remains",
                        )
                    self.db.set_campaign_signal_state(
                        signal_id,
                        SignalState.FILLED.value,
                    )

                    if self.db.open_trade(symbol) is None:
                        self.db.save_trade(
                            entry_time=datetime.fromtimestamp(
                                int(
                                    order.get(
                                        "transactTime",
                                        order.get("time", 0),
                                    ) or 0
                                ) / 1000,
                                tz=timezone.utc,
                            ).isoformat(),
                            symbol=symbol,
                            side="LONG",
                            entry_price=avg,
                            quantity=executed,
                            entry_order_id=str(order.get("orderId", "")),
                            entry_client_order_id=client_id,
                            stop_price=stop,
                            take_profit_price=None,
                            risk_pct=float(
                                campaign.tags.get("initial_risk_pct", 0.0) or 0.0
                            ) * 100.0,
                            fees=fee_quote,
                        )

                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(
                        f"position_state:{symbol}",
                        "RECONCILE_REQUIRED" if campaign.state == CampaignState.RECONCILE_REQUIRED else "OPEN",
                    )
                    campaign.tags.pop("pending_order_id", None)
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "RECONCILE_REQUIRED" if campaign.state == CampaignState.RECONCILE_REQUIRED else "OPEN",
                        "filled_quantity": executed,
                        "protective_quantity": protected_qty,
                        "unprotected_exposure_qty": protection_gap,
                        "partial_entry": status == "PARTIALLY_FILLED",
                    })
                    continue

                if status in {"CANCELED", "EXPIRED", "REJECTED"}:
                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        try:
                            campaign.transition(
                                CampaignState.TREND_ACTIVE,
                                reason=f"add-on terminal status {status}",
                            )
                        except ValueError:
                            campaign.state = CampaignState.TREND_ACTIVE
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.CANCELLED.value,
                        )
                    else:
                        campaign.state = CampaignState.CLOSED
                        campaign.next_action = "WAIT"
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        self.db.set_campaign_signal_state(
                            signal_id,
                            SignalState.CANCELLED.value,
                        )
                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(
                        f"position_state:{symbol}",
                        "OPEN" if self.db.open_trade(symbol) else "FLAT",
                    )
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": campaign.state.value,
                        "order_status": status,
                    })
                    continue

                self.engine.mark_reconcile_required(
                    campaign,
                    f"unknown pending conditional order status {status}",
                )
                self.db.state_set(
                    f"position_state:{symbol}",
                    "RECONCILE_REQUIRED",
                )
                results.append({
                    "symbol": symbol,
                    "campaign_id": campaign.campaign_id,
                    "state": "RECONCILE_REQUIRED",
                    "status": status,
                })
            except Exception as exc:
                if campaign is not None:
                    self.engine.mark_reconcile_required(
                        campaign,
                        str(exc),
                    )
                self.db.state_set(
                    f"position_state:{symbol}",
                    "RECONCILE_REQUIRED",
                )
                results.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def _current_equity_quote(self) -> float:
        account = self.client.account()
        equity = 0.0
        for row in account.get("balances", []):
            if str(row.get("asset", "")).upper() == "USDT":
                equity += float(row.get("free", 0) or 0) + float(row.get("locked", 0) or 0)
        return max(equity, 0.0)

    def reconcile_active_campaigns(self) -> list[dict[str, Any]]:
        """Rebuild active campaign protection without invoking the legacy OCO path."""
        results = []
        campaigns = self.db.open_campaigns()
        for row in campaigns:
            try:
                campaign = self.engine.load_campaign(row["campaign_id"])
                if campaign is None:
                    continue
                symbol = campaign.symbol.upper()
                if campaign.state in {
                    CampaignState.ENTRY_PENDING,
                    CampaignState.ENTRY_ARMING,
                    CampaignState.SIGNAL_DETECTED,
                    CampaignState.ADD_ON_PENDING,
                    CampaignState.ADD_ON_ARMING,
                }:
                    # Pending orders are handled by reconcile_pending_entries.
                    continue

                info = self.client.exchange_info(symbol)
                rows_info = info.get("symbols", [])
                if not rows_info:
                    raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable during campaign recovery")
                asset = str(rows_info[0].get("baseAsset", "")).upper()
                account = self.client.account()
                total_base = 0.0
                for balance in account.get("balances", []):
                    if str(balance.get("asset", "")).upper() == asset:
                        total_base = (
                            float(balance.get("free", 0) or 0) +
                            float(balance.get("locked", 0) or 0)
                        )
                        break

                expected = float(campaign.position_qty or 0.0)
                tolerance = max(
                    float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
                    max(expected, 1.0) * float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
                )
                if expected <= 0:
                    campaign.mark_reconcile_required("active campaign has no position quantity")
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                if total_base + tolerance < expected:
                    campaign.mark_reconcile_required(
                        f"campaign inventory below expected: expected={expected:.12g} actual={total_base:.12g}"
                    )
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                open_orders = self.client.open_orders(symbol)
                unknown_sells = [
                    o for o in open_orders
                    if str(o.get("side", "")).upper() == "SELL"
                    and not str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
                ]
                if unknown_sells:
                    raise CampaignExecutionError(
                        f"{symbol}: unrecognized open SELL order conflicts with campaign"
                    )

                managed_stops = [
                    o for o in open_orders
                    if str(o.get("side", "")).upper() == "SELL"
                    and str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
                    and str(o.get("type", "")).upper() in {"STOP_LOSS", "STOP_LOSS_LIMIT"}
                ]
                if len(managed_stops) > 1:
                    campaign.mark_reconcile_required(
                        "multiple active campaign protective stops found"
                    )
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    continue

                if not managed_stops:
                    protection = self.create_hard_stop(
                        campaign,
                        quantity=expected,
                        stop_price=float(campaign.current_stop_price),
                    )
                    campaign.tags["protective_order_id"] = protection.get("order_id", "")
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "action": "PROTECTION_RESTORED",
                    })
                    continue

                stop = managed_stops[0]
                stop_qty = float(stop.get("origQty", 0) or 0)
                if abs(stop_qty - expected) > tolerance:
                    order_id = stop.get("orderId")
                    if order_id is None:
                        raise CampaignExecutionError(f"{symbol}: protective stop has no orderId")
                    protection = self.replace_structural_stop(
                        campaign,
                        existing_order_id=int(order_id),
                        quantity=expected,
                        proposed_stop=float(campaign.current_stop_price),
                    )
                    new_id = str(
                        (protection.get("newOrderResponse") or {}).get("orderId", "")
                    )
                    if not new_id:
                        raise CampaignExecutionError(
                            f"{symbol}: protective stop replacement returned no new order id"
                        )
                    campaign.tags["protective_order_id"] = new_id
                    self.db.save_campaign(campaign)
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "action": "PROTECTION_RESIZED",
                        "old_quantity": stop_qty,
                        "new_quantity": expected,
                    })
                    continue

                campaign.tags["protective_order_id"] = str(stop.get("orderId", ""))
                campaign.health = "GREEN"
                campaign.next_action = "MONITOR_CAMPAIGN"
                campaign.reconciliation_state = "CLEAN"
                self.db.save_campaign(campaign)
                self.db.state_set(f"position_state:{symbol}", "OPEN")
                results.append({
                    "symbol": symbol,
                    "campaign_id": campaign.campaign_id,
                    "action": "CAMPAIGN_VERIFIED",
                })
            except Exception as exc:
                if row.get("campaign_id"):
                    campaign = self.engine.load_campaign(row["campaign_id"])
                    if campaign is not None:
                        self.engine.mark_reconcile_required(campaign, str(exc))
                results.append({
                    "campaign_id": row.get("campaign_id"),
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results

    def _active_campaign_for_symbol(self, symbol: str):
        rows = self.db.conn.execute(
            "SELECT campaign_id FROM campaigns WHERE symbol=? "
            "AND state NOT IN ('CLOSED','FLAT') ORDER BY updated_at DESC LIMIT 1",
            (str(symbol).upper(),),
        ).fetchall()
        return self.engine.load_campaign(str(rows[0]["campaign_id"])) if rows else None

    def _validate_add_on_submission(
        self,
        symbol: str,
        qty: float,
        trigger: float,
        average_entry_price: float,
    ) -> None:
        import math
        current = self._current_price(symbol)
        if not math.isfinite(current) or current <= average_entry_price:
            raise CampaignExecutionError(
                f"{symbol}: add-on rejected because price is not above campaign average entry"
            )
        if current >= trigger:
            raise CampaignExecutionError(
                f"{symbol}: add-on trigger crossed before submission"
            )
        account = self.client.account()
        balances = account.get("balances") if isinstance(account, dict) else None
        if not isinstance(balances, list):
            raise CampaignExecutionError(f"{symbol}: fresh free-USDT balance unavailable")
        try:
            free_usdt = float(next(
                (b.get("free") for b in balances if str(b.get("asset", "")).upper() == "USDT"),
                None,
            ))
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{symbol}: fresh free-USDT balance invalid") from exc
        if not math.isfinite(free_usdt) or free_usdt + 1e-9 < qty * trigger:
            raise CampaignExecutionError(f"{symbol}: fresh free-USDT balance is insufficient for add-on")
        self._check_buy_position_capacity(symbol, qty)
        self._check_algo_capacity(symbol, 1)

    def arm_add_on(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
    ) -> dict[str, Any]:
        import math

        campaign = self._active_campaign_for_symbol(signal.symbol)
        if campaign is None or campaign.position_qty <= 0:
            raise CampaignExecutionError(f"{signal.symbol}: no active campaign for add-on")
        if signal.signal_bar_time_ms <= 0:
            raise CampaignExecutionError("add-on signal time is invalid")

        try:
            equity = float(equity_quote)
            candidate_risk = float(candidate_risk_pct)
            fee_per_side = float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001"))
            slippage = float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
            capital_fraction = float(os.getenv("CAMPAIGN_ADD_CAPITAL_FRACTION", "0.25"))
            add_risk_cap = float(os.getenv("CAMPAIGN_ADD_RISK_MAX_PCT", "0.002"))
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError("add-on risk inputs must be numeric") from exc
        if not all(math.isfinite(v) for v in (
            equity, candidate_risk, fee_per_side, slippage, capital_fraction, add_risk_cap
        )):
            raise CampaignExecutionError("add-on risk inputs must be finite")
        if equity <= 0 or candidate_risk <= 0 or fee_per_side < 0 or slippage < 0:
            raise CampaignExecutionError("add-on equity/risk/fee/slippage input is invalid")
        if not 0 < capital_fraction <= 0.25 or not 0 < add_risk_cap <= 0.002:
            raise CampaignExecutionError("add-on capital/risk cap exceeds canonical limit")

        filters = self._rules(signal.symbol)
        if (
            not isinstance(filters.get("PRICE_FILTER"), dict)
            or not isinstance(filters.get("LOT_SIZE"), dict)
            or not isinstance(filters.get("MARKET_LOT_SIZE"), dict)
            or not (isinstance(filters.get("NOTIONAL"), dict) or isinstance(filters.get("MIN_NOTIONAL"), dict))
        ):
            raise CampaignExecutionError(f"{signal.symbol}: required add-on exchange filters are missing")

        current = self._current_price(signal.symbol)
        trigger = self._normalize_trigger_price(
            signal.symbol, signal.trigger_price, filters=filters
        )
        if not math.isfinite(current) or current <= 0 or trigger <= current:
            raise CampaignExecutionError(
                f"{signal.symbol}: add-on trigger is invalid/already crossed; no market substitution"
            )

        # The add-on must never average down. The live price and the eventual
        # trigger must both be above the actual campaign average entry.
        average_entry = float(campaign.average_entry_price or 0.0)
        if not math.isfinite(average_entry) or average_entry <= 0 or current <= average_entry or trigger <= average_entry:
            raise CampaignExecutionError(
                f"{signal.symbol}: add-on rejected because it would average down"
            )

        current_stop = float(campaign.current_stop_price or 0.0)
        protective_reference = float(signal.protective_reference or 0.0)
        stop_raw = current_stop if current_stop > 0.0 else protective_reference
        stop = self._normalize_price(signal.symbol, stop_raw, filters=filters)
        if not math.isfinite(stop) or stop <= 0 or stop >= trigger:
            raise CampaignExecutionError(f"{signal.symbol}: invalid add-on protection reference")

        stop_fraction = (trigger - stop) / trigger
        effective_loss_fraction = stop_fraction + 2.0 * fee_per_side + slippage
        if not math.isfinite(effective_loss_fraction) or effective_loss_fraction <= 0:
            raise CampaignExecutionError(f"{signal.symbol}: add-on effective loss fraction is invalid")

        account = self.client.account()
        balances = account.get("balances") if isinstance(account, dict) else None
        if not isinstance(balances, list):
            raise CampaignExecutionError(f"{signal.symbol}: free-USDT balance unavailable")
        try:
            free_usdt = float(next(
                (b.get("free") for b in balances if str(b.get("asset", "")).upper() == "USDT"),
                None,
            ))
        except (TypeError, ValueError, OverflowError) as exc:
            raise CampaignExecutionError(f"{signal.symbol}: free-USDT balance invalid") from exc
        if not math.isfinite(free_usdt) or free_usdt <= 0:
            raise CampaignExecutionError(f"{signal.symbol}: free-USDT balance is invalid or insufficient")

        cid = f"{self.ENTRY_PREFIX}ADD_{uuid.uuid4().hex[:16]}"
        prior = None
        requested = 0.0
        qty = 0.0
        campaign_id = campaign.campaign_id

        # Serialize portfolio and campaign capacity checks with the reservation
        # writes. Concurrent add-ons, or add-on vs initial entry, cannot spend
        # the same remaining risk budget.
        with self.db.transaction():
            if self.db.state_get("risk_entry_reservation") is not None:
                raise CampaignExecutionError("another portfolio risk reservation is pending")
            if self.db.state_get(f"entry_client_order_id:{signal.symbol}") is not None:
                raise CampaignExecutionError(f"{signal.symbol}: another conditional order is pending")
            campaign = self.engine.load_campaign(campaign_id)
            if campaign is None or campaign.position_qty <= 0:
                raise CampaignExecutionError(f"{signal.symbol}: active campaign changed during add-on reservation")
            if signal.signal_bar_time_ms <= int(campaign.tags.get("last_signal_time_ms", 0) or 0):
                raise CampaignExecutionError("signal is not newer than current campaign signal")
            if float(campaign.pending_risk_quote or 0.0) > 0:
                raise CampaignExecutionError("campaign already has unconfirmed reserved risk")

            prior = self.db.conn.execute(
                "SELECT state FROM campaign_signals WHERE campaign_id=? AND signal_id=?",
                (campaign.campaign_id, signal.signal_id),
            ).fetchone()
            if prior and str(prior["state"]).upper() in {"ARMED", "TRIGGERED", "FILLED"}:
                raise CampaignExecutionError("signal is already active or filled for this campaign")

            campaign_reserved = float(campaign.open_risk_quote or 0.0)
            campaign_capacity = equity * min(0.006, float(self.engine.campaign_risk_limit_pct))
            portfolio_reserved = float(self.engine.portfolio_reserved_risk_quote())
            portfolio_capacity = equity * min(0.006, float(self.engine.portfolio_risk_limit_pct))
            remaining = max(0.0, min(
                campaign_capacity - campaign_reserved,
                portfolio_capacity - portfolio_reserved,
            ))
            requested = min(
                remaining,
                equity * min(candidate_risk, add_risk_cap),
            )
            if not math.isfinite(requested) or requested <= 0:
                raise CampaignExecutionError("campaign/portfolio risk budget exhausted")

            notional = min(
                equity * capital_fraction,
                free_usdt * 0.95,
                requested / effective_loss_fraction,
            )
            qty = self._normalized_entry_qty(
                signal.symbol, notional, trigger, filters=filters
            )
            rounded_notional = qty * trigger
            rounded_risk = rounded_notional * effective_loss_fraction
            if not all(math.isfinite(v) for v in (rounded_notional, rounded_risk)):
                raise CampaignExecutionError("rounded add-on notional/risk is non-finite")
            if rounded_notional > notional + max(1e-10, notional * 1e-10):
                raise CampaignExecutionError("rounded add-on exceeds approved notional")
            if rounded_risk > requested + max(1e-8, requested * 1e-9):
                raise CampaignExecutionError("rounded add-on would exceed approved campaign risk")

            if not self.db.try_claim_state("risk_entry_reservation", cid):
                raise CampaignExecutionError("another portfolio risk reservation is pending")
            if not self.db.try_claim_state(f"entry_client_order_id:{signal.symbol}", cid):
                raise CampaignExecutionError(f"{signal.symbol}: another conditional order is pending")

            self.db.save_campaign_signal(
                signal, campaign.campaign_id, state=SignalState.DETECTED.value
            )
            campaign.tags["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
            campaign.tags["pending_add_signal_id"] = signal.signal_id
            campaign.tags["pending_order_client_id"] = cid
            campaign.tags["pending_order_quantity"] = qty
            campaign.tags["pending_order_trigger"] = trigger
            campaign.pending_risk_quote = requested
            campaign.capital_reserved_quote = rounded_notional
            self.engine.arm_add_on(
                campaign,
                signal,
                risk_quote=requested,
                capital_reserved_quote=rounded_notional,
            )

        intent = OrderIntent.new(
            signal.symbol,
            "BUY",
            "STOP_LOSS",
            required_context_versions=dict(signal.context_versions),
            hypothesis_id=f"WILLIAMS_ADD_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_ADD_ON",
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=requested,
            capital_reserved_quote=qty * trigger,
        )
        try:
            order = self._submit(
                intent,
                lambda: self.client.order_safe(
                    signal.symbol,
                    "BUY",
                    "STOP_LOSS",
                    quantity=self.client.decimal_format(qty),
                    stop_price=self.client.decimal_format(trigger),
                    new_client_order_id=cid,
                ),
                lambda _snapshot: self._validate_add_on_submission(
                    signal.symbol, qty, trigger, average_entry
                ),
            )
            with self.db.transaction():
                self.db.save_campaign_order(
                    PendingOrderRecord(
                        order_id=str(order.get("orderId", "") or ""),
                        client_order_id=cid,
                        symbol=signal.symbol,
                        side="BUY",
                        order_type="STOP_LOSS",
                        purpose="ADD_ON",
                        status=str(order.get("status", "NEW")),
                        stop_price=trigger,
                        quantity=qty,
                        risk_quote=requested,
                        capital_reserved_quote=qty * trigger,
                        signal_id=signal.signal_id,
                        campaign_id=campaign.campaign_id,
                    )
                )
                campaign.tags["pending_order_id"] = str(order.get("orderId", "") or "")
                self.db.save_campaign(campaign)
                self.db.state_delete("risk_entry_reservation")
            return {
                "campaign_id": campaign.campaign_id,
                "signal_id": signal.signal_id,
                "order_id": order.get("orderId"),
                "client_order_id": cid,
                "trigger_price": trigger,
                "quantity": qty,
                "risk_quote": requested,
            }
        except Exception as exc:
            # If the barrier proves the mutation never occurred, the reservation
            # can be rolled back. On every ambiguous outcome, preserve the
            # pending reservation and require exchange reconciliation.
            message = str(exc)
            if "ExecutionBarrier blocked" in message:
                with self.db.transaction():
                    campaign = self.engine.load_campaign(campaign_id) or campaign
                    campaign.pending_risk_quote = 0.0
                    campaign.capital_reserved_quote = 0.0
                    self.db.state_delete(f"entry_client_order_id:{signal.symbol}")
                    self.db.state_delete("risk_entry_reservation")
                    try:
                        campaign.transition(
                            CampaignState.TREND_ACTIVE,
                            reason="add-on rejected by pre-submit barrier",
                        )
                    except ValueError:
                        self.engine.mark_reconcile_required(campaign, message)
                    self.db.set_campaign_signal_state(
                        signal.signal_id, SignalState.INVALIDATED.value
                    )
                    self.db.save_campaign(campaign)
            else:
                self.engine.mark_reconcile_required(campaign, message)
            raise

    def create_hard_stop(
        self,
        campaign,
        *,
        quantity: float,
        stop_price: float,
    ) -> dict[str, Any]:
        stop = self._normalize_price(campaign.symbol, stop_price)
        if stop <= 0:
            raise CampaignExecutionError("invalid protective stop")
        qty = self._normalize_qty(campaign.symbol, quantity)
        nf = self._rules(campaign.symbol).get("NOTIONAL") or self._rules(campaign.symbol).get("MIN_NOTIONAL") or {}
        min_notional = float(nf.get("minNotional", "0") or 0)
        if min_notional > 0.0 and qty * stop < min_notional:
            raise CampaignExecutionError(
                f"{campaign.symbol}: protective stop notional {qty * stop:.8f} below Binance minimum {min_notional:.8f}"
            )
        self._check_algo_capacity(campaign.symbol, 1)
        cid = f"{self.STOP_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "STOP_LOSS",
            required_context_versions={},
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_PROTECTION",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
            risk_quote=campaign.open_risk_quote,
        )
        order = self._submit(
            intent,
            lambda: self.client.order_safe(
                campaign.symbol,
                "SELL",
                "STOP_LOSS",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(stop),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(campaign.symbol, 1),
        )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(order.get("orderId", "") or ""),
                client_order_id=cid,
                symbol=campaign.symbol,
                side="SELL",
                order_type="STOP_LOSS",
                purpose="PROTECTION",
                status=str(order.get("status", "NEW")),
                stop_price=stop,
                quantity=qty,
                risk_quote=campaign.open_risk_quote,
                signal_id=campaign.current_signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        return {
            "order_id": order.get("orderId"),
            "client_order_id": cid,
            "stop_price": stop,
            "quantity": qty,
        }

    def _execute_cancel(self, campaign, order_id: int, purpose: str) -> dict[str, Any]:
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "CANCEL",
            required_context_versions={},
            client_order_id=f"{self.STOP_PREFIX}CANCEL_{uuid.uuid4().hex[:14]}",
            purpose=purpose,
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        return self._submit(
            intent,
            lambda: self.client.cancel_order(
                campaign.symbol,
                order_id=order_id,
            ),
            lambda _snapshot: None,
        )

    def exit_market(
        self,
        campaign,
        *,
        reason: str,
    ) -> dict[str, Any]:
        """Cancel only the campaign protection, then market-sell actual free inventory."""
        symbol = campaign.symbol.upper()
        protective_id = str(
            campaign.tags.get("protective_order_id", "") or ""
        )
        if protective_id:
            try:
                self._execute_cancel(
                    campaign,
                    int(protective_id),
                    "CAMPAIGN_PROTECTION_CANCEL",
                )
            except Exception as exc:
                # An already-filled/cancelled stop is harmless; anything else is
                # ambiguous and must be reconciled instead of guessing.
                try:
                    current = self.client.get_order(
                        symbol,
                        order_id=int(protective_id),
                    )
                    status = str(current.get("status", "")).upper()
                    if status not in {"CANCELED", "EXPIRED", "FILLED", "REJECTED"}:
                        raise
                except Exception:
                    self.engine.mark_reconcile_required(
                        campaign,
                        f"cannot cancel campaign protection before exit: {exc}",
                    )
                    raise CampaignExecutionError(str(exc)) from exc

        open_orders = self.client.open_orders(symbol)
        unknown_sells = [
            o for o in open_orders
            if str(o.get("side", "")).upper() == "SELL"
            and not str(o.get("clientOrderId", "")).startswith(self.STOP_PREFIX)
        ]
        if unknown_sells:
            self.engine.mark_reconcile_required(
                campaign,
                "unrecognized open SELL order prevents campaign exit",
            )
            raise CampaignExecutionError(
                f"{symbol}: unrecognized open SELL order prevents safe campaign exit"
            )

        account = self.client.account()
        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise CampaignExecutionError(f"{symbol}: exchangeInfo unavailable")
        asset = str(rows[0].get("baseAsset", "")).upper()
        free_qty = next(
            (
                float(b.get("free", 0) or 0)
                for b in account.get("balances", [])
                if str(b.get("asset", "")).upper() == asset
            ),
            0.0,
        )
        qty = self._normalize_qty(symbol, free_qty)
        if qty <= 0:
            raise CampaignExecutionError(
                f"{symbol}: no free campaign inventory after protection cancel"
            )

        cid = f"{self.EXIT_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            symbol,
            "SELL",
            "MARKET",
            required_context_versions={},
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_EXIT",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        order = self._submit(
            intent,
            lambda: self.client.order_safe(
                symbol,
                "SELL",
                "MARKET",
                quantity=self.client.decimal_format(qty),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(symbol, 0),
        )
        self.db.save_order(order)
        executed = float(order.get("executedQty", 0) or 0)
        quote = float(order.get("cummulativeQuoteQty", 0) or 0)
        if executed <= 0 or quote <= 0:
            raise CampaignExecutionError(
                f"{symbol}: campaign exit returned no authoritative fill"
            )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(order.get("orderId", "") or ""),
                client_order_id=cid,
                symbol=symbol,
                side="SELL",
                order_type="MARKET",
                purpose="EXIT",
                status=str(order.get("status", "FILLED")),
                quantity=executed,
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
            )
        )
        exit_price = quote / executed
        remaining = max(0.0, float(campaign.position_qty) - executed)
        self.db.log_campaign_event(
            campaign.campaign_id,
            "EXIT_SUBMITTED",
            order_id=str(order.get("orderId", "")),
            reason=reason,
            payload={
                "quantity": executed,
                "exit_price": exit_price,
                "requested_quantity": qty,
            },
        )
        if remaining <= max(
            float(os.getenv("MIN_RECOVERY_QTY", "0.000001")),
            float(campaign.position_qty) * float(os.getenv("BALANCE_TOLERANCE_PCT", "0.005")),
        ):
            campaign.position_qty = 0.0
            campaign.open_risk_quote = 0.0
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            campaign.exit_reason = reason
            campaign.next_action = "WAIT"
            campaign.transition(CampaignState.EXIT_PENDING, reason=reason)
            campaign.transition(CampaignState.CLOSED, reason="exit fill complete")
            self.db.save_campaign(campaign)
            trade = self.db.open_trade(symbol)
            if trade is not None:
                entry = float(trade.get("entry_price") or campaign.average_entry_price or 0.0)
                entry_fee = float(trade.get("fees") or 0.0)
                pnl = quote - entry * float(trade.get("quantity") or campaign.position_qty) - entry_fee
                self.db.close_trade(
                    trade["id"],
                    datetime.now(timezone.utc).isoformat(),
                    exit_price,
                    pnl,
                    (exit_price / entry - 1.0) if entry > 0 else 0.0,
                    reason,
                    fees=entry_fee,
                )
            self.db.state_set(f"position_state:{symbol}", "FLAT")
            return {
                "campaign_id": campaign.campaign_id,
                "symbol": symbol,
                "state": "CLOSED",
                "quantity": executed,
                "exit_price": exit_price,
                "reason": reason,
            }

        campaign.position_qty = remaining
        self.db.save_campaign(campaign)
        campaign.state = CampaignState.RECONCILE_REQUIRED
        self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
        raise CampaignExecutionError(
            f"{symbol}: campaign exit partially filled; residual {remaining:.12g} requires reconciliation"
        )

    def replace_structural_stop(
        self,
        campaign,
        *,
        existing_order_id: int,
        quantity: float,
        proposed_stop: float,
    ) -> dict[str, Any]:
        if not campaign.current_stop_price <= proposed_stop:
            raise CampaignExecutionError("structural stop would loosen LONG risk")
        new_stop = self._normalize_price(campaign.symbol, proposed_stop)
        normalized_qty = self._normalize_qty(campaign.symbol, quantity)
        if normalized_qty <= 0:
            raise CampaignExecutionError(f"{campaign.symbol}: protective quantity rounds to zero")
        cid = f"{self.STOP_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "STOP_LOSS",
            required_context_versions={},
            invalidation_level=new_stop,
            quantity=self.client.decimal_format(normalized_qty),
            client_order_id=cid,
            purpose="CAMPAIGN_TRAIL",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        result = self._submit(
            intent,
            lambda: self.client.cancel_replace(
                campaign.symbol,
                existing_order_id,
                "SELL",
                "STOP_LOSS",
                quantity=self.client.decimal_format(normalized_qty),
                stop_price=self.client.decimal_format(new_stop),
                new_client_order_id=cid,
            ),
            lambda _snapshot: self._check_algo_capacity(campaign.symbol, 0),
        )

        cancel_result = str(result.get("cancelResult", "")).upper()
        new_result = str(result.get("newOrderResult", "")).upper()
        # Any non-success or transport ambiguity must be reconciled before
        # another mutation; cancelReplace is not atomic.
        if cancel_result not in {"SUCCESS", "NOT_FOUND"} or new_result not in {"SUCCESS", ""}:
            campaign.mark_reconcile_required(
                f"cancelReplace ambiguous: cancel={cancel_result} new={new_result}"
            )
            self.db.save_campaign(campaign)
            raise CampaignExecutionError(
                f"{campaign.symbol}: structural stop replacement ambiguous"
            )

        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(
                    (result.get("newOrderResponse") or {}).get("orderId", "")
                ),
                client_order_id=cid,
                symbol=campaign.symbol,
                side="SELL",
                order_type="STOP_LOSS",
                purpose="PROTECTION",
                status="NEW",
                stop_price=new_stop,
                quantity=normalized_qty,
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
            )
        )
        return result
