"""Production wiring: a UTC entry halt must never own operator pause or Kill."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()


def test_daily_halt_is_independent_of_operator_pause_and_keeps_order_gates():
    helper = RUNTIME.split("private fun dailyEntryHalt(", 1)[1].split("/**", 1)[0]
    assert "evaluateFuturesDailyEntryHalt(" in helper
    assert 'executionStore.readState("futures_daily_entry_halt_utc_day_start_ms")' in helper
    assert "paused =" not in helper and "killLatched =" not in helper
    gate = RUNTIME.split("private fun executionReady(): Boolean =", 1)[1].split("private fun executionBlockers", 1)[0]
    for guard in ("!paused", "!reconcileRequired", "!killLatched", "dailyRiskBaselineReady()",
                  "dailyEntryHalt().allowNewEntries", "!equityCircuitBreaker.isTripped()"):
        assert guard in gate
    assert "val dailyLossAllowsEntry = runCatching { checkFuturesDailyLoss() }" in RUNTIME


def test_daily_halt_persists_and_only_storage_failure_pauses_runtime():
    breach = RUNTIME.split("if (lossPct >= 0.03) {", 1)[1].split("val halt=dailyEntryHalt", 1)[0]
    assert 'if(!executionStore.writeState("futures_daily_entry_halt_utc_day_start_ms",utcDayStartMs.toString())) {' in breach
    assert 'FUTURES_DAILY_ENTRY_HALT_PERSIST_FAILED' in breach
    assert breach.index('paused=true') > breach.index('if(!executionStore.writeState')
    successful_check = RUNTIME.split("val halt=dailyEntryHalt(exchangeNowMs,true)", 1)[1].split("// UTC midnight clears", 1)[0]
    assert "paused =" not in successful_check and "paused=" not in successful_check
    assert '"auto_run"' not in successful_check
    assert 'cancelPendingEntries("DAILY_LOSS_LIMIT")' in successful_check
