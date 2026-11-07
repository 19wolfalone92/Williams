"""TC2 chapter 12: source 3/4/5 STOP choices are research only.

Reproducible synthetic costs/intrabar-order controls plus common cohort metrics.
"""
from research import tc2_context_walkforward as original
from research import tc2_three_four_five_historical_audit as audit
import pytest


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("window",[3,4,5])
def test_flat_historical_exit_still_charges_notional_fee(side,window):
    # Same close as the hypothetical exit reference; a zero PnL delta must
    # not mysteriously have zero fees. Prior code charged fee on price DELTA.
    rows=[]
    for i in range(70):
        low=90.0 if 36<=i<=40 else 96.0
        high=110.0 if 36<=i<=40 else 104.0
        rows.append((original.START+i*original.HOUR,
                     100.0,high,low,100.0,200.0))
    event={"i":40,"owner":side,"exit_ref":100.0}
    result=original.replay(event,rows,6,stop_bars=window,trailing=False)
    assert result["valid"],result
    assert not result["stop_hit"]
    assert result["delta_bps"]==-9.0
    assert result["exit_reference"]==100.0
    assert result["exit_cost_rate_per_notional"]==original.COST_SIDE
    assert result["costs_entry_common_and_excluded"]
    assert result["funding_not_modelled"]
    assert "STATIC_" in result["stop_model"]
    if window==4:
        assert result["stop_model"]=="STATIC_4_PRIOR_H1"


@pytest.mark.parametrize("window",[3,4,5])
def test_open_hour_future_data_cannot_retroactively_move_prior_stop(window):
    rows=[]
    for i in range(50):
        high=110.0 if 20<=i<=30 else 104.0
        low=90.0 if 20<=i<=30 else 96.0
        rows.append((original.START+i*original.HOUR,100.0,high,low,100.0,200.0))
    event={"i":30,"owner":"LONG","exit_ref":100.0}
    a=original.replay(event,rows,6,stop_bars=window,trailing=True)
    changed=list(rows)
    for i in range(39,50):
        changed[i]=(changed[i][0],100.0,1000.0,1.0,100.0,200.0)
    assert a==original.replay(event,changed,6,stop_bars=window,trailing=True)
    assert a["no_intrabar_sequence_or_bid_ask"]


def test_common_cohort_reports_both_wider_stop_early_save_and_extra_loss():
    def record(n,valid=True,delta=0,hit=False):
        if not valid:return {"valid":False,"reason":"TRIGGER_HOUR_STOP_AND_OPPOSITE_SIGNAL_ORDER_UNKNOWN"}
        return {"valid":True,"delta_bps":float(delta),
                "adverse_bps":3.0,"stop_hit":hit}
    def make(idx,valid=True):
        rows={}
        for horizon in audit.HORIZONS:
            for trail in audit.MODELS:
                key=f"{horizon}h_{'trail' if trail else 'static'}"
                if idx==0:
                    rows[key]={3:record(3,delta=-2,hit=True),
                               4:record(4,delta=-20,hit=False),
                               5:record(5,delta=5,hit=False)}
                elif idx==1:
                    rows[key]={3:record(3,delta=-3,hit=True),
                               4:record(4,delta=8,hit=False),
                               5:record(5,delta=12,hit=False)}
                else:
                    rows[key]={3:record(3),4:record(4,valid=False),5:record(5)}
        return {"source":"WM3_FRACTAL","outcomes":rows}
    res=audit._cohort([make(0),make(1),make(2)])
    h=res["windows"]["24h_trail"]
    assert h["eligible_each_window"]=={"3":3,"4":2,"5":3}
    assert h["common_matched_eligible"]==2
    assert h["excluded_reason_counts"]["4:TRIGGER_HOUR_STOP_AND_OPPOSITE_SIGNAL_ORDER_UNKNOWN"]==1
    comparison=h["comparisons"]["4_minus_3"]
    assert comparison["wider_is_worse_count"]==1
    assert comparison["wider_is_better_count"]==1
    assert comparison["extra_loss_from_wider_stop_total_bps"]==18.0
    assert comparison["additional_gain_from_wider_stop_total_bps"]==11.0
    assert comparison["earlier_stop_hits_tighter_only"]==2
    assert res["candidate_count"]==3


def test_invalid_window_or_cost_sign_cannot_conceal_book_variant():
    rows=[(original.START+i*original.HOUR,100.,105.,95.,100.,2.) for i in range(70)]
    event={"i":40,"owner":"LONG","exit_ref":100.0}
    assert original.replay(event,rows,6,stop_bars=2)["reason"]=="INVALID_REPLAY_ARGUMENTS"
    assert original.replay(event,rows,6,stop_bars=6)["reason"]=="INVALID_REPLAY_ARGUMENTS"
