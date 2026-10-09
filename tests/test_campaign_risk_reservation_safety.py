from db import Database


def campaign(campaign_id, state, open_risk, pending_risk):
    return {
        "campaign_id": campaign_id,
        "symbol": "BTCUSDT",
        "side": "LONG",
        "execution_timeframe": "5m",
        "state": state,
        "open_risk_quote": open_risk,
        "pending_risk_quote": pending_risk,
        "capital_reserved_quote": 0,
        "tags": {},
    }


def test_reconcile_required_campaign_still_counts_toward_portfolio_risk(tmp_path):
    db = Database(str(tmp_path / "risk.sqlite3"))
    try:
        db.save_campaign(campaign("open", "TREND_ACTIVE", 12.0, 3.0))
        db.save_campaign(campaign("unknown", "RECONCILE_REQUIRED", 20.0, 5.0))
        db.save_campaign(campaign("closed", "CLOSED", 100.0, 100.0))
        assert db.campaign_risk_reserved_quote() == 40.0
    finally:
        db.conn.close()


def test_flat_campaign_does_not_count_toward_portfolio_risk(tmp_path):
    db = Database(str(tmp_path / "flat.sqlite3"))
    try:
        db.save_campaign(campaign("flat", "FLAT", 0.0, 0.0))
        assert db.campaign_risk_reserved_quote() == 0.0
    finally:
        db.conn.close()
