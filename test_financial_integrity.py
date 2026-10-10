"""Financial integrity regressions: strict fills, durable identity and idempotence."""
from __future__ import annotations

import threading

import pytest

from db import Database
from execution_accumulator import ExecutionDataIncomplete, accumulate_fills, accumulate_order


def fill(**overrides):
    row = {
        "tradeId": 17, "orderId": 901, "price": "100.0", "qty": "0.4",
        "commission": "0.001", "commissionAsset": "BTC",
    }
    row.update(overrides)
    return row


def order_payload(fills):
    qty = sum(float(row["qty"]) for row in fills)
    quote = sum(float(row["qty"]) * float(row["price"]) for row in fills)
    return {
        "orderId": 901, "symbol": "BTCUSDT", "status": "FILLED",
        "executedQty": str(qty), "cummulativeQuoteQty": str(quote),
        "fills": fills,
    }


def test_complete_fill_with_base_commission_is_accounted_once():
    summary = accumulate_order(order_payload([fill()]), base_asset="BTC")
    assert summary.financials_complete
    assert summary.executed_qty == 0.4
    assert summary.quote_qty == 40
    assert summary.commission_base == 0.001
    assert summary.net_base_qty == 0.399
    assert summary.trade_ids == ("17",)


def test_confirmed_zero_commission_requires_explicit_asset():
    summary = accumulate_fills(
        [fill(commission="0", commissionAsset="USDT")], base_asset="BTC"
    )
    assert summary.financials_complete
    assert summary.fee_quote == 0


@pytest.mark.parametrize("changes,reason", [
    ({"commission": None}, "fill_missing_commission"),
    ({"commissionAsset": ""}, "fill_missing_commission_asset"),
    ({"qty": "NaN"}, "fill_invalid_qty"),
    ({"price": "-1"}, "fill_invalid_price"),
    ({"commission": "-0.1"}, "fill_invalid_commission"),
    ({"tradeId": None}, "fill_0_missing_trade_id"),
])
def test_incomplete_or_invalid_fill_fails_closed(changes, reason):
    with pytest.raises(ExecutionDataIncomplete, match=reason):
        accumulate_fills([fill(**changes)], base_asset="BTC")


def test_unknown_third_asset_fee_requires_verified_conversion():
    row = fill(commission="0.02", commissionAsset="BNB")
    with pytest.raises(ExecutionDataIncomplete, match="unvalued_commission_asset"):
        accumulate_fills([row], base_asset="BTC")
    summary = accumulate_fills(
        [row], base_asset="BTC", asset_quote_values={"BNB": "300"}
    )
    assert summary.fee_quote_equivalent == 6


def test_duplicate_or_conflicting_trade_id_is_not_silently_counted():
    with pytest.raises(ExecutionDataIncomplete, match="duplicate_trade_id"):
        accumulate_fills([fill(), fill()], base_asset="BTC")
    with pytest.raises(ExecutionDataIncomplete, match="conflicting_duplicate_trade_id"):
        accumulate_fills([fill(), fill(qty="0.5")], base_asset="BTC")


def test_order_aggregate_must_match_per_trade_history():
    payload = order_payload([fill()])
    payload["executedQty"] = "0.5"
    with pytest.raises(ExecutionDataIncomplete, match="order_fill_totals_mismatch"):
        accumulate_order(payload, base_asset="BTC")


def ledger_fill(**overrides):
    row = {
        "exchange": "BINANCE", "account_id": "test-account",
        "trade_id": "17", "campaign_id": "campaign-1",
        "order_id": "901", "symbol": "BTCUSDT", "side": "BUY",
        "quantity": "0.4", "price": "100", "quote_quantity": "40",
        "commission": "0.001", "commission_asset": "BTC",
    }
    row.update(overrides)
    return row


def test_campaign_fill_identity_is_database_unique_and_idempotent(tmp_path):
    db = Database(str(tmp_path / "fills.sqlite3"))
    assert db.save_campaign_fill(ledger_fill()) is True
    assert db.save_campaign_fill(ledger_fill()) is False
    rows = db.conn.execute(
        "SELECT COUNT(*),SUM(quantity),SUM(commission_amount) FROM campaign_fills "
        "WHERE trade_id='17'"
    ).fetchone()
    assert rows[0] == 1
    assert rows[1] == pytest.approx(0.4)
    assert rows[2] == pytest.approx(0.001)


def test_conflicting_existing_trade_id_preserves_record_and_blocks_recovery(tmp_path):
    db = Database(str(tmp_path / "fill-conflict.sqlite3"))
    db.save_campaign_fill(ledger_fill())
    with pytest.raises(RuntimeError, match="conflicting_duplicate_trade_id"):
        db.save_campaign_fill(ledger_fill(quantity="0.5", quote_quantity="50"))
    assert db.conn.execute(
        "SELECT COUNT(*) FROM campaign_fills WHERE trade_id='17'"
    ).fetchone()[0] == 1
    assert db.state_get("recovery_status") == "RECOVERY_REQUIRED"
    assert db.conn.execute(
        "SELECT COUNT(*) FROM execution_events WHERE event='CONFLICTING_DUPLICATE_TRADE_ID'"
    ).fetchone()[0] == 1


def test_two_database_instances_racing_same_fill_do_not_duplicate(tmp_path):
    path = str(tmp_path / "fill-race.sqlite3")
    first, second = Database(path), Database(path)
    barrier = threading.Barrier(2)
    results, errors = [], []

    def worker(db):
        try:
            barrier.wait(timeout=5)
            results.append(db.save_campaign_fill(ledger_fill()))
        except Exception as exc:  # surfaced below, no silent worker failures
            errors.append(type(exc).__name__)

    a = threading.Thread(target=worker, args=(first,))
    b = threading.Thread(target=worker, args=(second,))
    a.start(); b.start(); a.join(timeout=10); b.join(timeout=10)
    assert not a.is_alive() and not b.is_alive()
    assert errors == []
    assert sorted(results) == [False, True]
    assert first.conn.execute(
        "SELECT COUNT(*) FROM campaign_fills WHERE trade_id='17'"
    ).fetchone()[0] == 1


def test_global_recovery_gate_blocks_buy_but_does_not_block_sell(tmp_path):
    from execution_barrier import ExecutionBarrier, OrderIntent
    from market_context import ContextCache

    db = Database(str(tmp_path / "global-gate.sqlite3"))
    gate = ExecutionBarrier(ContextCache(), db)
    buy = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {}, purpose="ENTRY")
    sell = OrderIntent.new("BTCUSDT", "SELL", "MARKET", {}, purpose="EMERGENCY_EXIT")
    # Assert the persisted gate contract directly without reaching exchange submit.
    assert db.state_get("recovery_status") == "RECOVERY_REQUIRED"
    assert gate._validate(buy, object()) == "global_recovery_gate:RECOVERY_REQUIRED"
    assert gate._validate(sell, object()) == ""
