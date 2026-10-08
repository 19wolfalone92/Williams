import threading

import pytest

from binance_client import BinanceAPIError
from db import Database
from domain.contracts import (
    ExecutionIntent as CanonicalExecutionIntent,
    ProofVector,
    RiskDecision,
    SignalDirection,
    WilliamsDecision,
)
from execution_barrier import ExecutionAmbiguousError, ExecutionBarrier, OrderIntent


class FakeContext:
    def __init__(self, version=1):
        self.version = version
        self.allow_long = True
        self.allow_short = False


class FakeSnapshot:
    def context(self, symbol, interval):
        return FakeContext(1)


class FakeCache:
    def __init__(self):
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return FakeSnapshot()


def order_intent(client_id="WILL_TEST_001", purpose="CAMPAIGN_ENTRY"):
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_LOSS",
        {},
        client_order_id=client_id,
        purpose=purpose,
        permission_interval="5m",
    )


def test_proof_vector_is_complete_and_score_is_diagnostic():
    proof = ProofVector(*(True for _ in range(8)))
    assert proof.is_fully_proven is True
    assert proof.diagnostic_score == 1.0
    with pytest.raises(Exception):
        proof.context_pass = False


def test_canonical_decision_and_risk_contracts_are_immutable():
    proof = ProofVector(True, True, True, True, True, True, True, True)
    decision = WilliamsDecision(
        timestamp=1,
        symbol="btcusdt",
        direction=SignalDirection.LONG,
        wise_man_stage=1,
        trigger_price=101.0,
        invalidation_price=97.0,
        proof_vector=proof,
        context_regime="BULLISH",
    )
    risk = RiskDecision(
        williams_decision=decision,
        approved=True,
        allocated_r_multiple=0.25,
        calculated_quantity=0.1,
        max_allowed_slippage=0.001,
    )
    intent = CanonicalExecutionIntent(
        risk_decision=risk,
        order_type="STOP_LOSS",
        client_order_id="WILL_CAMP_9A72_WM1_TEST",
        recv_window=5000,
        time_in_force="GTC",
        reduce_only=False,
    )
    assert decision.symbol == "BTCUSDT"
    assert decision.to_dict()["direction"] == "LONG"
    assert risk.to_dict()["approved"] is True
    assert intent.order_type == "STOP_LOSS"
    with pytest.raises(Exception):
        intent.client_order_id = "OTHER"


def test_execution_barrier_accepts_first_client_order_id_and_blocks_duplicate(tmp_path):
    db = Database(str(tmp_path / "barrier.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    first = order_intent("WILL_TEST_DUP")
    calls = []

    accepted = barrier.execute(first, lambda: calls.append(1) or {
        "symbol": "BTCUSDT",
        "status": "NEW",
        "orderId": 123,
        "clientOrderId": "WILL_TEST_DUP",
        "executedQty": "0",
    })
    assert accepted.accepted is True
    assert calls == [1]

    second = barrier.execute(first, lambda: calls.append(2) or {
        "symbol": "BTCUSDT",
        "status": "NEW",
    })
    assert second.accepted is False
    assert "duplicate client_order_id" in second.reason
    assert calls == [1]


def test_ambiguous_submission_locks_mutations_and_requires_reconciliation(tmp_path):
    db = Database(str(tmp_path / "unknown.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_001")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                BinanceAPIError("timeout", unknown_execution=True)
            ),
        )

    assert barrier.mutation_locked is True
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY) is not None

    blocked = barrier.execute(
        order_intent("WILL_AFTER_UNKNOWN"),
        lambda: {
            "symbol": "BTCUSDT",
            "status": "NEW",
        },
    )
    assert blocked.accepted is False
    assert "MUTATION_LOCKED_RECONCILIATION_REQUIRED" in blocked.reason

    reconciled = barrier.reconcile(
        intent,
        lambda: {
            "symbol": "BTCUSDT",
            "status": "FILLED",
            "orderId": 123,
            "clientOrderId": "WILL_UNKNOWN_001",
            "executedQty": "0.1",
        },
    )
    assert reconciled.accepted is True
    assert reconciled.reason == "FILLED"
    assert barrier.mutation_locked is False


def test_not_found_reconciliation_releases_lock_without_post_retry(tmp_path):
    db = Database(str(tmp_path / "not-found.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_002")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                TimeoutError("network timeout")
            ),
        )

    calls = []
    reconciled = barrier.reconcile(intent, lambda: calls.append("GET") or None)
    assert reconciled.accepted is True
    assert reconciled.reason == "NOT_FOUND"
    assert calls == ["GET"]
    assert barrier.mutation_locked is False


def test_deterministic_binance_rejection_does_not_lock_mutations(tmp_path):
    db = Database(str(tmp_path / "reject.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_REJECT_001")

    result = barrier.execute(
        intent,
        lambda: (_ for _ in ()).throw(
            BinanceAPIError("invalid order", unknown_execution=False, status_code=400)
        ),
    )
    assert result.accepted is False
    assert barrier.mutation_locked is False

def test_core_returns_canonical_decision_without_exposing_exchange():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from digital_williams_core import CoreComposition, DigitalWilliamsCore

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    result = DigitalWilliamsCore().compose([signal], now_ms=150)
    assert isinstance(result, CoreComposition)
    assert result.decision is not None
    assert result.decision.direction is SignalDirection.LONG
    assert result.decision.wise_man_stage == 1
    assert result.decision.proof_vector.price_proof_pass is False
    assert result.action == "ARM_ENTRY"


def test_order_state_machine_uses_unknown_for_ambiguous_lifecycle():
    from order_state_machine import OrderState, OrderStateMachine

    fsm = OrderStateMachine()
    fsm.transition(OrderState.PENDING_NEW)
    fsm.mark_unknown()
    assert fsm.state is OrderState.UNKNOWN
    fsm.reconcile("FILLED", executed_qty=1.0)
    assert fsm.state is OrderState.FILLED


def test_execution_barrier_does_not_unlock_on_failed_reconciliation(tmp_path):
    db = Database(str(tmp_path / "failed-reconcile.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_003")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                BinanceAPIError("timeout", unknown_execution=True)
            ),
        )

    failed = barrier.reconcile(
        intent,
        lambda: (_ for _ in ()).throw(
            BinanceAPIError("still unavailable", unknown_execution=False, status_code=503)
        ),
    )
    assert failed.accepted is False
    assert barrier.mutation_locked is True


def test_wm1_can_be_armable_without_wm2_momentum_score():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from proof_engine import WilliamsProofEngine
    from why_not_engine import WhyNotEngine

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    evaluation = WilliamsProofEngine.evaluate(signal)
    assert evaluation.proof_vector.momentum_pass is False
    assert evaluation.armable is True
    assert WhyNotEngine.armable(
        evaluation.proof_vector,
        wise_man_stage=1,
    ) is True
    assert evaluation.hypothesis_proven is False


def test_wm2_requires_momentum_before_add_on_arm():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from proof_engine import WilliamsProofEngine
    from why_not_engine import WhyNotEngine

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        htf_confirmed=True,
        source_candle_index=10,
        wave_confidence=0.0,
    )
    evaluation = WilliamsProofEngine.evaluate(signal)
    assert evaluation.proof_vector.momentum_pass is True
    assert evaluation.armable is True
    assert WhyNotEngine.explain_pre_price(
        evaluation.proof_vector,
        wise_man_stage=2,
    ) == ()


def test_proof_diagnostic_score_never_replaces_missing_gate():
    from domain.contracts import ProofVector
    from why_not_engine import WhyNotEngine

    proof = ProofVector(
        context_pass=True,
        behavior_pass=True,
        structure_pass=True,
        location_pass=True,
        angulation_pass=True,
        momentum_pass=True,
        price_proof_pass=False,
        invalidation_present=True,
    )
    assert proof.diagnostic_score == 7 / 8
    assert proof.is_fully_proven is False
    assert WhyNotEngine.explain_full(proof) == ("price_proof_not_triggered",)


def test_williams_core_has_no_binance_dependency():
    import inspect
    import digital_williams_core

    source = inspect.getsource(digital_williams_core)
    assert "BinanceSpotClient" not in source
    assert "BinanceDataContract" not in source


def test_campaign_order_fsm_has_separate_reconciliation_interrupts():
    from campaign_order_fsm import CampaignOrderState, CampaignOrderStateMachine

    fsm = CampaignOrderStateMachine()
    for state in (
        CampaignOrderState.CONTEXT_READY,
        CampaignOrderState.SETUP_IDENTIFIED,
        CampaignOrderState.ARMED,
        CampaignOrderState.ENTRY_PENDING,
        CampaignOrderState.TRIGGERED,
        CampaignOrderState.INITIAL_POSITION,
        CampaignOrderState.PROTECTED,
        CampaignOrderState.CAMPAIGN_ACTIVE,
    ):
        fsm.transition(state)
    fsm.require_reconciliation("ambiguous exchange mutation")
    assert fsm.interrupted is True

    with pytest.raises(ValueError):
        fsm.transition(CampaignOrderState.EXIT_PENDING)

    fsm.reconcile_to(CampaignOrderState.CAMPAIGN_ACTIVE)
    assert fsm.state is CampaignOrderState.CAMPAIGN_ACTIVE

    fsm.fault("unresolvable state mismatch")
    assert fsm.state is CampaignOrderState.FAULT
    with pytest.raises(ValueError):
        fsm.reconcile_to(CampaignOrderState.NO_IDEA)


def test_campaign_order_fsm_rejects_illegal_operational_jump():
    from campaign_order_fsm import CampaignOrderState, CampaignOrderStateMachine

    fsm = CampaignOrderStateMachine(CampaignOrderState.NO_IDEA)
    with pytest.raises(ValueError):
        fsm.transition(CampaignOrderState.CAMPAIGN_ACTIVE)


def test_campaign_engine_persists_canonical_lifecycle_state(tmp_path):
    from campaign_engine import CampaignEngine
    from campaign_model import SignalRole, SignalSpec, SignalType
    from campaign_order_fsm import CampaignOrderState

    db = Database(str(tmp_path / "campaign.sqlite3"))
    engine = CampaignEngine(db)
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=1000,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
    assert engine.canonical_state(campaign) is CampaignOrderState.SETUP_IDENTIFIED

    engine.arm_entry(campaign, signal)
    assert engine.canonical_state(campaign) is CampaignOrderState.ENTRY_PENDING

    engine.mark_triggered(campaign, signal.signal_id, "123")
    assert engine.canonical_state(campaign) is CampaignOrderState.TRIGGERED

    engine.record_initial_fill(
        campaign,
        quantity=0.1,
        average_entry_price=100.5,
        initial_stop_price=97.0,
        fill_order_id="123",
        risk_quote=10.0,
    )
    assert engine.canonical_state(campaign) is CampaignOrderState.PROTECTED

    reloaded = engine.load_campaign(campaign.campaign_id)
    assert reloaded is not None
    assert engine.canonical_state(reloaded) is CampaignOrderState.PROTECTED


def test_campaign_engine_reconciliation_interrupt_blocks_normal_state_progression(tmp_path):
    from campaign_engine import CampaignEngine
    from campaign_model import SignalRole, SignalSpec, SignalType
    from campaign_order_fsm import CampaignOrderState

    db = Database(str(tmp_path / "campaign-reconcile.sqlite3"))
    engine = CampaignEngine(db)
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=1000,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
    engine.mark_reconcile_required(campaign, "exchange/local mismatch")
    assert engine.canonical_state(campaign) is CampaignOrderState.RECONCILIATION_REQUIRED
    with pytest.raises(ValueError):
        engine.arm_entry(campaign, signal)


def _sample_williams_decision():
    proof = ProofVector(
        context_pass=True,
        behavior_pass=True,
        structure_pass=True,
        location_pass=True,
        angulation_pass=True,
        momentum_pass=False,
        price_proof_pass=False,
        invalidation_present=True,
    )
    return WilliamsDecision(
        timestamp=1,
        symbol="BTCUSDT",
        direction=SignalDirection.LONG,
        wise_man_stage=1,
        trigger_price=101.0,
        invalidation_price=97.0,
        proof_vector=proof,
        context_regime="BULLISH_AWAKE",
    )


def test_canonical_risk_decision_preserves_williams_and_is_bounded():
    from risk_engine import (
        CanonicalRiskEngine,
        RiskPolicy,
        canonical_risk_preserves_williams_decision,
    )

    decision = _sample_williams_decision()
    engine = CanonicalRiskEngine(
        RiskPolicy(
            campaign_risk_fraction=0.005,
            max_position_fraction=0.25,
            max_allowed_slippage=0.001,
            fee_buffer_per_side=0.001,
        )
    )
    result = engine.approve(
        decision,
        equity_quote=10_000.0,
        remaining_campaign_risk_quote=20.0,
    )
    assert result.approved is True
    assert result.williams_decision is decision
    assert canonical_risk_preserves_williams_decision(decision, result) is True
    assert 0.0 < result.allocated_r_multiple <= 0.4
    assert result.calculated_quantity > 0
    assert decision.proof_vector.price_proof_pass is False


def test_canonical_risk_engine_rejects_invalid_directional_invalidation():
    from risk_engine import CanonicalRiskEngine

    decision = WilliamsDecision(
        timestamp=1,
        symbol="BTCUSDT",
        direction=SignalDirection.LONG,
        wise_man_stage=1,
        trigger_price=101.0,
        invalidation_price=102.0,
        proof_vector=ProofVector(True, True, True, True, True, False, False, True),
        context_regime="BULLISH",
    )
    result = CanonicalRiskEngine().approve(
        decision,
        equity_quote=10_000.0,
    )
    assert result.approved is False
    assert "invalidation" in result.rejection_reason


def test_persisted_unknown_intent_survives_new_barrier_instance(tmp_path):
    db = Database(str(tmp_path / "persisted-unknown.sqlite3"))
    first = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_PERSISTED")
    from dataclasses import replace
    intent = replace(intent, related_order_id="991")

    with pytest.raises(ExecutionAmbiguousError):
        first.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                TimeoutError("transport timeout")
            ),
        )

    second = ExecutionBarrier(FakeCache(), db)
    restored = second.persisted_unknown_intent()
    assert restored is not None
    assert restored.client_order_id == "WILL_UNKNOWN_PERSISTED"
    assert restored.related_order_id == "991"

    reconciled = second.reconcile_persisted_unknown(
        lambda restored_intent: {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "status": "NEW",
            "orderId": 991,
            "clientOrderId": restored_intent.client_order_id,
            "executedQty": "0",
        }
    )
    assert reconciled.accepted is True
    assert reconciled.reason == "NEW"
    assert second.mutation_locked is False


def test_reconciliation_rejects_wrong_exchange_order_identity(tmp_path):
    db = Database(str(tmp_path / "identity.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_IDENTITY")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                TimeoutError("transport timeout")
            ),
        )

    wrong = barrier.reconcile(
        intent,
        lambda: {
            "symbol": "ETHUSDT",
            "side": "BUY",
            "status": "NEW",
            "orderId": 555,
            "clientOrderId": "WILL_SOME_OTHER_ORDER",
        },
    )
    assert wrong.accepted is False
    assert barrier.mutation_locked is True
    assert "mismatch" in wrong.reason


def test_ambiguous_cancel_unlocks_only_after_target_is_terminal(tmp_path):
    db = Database(str(tmp_path / "cancel-unknown.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "CANCEL",
        {},
        client_order_id="WILL_CANCEL_OPERATION",
        related_order_id="777",
        purpose="CAMPAIGN_PROTECTION_CANCEL",
    )

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                TimeoutError("transport timeout")
            ),
        )

    still_open = barrier.reconcile(
        intent,
        lambda: {
            "symbol": "BTCUSDT",
            "side": "SELL",
            "status": "NEW",
            "orderId": 777,
            "clientOrderId": "WILL_STOP_TARGET",
        },
    )
    assert still_open.accepted is False
    assert barrier.mutation_locked is True

    done = barrier.reconcile(
        intent,
        lambda: {
            "symbol": "BTCUSDT",
            "side": "SELL",
            "status": "CANCELED",
            "orderId": 777,
            "clientOrderId": "WILL_STOP_TARGET",
        },
    )
    assert done.accepted is True
    assert done.reason == "CANCELED"
    assert barrier.mutation_locked is False


def test_order_intent_canonical_adapter_preserves_signal_identity():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from digital_williams_core import DigitalWilliamsCore
    from execution_barrier import OrderIntent
    from risk_engine import CanonicalRiskEngine

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    composition = DigitalWilliamsCore().evaluate_signal(signal, campaign_id="C1")
    risk = CanonicalRiskEngine().approve(
        composition.decision,
        equity_quote=10_000.0,
    )
    intent = __import__(
        "domain.contracts",
        fromlist=["ExecutionIntent"],
    ).ExecutionIntent(
        risk_decision=risk,
        order_type="STOP_LOSS",
        client_order_id="WILL_C1_WM1",
        recv_window=5000,
        time_in_force="GTC",
        reduce_only=False,
    )
    order_intent = OrderIntent.from_canonical(
        intent,
        campaign_id="C1",
        signal_id=signal.signal_id,
        purpose="CAMPAIGN_ENTRY",
        permission_interval="5m",
    )
    assert order_intent.signal_id == signal.signal_id
    assert order_intent.campaign_id == "C1"


def test_campaign_fault_is_terminal_and_cannot_reenter_reconciliation():
    from campaign_order_fsm import CampaignOrderState, CampaignOrderStateMachine

    fsm = CampaignOrderStateMachine(CampaignOrderState.CAMPAIGN_ACTIVE)
    fsm.fault("unresolvable mismatch")
    assert fsm.state is CampaignOrderState.FAULT

    with pytest.raises(ValueError):
        fsm.transition(CampaignOrderState.RECONCILIATION_REQUIRED)

    assert not __import__("campaign_order_fsm").campaign_order_transition_allowed(
        CampaignOrderState.FAULT,
        CampaignOrderState.RECONCILIATION_REQUIRED,
    )


def test_order_reconciliation_accepts_filled_after_missed_user_stream_event():
    from order_state_machine import OrderState, OrderStateMachine

    fsm = OrderStateMachine(OrderState.NEW)
    assert fsm.observe_exchange_status("FILLED", executed_qty=1.0) is OrderState.FILLED
    assert fsm.terminal is True


def test_order_reconciliation_accepts_cancel_after_missed_user_stream_event():
    from order_state_machine import OrderState, OrderStateMachine

    fsm = OrderStateMachine(OrderState.NEW)
    assert fsm.observe_exchange_status("CANCELED", executed_qty=0.0) is OrderState.CANCELED


def test_deterministic_client_order_id_is_stable_and_compact():
    from order_identity import deterministic_client_order_id

    a = deterministic_client_order_id(
        "ENTRY",
        "CAMP-123",
        "WM1",
        "BTCUSDT:5m:REVERSAL:1000",
    )
    b = deterministic_client_order_id(
        "ENTRY",
        "CAMP-123",
        "WM1",
        "BTCUSDT:5m:REVERSAL:1000",
    )
    c2 = deterministic_client_order_id(
        "ENTRY",
        "CAMP-123",
        "WM1",
        "BTCUSDT:5m:REVERSAL:1001",
    )
    assert a == b
    assert a != c2
    assert len(a) <= 36
    assert a.startswith("WILLV5_ENTRY_")


def test_fault_persists_williams_error_report(tmp_path, monkeypatch):
    from campaign_engine import CampaignEngine
    from campaign_model import SignalRole, SignalSpec, SignalType
    from campaign_order_fsm import CampaignOrderState
    import json

    monkeypatch.setenv("WILLIAMS_ERROR_REPORT_DIR", str(tmp_path / "reports"))
    db = Database(str(tmp_path / "fault.sqlite3"))
    engine = CampaignEngine(db)
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=1000,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
    engine.mark_fault(campaign, "unresolvable exchange/local mismatch")
    assert engine.canonical_state(campaign) is CampaignOrderState.FAULT
    path = campaign.tags.get("williams_error_report_path")
    assert path
    with open(path, "r", encoding="utf-8") as handle:
        report = json.load(handle)
    assert report["severity"] == "CRITICAL"
    assert report["component"] == "CampaignEngine"
    assert report["runtime_snapshot"]["campaign_id"] == campaign.campaign_id


def test_execution_barrier_blocks_ambiguous_mutation_after_restart(tmp_path):
    db = Database(str(tmp_path / "restart-lock.sqlite3"))
    first = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_RESTART_UNKNOWN")

    with pytest.raises(ExecutionAmbiguousError):
        first.execute(
            intent,
            lambda: (_ for _ in ()).throw(TimeoutError("transport timeout")),
        )

    second = ExecutionBarrier(FakeCache(), db)
    blocked = second.execute(
        order_intent("WILL_NEW_AFTER_RESTART"),
        lambda: {
            "symbol": "BTCUSDT",
            "status": "NEW",
            "clientOrderId": "WILL_NEW_AFTER_RESTART",
        },
    )
    assert blocked.accepted is False
    assert "MUTATION_LOCKED_RECONCILIATION_REQUIRED" in blocked.reason
