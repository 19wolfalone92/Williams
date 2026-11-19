"""Release contract for three-consecutive-loss entry gate and journal durability."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main/java/com/williamsbot"


def test_android_trade_journal_risk_is_strict_durable_and_coordinator_backlog_aware():
    journal = (ANDROID / "TradeJournal.kt").read_text()
    runtime = (ANDROID / "StandaloneRuntime.kt").read_text()
    assert "fun tradesForRisk(prefs: SharedPreferences): JSONArray?" in journal
    assert "private fun readStrict(prefs: SharedPreferences)" in journal
    assert "write(prefs, all, durable = true)" in journal
    assert "FUTURES_TRADE_JOURNAL_DURABLE_COMMIT_FAILED" in journal
    assert "futuresUnjournalledClosedCampaignBlocker(" in journal
    assert "TradeJournal.tradesForRisk(prefs)" in runtime
    assert "FUTURES_LOSS_STREAK_JOURNAL_UNVERIFIED" in runtime
    assert "FUTURES_LOSS_STREAK_EXCHANGE_CLOSE_UNJOURNALLED" in journal
    assert "dailyTradeGuard(): JSONObject" not in runtime
    assert "consecutiveLosses >= 2" not in runtime


def test_backend_matches_android_without_legacy_trade_count_or_cooldown_veto():
    backend = (ROOT / "futures_williams_runtime.py").read_text()
    conf = (ROOT / ".env.example").read_text()
    assert 'self.max_trades_day = 0' in backend
    assert 'self.cooldown_minutes = 0' in backend
    assert "MAX_TRADES_PER_DAY reached" not in backend
    assert "COOLDOWN active" not in backend
    assert 'if hard_pause:' in backend
    assert 'MAX_CONSECUTIVE_LOSSES reached: 3' in backend
    assert "MAX_TRADES_PER_DAY=0" in conf
    assert "COOLDOWN_MINUTES=0" in conf
    assert "MAX_OPEN_POSITIONS=10" in conf
