from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

import pytest

from daily_risk_accounting import (
    DailyRiskDataError,
    calculate_daily_risk_model_b,
    model_b_snapshot_is_valid,
    reconstruct_midnight_equity,
    reverse_position_at_midnight,
    summarize_income_rows,
)
from db import Database
from equity_breaker import EquityCircuitBreaker


VECTORS = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "utc-risk-comparison-21.json"


def test_replay_exact_previous_21_daily_risk_scenarios_under_model_b():
    fixture = json.loads(VECTORS.read_text(encoding="utf-8"))
    assert fixture["scenarios"] == 21
    assert len(fixture["results"]) == 21
    blocked_for_missing_evidence = 0
    for row in fixture["results"]:
        expected = row["independent_midnight_equity_B"]
        inputs = row["inputs"]
        if expected is None:
            blocked_for_missing_evidence += 1
            continue
        e0 = Decimal(str(inputs["w0"])) + Decimal(str(inputs["u0"]))
        et = (
            Decimal(str(inputs["w0"]))
            + Decimal(str(inputs["realized"]))
            + Decimal(str(inputs["commission"]))
            + Decimal(str(inputs["funding"]))
            + Decimal(str(inputs["external"]))
            + Decimal(str(inputs["u1"]))
        )
        actual = calculate_daily_risk_model_b(e0, et, Decimal(str(inputs["external"])))
        assert actual["start_equity"] == Decimal(str(expected["baseline"])), row["case"]
        assert actual["daily_pnl"] == Decimal(str(expected["pnl"])), row["case"]
        assert abs(actual["loss_pct"] - Decimal(str(expected["loss_pct"]))) < Decimal("1e-24"), row["case"]
        assert actual["entry_blocked"] is (not expected["allow_entry"]), row["case"]
        assert actual["emergency_breaker_tripped"] is expected["emergency_5pct_threshold_reached"], row["case"]
    assert blocked_for_missing_evidence == 4


def test_model_b_handles_exact_three_and_five_percent_boundaries_and_zero_equity():
    assert calculate_daily_risk_model_b("1000", "970.1", "0")["entry_blocked"] is False
    assert calculate_daily_risk_model_b("1000", "970", "0")["entry_blocked"] is True
    assert calculate_daily_risk_model_b("1000", "969.9", "0")["entry_blocked"] is True
    assert calculate_daily_risk_model_b("1000", "950", "0")["emergency_breaker_tripped"] is True
    depleted = calculate_daily_risk_model_b("1000", "0", "0")
    assert depleted["loss_pct"] == Decimal("100")
    assert depleted["entry_blocked"] and depleted["emergency_breaker_tripped"]
    with pytest.raises(DailyRiskDataError):
        calculate_daily_risk_model_b("0", "0", "0")


def test_external_deposit_and_withdrawal_are_neutral_but_fees_remain_pnl():
    rows = [
        {"incomeType": "TRANSFER", "asset": "USDT", "income": "100", "time": 10, "tranId": "1"},
        {"incomeType": "WITHDRAW", "asset": "USDT", "income": "-50", "time": 11, "tranId": "2"},
        {"incomeType": "COMMISSION", "asset": "USDT", "income": "-1", "time": 12, "tranId": "3"},
    ]
    summary = summarize_income_rows(
        rows, day_start_ms=0, end_ms=100, snapshot_window_start_ms=90,
        snapshot_window_end_ms=95,
    )
    assert summary["total_income"] == Decimal("49")
    assert summary["capital_flow"] == Decimal("50")
    result = calculate_daily_risk_model_b("1000", "1049", summary["capital_flow"])
    assert result["daily_pnl"] == Decimal("-1")
    assert result["loss_pct"] == Decimal("0.1")


def test_unknown_non_usdt_or_snapshot_window_income_fails_closed():
    common = {"asset": "USDT", "income": "1", "time": 10, "tranId": "1"}
    with pytest.raises(DailyRiskDataError, match="Unclassified"):
        summarize_income_rows(
            [{**common, "incomeType": "NEW_UNKNOWN_TYPE"}], day_start_ms=0,
            end_ms=100, snapshot_window_start_ms=90, snapshot_window_end_ms=95,
        )
    with pytest.raises(DailyRiskDataError, match="non-USDT"):
        summarize_income_rows(
            [{**common, "asset": "BTC", "incomeType": "REALIZED_PNL"}], day_start_ms=0,
            end_ms=100, snapshot_window_start_ms=90, snapshot_window_end_ms=95,
        )
    with pytest.raises(DailyRiskDataError, match="overlaps"):
        summarize_income_rows(
            [{**common, "time": 92, "incomeType": "COMMISSION"}], day_start_ms=0,
            end_ms=100, snapshot_window_start_ms=90, snapshot_window_end_ms=95,
        )


def test_reverse_position_handles_open_partial_close_full_close_and_flip():
    window = dict(day_start_ms=0, snapshot_window_start_ms=900, snapshot_window_end_ms=950)
    opened_after_midnight = reverse_position_at_midnight(
        "1", "101", [{"id": 1, "time": 100, "symbol": "BTCUSDT", "side": "BUY",
                       "qty": "1", "price": "101", "realizedPnl": "0", "positionSide": "BOTH"}],
        symbol="BTCUSDT", **window,
    )
    assert opened_after_midnight == (Decimal("0"), None)
    partial = reverse_position_at_midnight(
        "0.5", "100", [{"id": 2, "time": 100, "symbol": "BTCUSDT", "side": "SELL",
                         "qty": "0.5", "price": "110", "realizedPnl": "5", "positionSide": "BOTH"}],
        symbol="BTCUSDT", **window,
    )
    assert partial == (Decimal("1.0"), Decimal("100"))
    closed = reverse_position_at_midnight(
        "0", "0", [{"id": 3, "time": 100, "symbol": "BTCUSDT", "side": "SELL",
                     "qty": "1", "price": "110", "realizedPnl": "10", "positionSide": "BOTH"}],
        symbol="BTCUSDT", **window,
    )
    assert closed == (Decimal("1"), Decimal("100"))
    flipped = reverse_position_at_midnight(
        "-0.5", "110", [{"id": 4, "time": 100, "symbol": "BTCUSDT", "side": "SELL",
                          "qty": "1.5", "price": "110", "realizedPnl": "10", "positionSide": "BOTH"}],
        symbol="BTCUSDT", **window,
    )
    assert flipped == (Decimal("1.0"), Decimal("100"))


def test_midnight_equity_reconstruction_uses_historical_mark_and_wallet_ledger_once():
    account = {
        "multiAssetsMargin": False,
        "totalWalletBalance": "1009.7",
        "totalUnrealizedProfit": "10",
        "totalMarginBalance": "1019.7",
    }
    positions = [{
        "symbol": "BTCUSDT", "positionAmt": "0.5", "entryPrice": "100",
        "positionSide": "BOTH",
    }]
    income = [
        {"incomeType": "REALIZED_PNL", "asset": "USDT", "income": "10",
         "time": 100, "tranId": "1", "symbol": "BTCUSDT"},
        {"incomeType": "COMMISSION", "asset": "USDT", "income": "-0.1",
         "time": 110, "tranId": "2", "symbol": "BTCUSDT"},
        {"incomeType": "FUNDING_FEE", "asset": "USDT", "income": "-0.2",
         "time": 120, "tranId": "3", "symbol": "BTCUSDT"},
    ]
    trades = {
        "BTCUSDT": [{
            "id": 9, "time": 130, "symbol": "BTCUSDT", "side": "SELL",
            "qty": "0.5", "price": "120", "realizedPnl": "10",
            "positionSide": "BOTH",
        }]
    }
    snapshot = reconstruct_midnight_equity(
        account, positions, income, trades, {"BTCUSDT": "110"},
        day_start_ms=0, snapshot_window_start_ms=900,
        snapshot_window_end_ms=950, account_identity="account-hash",
        symbols=["BTCUSDT"],
    )
    assert snapshot["wallet_balance_at_midnight_usdt"] == "1000.0"
    assert snapshot["unrealized_pnl_at_midnight_usdt"] == "10.0"
    assert snapshot["equity_at_midnight_usdt"] == "1010.0"
    assert snapshot["positions_at_midnight"][0]["quantity"] == "1.0"
    assert model_b_snapshot_is_valid(snapshot, day_start_ms=0, account_identity="account-hash")
    assert not model_b_snapshot_is_valid(snapshot, day_start_ms=0, account_identity="other-account")
    result = calculate_daily_risk_model_b(snapshot["equity_at_midnight_usdt"], "1019.7", "0")
    assert result["daily_pnl"] == Decimal("9.7")


def test_python_production_breaker_uses_model_b_and_persists_utc_latch():
    db = Database(":memory:")
    breaker = EquityCircuitBreaker(0.03)
    today = "2026-11-25"
    allowed, _ = breaker.check_model_b(
        db, "970.1", "1000", "0", complete=True,
        account_identity="same", expected_account_identity="same", today=today,
    )
    assert allowed
    allowed, reason = breaker.check_model_b(
        db, "970", "1000", "0", complete=True,
        account_identity="same", expected_account_identity="same", today=today,
    )
    assert not allowed and "Model B" in reason
    assert db.state_get("daily_equity_loss_latched_day") == today
    assert db.state_get("daily_equity_loss_latched_model") == "MODEL_B"
    db.close()


def test_python_breaker_rejects_incomplete_or_changed_account_identity():
    db = Database(":memory:")
    breaker = EquityCircuitBreaker(0.03)
    assert breaker.check_model_b(
        db, "1000", "1000", "0", complete=False,
        account_identity="same", expected_account_identity="same", today="2026-11-25",
    )[0] is False
    assert breaker.check_model_b(
        db, "1000", "1000", "0", complete=True,
        account_identity="new", expected_account_identity="old", today="2026-11-25",
    )[0] is False
    db.close()
