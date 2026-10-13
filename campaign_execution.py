"""Execution adapter for Williams conditional campaign entries and stops."""

from __future__ import annotations

from datetime import datetime, timezone
from contextlib import nullcontext
from typing import Any
import json
import os
import uuid
from decimal import Decimal

from binance_client import BinanceAPIError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingOrderRecord, SignalSpec, SignalState, SignalType
from decision_trace import DecisionTrace
from execution_barrier import ExecutionBarrier, OrderIntent
from execution_recovery import ExecutionRecoveryCoordinator
from execution_account_identity import verify_execution_account_identity


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
        self._recovery_depth = 0
        self.engine = CampaignEngine(
            db,
            portfolio_risk_limit_pct=float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01")),
            campaign_risk_limit_pct=float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005")),
            initial_risk_fraction_of_campaign=float(
                os.getenv("CAMPAIGN_INITIAL_RISK_FRACTION", "0.40")
            ),
        )
        self.recovery_coordinator = ExecutionRecoveryCoordinator(
            client,
            db,
            fill_handler=self._apply_recovered_campaign_fill,
            post_fill_handler=self._ensure_campaign_protection_after_recovery,
            exchange="BINANCE",
            account_scope=os.getenv("WILLIAMS_ACCOUNT_SCOPE") or None,
            execution_scope="spot",
        )

    def _verify_account_identity(self) -> dict[str, Any]:
        try:
            return verify_execution_account_identity(
                self.client,
                self.db,
                execution_scope="spot",
                account_scope=os.getenv("WILLIAMS_ACCOUNT_SCOPE") or None,
            )
        except Exception as exc:
            raise CampaignExecutionError(
                "Binance account identity is not verified; execution remains blocked: "
                + str(exc)
            ) from exc

    def _ensure_recovered(self) -> list[dict[str, Any]]:
        # Use the same in-process lock as ExecutionBarrier. The durable DB gate
        # remains authoritative across independent processes.
        lock = (
            self.barrier.context_cache.execution_lock
            if self.barrier is not None else nullcontext()
        )
        with lock:
            self._verify_account_identity()
            self._recovery_depth += 1
            try:
                outcomes: list[dict[str, Any]] = []
                for attempt in range(3):
                    pass_outcomes = self.recovery_coordinator.recover()
                    outcomes.extend(pass_outcomes)
                    readiness = self.recovery_coordinator.readiness()
                    if readiness.get("ready"):
                        return outcomes
                    if any(row.get("state") == "RECONCILE_REQUIRED" for row in pass_outcomes):
                        break
                    if readiness.get("reason") not in {"UNRESOLVED_INTENT", "EXECUTION_LOCKED"}:
                        break
                readiness = self.recovery_coordinator.readiness()
                if not readiness.get("ready"):
                    blocked_details = [
                        {
                            "intent_id": row.get("intent_id"),
                            "state": row.get("state"),
                            "reason": row.get("reason"),
                        }
                        for row in outcomes
                        if row.get("state") in {"RECONCILE_REQUIRED", "UNKNOWN", "AMBIGUOUS"}
                    ]
                    raise CampaignExecutionError(
                        "execution recovery/protection repair is incomplete; no new mutation allowed: "
                        + str(readiness.get("reason", "UNKNOWN"))
                        + "; recovery_details=" + repr(blocked_details[-5:])
                    )
                return outcomes
            finally:
                self._recovery_depth -= 1

    def _ensure_campaign_protection_after_recovery(
        self, intent: dict[str, Any], order: dict[str, Any], trades: list[dict[str, Any]]
    ) -> None:
        """Before clearing a recovered BUY fill, prove a live stop or create it through the barrier."""
        side = str(intent.get("side", "")).upper()
        if not trades:
            return
        campaign_id = str(intent.get("campaign_id") or "")
        if not campaign_id:
            raise CampaignExecutionError("recovered campaign fills lack a durable campaign ID")
        if side == "SELL":
            campaign = self.engine.load_campaign(campaign_id)
            if campaign is None:
                raise CampaignExecutionError("recovered SELL fill campaign is missing")
            if float(campaign.position_qty or 0) > 0:
                raise CampaignExecutionError(
                    "SELL fill leaves residual inventory; automated protection resize is not yet proven; contour remains locked"
                )
            return
        if side != "BUY":
            raise CampaignExecutionError(f"unsupported side for protection recovery: {side!r}")
        campaign = self.engine.load_campaign(campaign_id)
        if campaign is None or float(campaign.position_qty or 0) <= 0:
            raise CampaignExecutionError("recovered BUY fill did not produce a durable positive campaign position")
        if campaign.additions > 0 or "ADD_ON" in str(intent.get("purpose", "")).upper():
            # A changed tranche size needs cancel/replace proof, not a second oversized stop.
            raise CampaignExecutionError(
                "add-on recovery requires atomic protective-stop resize; entry remains blocked"
            )
        order_status = str(order.get("status", "")).upper()
        if order_status == "PARTIALLY_FILLED" and str(intent.get("order_type", "")).upper() != "CANCEL":
            if order.get("orderId") is None:
                raise CampaignExecutionError("partial order cannot be canceled without authoritative orderId")
            self._execute_cancel(
                campaign,
                int(order["orderId"]),
                "CAMPAIGN_ENTRY_CANCEL",
            )
            canceled = self.client.get_order(campaign.symbol, order_id=order["orderId"])
            canceled_status = str(canceled.get("status", "")).upper()
            if canceled_status not in {"CANCELED", "EXPIRED", "FILLED", "REJECTED"}:
                raise CampaignExecutionError(
                    f"partial entry remains non-terminal after cancel: {canceled_status}"
                )
            trade_reader = getattr(self.client, "my_trades", None)
            if not callable(trade_reader):
                raise CampaignExecutionError("cannot prove cancel/fill race without signed myTrades")
            fresh_trades = trade_reader(campaign.symbol, order_id=order["orderId"], limit=1000)
            if not isinstance(fresh_trades, list) or len(fresh_trades) >= 1000:
                raise CampaignExecutionError("post-cancel trade history is incomplete")
            exchange_qty = sum(
                (Decimal(str(x.get("qty", "0") or "0")) for x in fresh_trades),
                Decimal("0"),
            )
            reported_qty = Decimal(str(canceled.get("executedQty", "0") or "0"))
            if abs(exchange_qty - reported_qty) > Decimal("0.000000000001"):
                raise CampaignExecutionError(
                    "cancel/fill race remains ambiguous: post-cancel myTrades does not match executedQty"
                )
            initial_qty = sum(
                (Decimal(str(x.get("qty", "0") or "0")) for x in trades),
                Decimal("0"),
            )
            if exchange_qty != initial_qty:
                raise CampaignExecutionError(
                    "a fill arrived during cancellation; rerun reconciliation before protecting/unlocking"
                )
        raw_stop_price = float(
            campaign.current_stop_price
            or campaign.initial_stop_price
            or campaign.tags.get("initial_stop_price", 0.0)
            or 0.0
        )
        # Compare the exchange's tick-rounded stop with the same normalized target
        # we would actually submit, not with an unrounded structural float.
        stop_price = self._normalize_price(campaign.symbol, raw_stop_price)
        if stop_price <= 0:
            raise CampaignExecutionError("recovered campaign lacks a valid structural stop")
        position_qty = float(campaign.position_qty)
        protective_id = str(campaign.tags.get("protective_order_id", "") or "")
        if protective_id:
            try:
                existing = self.client.get_order(campaign.symbol, order_id=int(protective_id))
                status = str(existing.get("status", "")).upper()
                existing_qty = float(existing.get("origQty", 0) or 0)
                existing_stop = float(existing.get("stopPrice", 0) or 0)
                if status == "NEW":
                    if (
                        existing_qty + 1e-12 >= position_qty
                        and existing_stop > 0
                        and abs(existing_stop - stop_price) <= max(1e-8, stop_price * 1e-8)
                    ):
                        return
                    raise CampaignExecutionError(
                        "existing live protective order mismatch: "
                        f"status={status}, order_id={protective_id}, "
                        f"existing_qty={existing_qty}, position_qty={position_qty}, "
                        f"existing_stop={existing_stop}, desired_stop={stop_price}; safe resize required"
                    )
                if status not in {"CANCELED", "EXPIRED", "FILLED", "REJECTED"}:
                    raise CampaignExecutionError(
                        f"protective order status {status!r} is ambiguous"
                    )
            except CampaignExecutionError:
                raise
            except Exception as exc:
                raise CampaignExecutionError(
                    f"cannot verify existing protective order: {exc}"
                ) from exc
        protection = self.create_hard_stop(
            campaign,
            quantity=position_qty,
            stop_price=stop_price,
        )
        campaign.tags["protective_order_id"] = str(protection.get("order_id") or "")
        campaign.tags["protective_client_order_id"] = str(protection.get("client_order_id") or "")
        campaign.tags["protective_stop_price"] = float(protection.get("stop_price") or stop_price)
        self.db.save_campaign(campaign)

    def _fee_in_quote(
        self,
        symbol: str,
        commission: Decimal,
        asset: str,
        price: Decimal,
        *,
        base_asset: str,
        quote_asset: str,
    ) -> tuple[Decimal, Decimal]:
        # Network reads are deliberately forbidden inside apply_execution_fill's
        # SQLite transaction; validated asset metadata is passed in with the fill.
        if commission < 0 or price <= 0:
            raise CampaignExecutionError("invalid commission/price from exchange trade")
        base_asset = str(base_asset or "").upper()
        quote_asset = str(quote_asset or "").upper()
        if not base_asset or not quote_asset:
            raise CampaignExecutionError("base/quote asset metadata missing during fill accounting")
        asset = str(asset or "").upper()
        if commission == 0:
            return Decimal("0"), Decimal("0")
        if asset == quote_asset:
            return commission, Decimal("0")
        if asset == base_asset:
            return commission * price, commission
        raise CampaignExecutionError(
            f"commission asset {asset!r} cannot be converted to quote asset {quote_asset!r}; fill remains unresolved"
        )

    def _apply_authoritative_order_fills(
        self,
        order: dict[str, Any],
        campaign,
        *,
        purpose: str,
        signal_id: str,
        client_id: str,
    ) -> bool:
        """Apply the order's actual Binance trade IDs; executedQty is only a cross-check."""
        order_id = order.get("orderId")
        if order_id is None:
            raise CampaignExecutionError("filled order has no exchange orderId")
        trade_reader = getattr(self.client, "my_trades", None)
        if not callable(trade_reader):
            raise CampaignExecutionError("signed Binance myTrades is required for fill accounting")
        rows = trade_reader(
            str(order["symbol"]).upper(), order_id=order_id, limit=1000
        )
        if not isinstance(rows, list):
            raise CampaignExecutionError("Binance myTrades did not return a JSON array")
        if len(rows) >= 1000:
            raise CampaignExecutionError("myTrades page limit reached; fill history is incomplete")
        executed = Decimal(str(order.get("executedQty", "0") or "0"))
        info = self.client.exchange_info(str(order["symbol"]).upper())
        symbol_row = self._symbols_rows(info).get(str(order["symbol"]).upper())
        if symbol_row is None:
            raise CampaignExecutionError("exchangeInfo lacks the fill symbol; accounting blocked")
        base_asset = str(symbol_row.get("baseAsset", "")).upper()
        quote_asset = str(symbol_row.get("quoteAsset", "")).upper()
        if not base_asset or not quote_asset:
            raise CampaignExecutionError("exchangeInfo lacks base/quote assets; accounting blocked")
        total_qty = Decimal("0")
        normalized = []
        seen = set()
        for trade in rows:
            if not isinstance(trade, dict):
                raise CampaignExecutionError("malformed Binance myTrades row")
            trade_id = trade.get("id", trade.get("tradeId"))
            if trade_id is None:
                raise CampaignExecutionError("Binance fill lacks a stable trade ID")
            if str(trade.get("orderId")) != str(order_id):
                raise CampaignExecutionError("Binance fill orderId mismatch")
            if str(trade.get("symbol", order["symbol"])).upper() != str(order["symbol"]).upper():
                raise CampaignExecutionError("Binance fill symbol mismatch")
            if "isBuyer" in trade and bool(trade["isBuyer"]) != (str(order.get("side", "")).upper() == "BUY"):
                raise CampaignExecutionError("Binance fill side mismatch")
            if str(trade_id) in seen:
                raise CampaignExecutionError("duplicate trade ID in one Binance response")
            seen.add(str(trade_id))
            qty = Decimal(str(trade.get("qty", "0") or "0"))
            price = Decimal(str(trade.get("price", "0") or "0"))
            if qty <= 0 or price <= 0:
                raise CampaignExecutionError("Binance fill quantity/price must be positive")
            total_qty += qty
            normalized.append((trade_id, qty, price, trade))
        if abs(total_qty - executed) > Decimal("0.000000000001"):
            raise CampaignExecutionError(
                f"Binance myTrades quantity {total_qty} does not match order executedQty {executed}"
            )
        if executed > 0 and not normalized:
            raise CampaignExecutionError("order reports execution but no authoritative trade IDs were returned")

        intent_row = self.db.conn.execute(
            """SELECT intent_id,account_scope,execution_scope,exchange,campaign_id,purpose
               FROM execution_intents WHERE client_order_id=?
               ORDER BY created_at DESC LIMIT 1""",
            (str(client_id),),
        ).fetchone()
        intent = dict(intent_row) if intent_row else {}
        intent.setdefault("intent_id", "")
        intent.setdefault("account_scope", self.db._default_account_scope("spot"))
        intent.setdefault("execution_scope", "spot")
        intent.setdefault("exchange", "BINANCE")
        intent.setdefault("campaign_id", campaign.campaign_id)
        intent.setdefault("purpose", purpose)
        intent["signal_id"] = signal_id
        for trade_id, qty, price, raw in normalized:
            fill = {
                "exchange": str(intent.get("exchange") or "BINANCE").upper(),
                "account_scope": str(intent.get("account_scope") or self.db._default_account_scope("spot")),
                "symbol": str(order["symbol"]).upper(),
                "trade_id": str(trade_id),
                "order_id": str(order_id),
                "intent_id": str(intent.get("intent_id") or ""),
                "campaign_id": str(campaign.campaign_id),
                "purpose": str(purpose or intent.get("purpose") or ""),
                "side": str(order.get("side", "")).upper(),
                "client_order_id": str(client_id),
                "quantity": str(qty),
                "price": str(price),
                "commission": str(raw.get("commission", "0") or "0"),
                "commission_asset": str(raw.get("commissionAsset", "") or ""),
                "raw": raw,
                "base_asset": base_asset,
                "quote_asset": quote_asset,
            }
            self.db.apply_execution_fill(
                fill,
                lambda _db, data, i=intent, o=order, f=fill: self._apply_recovered_campaign_fill(i, o, f),
            )
        return True

    def _apply_recovered_campaign_sell_fill(
        self, intent: dict[str, Any], order: dict[str, Any], fill: dict[str, Any]
    ) -> None:
        """Apply an actual SELL trade once; a residual position becomes reconcile-required."""
        campaign_id = str(fill.get("campaign_id") or intent.get("campaign_id") or "")
        if not campaign_id:
            raise CampaignExecutionError("sell fill has no durable campaign_id")
        campaign = self.engine.load_campaign(campaign_id)
        if campaign is None:
            raise CampaignExecutionError("sell fill campaign is missing from durable storage")
        if campaign.state == CampaignState.CLOSED:
            raise CampaignExecutionError("late sell fill conflicts with a closed campaign; manual reconciliation required")

        symbol = str(fill["symbol"]).upper()
        order_id = str(fill["order_id"])
        qty = Decimal(str(fill["quantity"]))
        price = Decimal(str(fill["price"]))
        commission = Decimal(str(fill.get("commission") or "0"))
        commission_asset = str(fill.get("commission_asset") or "")
        base_asset = str(fill.get("base_asset") or "").upper()
        quote_asset = str(fill.get("quote_asset") or "").upper()
        fee_quote, base_fee_qty = self._fee_in_quote(
            symbol, commission, commission_asset, price,
            base_asset=base_asset, quote_asset=quote_asset,
        )
        if qty <= 0 or price <= 0:
            raise CampaignExecutionError("sell fill quantity and price must be positive")
        sold_inventory = qty + base_fee_qty
        position_before = Decimal(str(campaign.position_qty or 0))
        entry_price = Decimal(str(campaign.average_entry_price or 0))
        if position_before <= 0 or entry_price <= 0 or sold_inventory > position_before + Decimal("0.000000000001"):
            raise CampaignExecutionError("sell fill exceeds the durable campaign position")
        trade_id = int(campaign.tags.get("trade_id") or 0)
        if trade_id:
            trade_row = self.db.conn.execute(
                "SELECT * FROM trades WHERE id=? AND exit_time IS NULL", (trade_id,)
            ).fetchone()
            trade = dict(trade_row) if trade_row else None
        else:
            trade = self.db.open_trade(symbol)
            if trade:
                campaign.tags["trade_id"] = int(trade["id"])
        if trade is None:
            raise CampaignExecutionError("sell fill has no matching open financial trade row")

        fee_remaining = Decimal(str(
            campaign.tags.get(
                "entry_fee_unallocated_quote",
                campaign.tags.get("entry_fee_quote", 0) or 0,
            ) or 0
        ))
        if fee_remaining < 0:
            raise CampaignExecutionError("negative unallocated entry fee state")
        entry_fee_allocated = min(
            fee_remaining,
            fee_remaining * sold_inventory / position_before,
        )
        fee_remaining_after = fee_remaining - entry_fee_allocated
        pnl_delta = (
            qty * price
            - sold_inventory * entry_price
            - fee_quote
            - entry_fee_allocated
        )
        remaining = max(Decimal("0"), position_before - sold_inventory)
        tolerance = max(
            Decimal(str(os.getenv("MIN_RECOVERY_QTY", "0.000001"))),
            position_before * Decimal(str(os.getenv("BALANCE_TOLERANCE_PCT", "0.005"))),
        )
        quote_qty = qty * price
        cumulative_exit_qty = Decimal(str(campaign.tags.get("exit_filled_qty", 0) or 0)) + qty
        cumulative_exit_quote = Decimal(str(campaign.tags.get("exit_filled_quote", 0) or 0)) + quote_qty
        cumulative_exit_fees = Decimal(str(campaign.tags.get("exit_fee_quote", 0) or 0)) + fee_quote
        cumulative_pnl = Decimal(str(trade.get("pnl") or 0)) + pnl_delta
        cumulative_fees = Decimal(str(trade.get("fees") or 0)) + fee_quote

        self.db.conn.execute(
            """INSERT INTO campaign_fills(
                campaign_id,order_id,symbol,side,quantity,price,quote_quantity,
                fee_quote,commission_base,commission_asset,raw_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (
                campaign_id, order_id, symbol, "SELL", str(qty), str(price),
                str(quote_qty), str(fee_quote), str(base_fee_qty),
                commission_asset, json.dumps(fill.get("raw", fill), default=str, sort_keys=True),
            ),
        )
        campaign.position_qty = float(remaining)
        campaign.realized_pnl_quote = float(
            Decimal(str(campaign.realized_pnl_quote or 0)) + pnl_delta
        )
        campaign.tags["entry_fee_unallocated_quote"] = float(fee_remaining_after)
        campaign.tags["exit_filled_qty"] = str(cumulative_exit_qty)
        campaign.tags["exit_filled_quote"] = str(cumulative_exit_quote)
        campaign.tags["exit_fee_quote"] = str(cumulative_exit_fees)
        campaign.tags["last_exit_order_id"] = order_id
        campaign.tags["last_exit_trade_id"] = str(fill["trade_id"])
        campaign.open_risk_quote = (
            float(Decimal(str(campaign.open_risk_quote or 0)) * remaining / position_before)
            if remaining > 0 else 0.0
        )
        campaign.next_action = "WAIT" if remaining <= tolerance else "RECONCILE_REQUIRED"
        if remaining <= tolerance:
            campaign.position_qty = 0.0
            campaign.open_risk_quote = 0.0
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            campaign.exit_reason = campaign.exit_reason or (
                "PROTECTIVE_STOP" if "PROTECTION" in str(intent.get("purpose", "")).upper()
                else "CAMPAIGN_EXIT"
            )
            if campaign.state == CampaignState.CLOSED:
                raise CampaignExecutionError("duplicate terminal campaign fill cannot mutate a closed campaign")
            if campaign.state in {
                CampaignState.OPEN_INITIAL, CampaignState.TREND_ACTIVE,
                CampaignState.TRAILING, CampaignState.EXHAUSTION_WATCH,
            }:
                campaign.transition(CampaignState.EXIT_SIGNALLED, reason="exchange-confirmed sell fill")
            if campaign.state != CampaignState.EXIT_PENDING:
                if campaign.state == CampaignState.RECONCILE_REQUIRED:
                    campaign.transition(CampaignState.EXIT_PENDING, reason="exchange position is flat")
                elif campaign.state == CampaignState.EXIT_SIGNALLED:
                    campaign.transition(CampaignState.EXIT_PENDING, reason="exchange position is flat")
                else:
                    raise CampaignExecutionError(
                        f"cannot close campaign from {campaign.state.value} after sell fill"
                    )
            campaign.transition(CampaignState.CLOSED, reason="exchange-confirmed flat position")
            campaign.next_action = "WAIT"
            self.db.state_set(f"position_state:{symbol}", "FLAT")
            avg_exit = cumulative_exit_quote / cumulative_exit_qty if cumulative_exit_qty > 0 else price
            self.db.close_trade(
                trade["id"],
                datetime.now(timezone.utc).isoformat(),
                float(avg_exit),
                float(cumulative_pnl),
                (float(avg_exit) / float(trade.get("entry_price") or entry_price) - 1.0)
                if float(trade.get("entry_price") or entry_price) > 0 else 0.0,
                campaign.exit_reason,
                fees=float(cumulative_fees),
            )
        else:
            campaign.state = CampaignState.RECONCILE_REQUIRED
            campaign.health = "RED"
            campaign.reconciliation_state = "RECONCILE_REQUIRED"
            self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
            self.db.mark_execution_protection_pending(
                str(intent.get("intent_id") or ""),
                "SELL fill left residual inventory; protective order coverage must be reconciled",
            )
            self.db.log_campaign_event(
                campaign_id,
                "PARTIAL_SELL_FILL_RECONCILE_REQUIRED",
                order_id=order_id,
                reason="partial/late sell fill changes residual position and requires protection reconciliation",
                payload={
                    "trade_id": str(fill["trade_id"]),
                    "filled_qty": str(qty),
                    "residual_qty": str(remaining),
                    "fee_quote": str(fee_quote),
                    "realized_pnl_delta": str(pnl_delta),
                },
            )
        self.db.conn.execute(
            """UPDATE trades SET pnl=?,fees=?,updated_at=? WHERE id=? AND exit_time IS NULL""",
            (
                float(cumulative_pnl),
                float(cumulative_fees),
                datetime.now(timezone.utc).isoformat(),
                int(trade["id"]),
            ),
        ) if remaining > tolerance else None
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign_id,
            "EXIT_FILL_APPLIED",
            order_id=order_id,
            reason=campaign.exit_reason or "exchange-confirmed sell fill",
            payload={
                "trade_id": str(fill["trade_id"]),
                "quantity": str(qty),
                "price": str(price),
                "fee_quote": str(fee_quote),
                "realized_pnl_delta": str(pnl_delta),
                "remaining_qty": str(remaining),
            },
        )

    def _apply_recovered_campaign_fill(
        self, intent: dict[str, Any], order: dict[str, Any], fill: dict[str, Any]
    ) -> None:
        """Apply one exchange trade to campaign position, fee, PnL and journal in this DB transaction."""
        side = str(fill.get("side", intent.get("side", ""))).upper()
        if side == "SELL":
            self._apply_recovered_campaign_sell_fill(intent, order, fill)
            return
        if side != "BUY":
            raise CampaignExecutionError(f"unsupported campaign fill side {side!r}")
        campaign_id = str(fill.get("campaign_id") or intent.get("campaign_id") or "")
        if not campaign_id:
            raise CampaignExecutionError("fill has no durable campaign_id")
        campaign = self.engine.load_campaign(campaign_id)
        if campaign is None:
            raise CampaignExecutionError("fill's campaign is missing from durable storage")
        symbol = str(fill["symbol"]).upper()
        order_id = str(fill["order_id"])
        entry_fee_before = Decimal(str(campaign.tags.get("entry_fee_quote", 0) or 0))
        unallocated_fee_before = Decimal(str(
            campaign.tags.get("entry_fee_unallocated_quote", entry_fee_before) or 0
        ))
        qty = Decimal(str(fill["quantity"]))
        price = Decimal(str(fill["price"]))
        commission = Decimal(str(fill.get("commission") or "0"))
        commission_asset = str(fill.get("commission_asset") or "")
        base_asset = str(fill.get("base_asset") or "").upper()
        quote_asset = str(fill.get("quote_asset") or "").upper()
        fee_quote, base_fee_qty = self._fee_in_quote(
            symbol, commission, commission_asset, price,
            base_asset=base_asset, quote_asset=quote_asset,
        )
        net_qty = qty - base_fee_qty
        if qty <= 0 or price <= 0 or net_qty <= 0:
            raise CampaignExecutionError("fill quantity/price is invalid after commission")
        purpose = str(fill.get("purpose") or intent.get("purpose") or "").upper()
        initial_stop = float(
            campaign.initial_stop_price
            or campaign.tags.get("initial_stop_price", 0.0)
            or 0.0
        )
        if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
            if campaign.state == CampaignState.ADD_ON_PENDING:
                campaign.transition(
                    CampaignState.POSITION_EXPANDING,
                    reason="exchange-confirmed add-on fill",
                )
            if campaign.state == CampaignState.POSITION_EXPANDING:
                self.engine.record_add_on_fill(
                    campaign,
                    quantity=float(net_qty),
                    average_entry_price=float(price),
                    fill_order_id=order_id,
                    risk_quote=float(campaign.pending_risk_quote),
                    fee_quote=float(fee_quote),
                )
            elif campaign.state in {
                CampaignState.TREND_ACTIVE, CampaignState.TRAILING,
                CampaignState.EXHAUSTION_WATCH, CampaignState.OPEN_INITIAL,
            }:
                old_qty = Decimal(str(campaign.position_qty))
                old_entry = Decimal(str(campaign.average_entry_price))
                new_qty = old_qty + net_qty
                campaign.average_entry_price = float(
                    (old_qty * old_entry + net_qty * price) / new_qty
                )
                campaign.position_qty = float(new_qty)
                campaign.tags["add_on_fee_quote"] = float(
                    Decimal(str(campaign.tags.get("add_on_fee_quote", 0) or 0)) + fee_quote
                )
                campaign.next_action = "MONITOR_CAMPAIGN"
                self.db.save_campaign(campaign)
            else:
                raise CampaignExecutionError(
                    f"add-on fill conflicts with campaign state {campaign.state.value}"
                )
        else:
            if campaign.state == CampaignState.SIGNAL_DETECTED:
                campaign.transition(
                    CampaignState.ENTRY_PENDING,
                    reason="recovery found exchange-accepted entry",
                )
            if campaign.state == CampaignState.ENTRY_PENDING:
                self.engine.mark_triggered(
                    campaign,
                    str(intent.get("signal_id") or campaign.current_signal_id),
                    order_id,
                )
            if campaign.state == CampaignState.ENTRY_TRIGGERED:
                if initial_stop <= 0:
                    raise CampaignExecutionError("initial structural stop missing during fill recovery")
                self.engine.record_initial_fill(
                    campaign,
                    quantity=float(net_qty),
                    average_entry_price=float(price),
                    initial_stop_price=initial_stop,
                    fill_order_id=order_id,
                    risk_quote=float(campaign.pending_risk_quote),
                    fee_quote=float(fee_quote),
                )
            elif campaign.state in {
                CampaignState.OPEN_INITIAL, CampaignState.TREND_ACTIVE,
                CampaignState.TRAILING, CampaignState.EXHAUSTION_WATCH,
            }:
                old_qty = Decimal(str(campaign.position_qty))
                old_entry = Decimal(str(campaign.average_entry_price))
                new_qty = old_qty + net_qty
                campaign.average_entry_price = float(
                    (old_qty * old_entry + net_qty * price) / new_qty
                )
                campaign.position_qty = float(new_qty)
                self.db.save_campaign(campaign)
            else:
                raise CampaignExecutionError(
                    f"entry fill conflicts with campaign state {campaign.state.value}"
                )

        quote_qty = qty * price
        self.db.conn.execute(
            """INSERT INTO campaign_fills(
                campaign_id,order_id,symbol,side,quantity,price,quote_quantity,
                fee_quote,commission_base,commission_asset,raw_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (
                campaign_id, order_id, symbol, "BUY", str(qty), str(price),
                str(quote_qty), str(fee_quote), str(base_fee_qty),
                commission_asset, json.dumps(fill.get("raw", fill), default=str, sort_keys=True),
            ),
        )
        existing_trade_id = int(campaign.tags.get("trade_id") or 0)
        if existing_trade_id:
            trade_row = self.db.conn.execute(
                "SELECT * FROM trades WHERE id=? AND exit_time IS NULL",
                (existing_trade_id,),
            ).fetchone()
            trade = dict(trade_row) if trade_row else None
        else:
            trade = self.db.open_trade(symbol)
        if trade is None:
            order_time_ms = int(
                fill.get("raw", {}).get("time", order.get("transactTime", order.get("time", 0))) or 0
            )
            entry_time = (
                datetime.fromtimestamp(order_time_ms / 1000, tz=timezone.utc).isoformat()
                if order_time_ms > 0 else datetime.now(timezone.utc).isoformat()
            )
            new_trade_id = self.db.save_trade(
                entry_time=entry_time,
                symbol=symbol,
                side="LONG",
                entry_price=float(price),
                quantity=float(net_qty),
                entry_order_id=order_id,
                entry_client_order_id=str(fill.get("client_order_id") or order.get("clientOrderId") or ""),
                stop_price=initial_stop or None,
                take_profit_price=None,
                risk_pct=float(campaign.tags.get("initial_risk_pct", 0.0) or 0.0) * 100.0,
                fees=float(fee_quote),
                pnl=0.0,
            )
            campaign.tags["trade_id"] = int(new_trade_id)
        else:
            campaign.tags["trade_id"] = int(trade["id"])
            old_trade_qty = Decimal(str(trade.get("quantity") or "0"))
            old_trade_price = Decimal(str(trade.get("entry_price") or "0"))
            total_trade_qty = old_trade_qty + net_qty
            total_trade_price = (
                (old_trade_qty * old_trade_price + net_qty * price) / total_trade_qty
                if total_trade_qty > 0 else price
            )
            self.db.conn.execute(
                """UPDATE trades SET quantity=?,entry_price=?,fees=COALESCE(fees,0)+?,
                   updated_at=? WHERE id=? AND exit_time IS NULL""",
                (
                    float(total_trade_qty), float(total_trade_price), float(fee_quote),
                    datetime.now(timezone.utc).isoformat(), int(trade["id"]),
                ),
            )
        campaign.tags["entry_fee_quote"] = float(entry_fee_before + fee_quote)
        campaign.tags["entry_fee_unallocated_quote"] = float(unallocated_fee_before + fee_quote)
        self.db.save_campaign(campaign)
        # Keep the contour locked until post-commit exchange verification proves
        # that a hard stop covers this recovered BUY position.
        self.db.mark_execution_protection_pending(
            str(intent.get("intent_id") or ""),
            "exchange-confirmed BUY fill requires verified campaign protection",
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
        if step <= 0:
            return float(value)
        import math
        return math.floor(float(value) / step + 1e-12) * step

    @staticmethod
    def _ceil(value: float, step: float) -> float:
        """Round a conditional BUY trigger upward to the exchange tick grid."""
        if step <= 0:
            return float(value)
        from decimal import ROUND_CEILING
        value_d = Decimal(str(value))
        step_d = Decimal(str(step))
        return float(
            (value_d / step_d).to_integral_value(rounding=ROUND_CEILING) * step_d
        )

    def _normalize_qty(self, symbol: str, quantity: float) -> float:
        filters = self._rules(symbol)
        lot = filters.get("LOT_SIZE") or filters.get("MARKET_LOT_SIZE") or {}
        step = float(lot.get("stepSize", "0") or 0)
        minimum = float(lot.get("minQty", "0") or 0)
        maximum = float(lot.get("maxQty", "inf") or "inf")
        qty = self._floor(float(quantity), step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty < minimum:
            raise CampaignExecutionError(
                f"{symbol}: quantity {qty:.12g} below LOT_SIZE {minimum:.12g}"
            )
        return qty

    def _normalized_entry_qty(self, symbol: str, notional: float, trigger: float) -> float:
        filters = self._rules(symbol)
        lot = filters.get("LOT_SIZE") or {}
        step = float(lot.get("stepSize", "0") or 0)
        minimum = float(lot.get("minQty", "0") or 0)
        maximum = float(lot.get("maxQty", "inf") or "inf")
        qty = self._floor(notional / max(trigger, 1e-12), step)
        if qty > maximum:
            qty = self._floor(maximum, step)
        if qty < minimum:
            raise CampaignExecutionError(
                f"{symbol}: campaign entry quantity {qty:.12g} below LOT_SIZE {minimum:.12g}"
            )
        nf = filters.get("NOTIONAL") or filters.get("MIN_NOTIONAL") or {}
        min_notional = float(nf.get("minNotional", "0") or 0)
        if qty * trigger < min_notional:
            raise CampaignExecutionError(
                f"{symbol}: campaign entry notional {qty * trigger:.8f} below minimum {min_notional:.8f}"
            )
        return qty

    def _normalize_price(self, symbol: str, price: float) -> float:
        filters = self._rules(symbol)
        pf = filters.get("PRICE_FILTER") or {}
        tick = float(pf.get("tickSize", "0") or 0)
        if tick <= 0:
            raise CampaignExecutionError(f"{symbol}: PRICE_FILTER.tickSize unavailable")
        return self._floor(price, tick)

    def _normalize_trigger_price(self, symbol: str, price: float) -> float:
        """Normalize breakout triggers upward; never silently lower the trigger."""
        filters = self._rules(symbol)
        pf = filters.get("PRICE_FILTER") or {}
        tick = float(pf.get("tickSize", "0") or 0)
        if tick <= 0:
            raise CampaignExecutionError(
                f"{symbol}: PRICE_FILTER.tickSize unavailable for trigger"
            )
        return self._ceil(price, tick)

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
        if price <= 0:
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
        self._verify_account_identity()
        result = self.barrier.execute(
            intent,
            submit,
            pre_submit_checks=pre_submit_checks,
        )
        if not result.accepted:
            raise CampaignExecutionError(
                f"ExecutionBarrier blocked {intent.purpose}: {result.reason}"
            )
        response = result.response
        # Avoid recursive recovery when a coordinator is repairing protection;
        # only the explicitly admitted CAMPAIGN_PROTECTION mutation may nest.
        if self._recovery_depth > 0:
            if str(intent.purpose).upper() not in {"CAMPAIGN_PROTECTION", "CAMPAIGN_ENTRY_CANCEL"}:
                raise CampaignExecutionError(
                    "nested exchange mutation is forbidden during recovery"
                )
        else:
            self._recovery_depth += 1
            try:
                self.recovery_coordinator.recover()
            finally:
                self._recovery_depth -= 1
            readiness = self.recovery_coordinator.readiness()
            if not readiness.get("ready"):
                raise CampaignExecutionError(
                    "exchange accepted an operation but durable recovery is incomplete; "
                    "execution contour remains blocked"
                )
        if intent.order_type == "CANCEL" and self._recovery_depth == 0:
            row = self.db.conn.execute(
                "SELECT status FROM execution_intents WHERE intent_id=?",
                (intent.intent_id,),
            ).fetchone()
            reconciled_status = str(row["status"] if row else "").upper()
            if reconciled_status not in {"CANCELED", "EXPIRED", "FILLED", "REJECTED"}:
                raise CampaignExecutionError(
                    f"cancel target remains {reconciled_status or 'UNKNOWN'}; refusing subsequent mutation"
                )
        return response

    @staticmethod
    def _validate_signal_freshness(signal: SignalSpec) -> None:
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        if int(signal.signal_bar_time_ms or 0) <= 0:
            raise CampaignExecutionError("signal has no valid source-candle timestamp")
        if int(signal.expires_at_ms or 0) <= 0:
            raise CampaignExecutionError("signal has no explicit expiry; refusing stale/ambiguous entry")
        if now_ms >= int(signal.expires_at_ms):
            raise CampaignExecutionError("signal expired before exchange admission")

    def arm_initial_entry(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
        capital_fraction: float = 0.25,
    ) -> dict[str, Any]:
        # Recovery admission precedes every new entry-side decision/mutation.
        self._ensure_recovered()
        if signal.side != "BUY":
            raise CampaignExecutionError("Current Spot campaign executor only arms LONG entries")

        self._validate_signal_freshness(signal)

        reserved = self.engine.portfolio_reserved_risk_quote()
        capacity = max(0.0, float(equity_quote) * self.engine.portfolio_risk_limit_pct)
        remaining_risk = max(0.0, capacity - reserved)
        requested_risk = min(
            float(candidate_risk_pct),
            self.engine.initial_risk_pct(),
            remaining_risk / max(float(equity_quote), 1e-12),
        )
        if requested_risk <= 0:
            raise CampaignExecutionError("No portfolio risk capacity for campaign entry")

        current = self._current_price(signal.symbol)
        trigger = self._normalize_trigger_price(signal.symbol, signal.trigger_price)
        stop = self._normalize_price(
            signal.symbol,
            float(signal.protective_reference) - max(
                float(self._rules(signal.symbol).get("PRICE_FILTER", {}).get("tickSize", "0") or 0),
                1e-12,
            ),
        )

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
        effective_loss_fraction = (
            stop_fraction
            + 2.0 * float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001"))
            + float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        )
        risk_quote = float(equity_quote) * requested_risk
        notional = min(
            float(equity_quote) * capital_fraction,
            risk_quote / max(effective_loss_fraction, 1e-12),
        )
        qty = self._normalized_entry_qty(signal.symbol, notional, trigger)
        client_id = f"{self.ENTRY_PREFIX}{uuid.uuid4().hex[:20]}"

        claimed = self.db.try_claim_state(
            f"entry_client_order_id:{signal.symbol}",
            client_id,
        )
        if not claimed:
            raise CampaignExecutionError(
                f"{signal.symbol}: another conditional entry is already reserved"
            )

        campaign = self.engine.create_campaign(
            signal,
            initial_risk_pct=requested_risk,
        )
        campaign.tags["signal_role"] = signal.role.value
        campaign.tags["decision_trace"] = DecisionTrace.from_signal(signal).to_dict()
        campaign.tags["last_signal_time_ms"] = int(signal.event_time_ms)
        campaign.tags["pending_signal_expires_at_ms"] = int(signal.expires_at_ms or 0)
        campaign.tags["initial_stop_price"] = stop
        campaign.initial_stop_price = stop
        campaign.current_stop_price = stop
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = qty * trigger
        self.db.save_campaign(campaign)
        campaign.tags["pending_order_client_id"] = client_id
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_trigger"] = trigger
        # IMPORTANT: persist ENTRY_PENDING before touching Binance. A fast
        # conditional fill can arrive on the user stream immediately.
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

    def reconcile_pending_entries(self) -> list[dict[str, Any]]:
        """Adopt conditional BUYs only after unresolved execution intents are reconciled."""
        try:
            self._ensure_recovered()
        except CampaignExecutionError as exc:
            return [{"state": "RECONCILE_REQUIRED", "reason": str(exc)}]
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
                durable_intent = self.db.conn.execute(
                    "SELECT intent_id,status FROM execution_intents WHERE client_order_id=?",
                    (client_id,),
                ).fetchone()
                if durable_intent is not None:
                    # Current-format orders are owned by ExecutionRecoveryCoordinator.
                    # Do not reapply aggregate executedQty or place another hard stop
                    # from this legacy adoption loop.
                    order = self.client.get_order(
                        symbol,
                        orig_client_order_id=client_id,
                    )
                    self.db.save_order(order)
                    campaign = self._find_campaign_by_pending_client_id(client_id)
                    if campaign is None:
                        self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                        results.append({
                            "symbol": symbol,
                            "state": "RECONCILE_REQUIRED",
                            "reason": "durable execution intent has no persisted campaign",
                        })
                        continue
                    live_status = str(order.get("status", "")).upper()
                    executed_now = Decimal(str(order.get("executedQty", "0") or "0"))
                    if live_status in {"NEW", "PENDING_NEW"} and executed_now <= 0:
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": "ENTRY_PENDING",
                            "order_status": live_status,
                        })
                        continue
                    if float(campaign.position_qty or 0) <= 0:
                        if live_status in {"CANCELED", "EXPIRED", "REJECTED"} and executed_now <= 0:
                            self.db.state_delete(f"entry_client_order_id:{symbol}")
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": "CLOSED",
                                "reason": f"unfilled entry is {live_status}",
                            })
                            continue
                        raise CampaignExecutionError(
                            "exchange reports a triggered/fill state but campaign position is not reconciled"
                        )
                    protective_id = str(campaign.tags.get("protective_order_id", "") or "")
                    if not protective_id:
                        raise CampaignExecutionError("durable campaign has no linked protective order")
                    protective = self.client.get_order(symbol, order_id=int(protective_id))
                    protective_status = str(protective.get("status", "")).upper()
                    protective_qty = Decimal(str(protective.get("origQty", "0") or "0"))
                    campaign_qty = Decimal(str(campaign.position_qty))
                    raw_stop_price = float(
                        campaign.current_stop_price or campaign.initial_stop_price or 0
                    )
                    stop_price = Decimal(str(self._normalize_price(symbol, raw_stop_price)))
                    exchange_stop = Decimal(str(protective.get("stopPrice", "0") or "0"))
                    if (
                        protective_status != "NEW"
                        or protective_qty + Decimal("0.000000000001") < campaign_qty
                        or stop_price <= 0
                        or abs(exchange_stop - stop_price) > max(Decimal("0.00000001"), stop_price * Decimal("0.00000001"))
                    ):
                        raise CampaignExecutionError(
                            "live protective order does not verifiably cover the persisted campaign quantity/stop"
                        )
                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    campaign.health = "GREEN"
                    campaign.reconciliation_state = "CLEAN"
                    campaign.next_action = "MONITOR_CAMPAIGN"
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "OPEN",
                        "execution_intent_id": str(durable_intent["intent_id"]),
                        "protective_order_id": protective_id,
                    })
                    continue

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

                executed = float(order.get("executedQty", 0) or 0)
                quote = float(order.get("cummulativeQuoteQty", 0) or 0)
                if quote <= 0 and executed > 0 and hasattr(self.client, "my_trades"):
                    try:
                        fills = self.client.my_trades(
                            symbol,
                            order_id=order.get("orderId"),
                            limit=1000,
                        ) or []
                        quote = sum(
                            float(x.get("price", 0) or 0) *
                            float(x.get("qty", x.get("executedQty", 0)) or 0)
                            for x in fills
                        )
                    except Exception:
                        pass

                if status in {"NEW", "PENDING_NEW"} and executed <= 0:
                    # Still waiting for the conditional trigger.
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "ENTRY_PENDING",
                        "order_status": status,
                    })
                    continue

                fills_applied = False
                if status == "PARTIALLY_FILLED" and order.get("orderId") is not None:
                    try:
                        # Cancellation receives its own durable intent with target
                        # order identity; never call DELETE directly from recovery.
                        self._execute_cancel(
                            campaign,
                            int(order.get("orderId")),
                            "CAMPAIGN_ENTRY_CANCEL",
                        )
                        # Cancel may race a final fill. Always reread the authoritative
                        # order and its individual trades after the cancel response.
                        order = self.client.get_order(symbol, order_id=order.get("orderId"))
                        self.db.save_order(order)
                        status = str(order.get("status", "")).upper()
                        executed = float(order.get("executedQty", 0) or 0)
                        quote = float(order.get("cummulativeQuoteQty", 0) or 0)
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
                    fills_applied = self._apply_authoritative_order_fills(
                        order,
                        campaign,
                        purpose=purpose,
                        signal_id=signal_id,
                        client_id=client_id,
                    )
                    campaign = self.engine.load_campaign(campaign.campaign_id)
                    if campaign is None:
                        raise CampaignExecutionError("campaign disappeared after atomic fill application")

                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        if campaign.state == CampaignState.ADD_ON_PENDING:
                            campaign.transition(
                                CampaignState.POSITION_EXPANDING,
                                reason="conditional add-on triggered",
                            )

                        old_qty = float(campaign.position_qty)
                        total_qty = old_qty if fills_applied else old_qty + executed
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
                            self.replace_structural_stop(
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
                                "order_id", ""
                            )

                        if not fills_applied:
                            self.engine.record_add_on_fill(
                                campaign,
                                quantity=executed,
                                average_entry_price=avg,
                                fill_order_id=str(order.get("orderId", "")),
                                risk_quote=float(campaign.pending_risk_quote),
                                fee_quote=0.0,
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
                                "risk_pct=?, updated_at=CURRENT_TIMESTAMP "
                                "WHERE id=? AND exit_time IS NULL",
                                (
                                    campaign.average_entry_price,
                                    campaign.current_stop_price,
                                    float(
                                        campaign.open_risk_quote /
                                        max(self._current_equity_quote(), 1e-12)
                                    ) * 100.0,
                                    int(trade["id"]),
                                ),
                            )
                            self.db.conn.commit()

                        self.db.state_delete(f"entry_client_order_id:{symbol}")
                        self.db.state_set(f"position_state:{symbol}", "OPEN")
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

                    if not fills_applied:
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
                    elif campaign.position_qty <= 0 or campaign.state not in {
                        CampaignState.OPEN_INITIAL, CampaignState.TREND_ACTIVE,
                        CampaignState.TRAILING, CampaignState.EXHAUSTION_WATCH,
                    }:
                        raise CampaignExecutionError(
                            f"{symbol}: atomic fill effects conflict with campaign state {campaign.state.value}"
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

                    if not fills_applied:
                        self.engine.record_initial_fill(
                            campaign,
                            quantity=executed,
                            average_entry_price=avg,
                            initial_stop_price=stop,
                            fill_order_id=str(order.get("orderId", "")),
                            risk_quote=float(campaign.pending_risk_quote),
                            fee_quote=0.0,
                        )
                    else:
                        # Fill, position and fee journal were committed atomically
                        # by _apply_authoritative_order_fills before external protection.
                        campaign.tags["protective_order_id"] = str(
                            campaign.tags.get("protective_order_id", "") or ""
                        )
                        self.db.save_campaign(campaign)
                    self.db.set_campaign_signal_state(
                        signal_id,
                        SignalState.FILLED.value,
                    )

                    if self.db.open_trade(symbol) is None and not fills_applied:
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
                            fees=0.0,
                        )

                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self.db.state_set(f"position_state:{symbol}", "OPEN")
                    campaign.tags.pop("pending_order_id", None)
                    self.db.save_campaign(campaign)
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "OPEN",
                        "filled_quantity": executed,
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

    def _validate_add_on_submission(self, symbol: str, qty: float, trigger: float) -> None:
        if self._current_price(symbol) >= trigger:
            raise CampaignExecutionError(
                f"{symbol}: add-on trigger crossed before submission"
            )
        self._check_buy_position_capacity(symbol, qty)
        self._check_algo_capacity(symbol, 1)

    def arm_add_on(
        self,
        signal: SignalSpec,
        *,
        equity_quote: float,
        candidate_risk_pct: float,
    ) -> dict[str, Any]:
        campaign = self._active_campaign_for_symbol(signal.symbol)
        if campaign is None or campaign.position_qty <= 0:
            raise CampaignExecutionError(f"{signal.symbol}: no active campaign for add-on")
        if signal.signal_bar_time_ms <= 0:
            raise CampaignExecutionError("add-on signal time is invalid")
        self._validate_signal_freshness(signal)
        if signal.event_time_ms <= int(campaign.tags.get("last_signal_time_ms", 0) or 0):
            raise CampaignExecutionError("signal confirmation is not newer than current campaign signal")

        reserved = float(campaign.open_risk_quote or 0) + float(campaign.pending_risk_quote or 0)
        campaign_capacity = float(equity_quote) * self.engine.campaign_risk_limit_pct
        remaining = max(0.0, campaign_capacity - reserved)
        requested = min(
            remaining,
            float(equity_quote) * min(
                float(candidate_risk_pct),
                float(os.getenv("CAMPAIGN_ADD_RISK_MAX_PCT", "0.002")),
            ),
        )
        if requested <= 0:
            raise CampaignExecutionError("campaign risk budget exhausted")

        current = self._current_price(signal.symbol)
        trigger = self._normalize_trigger_price(signal.symbol, signal.trigger_price)
        if trigger <= current:
            raise CampaignExecutionError(
                f"{signal.symbol}: add-on trigger already crossed; no market substitution"
            )

        current_stop = float(campaign.current_stop_price or 0.0)
        protective_reference = float(signal.protective_reference or 0.0)
        # The add-on must inherit the already-protective campaign stop; a new
        # signal can never move that stop backward.
        stop = self._normalize_price(
            signal.symbol,
            current_stop if current_stop > 0.0 else protective_reference,
        )
        if stop <= 0 or stop >= trigger:
            raise CampaignExecutionError(f"{signal.symbol}: invalid add-on protection reference")

        stop_fraction = (trigger - stop) / trigger
        effective_loss_fraction = stop_fraction + 0.002 + float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        notional = min(
            equity_quote * float(os.getenv("CAMPAIGN_ADD_CAPITAL_FRACTION", "0.25")),
            requested / max(effective_loss_fraction, 1e-12),
        )
        qty = self._normalized_entry_qty(signal.symbol, notional, trigger)
        cid = f"{self.ENTRY_PREFIX}ADD_{uuid.uuid4().hex[:16]}"

        prior = self.db.conn.execute(
            "SELECT state FROM campaign_signals WHERE campaign_id=? AND signal_id=?",
            (campaign.campaign_id, signal.signal_id),
        ).fetchone()
        if prior and str(prior["state"]).upper() in {"ARMED", "TRIGGERED", "FILLED"}:
            raise CampaignExecutionError("signal is already active or filled for this campaign")
        self.db.save_campaign_signal(signal, campaign.campaign_id, state=SignalState.DETECTED.value)
        campaign.tags["last_signal_time_ms"] = int(signal.event_time_ms)
        campaign.tags["pending_add_signal_id"] = signal.signal_id
        campaign.pending_risk_quote = requested
        campaign.capital_reserved_quote = qty * trigger
        self.engine.arm_add_on(
            campaign,
            signal,
            risk_quote=requested,
            capital_reserved_quote=qty * trigger,
        )
        # Reuse the same durable per-symbol pending key. Only one conditional
        # order for a symbol may be waiting at a time; this keeps startup
        # recovery idempotent and avoids a second persistence protocol.
        claimed = self.db.try_claim_state(
            f"entry_client_order_id:{signal.symbol}",
            cid,
        )
        if not claimed:
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            try:
                campaign.transition(
                    CampaignState.TREND_ACTIVE,
                    reason="another pending order already exists for symbol",
                )
            except ValueError:
                pass
            self.db.save_campaign(campaign)
            raise CampaignExecutionError(
                f"{signal.symbol}: another pending conditional order exists"
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
                signal.symbol,
                qty,
                trigger,
            ),
        )
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
        self.db.save_campaign(campaign)
        return {
            "campaign_id": campaign.campaign_id,
            "signal_id": signal.signal_id,
            "order_id": order.get("orderId"),
            "client_order_id": cid,
            "trigger_price": trigger,
            "quantity": qty,
            "risk_quote": requested,
        }

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
        # Resolve target ownership from the durable campaign-order journal before
        # asking Binance to cancel anything. The caller's numeric id alone is not
        # sufficient evidence that this target belongs to this campaign.
        local_target = self.db.conn.execute(
            """SELECT order_id,client_order_id,campaign_id,symbol
               FROM campaign_orders
               WHERE campaign_id=? AND symbol=? AND order_id=?
                 AND COALESCE(client_order_id,'')<>''
               ORDER BY id DESC LIMIT 1""",
            (campaign.campaign_id, campaign.symbol.upper(), str(order_id)),
        ).fetchone()
        if local_target is None:
            raise CampaignExecutionError(
                "cancel target has no matching durable campaign-order ownership record"
            )
        # Persist both target IDs on the cancellation intent before DELETE. A
        # cancel operation's own client id cannot locate the target after a lost response.
        target = self.client.get_order(campaign.symbol, order_id=order_id)
        if (
            str(target.get("symbol", "")).upper() != campaign.symbol.upper()
            or str(target.get("orderId")) != str(order_id)
            or str(target.get("clientOrderId") or "") != str(local_target["client_order_id"])
        ):
            raise CampaignExecutionError(
                "exchange cancellation target does not match durable campaign-order identity"
            )
        intent = OrderIntent.new(
            campaign.symbol,
            str(target.get("side", "SELL")).upper(),
            "CANCEL",
            required_context_versions={},
            client_order_id=f"{self.STOP_PREFIX}CANCEL_{uuid.uuid4().hex[:14]}",
            target_order_id=str(order_id),
            target_client_order_id=str(target["clientOrderId"]),
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
                f"{symbol}: campaign exit has no reconciled authoritative fill"
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
        # Financial effects have already been applied exactly once through
        # execution_fills + _apply_recovered_campaign_sell_fill. Do not recompute
        # PnL from cummulativeQuoteQty or mutate campaign quantity a second time.
        reconciled = self.engine.load_campaign(campaign.campaign_id)
        if reconciled is None:
            raise CampaignExecutionError("campaign missing after verified exit fills")
        exit_qty = Decimal(str(reconciled.tags.get("exit_filled_qty", "0") or "0"))
        exit_quote = Decimal(str(reconciled.tags.get("exit_filled_quote", "0") or "0"))
        exit_price = float(exit_quote / exit_qty) if exit_qty > 0 else quote / executed
        if reconciled.state == CampaignState.CLOSED and float(reconciled.position_qty) <= 0:
            return {
                "campaign_id": reconciled.campaign_id,
                "symbol": symbol,
                "state": "CLOSED",
                "quantity": executed,
                "exit_price": exit_price,
                "reason": reconciled.exit_reason or reason,
            }
        remaining = float(reconciled.position_qty)
        self.engine.mark_reconcile_required(
            reconciled,
            f"exit execution reconciled but residual position {remaining:.12g} remains",
        )
        self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
        raise CampaignExecutionError(
            f"{symbol}: exit fill left residual {remaining:.12g}; position and protection reconciliation required"
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
        cid = f"{self.STOP_PREFIX}{uuid.uuid4().hex[:20]}"
        intent = OrderIntent.new(
            campaign.symbol,
            "SELL",
            "STOP_LOSS",
            required_context_versions={},
            invalidation_level=new_stop,
            quantity=self.client.decimal_format(quantity),
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
                quantity=self.client.decimal_format(quantity),
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
                quantity=quantity,
                campaign_id=campaign.campaign_id,
                signal_id=campaign.current_signal_id,
            )
        )
        return result
