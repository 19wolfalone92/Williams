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
        original_position_qty = float(campaign.position_qty)
        remaining = max(0.0, original_position_qty - executed)
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
            self.engine._canonical_set(
                campaign,
                __import__(
                    "campaign_order_fsm",
                    fromlist=["CampaignOrderState"],
                ).CampaignOrderState.CLOSED,
                reason="authoritative campaign exit fill complete",
            )
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

        # Partial market exit is authoritative. Re-protect the residual before
        # declaring recovery complete; the residual must never remain naked after
        # the original protective stop was canceled.
        try:
            residual_protection = self.create_hard_stop(
                campaign,
                quantity=remaining,
                stop_price=float(campaign.current_stop_price or 0.0),
            )
        except Exception as exc:
            campaign.position_qty = remaining
            self.engine.mark_reconcile_required(
                campaign,
                f"partial exit left residual without confirmed protection: {exc}",
            )
            self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
            raise CampaignExecutionError(
                f"{symbol}: partial exit residual cannot be re-protected safely"
            ) from exc

        campaign.position_qty = remaining
        if original_position_qty > 0:
            campaign.open_risk_quote = (
                float(campaign.open_risk_quote)
                * remaining
                / original_position_qty
            )
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protective_order_id"] = residual_protection.get(
            "order_id",
            "",
        )
        campaign.next_action = "MONITOR_RESIDUAL"
        campaign.reconciliation_state = "CLEAN"
        campaign.transition(
            CampaignState.EXIT_PENDING,
            reason="partial exit filled; residual protected",
        )
        self.engine._canonical_set(
            campaign,
            __import__(
                "campaign_order_fsm",
                fromlist=["CampaignOrderState"],
            ).CampaignOrderState.EXIT_PARTIAL,
            reason="partial exit filled; residual protected",
        )
        self.db.save_campaign(campaign)
        self.db.state_set(f"position_state:{symbol}", "OPEN")
        self.db.log_campaign_event(
            campaign.campaign_id,
            "EXIT_PARTIAL_FILL",
            order_id=str(order.get("orderId", "")),
            reason="residual re-protected",
            payload={
                "executed": executed,
                "remaining": remaining,
                "residual_protective_order_id": residual_protection.get("order_id", ""),
            },
        )
        return {
            "campaign_id": campaign.campaign_id,
            "symbol": symbol,
            "state": "EXIT_PARTIAL",
            "quantity": executed,
            "remaining_quantity": remaining,
            "exit_price": exit_price,
            "reason": reason,
        }

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
            related_order_id=str(existing_order_id),
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
