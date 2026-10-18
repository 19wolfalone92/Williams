from dataclasses import replace
import pytest
from williams_course_campaign import (
    CourseState, course_plan, acknowledge_course_fill, STAGES, TARGETS)

def plan(s,phase,t,**kw):
    opts=dict(wave_confirmed=True,exchange_reconciled=True,protection_verified=True,
              entry_or_add_risk_admitted=True,allowed_risk_slots=10,now_ms=t+1000)
    opts.update(kw)
    return course_plan(s,phase,t,**opts)

@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_course_example_requires_actual_fills_and_is_symmetric(side):
    state=CourseState("owner-1",side)
    for i,(phase,expected) in enumerate(zip(STAGES,[3,1,6,10,3,6,0])):
        t=100000+i*3600000
        proposal=plan(state,phase,t)
        assert proposal.status=="PROPOSED"
        assert proposal.target_units==expected
        assert proposal.signed_unit_delta==expected-state.confirmed_units
        assert not proposal.can_place_orders
        unchanged=state
        assert unchanged.stage!=phase
        new=acknowledge_course_fill(state,proposal,fill_id=f"fills-{side}-{i}",
            confirmed_delta=proposal.signed_unit_delta,new_confirmed_units=expected,
            signal_close_ms=t,exchange_reconciled=True,protective_stop_verified=True)
        assert new.stage==phase and new.confirmed_units==expected
        assert acknowledge_course_fill(new,proposal,fill_id=f"fills-{side}-{i}",
            confirmed_delta=proposal.signed_unit_delta,new_confirmed_units=expected,
            signal_close_ms=t,exchange_reconciled=True,protective_stop_verified=True)==new
        state=new
    assert state.stage=="EXIT" and state.confirmed_units==0
    assert state.revision==7

def test_reconciliation_protection_and_risk_never_relaxed():
    state=CourseState("campaign","LONG")
    for kw,reason in [({"wave_confirmed":False},"WAVE_DEGREE_UNCONFIRMED"),
                      ({"exchange_reconciled":False},"EXCHANGE_RECONCILIATION_REQUIRED"),
                      ({"protection_verified":False},"PROTECTIVE_STOP_NOT_VERIFIED"),
                      ({"entry_or_add_risk_admitted":False},"RISK_GATE_BLOCKS_NEW_EXPOSURE"),
                      ({"allowed_risk_slots":2},"RISK_SLOT_CAP")]:
        result=plan(state,"W1",100000,**kw)
        assert result.status=="BLOCKED"
        assert result.reason==reason and result.signed_unit_delta==0

def test_stale_or_replayed_proposal_cannot_mutate_campaign():
    state=CourseState("campaign","SHORT")
    proposal=plan(state,"W1",100000)
    for args in [
        dict(confirmed_delta=2,new_confirmed_units=2),
        dict(confirmed_delta=3,new_confirmed_units=4),
    ]:
        with pytest.raises(ValueError):
            acknowledge_course_fill(state,proposal,fill_id="1",signal_close_ms=100000,
                                    exchange_reconciled=True,protective_stop_verified=True,**args)
    with pytest.raises(ValueError):
        acknowledge_course_fill(state,proposal,fill_id="1",signal_close_ms=100000,
                                exchange_reconciled=False,protective_stop_verified=True,
                                confirmed_delta=3,new_confirmed_units=3)
    confirmed=acknowledge_course_fill(state,proposal,fill_id="1",signal_close_ms=100000,
                         exchange_reconciled=True,protective_stop_verified=True,
                         confirmed_delta=3,new_confirmed_units=3)
    assert plan(confirmed,"W1",103600000).reason=="NON_SEQUENTIAL_STAGE"
    with pytest.raises(ValueError):
        acknowledge_course_fill(confirmed,proposal,fill_id="different",signal_close_ms=103600000,
                                exchange_reconciled=True,protective_stop_verified=True,
                                confirmed_delta=3,new_confirmed_units=6)

def test_future_and_unclosed_stage_is_never_allowed():
    s=CourseState("campaign","LONG")
    for t,now in [(0,1000),(9999,9000),(1000,1000+18_000_001)]:
        assert plan(s,"W1",t,now_ms=now).status=="BLOCKED"
    assert plan(s,"W3",1000).reason=="NON_SEQUENTIAL_STAGE"

def test_partial_btc_fill_cannot_invent_a_completed_course_stage():
    s=CourseState("campaign","LONG")
    p=plan(s,"W1",100000)
    with pytest.raises(ValueError):
        acknowledge_course_fill(s,p,fill_id="partial-order",
            confirmed_delta=1,new_confirmed_units=1,signal_close_ms=100000,
            exchange_reconciled=True,protective_stop_verified=True)
