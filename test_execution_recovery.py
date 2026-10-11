"""Crash-recovery tests using a local SQLite DB and a read-only fake exchange."""
from __future__ import annotations

from db import Database
from execution_barrier import OrderIntent
from execution_recovery import ExecutionRecoveryResolver


class FakeExchange:
    def __init__(self):
        self.orders_by_id = {}
        self.orders_by_cid = {}
        self.lists_by_cid = {}
        self.lists_by_id = {}
        self.mutations = 0
        self.reads = 0

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        self.reads += 1
        row = (
            self.orders_by_id.get(str(order_id))
            if order_id is not None
            else self.orders_by_cid.get(str(orig_client_order_id))
        )
        if row is None:
            raise RuntimeError("synthetic order not found")
        return dict(row)

    def order_list(self, symbol, order_list_id=None, list_client_order_id=None):
        self.reads += 1
        row = (
            self.lists_by_id.get(str(order_list_id))
            if order_list_id is not None
            else self.lists_by_cid.get(str(list_client_order_id))
        )
        if row is None:
            raise RuntimeError("synthetic order list not found")
        return dict(row)

    # The resolver intentionally has no path to invoke mutation methods.
    def order_safe(self, *args, **kwargs):
        self.mutations += 1
        raise AssertionError("recovery must never submit a replacement order")

    def cancel_order(self, *args, **kwargs):
        self.mutations += 1
        raise AssertionError("recovery must never repeat a cancel")

    def cancel_oco(self, *args, **kwargs):
        self.mutations += 1
        raise AssertionError("recovery must never repeat an OCO cancel")


def make_intent(db, *, order_type="MARKET", cid="WILL_RECOVERY_ORDER_001",
                mutation_kind="ORDER", **kwargs):
    intent = OrderIntent.new(
        "BTCUSDT", "SELL" if order_type in {"CANCEL", "OCO"} else "BUY",
        order_type, {}, purpose="CAMPAIGN_RECOVERY",
        client_order_id=cid, mutation_kind=mutation_kind, **kwargs,
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "synthetic lost response")
    db.save_execution_event(intent.intent_id, "SUBMIT_ATTEMPT_STARTED", {
        "mutation_kind": intent.mutation_kind, "client_order_id": cid,
    })
    return intent


def order(*, oid, cid, status, side="BUY", executed="0.0"):
    return {
        "symbol": "BTCUSDT", "orderId": oid, "clientOrderId": cid,
        "status": status, "side": side, "type": "MARKET",
        "origQty": "1.0", "executedQty": executed,
        "cummulativeQuoteQty": str(float(executed) * 100.0),
    }


def test_lost_post_response_is_reconciled_after_restart_without_second_mutation(tmp_path):
    db = Database(str(tmp_path / "lost-post.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_LOST_POST_001"
    intent = make_intent(db, cid=cid)
    exchange.orders_by_cid[cid] = order(oid=901, cid=cid, status="FILLED", executed="1.0")

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert report["mutations_sent"] == 0
    assert report["resolved"] == [{"intent_id": intent.intent_id, "status": "RECONCILED"}]
    assert db.find_execution_intent(intent.intent_id, cid)["status"] == "RECONCILED"
    assert db.unresolved_execution_intents() == []
    assert db.conn.execute("SELECT order_id FROM orders WHERE order_id='901'").fetchone()
    assert exchange.mutations == 0


def test_partial_fill_is_persisted_as_exchange_authority_not_replayed(tmp_path):
    db = Database(str(tmp_path / "partial-fill.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_PARTIAL_002"
    intent = make_intent(db, cid=cid)
    exchange.orders_by_cid[cid] = order(
        oid=902, cid=cid, status="PARTIALLY_FILLED", executed="0.4"
    )

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    saved = db.conn.execute(
        "SELECT status,quantity,raw_json FROM orders WHERE order_id='902'"
    ).fetchone()
    assert report["ok"] is True
    assert saved["status"] == "PARTIALLY_FILLED"
    assert saved["quantity"] == 1.0
    assert "0.4" in saved["raw_json"]
    assert db.find_execution_intent(intent.intent_id, cid)["status"] == "RECONCILED"
    assert exchange.mutations == 0


def test_unknown_order_state_remains_global_fail_closed(tmp_path):
    db = Database(str(tmp_path / "unknown-order.sqlite3"))
    exchange = FakeExchange()
    intent = make_intent(db, cid="WILL_RECOVERY_UNKNOWN_003")

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert report["blocked"][0]["intent_id"] == intent.intent_id
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_oco_recovery_requires_exact_list_and_both_confirmed_legs(tmp_path):
    db = Database(str(tmp_path / "oco-recovery.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_OCO_004"
    intent = make_intent(db, order_type="OCO", cid=cid)
    legs = [
        order(oid=911, cid=cid + "_TP", status="NEW", side="SELL"),
        order(oid=912, cid=cid + "_SL", status="NEW", side="SELL"),
    ]
    for leg in legs:
        exchange.orders_by_id[str(leg["orderId"])] = leg
        exchange.orders_by_cid[leg["clientOrderId"]] = leg
    listing = {
        "symbol": "BTCUSDT", "orderListId": 77, "listClientOrderId": cid,
        "listStatusType": "EXECUTING",
        "orders": [
            {"symbol": "BTCUSDT", "orderId": 911, "clientOrderId": cid + "_TP"},
            {"symbol": "BTCUSDT", "orderId": 912, "clientOrderId": cid + "_SL"},
        ],
    }
    exchange.lists_by_cid[cid] = listing

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert db.find_execution_intent(intent.intent_id, cid)["status"] == "RECONCILED"
    assert db.conn.execute("SELECT COUNT(*) FROM orders WHERE order_list_id='77'").fetchone()[0] == 2
    assert exchange.mutations == 0


def test_cancel_recovery_uses_target_order_id_not_cancel_intent_id(tmp_path):
    db = Database(str(tmp_path / "cancel-recovery.sqlite3"))
    exchange = FakeExchange()
    target_cid = "WILL_RECOVERY_PROTECTIVE_005"
    intent = make_intent(
        db, order_type="CANCEL", cid="WILL_RECOVERY_CANCEL_INTENT_005",
        mutation_kind="CANCEL_ORDER", target_order_id="920",
        target_client_order_id=target_cid,
    )
    exchange.orders_by_id["920"] = order(
        oid=920, cid=target_cid, status="CANCELED", side="SELL"
    )

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert db.find_execution_intent(intent.intent_id, intent.client_order_id)["status"] == "RECONCILED"
    assert exchange.mutations == 0


def test_cancel_replace_requires_authoritative_old_and_replacement_orders(tmp_path):
    db = Database(str(tmp_path / "cancel-replace.sqlite3"))
    exchange = FakeExchange()
    old_cid = "WILL_RECOVERY_OLD_STOP_006"
    new_cid = "WILL_RECOVERY_NEW_STOP_006"
    intent = make_intent(
        db, order_type="STOP_LOSS", cid=new_cid, mutation_kind="CANCEL_REPLACE",
        target_order_id="930", target_client_order_id=old_cid,
        replacement_client_order_id=new_cid,
    )
    exchange.orders_by_id["930"] = order(oid=930, cid=old_cid, status="CANCELED", side="SELL")
    exchange.orders_by_cid[new_cid] = order(oid=931, cid=new_cid, status="NEW", side="SELL")

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert db.find_execution_intent(intent.intent_id, new_cid)["status"] == "RECONCILED"
    assert exchange.mutations == 0


def test_cancel_replace_missing_replacement_remains_blocked(tmp_path):
    db = Database(str(tmp_path / "cancel-replace-unknown.sqlite3"))
    exchange = FakeExchange()
    intent = make_intent(
        db, order_type="STOP_LOSS", cid="WILL_RECOVERY_NEW_STOP_007",
        mutation_kind="CANCEL_REPLACE", target_order_id="940",
        target_client_order_id="WILL_RECOVERY_OLD_STOP_007",
        replacement_client_order_id="WILL_RECOVERY_NEW_STOP_007",
    )
    exchange.orders_by_id["940"] = order(
        oid=940, cid="WILL_RECOVERY_OLD_STOP_007", status="CANCELED", side="SELL"
    )

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_failed_local_persistence_rolls_back_resolution_and_keeps_intent_blocked(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "write-failure.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_LOCAL_WRITE_FAIL_008"
    intent = make_intent(db, cid=cid)
    exchange.orders_by_cid[cid] = order(oid=950, cid=cid, status="FILLED", executed="1.0")
    original = db.save_order

    def fail_write(_order):
        raise OSError("synthetic local SQLite failure")

    monkeypatch.setattr(db, "save_order", fail_write)
    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()
    monkeypatch.setattr(db, "save_order", original)

    assert report["ok"] is False
    assert db.find_execution_intent(intent.intent_id, cid)["status"] == "AMBIGUOUS"
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_confirmed_resolution_never_replays_the_same_intent(tmp_path):
    import threading
    from execution_barrier import ExecutionBarrier

    class Cache:
        execution_lock = threading.RLock()
        def snapshot(self):
            return object()

    db = Database(str(tmp_path / "no-replay.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_NO_REPLAY_009"
    intent = make_intent(db, cid=cid)
    exchange.orders_by_cid[cid] = order(oid=960, cid=cid, status="FILLED", executed="1.0")
    assert ExecutionRecoveryResolver(db, exchange).resolve_pending()["ok"] is True

    submitted = []
    result = ExecutionBarrier(Cache(), db).execute(
        intent, lambda: submitted.append("duplicate") or {"status": "FILLED"}
    )
    assert result.accepted is False
    assert "prior_intent_exists:RECONCILED" in result.reason
    assert submitted == []
    assert exchange.mutations == 0


def test_oco_cancel_requires_exact_list_and_leg_evidence(tmp_path):
    db = Database(str(tmp_path / "oco-cancel.sqlite3"))
    exchange = FakeExchange()
    list_cid = "WILL_RECOVERY_OCO_CANCEL_010"
    intent = make_intent(
        db, order_type="CANCEL", cid="WILL_RECOVERY_CANCEL_LIST_010",
        mutation_kind="CANCEL_OCO", target_order_list_id="88",
        target_list_client_order_id=list_cid,
    )
    legs = [
        order(oid=971, cid=list_cid + "_TP", status="CANCELED", side="SELL"),
        order(oid=972, cid=list_cid + "_SL", status="CANCELED", side="SELL"),
    ]
    for leg in legs:
        exchange.orders_by_id[str(leg["orderId"])] = leg
        exchange.orders_by_cid[leg["clientOrderId"]] = leg
    exchange.lists_by_id["88"] = {
        "symbol": "BTCUSDT", "orderListId": 88,
        "listClientOrderId": list_cid, "listStatusType": "ALL_DONE",
        "orders": [
            {"symbol": "BTCUSDT", "orderId": 971, "clientOrderId": list_cid + "_TP"},
            {"symbol": "BTCUSDT", "orderId": 972, "clientOrderId": list_cid + "_SL"},
        ],
    }

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert db.find_execution_intent(intent.intent_id, intent.client_order_id)["status"] == "RECONCILED"
    assert exchange.mutations == 0


def test_pre_submit_crash_resolves_only_after_exact_exchange_not_found(tmp_path):
    from binance_client import BinanceAPIError

    db = Database(str(tmp_path / "pre-submit-crash.sqlite3"))
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {},
        purpose="CAMPAIGN_RECOVERY",
        client_order_id="WILL_RECOVERY_NOT_SUBMITTED_011",
    )
    db.save_execution_intent(intent, "PENDING")
    # No SUBMIT_ATTEMPT_STARTED event means the mutation callable was never entered.

    class NotFoundExchange(FakeExchange):
        def get_order(self, symbol, order_id=None, orig_client_order_id=None):
            self.reads += 1
            raise BinanceAPIError(
                "Binance HTTP 400; API code -2013",
                status_code=400,
                payload={"code": -2013, "msg": "response body redacted"},
            )

    exchange = NotFoundExchange()
    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is True
    assert report["resolved"][0]["exchange_result"] == "NOT_SUBMITTED_CONFIRMED"
    assert db.find_execution_intent(intent.intent_id, intent.client_order_id)["status"] == "RECONCILED"
    assert db.unresolved_execution_intents() == []
    assert exchange.mutations == 0


def test_production_binance_mutation_call_sites_are_barrier_routed():
    import ast
    from pathlib import Path

    root = Path(__file__).resolve().parent
    production = ("trader.py", "campaign_execution.py", "portfolio_trader.py")
    mutation_names = {
        "order", "order_safe", "create_oco_sell", "create_oco_sell_safe",
        "cancel_order", "cancel_open_orders", "cancel_oco", "cancel_replace",
    }
    findings = []
    for relative in production:
        source = (root / relative).read_text()
        tree = ast.parse(source, filename=relative)
        parent = {}
        for node in ast.walk(tree):
            for child in ast.iter_child_nodes(node):
                parent[child] = node

        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                continue
            if node.func.attr not in mutation_names:
                continue
            if not isinstance(node.func.value, ast.Attribute):
                continue
            if node.func.value.attr != "client":
                continue

            ancestor = parent.get(node)
            while ancestor is not None and not isinstance(
                ancestor, (ast.FunctionDef, ast.AsyncFunctionDef)
            ):
                ancestor = parent.get(ancestor)
            assert ancestor is not None, f"{relative}:{node.lineno} has no owning function"
            body = ast.get_source_segment(source, ancestor) or ""
            barrier_bound = any(
                marker in body
                for marker in (
                    "self._submit(",
                    "self._submit_order(",
                    "self.execution_barrier.execute(",
                )
            )
            findings.append((relative, node.lineno, node.func.attr, barrier_bound))
            assert barrier_bound, (
                f"unbarriered Binance mutation call site: "
                f"{relative}:{node.lineno} {node.func.attr}"
            )

    # Guard against silently dropping the inventory as files are refactored.
    assert findings, "no production mutation call sites were inventoried"
    assert all(row[3] for row in findings), findings


def test_unresolved_execution_intent_prevents_stale_symbol_barrier_clear(tmp_path, monkeypatch):
    from portfolio_trader import MultiPositionTrader

    monkeypatch.setenv("CAMPAIGN_ENGINE", "false")
    db = Database(str(tmp_path / "stale-symbol-barrier.sqlite3"))
    exchange = FakeExchange()
    intent = make_intent(db, cid="WILL_RECOVERY_STALE_BARRIER_012")
    db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")

    trader = MultiPositionTrader(exchange, db=db, symbols=[])
    repaired = trader._repair_stale_reconcile_states()

    assert repaired == []
    assert trader.state("BTCUSDT") == "RECONCILE_REQUIRED"
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_unknown_cancel_with_live_target_remains_blocked_and_is_not_reissued(tmp_path):
    db = Database(str(tmp_path / "cancel-live-target.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_CANCEL_LIVE_013"
    intent = make_intent(
        db, order_type="CANCEL", cid="WILL_RECOVERY_CANCEL_INTENT_013",
        mutation_kind="CANCEL_ORDER", target_order_id="980",
        target_client_order_id=cid,
    )
    exchange.orders_by_id["980"] = order(oid=980, cid=cid, status="NEW", side="SELL")

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_unknown_oco_cancel_with_active_list_remains_blocked(tmp_path):
    db = Database(str(tmp_path / "oco-cancel-active.sqlite3"))
    exchange = FakeExchange()
    list_cid = "WILL_RECOVERY_OCO_CANCEL_ACTIVE_014"
    intent = make_intent(
        db, order_type="CANCEL", cid="WILL_RECOVERY_CANCEL_LIST_014",
        mutation_kind="CANCEL_OCO", target_order_list_id="89",
        target_list_client_order_id=list_cid,
    )
    legs = [
        order(oid=981, cid=list_cid + "_TP", status="NEW", side="SELL"),
        order(oid=982, cid=list_cid + "_SL", status="NEW", side="SELL"),
    ]
    for leg in legs:
        exchange.orders_by_id[str(leg["orderId"])] = leg
        exchange.orders_by_cid[leg["clientOrderId"]] = leg
    exchange.lists_by_id["89"] = {
        "symbol": "BTCUSDT", "orderListId": 89,
        "listClientOrderId": list_cid, "listStatusType": "EXECUTING",
        "orders": [
            {"symbol": "BTCUSDT", "orderId": 981, "clientOrderId": list_cid + "_TP"},
            {"symbol": "BTCUSDT", "orderId": 982, "clientOrderId": list_cid + "_SL"},
        ],
    }

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_cancel_replace_old_live_order_remains_blocked_even_if_replacement_exists(tmp_path):
    db = Database(str(tmp_path / "cancel-replace-old-live.sqlite3"))
    exchange = FakeExchange()
    old_cid = "WILL_RECOVERY_OLD_STOP_015"
    new_cid = "WILL_RECOVERY_NEW_STOP_015"
    intent = make_intent(
        db, order_type="STOP_LOSS", cid=new_cid, mutation_kind="CANCEL_REPLACE",
        target_order_id="990", target_client_order_id=old_cid,
        replacement_client_order_id=new_cid,
    )
    exchange.orders_by_id["990"] = order(oid=990, cid=old_cid, status="NEW", side="SELL")
    exchange.orders_by_cid[new_cid] = order(oid=991, cid=new_cid, status="NEW", side="SELL")

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_unknown_oco_create_after_submit_attempt_stays_blocked(tmp_path):
    db = Database(str(tmp_path / "oco-create-unknown.sqlite3"))
    exchange = FakeExchange()
    cid = "WILL_RECOVERY_OCO_UNKNOWN_016"
    intent = make_intent(db, order_type="OCO", cid=cid)

    report = ExecutionRecoveryResolver(db, exchange).resolve_pending()

    assert report["ok"] is False
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0


def test_full_portfolio_recover_keeps_global_and_symbol_barriers_on_unknown_order(tmp_path, monkeypatch):
    from portfolio_trader import MultiPositionTrader

    monkeypatch.setenv("CAMPAIGN_ENGINE", "false")
    db = Database(str(tmp_path / "full-recover-blocked.sqlite3"))
    exchange = FakeExchange()
    intent = make_intent(db, cid="WILL_RECOVERY_FULL_RESTART_017")

    report = MultiPositionTrader(exchange, db=db, symbols=[]).recover()

    assert report["ok"] is False
    assert report["execution_recovery"]["mutations_sent"] == 0
    assert "BTCUSDT" in report["unresolved_symbols"]
    assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    assert db.unresolved_execution_intents()[0]["intent_id"] == intent.intent_id
    assert exchange.mutations == 0
