import os
import tempfile

from campaign_engine import CampaignEngine
from campaign_model import (
    CampaignState,
    SignalRole,
    SignalSpec,
    SignalType,
    stop_only_reduces_risk,
    structural_stop_for_long,
)
from db import Database


def make_signal(signal_type=SignalType.REVERSAL, role=SignalRole.ENTRY, trigger=101.0, bar=1):
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=signal_type,
        role=role,
        timeframe="5m",
        signal_bar_time_ms=bar,
        trigger_price=trigger,
        protective_reference=97.0,
        htf_confirmed=True,
    )


def test_initial_campaign_prefers_canonical_wise_man_order():
    signals = [
        make_signal(SignalType.FRACTAL, bar=30),
        make_signal(SignalType.SUPER_AO, bar=20),
        make_signal(SignalType.REVERSAL, bar=10),
    ]
    assert CampaignEngine.choose_initial_signal(signals).signal_type == SignalType.REVERSAL


def test_fractal_can_start_when_no_earlier_wise_man_is_available():
    signal = make_signal(SignalType.FRACTAL, bar=30)
    assert CampaignEngine.choose_initial_signal([signal]).signal_type == SignalType.FRACTAL


def test_wise_men_can_be_later_adds_not_two_of_three_gate():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        signal = make_signal(SignalType.REVERSAL, bar=10)
        campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
        assert campaign.state == CampaignState.SIGNAL_DETECTED
        engine.arm_entry(campaign, signal)
        assert db.get_campaign(campaign.campaign_id)["state"] == CampaignState.ENTRY_PENDING


def test_stop_never_loosens_long_risk():
    assert stop_only_reduces_risk("LONG", 100.0, 101.0)
    assert not stop_only_reduces_risk("LONG", 100.0, 99.9)


def test_structural_stop_follows_book_3_5_bar_structure():
    stop, source = structural_stop_for_long(
        signal_type=SignalType.REVERSAL,
        signal_bar_low=97.0,
        recent_lows=[98.0, 98.5, 99.0],
        teeth=98.2,
        wave_invalidation=96.0,
        buffer=0.1,
    )
    assert abs(stop - 98.1) < 1e-9
    assert source == "3_5_BAR_STRUCTURE"


def test_stop_proposal_persists_and_blocks_loosen():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        signal = make_signal()
        campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
        engine.arm_entry(campaign, signal)
        campaign = engine.mark_triggered(campaign, signal.signal_id, "10")
        campaign = engine.record_initial_fill(
            campaign,
            quantity=0.01,
            average_entry_price=101.0,
            initial_stop_price=97.0,
            fill_order_id="10",
            risk_quote=2.0,
        )
        assert campaign.state == CampaignState.OPEN_INITIAL
        assert engine.propose_stop(campaign, 99.0, "3_5_BAR_STRUCTURE")
        assert campaign.current_stop_price == 99.0
        assert not engine.propose_stop(campaign, 98.0, "BAD_LOOSEN")


def test_portfolio_risk_includes_pending_campaign():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        signal = make_signal()
        campaign = engine.create_campaign(signal, initial_risk_pct=0.004)
        campaign.pending_risk_quote = 40.0
        campaign.capital_reserved_quote = 500.0
        db.save_campaign(campaign)
        assert engine.portfolio_reserved_risk_quote() == 40.0
        assert engine.portfolio_reserved_capital_quote() == 500.0


def test_campaign_limits_one_wm2_and_three_fractal_add_ons():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "addons.sqlite3"))
        engine = CampaignEngine(db)
        initial = make_signal(SignalType.REVERSAL, bar=10)
        campaign = engine.create_campaign(initial, initial_risk_pct=0.002)
        engine.arm_entry(campaign, initial)
        campaign = engine.mark_triggered(campaign, initial.signal_id, "1")
        campaign = engine.record_initial_fill(
            campaign,
            quantity=0.01,
            average_entry_price=101.0,
            initial_stop_price=97.0,
            fill_order_id="1",
            risk_quote=2.0,
        )

        wm2 = make_signal(SignalType.SUPER_AO, role=SignalRole.ENTRY, bar=20)
        engine.arm_add_on(campaign, wm2, risk_quote=1.0, capital_reserved_quote=10.0)
        campaign = engine.load_campaign(campaign.campaign_id)
        campaign = engine.record_add_on_fill(
            campaign,
            quantity=0.001,
            average_entry_price=102.0,
            fill_order_id="2",
            risk_quote=1.0,
        )

        with __import__("pytest").raises(ValueError, match="WM2"):
            engine.arm_add_on(campaign, make_signal(SignalType.SUPER_AO, bar=25), risk_quote=1.0, capital_reserved_quote=10.0)
