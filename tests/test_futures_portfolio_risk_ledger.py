import concurrent.futures
import threading

import pytest

from db import Database


def _campaign(symbol, pending_risk_quote, *, campaign_id=None, reservation_id=None,
              open_risk_quote=0.0):
    campaign_id = campaign_id or f"campaign-{symbol}"
    reservation_id = reservation_id or f"client-{symbol}"
    return {
        "campaign_id": campaign_id,
        "symbol": symbol,
        "side": "LONG",
        "execution_timeframe": "H1",
        "state": "ENTRY_PENDING",
        "origin_signal_id": f"signal-{symbol}",
        "current_signal_id": f"signal-{symbol}",
        "current_signal_type": "FRACTAL",
        "position_qty": 0.0,
        "average_entry_price": 0.0,
        "initial_stop_price": 0.0,
        "current_stop_price": 0.0,
        "structural_stop_source": "TC2",
        "additions": 0,
        "tranche_index": 0,
        "realized_pnl_quote": 0.0,
        "unrealized_pnl_quote": 0.0,
        "open_risk_quote": open_risk_quote,
        "pending_risk_quote": pending_risk_quote,
        "capital_reserved_quote": 0.0,
        "wave_context": {},
        "health": "HEALTHY",
        "next_action": "SUBMIT_ENTRY",
        "reconciliation_state": "CLEAN",
        "exit_reason": "",
        "tags": {"pending_order_client_id": reservation_id},
    }


def test_fifteen_concurrent_symbol_reservations_cannot_exceed_portfolio_limit(tmp_path):
    path = str(tmp_path / "portfolio-risk.sqlite3")
    dbs = [Database(path) for _ in range(15)]
    barrier = threading.Barrier(15)

    def reserve(index):
        barrier.wait(timeout=10)
        dbs[index].reserve_campaign_risk(
            _campaign(f"SYM{index:02d}", 2.0),
            equity_quote=5000.0,
            portfolio_limit_pct=0.006,
        )
        return True

    with concurrent.futures.ThreadPoolExecutor(max_workers=15) as pool:
        assert all(pool.map(reserve, range(15)))

    assert dbs[0].campaign_risk_reserved_quote() == pytest.approx(30.0)
    with pytest.raises(RuntimeError, match="PORTFOLIO_RISK_RESERVATION_EXCEEDED"):
        dbs[0].reserve_campaign_risk(
            _campaign("SYM16", 0.01),
            equity_quote=5000.0,
            portfolio_limit_pct=0.006,
        )


def test_replacing_same_campaign_reservation_replaces_not_double_counts(tmp_path):
    db = Database(str(tmp_path / "replace-risk.sqlite3"))
    db.reserve_campaign_risk(_campaign("BTCUSDT", 2.0), equity_quote=5000.0,
                             portfolio_limit_pct=0.006)
    replacement = _campaign(
        "BTCUSDT", 3.0, campaign_id="campaign-BTCUSDT",
        reservation_id="client-BTCUSDT-replacement", open_risk_quote=0.0,
    )
    db.reserve_campaign_risk(replacement, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert db.campaign_risk_reserved_quote() == pytest.approx(3.0)


def test_same_reservation_retry_is_idempotent(tmp_path):
    db = Database(str(tmp_path / "idempotent-risk.sqlite3"))
    campaign = _campaign("ETHUSDT", 2.0)
    db.reserve_campaign_risk(campaign, equity_quote=5000.0, portfolio_limit_pct=0.006)
    db.reserve_campaign_risk(campaign, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert db.campaign_risk_reserved_quote() == pytest.approx(2.0)
    count = db.conn.execute(
        "SELECT COUNT(*) FROM campaign_events WHERE campaign_id=? AND event='RESERVE' AND order_id=?",
        ("campaign-ETHUSDT", "client-ETHUSDT"),
    ).fetchone()[0]
    assert count == 1


def test_rejected_reservation_rolls_back_campaign_and_ledger_event(tmp_path):
    db = Database(str(tmp_path / "reject-risk.sqlite3"))
    db.reserve_campaign_risk(_campaign("BTCUSDT", 28.0), equity_quote=5000.0,
                             portfolio_limit_pct=0.006)
    with pytest.raises(RuntimeError, match="PORTFOLIO_RISK_RESERVATION_EXCEEDED"):
        db.reserve_campaign_risk(_campaign("ETHUSDT", 3.0), equity_quote=5000.0,
                                 portfolio_limit_pct=0.006)
    assert db.get_campaign("campaign-ETHUSDT") is None
    count = db.conn.execute(
        "SELECT COUNT(*) FROM campaign_events WHERE campaign_id=? AND event='RESERVE'",
        ("campaign-ETHUSDT",),
    ).fetchone()[0]
    assert count == 0
