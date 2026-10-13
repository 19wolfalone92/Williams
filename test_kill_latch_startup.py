import threading

import server


class _DB:
    def __init__(self, latched=True):
        self.state = {"kill_switch_latched": "true" if latched else "false"}
        self.events = []

    def state_get(self, key, default=None):
        return self.state.get(key, default)

    def state_set(self, key, value):
        self.state[key] = str(value)

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))


class _Trader:
    def __init__(self, control=None, latched=True):
        self.db = _DB(latched)
        self.poll_seconds = 0
        self.setup_calls = 0
        self.process_calls = 0
        self.control = control

    def setup(self):
        self.setup_calls += 1

    def process(self):
        self.process_calls += 1
        if self.control is not None:
            self.control.running = False


class _Multi:
    def unresolved_symbols(self):
        return []

    def _pending_entries(self):
        return []


def test_automatic_start_cannot_clear_a_persisted_kill_latch(monkeypatch):
    control = server.ControlState()
    trader = _Trader(latched=True)
    control.api_key = "test-key"
    control.api_secret = "test-secret"
    control.testnet = True
    control.ensure_trader = lambda: trader
    monkeypatch.setattr(server, "execution_start_blocker", lambda *args, **kwargs: None)

    class _Thread:
        def __init__(self, target, **kwargs):
            self.target = target
            self.started = False

        def is_alive(self):
            return False

        def start(self):
            self.started = True

    monkeypatch.setattr(server.threading, "Thread", _Thread)

    assert control.start() is False
    assert trader.db.state["kill_switch_latched"] == "true"
    assert control.last_error and "KILL_SWITCH_LATCHED" in control.last_error


def test_explicit_start_clears_kill_latch_only_after_recovery(monkeypatch):
    control = server.ControlState()
    trader = _Trader(control=control, latched=True)
    control.ensure_trader = lambda: trader
    control.ensure_multi = lambda: _Multi()
    control.explicit_rearm_requested = True

    control.loop()

    assert trader.setup_calls == 1
    assert trader.process_calls == 1
    assert trader.db.state["kill_switch_latched"] == "false"
    assert control.explicit_rearm_requested is False
