"""Regression contracts for WILLIAMS BOT 2.1 TASK 001.

These are offline tests only: every callback is a fake and no Binance order is sent.
"""
import ast
import time
from pathlib import Path

from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext
from campaign_model import SignalRole, SignalSpec, SignalType, CampaignState
from pending_signal import PendingSignal


def _context_cache(*, close_ms=None, allow_long=True, allow_short=False):
    cache = ContextCache()
    now = int(time.time() * 1000)
    cache.publish(
        TFMarketContext(
            symbol="BTCUSDT",
            interval="1h",
            version=0,
            candle_open_time_ms=(close_ms or now) - 3_600_000,
            candle_close_time_ms=close_ms or now,
            price=100.0,
            allow_long=allow_long,
            allow_short=allow_short,
            decision="LONG" if allow_long else "NO_TRADE",
        )
    )
    return cache


def _entry_intent(cache):
    ctx = cache.snapshot().context("BTCUSDT", "1h")
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_LOSS",
        required_context_versions={"1h": ctx.version},
        purpose="ENTRY",
        permission_interval="1h",
        client_order_id="TASK001_OFFLINE_ENTRY",
    )


def _ready_db(tmp_path):
    db = Database(str(tmp_path / "task001.sqlite3"))
    # This fixture explicitly models a completed clean exchange reconciliation.
    db.state_set("position_state", "FLAT")
    db.state_set("position_state:BTCUSDT", "FLAT")
    return db


def test_entry_requires_current_versioned_permission_context(tmp_path):
    cache = _context_cache()
    db = _ready_db(tmp_path)
    barrier = ExecutionBarrier(cache, db)
    intent = _entry_intent(cache)
    called = []
    result = barrier.execute(intent, lambda: called.append(True) or {
        "status": "NEW", "executedQty": "0"
    })
    assert result.accepted is True
    assert called == [True]


def test_stale_or_missing_context_blocks_entry_without_exchange_call(tmp_path):
    stale = _context_cache(close_ms=int(time.time() * 1000) - 3_700_000)
    db = _ready_db(tmp_path)
    barrier = ExecutionBarrier(stale, db)
    intent = _entry_intent(stale)
    called = []
    result = barrier.execute(intent, lambda: called.append(True) or {})
    assert result.accepted is False
    assert "stale" in result.reason.lower()
    assert called == []

    empty_cache = ContextCache()
    result2 = ExecutionBarrier(empty_cache, db).execute(
        OrderIntent.new(
            "BTCUSDT", "BUY", "MARKET",
            required_context_versions={"1h": 1},
            purpose="ENTRY", permission_interval="1h",
        ),
        lambda: called.append(True) or {},
    )
    assert result2.accepted is False
    assert called == []


def test_unknown_and_reconcile_required_block_entries_but_not_exits(tmp_path):
    cache = _context_cache()
    db = _ready_db(tmp_path)
    barrier = ExecutionBarrier(cache, db)
    db.state_set("position_state", "RECONCILE_REQUIRED")

    called = []
    blocked = barrier.execute(_entry_intent(cache), lambda: called.append("BUY") or {})
    assert blocked.accepted is False
    assert blocked.reason == "RECONCILE_REQUIRED"
    assert called == []

    exit_intent = OrderIntent.new(
        "BTCUSDT", "SELL", "MARKET", required_context_versions={},
        purpose="CAMPAIGN_EXIT", client_order_id="TASK001_OFFLINE_EXIT",
    )
    allowed = barrier.execute(
        exit_intent,
        lambda: called.append("SELL") or {"status": "FILLED", "executedQty": "1"},
    )
    assert allowed.accepted is True
    assert called == ["SELL"]


def test_missing_global_state_is_unknown_not_flat(tmp_path):
    cache = _context_cache()
    db = Database(str(tmp_path / "unknown-state.sqlite3"))
    db.state_set("position_state:BTCUSDT", "FLAT")
    result = ExecutionBarrier(cache, db).execute(
        _entry_intent(cache), lambda: (_ for _ in ()).throw(AssertionError("must not submit"))
    )
    assert result.accepted is False
    assert result.reason == "UNKNOWN"


def test_failed_intent_persistence_prevents_exchange_mutation():
    class BrokenDB:
        def save_execution_intent(self, *_args, **_kwargs):
            raise OSError("disk full")

    cache = _context_cache()
    barrier = ExecutionBarrier(cache, BrokenDB())
    called = []
    result = barrier.execute(_entry_intent(cache), lambda: called.append(True) or {})
    assert result.accepted is False
    assert "persistence_failed" in result.reason
    assert called == []


def test_pending_signal_is_not_an_exchange_order_or_open_position():
    spec = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=1,
        trigger_price=101.0, protective_reference=97.0,
        context_versions={"1h": 1},
    )
    pending = PendingSignal.from_spec(spec, campaign_id="c1")
    payload = pending.to_dict()
    assert payload["state"] == "DETECTED"
    assert "order_id" not in payload
    assert "executed_qty" not in payload
    assert "position_qty" not in payload
    assert CampaignState.OPEN_INITIAL.value != pending.state


def test_legacy_spot_mutations_are_submitted_through_common_wrapper():
    source = Path(__file__).with_name("portfolio_trader.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    mutations = {"order_safe", "create_oco_sell", "create_oco_sell_safe", "cancel_oco"}
    violations = []

    class MutationVisitor(ast.NodeVisitor):
        def __init__(self):
            self.parents = []

        def generic_visit(self, node):
            self.parents.append(node)
            super().generic_visit(node)
            self.parents.pop()

        def visit_Call(self, node):
            func = node.func
            if isinstance(func, ast.Attribute) and func.attr in mutations:
                in_lambda = any(isinstance(parent, ast.Lambda) for parent in self.parents)
                under_barrier_helper = any(
                    isinstance(parent, ast.Call)
                    and isinstance(parent.func, ast.Attribute)
                    and parent.func.attr == "_submit_via_execution_barrier"
                    for parent in self.parents
                )
                if not (in_lambda and under_barrier_helper):
                    violations.append((func.attr, node.lineno))
            self.generic_visit(node)

    MutationVisitor().visit(tree)
    assert violations == [], f"uncontrolled Spot mutation paths: {violations}"


def test_server_trader_and_mtf_service_share_the_same_context_cache(monkeypatch):
    import server

    assert server.mtf_service.cache is server.context_cache

    captured = {}

    class StubTrader:
        def __init__(self, **kwargs):
            captured.update(kwargs)
            self.context_cache = kwargs["context_cache"]

    monkeypatch.setattr(server, "Trader", StubTrader)
    monkeypatch.setattr(server.state, "trader", None)
    created = server.state.ensure_trader()
    assert created.context_cache is server.context_cache
    assert captured["context_cache"] is server.mtf_service.cache


def test_android_spot_rest_mutations_have_one_fail_closed_boundary():
    source = Path(__file__).parent.joinpath(
        "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
    ).read_text(encoding="utf-8")
    wrapper_start = source.index("    private fun signedRequest(")
    perform_start = source.index("    private fun performSignedRequest(", wrapper_start)
    wrapper = source[wrapper_start:perform_start]
    assert "authorizeExchangeMutation(normalizedMethod, path, params)" in wrapper
    assert "auditStore.beginMutationIntent(" in wrapper
    assert wrapper.index("auditStore.beginMutationIntent(") < wrapper.index(
        "val response = performSignedRequest(normalizedMethod, path, params)"
    )
    assert '"UNKNOWN"' in wrapper
    assert "setReconcileRequired(" in wrapper
    # The only OkHttp POST and DELETE primitives stay inside the guarded transport.
    assert source.count(".post(") == 1
    assert source.count(".delete()") == 1
    assert "else -> error(\"Unapproved Binance mutation route:" in wrapper
    assert "private fun reconcileUnresolvedMutationIntents()" in source
    assert "unresolvedMutationIntents()" in source
    assert "resolveUnresolvedMutationIntents" not in source
    assert 'signedGet("/api/v3/order", query)' in source
    assert 'signedGet("/api/v3/orderList", query)' in source
    runtime_path = Path(__file__).parent / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
    for other in (Path(__file__).parent / "app/src/main/java").rglob("*.kt"):
        if other == runtime_path:
            continue
        text = other.read_text(encoding="utf-8")
        assert "/api/v3/orderList/oco" not in text, f"alternate OCO writer: {other}"
        assert "/api/v3/order/cancelReplace" not in text, f"alternate cancelReplace writer: {other}"
    # Legacy direct MARKET BUY implementations must remain tombstoned, not hidden
    # after a guard clause where they could later be re-enabled accidentally.
    legacy_start = source.index("    private fun executeBuyWithProtection(")
    legacy_end = source.index("\n    private fun createProtection(", legacy_start)
    assert "signedPost(" not in source[legacy_start:legacy_end]


def test_spot_client_and_release_contract_do_not_alias_futures_routes():
    spot_client = Path(__file__).with_name("binance_client.py").read_text(encoding="utf-8")
    server = Path(__file__).with_name("server.py").read_text(encoding="utf-8")
    gate = Path(__file__).with_name("ci_release_gate.py").read_text(encoding="utf-8")
    assert "/api/v3/order" in spot_client
    assert "/fapi/" not in spot_client and "/dapi/" not in spot_client
    assert "TESTNET" in server and "testnet" in server.lower()
    assert "EXPECTED_VERSION = '4.25.0'" in gate


def test_global_flat_does_not_authorize_a_symbol_with_no_state_key(tmp_path):
    cache = _context_cache()
    db = Database(str(tmp_path / "missing-symbol-state.sqlite3"))
    db.state_set("position_state", "FLAT")
    called = []
    result = ExecutionBarrier(cache, db).execute(
        _entry_intent(cache),
        lambda: called.append(True) or {"status": "NEW"},
    )
    assert result.accepted is False
    assert result.reason == "BTCUSDT:UNKNOWN"
    assert called == []


def test_campaign_entry_needs_persisted_campaign_state(tmp_path):
    cache = _context_cache()
    db = _ready_db(tmp_path)
    barrier = ExecutionBarrier(cache, db)
    ctx = cache.snapshot().context("BTCUSDT", "1h")
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_LOSS",
        required_context_versions={"1h": ctx.version},
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        campaign_id="missing-campaign-state",
        signal_id="BTCUSDT:1h:REVERSAL:123",
        client_order_id="TASK001_OFFLINE_CAMPAIGN",
    )
    called = []
    result = barrier.execute(intent, lambda: called.append(True) or {"status": "NEW"})
    assert result.accepted is False
    assert result.reason == "campaign_unknown"
    assert called == []


def test_all_backend_order_mutators_converge_on_an_execution_boundary():
    import ast

    root = Path(__file__).parent
    mutation_methods = {
        "order_safe", "order", "new_order", "create_oco_sell",
        "create_oco_sell_safe", "cancel_order", "cancel_open_orders",
        "cancel_replace", "cancel_oco",
    }
    wrappers = {"execute", "_submit", "_submit_via_execution_barrier"}
    violations = []

    class Visitor(ast.NodeVisitor):
        def __init__(self, path):
            self.path = path
            self.parents = []

        def generic_visit(self, node):
            self.parents.append(node)
            super().generic_visit(node)
            self.parents.pop()

        def visit_Call(self, node):
            if isinstance(node.func, ast.Attribute) and node.func.attr in mutation_methods:
                in_lambda = any(isinstance(parent, ast.Lambda) for parent in self.parents)
                inside_wrapper = any(
                    isinstance(parent, ast.Call)
                    and isinstance(parent.func, ast.Attribute)
                    and parent.func.attr in wrappers
                    for parent in self.parents
                )
                if not (in_lambda and inside_wrapper):
                    violations.append(
                        (self.path.name, node.lineno, node.func.attr)
                    )
            self.generic_visit(node)

    for path in root.glob("*.py"):
        if path.name.startswith("test_") or path.name in {
            "binance_client.py", "execution_barrier.py"
        }:
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except (SyntaxError, UnicodeDecodeError):
            raise
        Visitor(path).visit(tree)

    assert violations == [], f"backend mutation call sites outside barrier: {violations}"
