import pytest

from futures_runtime import _assert_fresh_closed_candle


def test_fresh_closed_candle_is_accepted():
    now = 1_800_000_000_000
    _assert_fresh_closed_candle("BTCUSDT", "5m", now - 300_000, now_ms=now)


@pytest.mark.parametrize("close_ms", [0, -1, 1_800_000_000_000 + 60_001])
def test_invalid_or_future_closed_candle_is_rejected(close_ms):
    with pytest.raises(RuntimeError, match="stale or has an invalid timestamp"):
        _assert_fresh_closed_candle("BTCUSDT", "5m", close_ms, now_ms=1_800_000_000_000)


def test_stale_closed_candle_is_rejected():
    now = 1_800_000_000_000
    with pytest.raises(RuntimeError, match="stale or has an invalid timestamp"):
        _assert_fresh_closed_candle("BTCUSDT", "5m", now - 600_001, now_ms=now)


def test_monthly_candle_uses_monthly_staleness_window():
    now = 1_800_000_000_000
    _assert_fresh_closed_candle("BTCUSDT", "1M", now - 40 * 24 * 60 * 60 * 1000, now_ms=now)


def test_initial_williams_signal_expires_at_exact_boundary():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from futures_runtime import choose_initial_williams_signal

    now = 10_000
    expired = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=1_000,
        trigger_price=101.0, protective_reference=95.0,
        created_at_ms=1_000, expires_at_ms=now,
    )
    valid = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ADD_ON, timeframe="5m", signal_bar_time_ms=2_000,
        trigger_price=102.0, protective_reference=96.0,
        created_at_ms=2_000, expires_at_ms=now + 1,
    )
    chosen = choose_initial_williams_signal([expired, valid], "LONG", now_ms=now)
    assert chosen is not None
    assert chosen.signal_bar_time_ms == 2_000


def test_canonical_williams_decision_timeframe_defaults_to_h1():
    from trading_config import TradingConfig
    cfg = TradingConfig.from_env({})
    assert cfg.execution_timeframe == "1h"
    assert cfg.structural_timeframes == ("1d", "4h", "1h", "15m")


def test_noncanonical_signal_timeframe_is_not_the_default():
    from futures_runtime import CANONICAL_DECISION_TIMEFRAME
    from trading_config import TradingConfig
    assert CANONICAL_DECISION_TIMEFRAME == "1h"
    assert TradingConfig.from_env({"EXECUTION_TIMEFRAME": "5m"}).execution_timeframe == "5m"


def test_initial_williams_signal_without_expiry_is_not_actionable():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from futures_runtime import choose_initial_williams_signal
    signal = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=1_000,
        trigger_price=101.0, protective_reference=95.0,
    )
    assert choose_initial_williams_signal([signal], "LONG", now_ms=10_000) is None


def test_conservative_trading_defaults_match_release_policy():
    from trading_config import TradingConfig

    cfg = TradingConfig.from_env({})
    assert cfg.risk_per_trade_pct == pytest.approx(0.0025)
    assert cfg.max_total_risk_pct == pytest.approx(0.01)
    assert cfg.max_daily_loss_pct == pytest.approx(0.01)
    assert cfg.max_consecutive_losses == 2
    assert cfg.max_open_positions == 1


def test_risk_policy_environment_overrides_remain_explicit():
    from trading_config import TradingConfig

    cfg = TradingConfig.from_env({
        "RISK_PER_TRADE_PCT": "0.002",
        "MAX_TOTAL_RISK_PCT": "0.008",
        "MAX_DAILY_LOSS_PCT": "0.0075",
        "MAX_CONSECUTIVE_LOSSES": "4",
        "MAX_OPEN_POSITIONS": "3",
    })
    assert cfg.risk_per_trade_pct == pytest.approx(0.002)
    assert cfg.max_total_risk_pct == pytest.approx(0.008)
    assert cfg.max_daily_loss_pct == pytest.approx(0.0075)
    assert cfg.max_consecutive_losses == 4
    assert cfg.max_open_positions == 3


class _FakeCloseDb:
    def __init__(self, rows):
        self.rows = rows

    def recent_campaign_closes(self, limit=50):
        return self.rows[:limit]


def _close(pnl, closed_at="2026-10-10 10:00:00"):
    import json
    return {
        "created_at": closed_at,
        "payload_json": json.dumps({"realized_pnl_quote_net_known_fees": pnl}),
        "reason": "campaign close",
    }


def test_loss_guard_blocks_configured_consecutive_losses():
    from datetime import datetime, timezone
    from futures_runtime import _loss_streak_allows_entry

    now = datetime(2026, 10, 10, 10, 5, tzinfo=timezone.utc)
    ok, reason = _loss_streak_allows_entry(
        _FakeCloseDb([_close(-2), _close(-1), _close(5)]),
        max_consecutive_losses=2,
        cooldown_minutes=0,
        now=now,
    )
    assert not ok
    assert "consecutive loss limit" in reason


def test_loss_guard_clears_streak_after_a_non_loss():
    from datetime import datetime, timezone
    from futures_runtime import _loss_streak_allows_entry

    now = datetime(2026, 10, 10, 10, 5, tzinfo=timezone.utc)
    ok, reason = _loss_streak_allows_entry(
        _FakeCloseDb([_close(0), _close(-2), _close(-1)]),
        max_consecutive_losses=2,
        cooldown_minutes=0,
        now=now,
    )
    assert ok
    assert "consecutive_losses=0" in reason


def test_loss_guard_enforces_post_loss_cooldown():
    from datetime import datetime, timezone
    from futures_runtime import _loss_streak_allows_entry

    now = datetime(2026, 10, 10, 10, 5, tzinfo=timezone.utc)
    ok, reason = _loss_streak_allows_entry(
        _FakeCloseDb([_close(-2, "2026-10-10 10:00:00")]),
        max_consecutive_losses=3,
        cooldown_minutes=30,
        now=now,
    )
    assert not ok
    assert "cooldown active" in reason


@pytest.mark.parametrize("rows", [
    [{"created_at": "2026-10-10 10:00:00", "payload_json": "{}", "reason": "close"}],
    [{"created_at": "bad-timestamp", "payload_json": '{"realized_pnl_quote": -1}', "reason": "close"}],
    [{"created_at": "2026-10-10 10:00:00", "payload_json": '{"realized_pnl_quote": NaN}', "reason": "close"}],
])
def test_loss_guard_fails_closed_on_malformed_close_ledger(rows):
    from datetime import datetime, timezone
    from futures_runtime import _loss_streak_allows_entry

    ok, reason = _loss_streak_allows_entry(
        _FakeCloseDb(rows),
        max_consecutive_losses=2,
        cooldown_minutes=30,
        now=datetime(2026, 10, 10, 10, 5, tzinfo=timezone.utc),
    )
    assert not ok
    assert "blocked" in reason


def test_database_returns_only_finalized_campaign_close_events():
    from db import Database

    db = Database(":memory:")
    try:
        db.conn.execute(
            "INSERT INTO campaigns(campaign_id,symbol,side,execution_timeframe,state,tags_json) VALUES(?,?,?,?,?,?)",
            ("campaign-a", "BTCUSDT", "LONG", "1h", "CLOSED", '{"execution_mode":"FUTURES"}'),
        )
        db.conn.execute(
            "INSERT INTO campaigns(campaign_id,symbol,side,execution_timeframe,state,tags_json) VALUES(?,?,?,?,?,?)",
            ("campaign-spot", "ETHUSDT", "LONG", "1h", "CLOSED", '{"execution_mode":"SPOT"}'),
        )
        db.conn.commit()
        db.log_campaign_event(
            "campaign-a", "CAMPAIGN_CLOSED", reason="STOP_LOSS",
            payload={"realized_pnl_quote_net_known_fees": -2.0},
        )
        db.log_campaign_event(
            "campaign-spot", "CAMPAIGN_CLOSED", reason="STOP_LOSS",
            payload={"realized_pnl_quote_net_known_fees": -1.0},
        )
        db.log_campaign_event(
            "campaign-a", "EXIT_FILLED", reason="STOP_LOSS",
            payload={"realized_pnl_quote_net_known_fees": -1.0},
        )
        rows = db.recent_campaign_closes()
        assert len(rows) == 1
        assert rows[0]["reason"] == "STOP_LOSS"
        assert rows[0]["payload_json"] == '{"realized_pnl_quote_net_known_fees": -2.0}'
    finally:
        db.conn.close()
