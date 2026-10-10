import os
import tempfile
import time

import pytest

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


def make_signal(signal_type=SignalType.REVERSAL, role=SignalRole.ENTRY, trigger=101.0, bar=1, confirmation=None):
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=signal_type,
        role=role,
        timeframe="5m",
        signal_bar_time_ms=bar,
        confirmation_time_ms=bar if confirmation is None else confirmation,
        trigger_price=trigger,
        protective_reference=97.0,
        htf_confirmed=True,
        created_at_ms=int(time.time() * 1000),
        expires_at_ms=int(time.time() * 1000) + 3_600_000,
    )


def test_first_available_signal_starts_campaign():
    signals = [
        make_signal(SignalType.FRACTAL, bar=30),
        make_signal(SignalType.REVERSAL, bar=10),
        make_signal(SignalType.SUPER_AO, bar=20),
    ]
    assert CampaignEngine.choose_initial_signal(signals).signal_type == SignalType.REVERSAL


def test_initial_selection_skips_expired_signal_and_blocks_missing_expiry():
    now = int(time.time() * 1000)
    expired = make_signal(SignalType.REVERSAL, bar=10)
    expired = __import__("dataclasses").replace(expired, expires_at_ms=now)
    missing_expiry = make_signal(SignalType.SUPER_AO, bar=20)
    missing_expiry = __import__("dataclasses").replace(missing_expiry, expires_at_ms=0)
    later_valid = make_signal(SignalType.FRACTAL, bar=30)
    later_valid = __import__("dataclasses").replace(
        later_valid,
        signal_bar_time_ms=30,
        confirmation_time_ms=40,
        expires_at_ms=now + 60_000,
    )

    selected = CampaignEngine.choose_initial_signal(
        [expired, missing_expiry, later_valid],
        now_ms=now,
    )
    assert selected is later_valid
    assert CampaignEngine.choose_initial_signal(
        [expired, missing_expiry],
        now_ms=now,
    ) is None


def test_initial_signal_order_uses_confirmation_not_fractal_center_time():
    # The fractal's source/center candle is older, but its signal is not
    # actionable until later right-side bars have closed.
    fractal = make_signal(SignalType.FRACTAL, bar=10, confirmation=30)
    reversal = make_signal(SignalType.REVERSAL, bar=20, confirmation=21)
    chosen = CampaignEngine.choose_initial_signal([fractal, reversal])
    assert chosen.signal_type == SignalType.REVERSAL


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


def test_structural_stop_prefers_closest_valid_higher_stop():
    stop, source = structural_stop_for_long(
        signal_type=SignalType.REVERSAL,
        signal_bar_low=97.0,
        recent_lows=[98.0, 98.5, 99.0],
        teeth=98.2,
        wave_invalidation=96.0,
        buffer=0.1,
    )
    assert stop == 98.9
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


def _open_campaign_for_add_on(db, engine, *, open_risk=4.0, budget=5.0):
    initial = make_signal(SignalType.REVERSAL, role=SignalRole.ENTRY, bar=100)
    campaign = engine.create_campaign(initial, initial_risk_pct=0.002)
    campaign.tags["risk_budget_quote"] = budget
    engine.arm_entry(campaign, initial)
    engine.mark_triggered(campaign, initial.signal_id, "entry-1")
    campaign = engine.record_initial_fill(
        campaign,
        quantity=0.1,
        average_entry_price=101.0,
        initial_stop_price=97.0,
        fill_order_id="entry-1",
        risk_quote=open_risk,
    )
    campaign.tags["risk_budget_quote"] = budget
    db.save_campaign(campaign)
    return campaign


def test_add_on_stays_in_campaign_and_respects_remaining_risk_budget():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        campaign = _open_campaign_for_add_on(db, engine, open_risk=4.0, budget=5.0)
        signal = make_signal(SignalType.SUPER_AO, role=SignalRole.ADD_ON, trigger=103.0, bar=200)

        try:
            with pytest.raises(ValueError, match="exceed the campaign"):
                engine.arm_add_on(
                    campaign,
                    signal,
                    risk_quote=1.1,
                    capital_reserved_quote=25.0,
                )
            assert campaign.campaign_id == db.get_campaign(campaign.campaign_id)["campaign_id"]
            assert campaign.state == CampaignState.OPEN_INITIAL
        finally:
            db.conn.close()


@pytest.mark.parametrize("signal_type", [SignalType.SUPER_AO, SignalType.FRACTAL])
def test_entry_role_wm2_wm3_is_reclassified_as_add_on_for_open_campaign(signal_type):
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        try:
            campaign = _open_campaign_for_add_on(db, engine, open_risk=4.0, budget=5.0)
            campaign_id = campaign.campaign_id
            # Detectors use ENTRY so either WM2 or WM3 can start a flat campaign.
            # The campaign coordinator must reclassify it when this campaign is open.
            signal = make_signal(
                signal_type,
                role=SignalRole.ENTRY,
                trigger=103.0 if signal_type == SignalType.SUPER_AO else 104.0,
                bar=220,
            )
            armed = engine.arm_add_on(
                campaign,
                signal,
                risk_quote=1.0,
                capital_reserved_quote=25.0,
            )
            assert armed.campaign_id == campaign_id
            assert armed.state == CampaignState.ADD_ON_PENDING
            saved = [
                row for row in db.active_campaign_signals(campaign_id)
                if row["signal_id"] == signal.signal_id
            ]
            assert len(saved) == 1
            assert saved[0]["role"] == SignalRole.ADD_ON.value
            assert len([
                row for row in db.open_campaigns()
                if row.get("symbol") == "BTCUSDT"
            ]) == 1
        finally:
            db.conn.close()


def test_add_on_fill_rejects_non_finite_values_and_risk_overrun():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        try:
            # An armed add-on has a $1 reservation against a $5 campaign cap.
            campaign = _open_campaign_for_add_on(db, engine, open_risk=4.0, budget=5.0)
            signal = make_signal(SignalType.SUPER_AO, role=SignalRole.ADD_ON, trigger=103.0, bar=210)
            engine.arm_add_on(campaign, signal, risk_quote=1.0, capital_reserved_quote=25.0)
            campaign.transition(CampaignState.POSITION_EXPANDING, reason="test exchange fill")
            for bad_qty, bad_price, bad_risk in [
                (float("nan"), 103.0, 1.0),
                (1.0, float("inf"), 1.0),
                (1.0, 103.0, float("nan")),
                (1.0, 103.0, 1.01),
            ]:
                with pytest.raises(ValueError):
                    engine.record_add_on_fill(
                        campaign,
                        quantity=bad_qty,
                        average_entry_price=bad_price,
                        fill_order_id="test-fill-1",
                        risk_quote=bad_risk,
                    )
            assert campaign.position_qty == 0.1
            assert campaign.open_risk_quote == 4.0
        finally:
            db.conn.close()


def test_add_on_uses_existing_campaign_and_reserves_only_remaining_risk():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        campaign = _open_campaign_for_add_on(db, engine, open_risk=4.0, budget=5.0)
        campaign_id = campaign.campaign_id
        signal = make_signal(SignalType.FRACTAL, role=SignalRole.ADD_ON, trigger=104.0, bar=201)

        try:
            armed = engine.arm_add_on(
                campaign,
                signal,
                risk_quote=1.0,
                capital_reserved_quote=25.0,
            )
            assert armed.campaign_id == campaign_id
            assert armed.state == CampaignState.ADD_ON_PENDING
            assert armed.pending_risk_quote == 1.0
            assert len([
                row for row in db.open_campaigns()
                if row.get("symbol") == "BTCUSDT"
            ]) == 1
        finally:
            db.conn.close()

@pytest.mark.parametrize(
    "overrides,match",
    [
        ({"quantity": float("nan")}, "finite"),
        ({"average_entry_price": float("inf")}, "finite"),
        ({"initial_stop_price": float("nan")}, "finite"),
        ({"risk_quote": float("inf")}, "finite"),
        ({"fee_quote": float("nan")}, "finite"),
        ({"risk_quote": 0.0}, "positive"),
        ({"fill_order_id": ""}, "stable exchange order ID"),
    ],
)
def test_initial_fill_rejects_invalid_numeric_values_and_missing_order_id(overrides, match):
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "initial-fill-validation.sqlite3"))
        try:
            engine = CampaignEngine(db)
            signal = make_signal()
            campaign = engine.create_campaign(signal, initial_risk_pct=0.002)
            engine.arm_entry(campaign, signal)
            engine.mark_triggered(campaign, signal.signal_id, "entry-1")
            values = {
                "quantity": 0.01,
                "average_entry_price": 101.0,
                "initial_stop_price": 97.0,
                "fill_order_id": "entry-1",
                "risk_quote": 2.0,
                "fee_quote": 0.0,
            }
            values.update(overrides)
            with pytest.raises(ValueError, match=match):
                engine.record_initial_fill(campaign, **values)
        finally:
            db.conn.close()


def test_reconcile_required_campaign_retains_risk_and_capital_reservations():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "w.sqlite3"))
        engine = CampaignEngine(db)
        try:
            campaign = _open_campaign_for_add_on(db, engine, open_risk=4.0, budget=5.0)
            campaign.pending_risk_quote = 1.0
            campaign.capital_reserved_quote = 25.0
            campaign.mark_reconcile_required("simulated unknown exchange execution")
            db.save_campaign(campaign)

            assert engine.portfolio_reserved_risk_quote() == pytest.approx(5.0)
            assert engine.portfolio_reserved_capital_quote() == pytest.approx(25.0)
        finally:
            db.conn.close()


def test_signal_spec_rejects_conflicting_order_side_and_direction():
    import pytest
    from campaign_model import SignalRole, SignalSpec, SignalType

    with pytest.raises(ValueError, match="side/direction conflict"):
        SignalSpec.new(
            symbol="BTCUSDT",
            side="BUY",
            direction="SHORT",
            signal_type=SignalType.REVERSAL,
            role=SignalRole.ENTRY,
            timeframe="5m",
            signal_bar_time_ms=1_000,
            trigger_price=101.0,
            protective_reference=95.0,
        )


def test_signal_spec_accepts_matching_short_order_side_and_direction():
    from campaign_model import SignalRole, SignalSpec, SignalType

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="SELL",
        direction="SHORT",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=1_000,
        trigger_price=99.0,
        protective_reference=105.0,
    )
    assert signal.direction == "SHORT"
    assert signal.side == "SELL"
