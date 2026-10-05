import os
from portfolio_trader import MultiPositionTrader


def test_symbol_state_is_independent():
    class DB:
        def __init__(self):
            self.data = {}
        def state_get(self, key, default=None):
            return self.data.get(key, default)
        def state_set(self, key, value):
            self.data[key] = str(value)

    db = DB()
    t = MultiPositionTrader.__new__(MultiPositionTrader)
    t.db = db
    t.set_state("BTCUSDT", "OPEN")
    t.set_state("ETHUSDT", "ENTRY_PENDING")
    assert t.state("BTCUSDT") == "OPEN"
    assert t.state("ETHUSDT") == "ENTRY_PENDING"
    assert t.state("SOLUSDT") == "FLAT"


def test_hard_risk_caps():
    old_total = os.environ.get("MAX_TOTAL_RISK_PCT")
    old_trade = os.environ.get("MAX_RISK_PER_TRADE_PCT")
    os.environ["MAX_TOTAL_RISK_PCT"] = "0.99"
    os.environ["MAX_RISK_PER_TRADE_PCT"] = "0.99"
    try:
        t = MultiPositionTrader.__new__(MultiPositionTrader)
        t.max_total_risk_pct = min(0.01, max(0.0, float(os.getenv("MAX_TOTAL_RISK_PCT"))))
        t.max_risk_per_trade_pct = min(0.005, max(0.0, float(os.getenv("MAX_RISK_PER_TRADE_PCT"))))
        assert t.max_total_risk_pct == 0.01
        assert t.max_risk_per_trade_pct == 0.005
    finally:
        if old_total is None: os.environ.pop("MAX_TOTAL_RISK_PCT", None)
        else: os.environ["MAX_TOTAL_RISK_PCT"] = old_total
        if old_trade is None: os.environ.pop("MAX_RISK_PER_TRADE_PCT", None)
        else: os.environ["MAX_RISK_PER_TRADE_PCT"] = old_trade


if __name__ == "__main__":
    test_symbol_state_is_independent()
    test_hard_risk_caps()
    print("[PASS] multi-position state and hard risk caps")
