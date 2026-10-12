"""FI-01..FI-12 deterministic fake-exchange regression tests.

These exercise the Python production ExecutionBarrier, SQLite Database, and
ExecutionRecoveryWorker. They use temporary databases and synthetic orders only.
They do not claim Android runtime coverage; Android remains separately verified
by the Campaign CI job.
"""
import time

import pytest

from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from execution_recovery import ExecutionRecoveryWorker
from market_context import ContextCache


class DeterministicFakeExchange:
    """Small exchange model with explicit mutation count and lost-response faults."""

    def __init__(self):
        self.orders = {}
        self.mutation_count = 0
        self.lookup_count = 0
        self.trade_rows = {}
        self.accept_then_timeout = False
        self.fail_trade_history = False
        self.list_response = None

    def submit(self, intent):
        self.mutation_count += 1
        order = {
            "symbol": intent.symbol,
            "side": intent.side,
            "orderId": str(7000 + self.mutation_count),
            "clientOrderId": intent.client_order_id,
            "status": "NEW",
            "origQty": intent.quantity or "0.010",
            "executedQty": "0",
            "price": "0",
            "avgPrice": "0",
        }
        self.orders[intent.client_order_id] = dict(order)
        if self.accept_then_timeout:
            raise TimeoutError("synthetic response lost after exchange acceptance")
        return dict(order)

    def get_order(self, symbol, *, order_id=None, orig_client_order_id=None):
        self.lookup_count += 1
        for order in self.orders.values():
            if str(order.get("symbol", "")).upper() != str(symbol).upper():
                continue
            if order_id is not None and str(order.get("orderId")) == str(order_id):
                return dict(order)
            if orig_client_order_id is not None and order.get("clientOrderId") == orig_client_order_id:
                return dict(order)
        raise LookupError("synthetic order lookup unavailable or not found")

    def my_trades(self, symbol, order_id=None, limit=1000):
        if self.fail_trade_history:
            raise TimeoutError("synthetic trade history unavailable")
        return [dict(row) for row in self.trade_rows.get(str(order_id), [])][:limit]


def _new_intent(*, client_id="fi-stable-client-0001", order_type="MARKET", purpose="CAMPAIGN_ENTRY", quantity="0.010"):
    return OrderIntent.new(
        "BTCUSDT", "BUY", order_type, {},
        purpose=purpose, client_order_id=client_id, quantity=quantity,
        campaign_id="fi-campaign-1",
    )


def _persist_ambiguous(db, intent):
    db.save_execution_intent(intent, "AMBIGUOUS", "synthetic fault injection")


def _row(db, intent):
    row = db.conn.execute(
        "SELECT * FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
    ).fetchone()
    assert row is not None
    return row


def _worker(db, exchange):
    return ExecutionRecoveryWorker(db, exchange, base_retry_seconds=5, owner="fi-worker")


def test_fi_01_failure_before_durable_admission_never_mutates_exchange(tmp_path, monkeypatch):
    """Canonical FI-01: failure before submission/acceptance."""
    exchange = DeterministicFakeExchange()
    with Database(str(tmp_path / "fi01.sqlite3")) as db:
        intent = _new_intent()
        original = db.save_execution_intent

        def fail_before_persist(candidate, status, reason, **kwargs):
            if status == "PENDING":
                raise OSError("synthetic pre-submit persistence failure")
            return original(candidate, status, reason, **kwargs)

        monkeypatch.setattr(db, "save_execution_intent", fail_before_persist)
        result = ExecutionBarrier(ContextCache(), db).execute(intent, lambda: exchange.submit(intent))
        assert result.accepted is False
        assert exchange.mutation_count == 0
        assert db.conn.execute("SELECT COUNT(*) FROM execution_intents").fetchone()[0] == 0


def test_fi_02_exchange_accepts_but_response_is_lost_then_restart_reconciles(tmp_path):
    """Canonical FI-02: accepted mutation with lost client response."""
    path = tmp_path / "fi02.sqlite3"
    exchange = DeterministicFakeExchange()
    exchange.accept_then_timeout = True
    intent = _new_intent(client_id="fi02-client")
    with Database(str(path)) as db:
        with pytest.raises(RuntimeError, match="submission outcome is ambiguous"):
            ExecutionBarrier(ContextCache(), db).execute(intent, lambda: exchange.submit(intent))
        assert exchange.mutation_count == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert db.has_unresolved_execution_intents()

    exchange.accept_then_timeout = False
    with Database(str(path)) as reopened:
        result = _worker(reopened, exchange).run_once(now=time.time() + 10)
        row = _row(reopened, intent)
        assert result["active"] == 1
        assert row["status"] == "SUBMITTED"
        assert row["order_id"] == "7001"
        assert exchange.mutation_count == 1
        assert not reopened.has_unresolved_execution_intents()


def test_fi_03_response_received_but_sqlite_status_write_fails_then_recovers(tmp_path, monkeypatch):
    """Canonical FI-03: exchange response precedes failed local persistence."""
    path = tmp_path / "fi03.sqlite3"
    exchange = DeterministicFakeExchange()
    intent = _new_intent(client_id="fi03-client")
    with Database(str(path)) as db:
        original = db.save_execution_intent

        def fail_submitted(candidate, status, reason, **kwargs):
            if status == "SUBMITTED":
                raise OSError("synthetic SQLite write fault after response")
            return original(candidate, status, reason, **kwargs)

        monkeypatch.setattr(db, "save_execution_intent", fail_submitted)
        result = ExecutionBarrier(ContextCache(), db).execute(intent, lambda: exchange.submit(intent))
        assert result.accepted is False
        assert exchange.mutation_count == 1
        assert db.has_unresolved_execution_intents()
        assert _row(db, intent)["status"] == "AMBIGUOUS"

    with Database(str(path)) as reopened:
        result = _worker(reopened, exchange).run_once(now=time.time() + 10)
        assert result["active"] == 1
        assert _row(reopened, intent)["order_id"] == "7001"
        assert exchange.mutation_count == 1


def test_fi_04_restart_with_ambiguous_intent_queries_exchange_without_resubmit(tmp_path):
    """Canonical FI-04: restart while durable state is ambiguous."""
    path = tmp_path / "fi04.sqlite3"
    exchange = DeterministicFakeExchange()
    intent = _new_intent(client_id="fi04-client")
    exchange.orders[intent.client_order_id] = {
        "symbol": "BTCUSDT", "side": "BUY", "orderId": "704",
        "clientOrderId": intent.client_order_id, "status": "NEW",
        "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
    }
    with Database(str(path)) as db:
        _persist_ambiguous(db, intent)
    with Database(str(path)) as reopened:
        result = _worker(reopened, exchange).run_once(now=time.time() + 10)
        assert result["active"] == 1
        assert _row(reopened, intent)["status"] == "SUBMITTED"
        assert exchange.mutation_count == 0
        assert exchange.lookup_count == 1


def test_fi_05_partial_fill_then_cancel_requires_matching_trade_history(tmp_path):
    """Canonical FI-05: partial fill followed by terminal cancellation."""
    with Database(str(tmp_path / "fi05.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi05-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "705",
            "clientOrderId": intent.client_order_id, "status": "CANCELED",
            "origQty": "0.010", "executedQty": "0.004", "price": "0",
            "avgPrice": "100",
        }
        exchange.trade_rows["705"] = [{
            "id": "trade-705-1", "orderId": "705", "qty": "0.004", "price": "100", "quoteQty": "0.4", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False
        }]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        row = _row(db, intent)
        assert result["reconciled"] == 1
        assert row["status"] == "CANCELED"
        assert row["executed_qty"] == pytest.approx(0.004)
        assert exchange.mutation_count == 0


def test_fi_06_duplicate_recovery_delivery_is_idempotent(tmp_path):
    """Canonical FI-06: duplicate delivery/recovery invocation."""
    with Database(str(tmp_path / "fi06.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi06-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "706",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100",
            "avgPrice": "100",
        }
        exchange.trade_rows["706"] = [{
            "id": "trade-706-1", "orderId": "706", "qty": "0.010", "price": "100", "quoteQty": "1.0", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False
        }]
        worker = _worker(db, exchange)
        first = worker.run_once(now=time.time() + 10)
        calls_after_first = exchange.lookup_count
        second = worker.run_once(now=time.time() + 20)
        assert first["reconciled"] == 1
        assert second["claimed"] == 0
        assert exchange.lookup_count == calls_after_first == 1
        assert exchange.mutation_count == 0
        assert _row(db, intent)["status"] == "FILLED"


def test_fi_07_crash_after_reconciliation_commit_does_not_repeat_mutation(tmp_path, monkeypatch):
    """Canonical FI-07: crash boundary immediately after local reconciliation commit."""
    with Database(str(tmp_path / "fi07.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi07-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "707",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100",
            "avgPrice": "100",
        }
        exchange.trade_rows["707"] = [{
            "id": "trade-707-1", "orderId": "707", "qty": "0.010", "price": "100", "quoteQty": "1.0", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False
        }]
        original = db.complete_execution_intent_recovery

        def commit_then_crash(*args, **kwargs):
            persisted = original(*args, **kwargs)
            raise OSError("synthetic process crash after commit")

        monkeypatch.setattr(db, "complete_execution_intent_recovery", commit_then_crash)
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["lease_lost"] == 1
        assert _row(db, intent)["status"] == "FILLED"
        assert exchange.mutation_count == 0


def test_fi_08_exchange_order_exists_but_local_exchange_order_id_is_missing(tmp_path):
    """Canonical FI-08: exchange order exists; durable intent has only client ID."""
    with Database(str(tmp_path / "fi08.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi08-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "708",
            "clientOrderId": intent.client_order_id, "status": "NEW",
            "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
        }
        assert _row(db, intent)["order_id"] is None
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["active"] == 1
        assert _row(db, intent)["order_id"] == "708"
        assert exchange.mutation_count == 0


def test_fi_09_incomplete_or_unavailable_history_never_reconciles_fill(tmp_path):
    """Canonical FI-09: incomplete history is not proof of complete execution."""
    with Database(str(tmp_path / "fi09.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi09-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "709",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100",
            "avgPrice": "100",
        }
        exchange.trade_rows["709"] = [{
            "id": "trade-709-1", "orderId": "709", "qty": "0.004", "price": "100", "quoteQty": "0.4", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False
        }]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0


def test_fi_10_ambiguous_cancel_response_keeps_cancel_intent_unresolved(tmp_path):
    """Canonical FI-10: cancellation has no authoritative terminal confirmation."""
    with Database(str(tmp_path / "fi10.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi10-target-client", order_type="CANCEL", purpose="CAMPAIGN_EXIT")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "710",
            "clientOrderId": intent.client_order_id, "status": "NEW",
            "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
        }
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0


def test_fi_11_partial_trade_history_failure_stays_unresolved(tmp_path):
    """Canonical FI-11: failure partway through reconciliation evidence collection."""
    with Database(str(tmp_path / "fi11.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi11-client")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "711",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100",
            "avgPrice": "100",
        }
        exchange.fail_trade_history = True
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0


def test_fi_12_retry_while_unresolved_does_not_send_second_mutation(tmp_path):
    """Canonical FI-12: a new submit attempt is blocked while outcome is unresolved."""
    with Database(str(tmp_path / "fi12.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        unresolved = _new_intent(client_id="fi12-original-client")
        _persist_ambiguous(db, unresolved)
        exchange.orders[unresolved.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "712",
            "clientOrderId": unresolved.client_order_id, "status": "NEW",
            "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
        }
        retry = _new_intent(client_id="fi12-retry-client")
        result = ExecutionBarrier(ContextCache(), db).execute(retry, lambda: exchange.submit(retry))
        assert result.accepted is False
        assert "unresolved" in result.reason.lower() or "reconcile" in result.reason.lower()
        assert exchange.mutation_count == 0
        assert db.has_unresolved_execution_intents()


@pytest.mark.parametrize("unsupported_type", [
    "CANCEL_REPLACE", "CANCEL-REPLACE", "CANCEL_LIST",
    "CANCEL_ORDER_LIST", "ORDER_LIST_CANCEL", "CANCEL_OCO",
])
def test_compound_cancel_recovery_is_explicitly_unsupported_and_fail_closed(tmp_path, unsupported_type):
    """A single order lookup must never resolve a compound mutation."""
    with Database(str(tmp_path / f"unsupported-{unsupported_type.replace('-', '_')}.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(
            client_id=f"compound-{unsupported_type.replace('-', '_')}",
            order_type=unsupported_type,
            purpose="CAMPAIGN_EXIT",
        )
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "799",
            "clientOrderId": intent.client_order_id, "status": "CANCELED",
            "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
        }
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        row = _row(db, intent)
        assert result["deferred"] == 1
        assert row["status"] == "AMBIGUOUS"
        assert "unsupported" in row["last_reconcile_error"]
        assert db.has_unresolved_execution_intents()
        assert exchange.lookup_count == 0
        assert exchange.mutation_count == 0


def test_bl004_synthetic_secret_sentinel_is_absent_from_real_sqlite_and_public_exception(tmp_path):
    """Scan actual temporary SQLite contents after an ambiguous submit fault."""
    sentinel = "SYNTHETIC_BL004_SENTINEL_API_SECRET_SIGNATURE_AUTH_QUERY_9f41d2"
    with Database(str(tmp_path / "bl004-sentinel.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="bl004-safe-client")
        def accepted_then_secret_error():
            exchange.mutation_count += 1
            exchange.orders[intent.client_order_id] = {
                "symbol": "BTCUSDT", "side": "BUY", "orderId": "804",
                "clientOrderId": intent.client_order_id, "status": "NEW",
                "origQty": "0.010", "executedQty": "0", "price": "0", "avgPrice": "0",
            }
            raise TimeoutError(f"synthetic transport diagnostic {sentinel}")

        with pytest.raises(RuntimeError) as caught:
            ExecutionBarrier(ContextCache(), db).execute(intent, accepted_then_secret_error)
        assert sentinel not in str(caught.value)
        assert caught.value.__cause__ is None
        assert caught.value.__context__ is None
        persisted_dump = "\\n".join(db.conn.iterdump())
        assert sentinel not in persisted_dump
        assert exchange.mutation_count == 1
        assert db.has_unresolved_execution_intents()



def test_fi_09_missing_trade_id_does_not_reconcile(tmp_path):
    """Missing execution identity is not repaired by matching aggregate quantity."""
    with Database(str(tmp_path / "fi09-missing-trade-id.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi09-missing-trade-id")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "790",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100", "avgPrice": "100",
        }
        exchange.trade_rows["790"] = [{
            "orderId": "790", "qty": "0.010", "price": "100", "quoteQty": "1.0", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False
        }]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert "trade ID" in _row(db, intent)["last_reconcile_error"]
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0


def test_fi_09_duplicate_trade_ids_do_not_reconcile(tmp_path):
    """Duplicate execution IDs are rejected even if aggregate quantity matches."""
    with Database(str(tmp_path / "fi09-duplicate-trade-id.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi09-duplicate-trade-id")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "791",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100", "avgPrice": "100",
        }
        exchange.trade_rows["791"] = [
            {"id": "duplicate-trade-id", "orderId": "791", "qty": "0.005", "price": "100", "quoteQty": "0.5", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False},
            {"id": "duplicate-trade-id", "orderId": "791", "qty": "0.005", "price": "100", "quoteQty": "0.5", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False},
        ]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert "duplicate trade IDs" in _row(db, intent)["last_reconcile_error"]
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0


def test_fi_09_trade_history_at_limit_is_not_assumed_complete(tmp_path):
    """A 1000-row bounded response is conservatively treated as potentially truncated."""
    with Database(str(tmp_path / "fi09-truncated-trade-history.sqlite3")) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi09-truncated-trades")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "792",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100", "avgPrice": "100",
        }
        exchange.trade_rows["792"] = [
            {"id": f"trade-792-{i}", "orderId": "792", "qty": "0.00001",
             "price": "100", "quoteQty": "0.001", "commission": "0", "commissionAsset": "BNB", "time": 1760000000000, "isBuyer": True, "isMaker": False}
            for i in range(1000)
        ]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        assert result["deferred"] == 1
        assert _row(db, intent)["status"] == "AMBIGUOUS"
        assert "completeness is unproven" in _row(db, intent)["last_reconcile_error"]
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0



@pytest.mark.parametrize(("trade_updates", "expected_error"), [
    ({"quoteQty": "9.99"}, "quote quantity conflicts"),
    ({"isBuyer": False}, "buyer flag conflicts"),
    ({"commissionAsset": ""}, "lacks required execution evidence"),
])
def test_fi_09_contradictory_or_incomplete_trade_fields_fail_closed(tmp_path, trade_updates, expected_error):
    with Database(str(tmp_path / ("fi09-invalid-" + expected_error.replace(" ", "_") + ".sqlite3"))) as db:
        exchange = DeterministicFakeExchange()
        intent = _new_intent(client_id="fi09-invalid-trade-fields")
        _persist_ambiguous(db, intent)
        exchange.orders[intent.client_order_id] = {
            "symbol": "BTCUSDT", "side": "BUY", "orderId": "793",
            "clientOrderId": intent.client_order_id, "status": "FILLED",
            "origQty": "0.010", "executedQty": "0.010", "price": "100", "avgPrice": "100",
        }
        trade = {
            "id": "trade-793-1", "orderId": "793", "qty": "0.010",
            "price": "100", "quoteQty": "1.0", "commission": "0",
            "commissionAsset": "BNB", "time": 1760000000000,
            "isBuyer": True, "isMaker": False,
        }
        trade.update(trade_updates)
        exchange.trade_rows["793"] = [trade]
        result = _worker(db, exchange).run_once(now=time.time() + 10)
        row = _row(db, intent)
        assert result["deferred"] == 1
        assert row["status"] == "AMBIGUOUS"
        assert expected_error in row["last_reconcile_error"]
        assert db.has_unresolved_execution_intents()
        assert exchange.mutation_count == 0
