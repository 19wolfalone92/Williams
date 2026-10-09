import time

from campaign_model import SignalRole, SignalSpec, SignalType
from digital_williams_core import DigitalWilliamsCore


def signal(signal_bar_time_ms, *, expires_at_ms, trigger=101.0, protective=95.0, symbol="BTCUSDT"):
    return SignalSpec.new(
        symbol=symbol,
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=signal_bar_time_ms,
        trigger_price=trigger,
        protective_reference=protective,
        created_at_ms=1_000,
        expires_at_ms=expires_at_ms,
    )


def test_core_skips_expired_old_signal_and_selects_next_actionable_signal():
    core = DigitalWilliamsCore()
    expired = signal(1_000, expires_at_ms=9_999)
    valid = signal(2_000, expires_at_ms=20_000)
    decision = core.compose([expired, valid], now_ms=10_000)
    assert decision.action == "ARM_ENTRY"
    assert decision.signal.signal_bar_time_ms == 2_000


def test_core_blocks_when_all_candidate_signals_are_expired():
    core = DigitalWilliamsCore()
    decision = core.compose([signal(1_000, expires_at_ms=9_999)], now_ms=10_000)
    assert decision.action == "BLOCK"
    assert decision.vetoes == ("pending_signal_expired_or_invalid",)


def test_core_waits_when_there_are_no_entry_candidates():
    core = DigitalWilliamsCore()
    assert core.compose([], now_ms=10_000).action == "WAIT"


def test_core_skips_invalid_protection_reference_if_later_signal_is_valid():
    core = DigitalWilliamsCore()
    invalid = signal(1_000, expires_at_ms=20_000, protective=0.0)
    valid = signal(2_000, expires_at_ms=20_000, protective=95.0)
    decision = core.compose([invalid, valid], now_ms=10_000)
    assert decision.action == "ARM_ENTRY"
    assert decision.signal.signal_bar_time_ms == 2_000


def test_contract_keeps_protection_during_market_exit_resolution():
    contract = DigitalWilliamsCore().contract()["execution_contract"]["exit"]
    assert "keep exchange-side protection live" in contract
    assert "cancel protection ->" not in contract


def test_explicitly_expired_signal_is_not_revived_by_late_creation_time():
    core = DigitalWilliamsCore()
    # Signal creation happened after its source-derived expiry; preserve expiry.
    stale = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=1_000,
        trigger_price=101.0, protective_reference=95.0,
        created_at_ms=15_000, expires_at_ms=9_999,
    )
    decision = core.compose([stale], now_ms=15_000)
    assert decision.action == "BLOCK"
    assert decision.pending.expires_at_ms == 9_999
