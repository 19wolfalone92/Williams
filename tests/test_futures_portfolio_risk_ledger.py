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


def test_unresolved_reservation_cannot_be_replaced_by_a_new_client_id(tmp_path):
    db = Database(str(tmp_path / "replace-risk.sqlite3"))
    original = _campaign("BTCUSDT", 2.0)
    db.reserve_campaign_risk(original, equity_quote=5000.0, portfolio_limit_pct=0.006)
    replacement = _campaign(
        "BTCUSDT", 3.0, campaign_id="campaign-BTCUSDT",
        reservation_id="client-BTCUSDT-replacement", open_risk_quote=0.0,
    )
    replacement["state_revision"] = original["state_revision"]
    with pytest.raises(RuntimeError, match="PRIOR_RESERVATION_UNRESOLVED"):
        db.reserve_campaign_risk(replacement, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert db.campaign_risk_reserved_quote() == pytest.approx(2.0)


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



def test_stale_campaign_write_cannot_erase_or_restore_reservation(tmp_path):
    import copy
    path = str(tmp_path / "revision.sqlite3")
    a, b = Database(path), Database(path)
    c = _campaign("BTCUSDT", 2.0)
    a.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
    stale = copy.deepcopy(c)
    c["pending_risk_quote"] = 0.0
    c["state"] = "CLOSED"
    a.save_campaign(c)
    assert c["state_revision"] > stale["state_revision"]
    with pytest.raises(RuntimeError, match="CAMPAIGN_STATE_REVISION_CONFLICT"):
        b.save_campaign(stale)
    with pytest.raises(RuntimeError, match="CAMPAIGN_STATE_REVISION_CONFLICT"):
        b.reserve_campaign_risk(stale, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert b.get_campaign(c["campaign_id"])["state"] == "CLOSED"
    assert b.campaign_risk_reserved_quote() == 0.0


def test_reservation_replay_never_reopens_a_released_campaign_even_with_current_revision(tmp_path):
    db = Database(str(tmp_path / "released.sqlite3"))
    c = _campaign("ETHUSDT", 2.0)
    db.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
    c["pending_risk_quote"] = 0.0
    c["state"] = "CLOSED"
    db.save_campaign(c)
    c["state"] = "ENTRY_PENDING"
    c["pending_risk_quote"] = 2.0
    with pytest.raises(RuntimeError, match="TERMINAL_CAMPAIGN"):
        db.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert db.get_campaign(c["campaign_id"])["state"] == "CLOSED"
    assert db.campaign_risk_reserved_quote() == 0.0


def test_failed_outer_transaction_reverts_revision_and_row(tmp_path):
    db = Database(str(tmp_path / "rollback.sqlite3"))
    c = _campaign("BTCUSDT", 2.0)
    with pytest.raises(RuntimeError, match="simulated process abort"):
        with db.transaction():
            db.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
            assert c["state_revision"] == 1
            raise RuntimeError("simulated process abort")
    assert "state_revision" not in c
    assert db.get_campaign(c["campaign_id"]) is None
    assert db.campaign_risk_reserved_quote() == 0.0
    db.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert c["state_revision"] == 1


@pytest.mark.parametrize("open_risk,pending", [(-1.0, 2.0), (2.0, -1.0), (float("nan"), 2.0),
                                                (0.0, float("inf")), (0.0, 0.0)])
def test_reservation_components_must_individually_be_valid(tmp_path, open_risk, pending):
    db = Database(str(tmp_path / "invalid.sqlite3"))
    c = _campaign("BTCUSDT", pending, open_risk_quote=open_risk)
    with pytest.raises(RuntimeError, match="RESERVATION_AMOUNT_INVALID"):
        db.reserve_campaign_risk(c, equity_quote=5000.0, portfolio_limit_pct=0.006)
    assert db.get_campaign(c["campaign_id"]) is None


def test_fifteen_contenders_share_ten_available_reservations(tmp_path):
    path = str(tmp_path / "contention.sqlite3")
    dbs = [Database(path) for _ in range(15)]
    barrier = threading.Barrier(15)

    def attempt(i):
        barrier.wait(timeout=10)
        try:
            dbs[i].reserve_campaign_risk(_campaign(f"SYM{i:02d}", 3.0),
                                          equity_quote=5000.0, portfolio_limit_pct=0.006)
            return True
        except RuntimeError as exc:
            assert "PORTFOLIO_RISK_RESERVATION_EXCEEDED" in str(exc)
            return False

    with concurrent.futures.ThreadPoolExecutor(max_workers=15) as pool:
        result = list(pool.map(attempt, range(15)))
    assert result.count(True) == 10
    assert result.count(False) == 5
    assert dbs[0].campaign_risk_reserved_quote() == pytest.approx(30.0)


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_r51_signed_equity_drop_rejects_reserved_risk_before_transport(tmp_path, side):
    db = Database(str(tmp_path / "r51-equity.sqlite3"))
    for symbol in ("BTCUSDT", "ETHUSDT", "SOLUSDT"):
        c = _campaign(symbol, 10.0)
        c["side"] = side
        db.reserve_campaign_risk(c, equity_quote=5000, portfolio_limit_pct=.006)
    def check(equity):
        return db.require_campaign_quote_risk(campaign_id="campaign-SOLUSDT",
            reservation_id="client-SOLUSDT", expected_pending_quote=10,
            equity_quote=equity, portfolio_limit_pct=.006,
            campaign_limit_pct=.006, entry_limit_pct=.0025)
    assert check(5000)["portfolio_usdt"] == "30.0"
    with pytest.raises(RuntimeError, match="QUOTE_RISK_LEDGER_PORTFOLIO_CAP"):
        check(4900)
    # A rejected proof is not an implicit cancellation/release.
    assert db.campaign_risk_reserved_quote() == 30
    db.conn.close()


def test_r51_other_writer_reservation_is_included_after_restart(tmp_path):
    path = str(tmp_path / "r51-other-writer.sqlite3")
    a, b = Database(path), Database(path)
    a.reserve_campaign_risk(_campaign("BTCUSDT", 10), equity_quote=5000, portfolio_limit_pct=.006)
    def check(db):
        return db.require_campaign_quote_risk(campaign_id="campaign-BTCUSDT",
            reservation_id="client-BTCUSDT", expected_pending_quote=10,
            equity_quote=4500, portfolio_limit_pct=.006,
            campaign_limit_pct=.006, entry_limit_pct=.0025)
    assert float(check(a)["portfolio_usdt"]) == 10
    b.reserve_campaign_risk(_campaign("ETHUSDT", 19), equity_quote=5000, portfolio_limit_pct=.006)
    a.conn.close()
    a = Database(path)
    with pytest.raises(RuntimeError, match="QUOTE_RISK_LEDGER_PORTFOLIO_CAP"):
        check(a)
    with pytest.raises(RuntimeError, match="QUOTE_RISK_LEDGER_RESERVATION_CHANGED"):
        a.require_campaign_quote_risk(campaign_id="campaign-BTCUSDT",
            reservation_id="wrong", expected_pending_quote=10, equity_quote=5000,
            portfolio_limit_pct=.006, campaign_limit_pct=.006, entry_limit_pct=.0025)
    a.conn.close()
    b.conn.close()
