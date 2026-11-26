"""A scan omission is not a causal signal invalidation event."""
import json

from williams_signal_lifecycle import SignalJournal, signal_lifecycle


class FakeDB:
    def __init__(self):
        self.state = {}

    def state_get(self, key, default=""):
        return self.state.get(key, default)

    def state_set(self, key, value):
        self.state[key] = value


class AlwaysPolicy:
    def allows(self, now):
        return True

    def to_dict(self):
        return {"mode": "ALWAYS"}


def signal(now, expires_at):
    return {
        "signal_id": "wm1:btc:formation-1",
        "known_at_ms": now - 3_600_000,
        "signal_bar_time_ms": now - 3_600_000,
        "expires_at_ms": expires_at,
        "type": "REVERSAL",
        "direction": "LONG",
        "role": "ENTRY",
        "strategy_profile": "TC2_THREE_WISE_MEN",
        "trigger": 101.0,
        "protective_stop": 95.0,
        "setup_context": {},
    }


def test_absence_from_next_scan_is_not_structural_invalidation():
    now = 1_800_000_000_000
    item = signal(now, now + 3_600_000)
    db = FakeDB()
    journal = SignalJournal(db)

    journal.observe("BTCUSDT", [item], now, AlwaysPolicy())
    journal.observe("BTCUSDT", [], now + 1_000, AlwaysPolicy())

    data = json.loads(db.state["signal_timeline_BTCUSDT"])
    missing = data["events"][-1]
    assert missing["state"] == "NOT_REEMITTED"
    assert missing["reason"] == (
        "NOT_REEMITTED_IN_CURRENT_CLOSED_BAR_SCAN_NO_STRUCTURAL_INVALIDATION_PROOF"
    )
    assert signal_lifecycle(item, None, now + 1_000, now + 1_000, False) == "NOT_REEMITTED"
    assert not any(e["state"] == "INVALIDATED" for e in data["events"])


def test_explicit_expiry_remains_terminal_when_signal_is_not_reemitted():
    now = 1_800_000_000_000
    item = signal(now, now + 1_000)
    db = FakeDB()
    journal = SignalJournal(db)

    journal.observe("BTCUSDT", [item], now, AlwaysPolicy())
    journal.observe("BTCUSDT", [], now + 1_001, AlwaysPolicy())

    data = json.loads(db.state["signal_timeline_BTCUSDT"])
    assert data["events"][-1]["state"] == "EXPIRED"
    assert signal_lifecycle(item, None, now + 1_001, now + 1_001, False) == "EXPIRED"
