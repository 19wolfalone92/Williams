"""Offline causal audit: deterministic, no live Binance or private diagnostics."""
import importlib.util
from pathlib import Path


SCRIPT=Path(__file__).resolve().parents[1]/"tools"/"audit_tc2_diagnostics.py"
spec=importlib.util.spec_from_file_location("tc2_audit",SCRIPT)
audit=importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def make_trade(direction="SHORT", reason="OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"):
    at=1800000000000
    return {
        "symbol":"SOLUSDT","status":"CLOSED","direction":direction,
        "opened_at":at+3600000+15000,"closed_at":at+7200000+20000,
        "entry_price":99.9,"exit_price":100.4,
        "net_pnl_usdt":-0.5,"fee_known":True,"exit_reason":reason,
        "entry_snapshot":{
            "strategy_profile":"TC2_THREE_WISE_MEN",
            "signal_type":"SHORT_REVERSAL",
            "signal_bar_time_ms":at,
            "entry_price":"99.9",
            "initial_protective_stop":"105.1",
            "wave_context_at_entry":""
        }
    }


def test_archive_observes_opposition_without_claiming_stop_fill():
    at=1800000000000
    t=make_trade()
    trace={"candles":[[at-3600000,103.,104.,101.,103.],
                      [at,103.,105.,100.,100.2],
                      [at+3600000,100.,102.,98.,99.]],
           "order_chain":[{"intent_role":"EXIT","order_status":"FILLED"}],
           "wave_hypotheses":[]}
    item=audit.audit_trade(t,trace)
    assert item["reversal_geometry_valid_without_alligator"]
    assert item["candidate_book_h1_trigger"]==99.9
    assert item["estimated_original_protective_stop"]==105.1
    assert item["exit_class"]=="DISCRETIONARY_OPPOSITION_MARKET_EXIT"
    assert item["exchange_confirmed_exit_intent_in_trace"]
    assert not item["exchange_stop_order_fill_identity_verified"]
    assert not item["angulation_confirmed_from_original_decision"]


def test_reconciled_close_cannot_be_passed_off_as_exchange_stop_fill():
    item=audit.audit_trade(make_trade(reason="EXCHANGE_RECONCILED_CLOSE"),None)
    assert item["exit_class"]=="EXCHANGE_RECONCILED_CLOSE_UNVERIFIED_CAUSE"
    assert "NO_EXCHANGE_STOP_FILL_ID_TO_PROVE_EXIT_CAUSE" in item["limitations"]


def test_aggregate_does_not_invent_fills_or_profits():
    report={
        "created_at":"2026-10-19T16:55:00Z",
        "system_info":{"app_version":"4.34.0-debug"},
        "status":{"trade_observations":[make_trade()]},
        "signal_decision_history":{}
    }
    out=audit.audit_snapshot(report)
    assert out["recorded_net_pnl_usdt"]==-0.5
    assert out["discretionary_opposition_market_exits"]==1
    assert out["closed_trades_found"]==1
    assert "NOT_FULL_EXCHANGE_REPLAY" in out["purpose"]
