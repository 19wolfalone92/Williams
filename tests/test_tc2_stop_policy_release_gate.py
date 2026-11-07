"""Both-period TC2 chapter 12 STOP-selection safety evidence gates.

Tests are deliberately synthetic, independent of the archived market examples:
a positive yearly mean can never bypass missing signed exchange proofs.
"""
import copy
import pytest
from research.tc2_stop_policy_release_gate import (
    MODES, DIFFS, EXPECTED_SCHEMAS, assess_stop_selection, verify_report
)

def fixture(year,reverse=False):
    windows={}
    for mode in MODES:
        avg={"3":2.0,"4":3.0,"5":-1.0}
        if reverse and mode=="24h_static":avg={"3":4.0,"4":2.0,"5":-1.0}
        comparisons={
            "4_minus_3":avg["4"]-avg["3"],
            "5_minus_3":avg["5"]-avg["3"],
            "5_minus_4":avg["5"]-avg["4"],
        }
        windows[mode]={
            "common_matched_eligible":5,
            "summary_common":{n:{"mean_delta_bps":v} for n,v in avg.items()},
            "comparisons":{n:{
                "matched_events":5,
                "mean_delta_bps":delta,
                "wider_is_better_count":1,
                "wider_is_worse_count":4,
                "earlier_stop_hits_tighter_only":2}
                for n,delta in comparisons.items()}
        }
    data={
        "schema":EXPECTED_SCHEMAS[year],
        "source_book":"TC2_RU_PDF_161_AND_165",
        "can_place_orders":False,"can_close":False,"can_move_stop":False,
        "overall":{"candidate_count":10,"windows":windows},
    }
    if year=="2025":
        data.update(study_is_real_fills=False,not_a_prospective_holdout=True,
            cost_applied_to="EXIT_NOTIONAL_NOT_PRICE_DELTA",
            book_stop_windows=[3,4,5],
            by_quarter={"2025-Q1":{"windows":windows},
                        "2025-Q4":{"windows":windows}})
    else:
        data.update(previously_inspected_data_not_a_new_untouched_holdout=True,
            cost_charged_on="EXIT_NOTIONAL",
            by_month={"2026-09":{"windows":windows}})
    return data

def pair():
    return {"2025":fixture("2025"),"2026":fixture("2026",reverse=True)}

def test_positive_2025_is_not_certified_trade_proof_or_authorized_stop():
    result=assess_stop_selection(pair())
    assert result["status"]=="NOT_APPROVED_RESEARCH_ONLY"
    assert result["selected_live_stop_window"] is None
    assert not result["can_change_exchange_stop"]
    assert not result["can_place_orders"]
    assert not result["can_close"]
    assert not result["real_signed_binance_stop_cycle_verified"]
    assert result["overall_matched_24h_trailing_by_period"]=={"2025":5,"2026":5}
    assert "24h_static/4_minus_3" in result["across_period_sign_changes"]
    assert "Binance" in result["explanation_ru"]
    assert result["periods"]["24h_trail"]["3"]["both_periods_positive"]

@pytest.mark.parametrize("year,field,value",[
    ("2025","schema","invented"),
    ("2025","study_is_real_fills",True),
    ("2025","not_a_prospective_holdout",False),
    ("2025","cost_applied_to","PRICE_DELTA"),
    ("2025","can_move_stop",True),
    ("2026","previously_inspected_data_not_a_new_untouched_holdout",False),
    ("2026","can_place_orders",True),
    ("2026","cost_charged_on","NONE"),
])
def test_laundered_research_labels_cannot_promote_stop(year,field,value):
    data=pair()
    data[year][field]=value
    with pytest.raises(ValueError):
        assess_stop_selection(data)

def test_mismatched_matched_cohorts_fail_instead_of_cherry_pick():
    data=pair()
    data["2025"]["overall"]["windows"]["24h_trail"]["comparisons"]["5_minus_3"]["matched_events"]=7
    with pytest.raises(ValueError,match="comparison changed sample"):
        assess_stop_selection(data)

def test_missing_window_or_sign_of_unknown_fees_fails_closed():
    data=pair()
    data["2025"]["overall"]["windows"]["6h_trail"]["summary_common"].pop("4")
    with pytest.raises(ValueError,match="alternative missing"):
        assess_stop_selection(data)

def test_nan_mean_and_negative_matched_counts_rejected():
    data=pair()
    data["2026"]["overall"]["windows"]["6h_trail"]["summary_common"]["3"]["mean_delta_bps"]=float("nan")
    with pytest.raises(ValueError):
        assess_stop_selection(data)
    data=pair()
    data["2026"]["overall"]["windows"]["6h_static"]["common_matched_eligible"]=-10
    with pytest.raises(ValueError):
        assess_stop_selection(data)

def test_both_periods_strongly_positive_still_never_certifies_live_stop():
    data=pair()
    for period in data:
        for mode in MODES:
            for i,window in enumerate(("3","4","5")):
                data[period]["overall"]["windows"][mode]["summary_common"][window]["mean_delta_bps"]=10.0+i
    result=assess_stop_selection(data)
    assert not result["can_change_exchange_stop"]
    assert result["status"]=="NOT_APPROVED_RESEARCH_ONLY"
    assert not result["source_chart_labels_independently_verified"]
    assert not result["entry_fee_and_funding_complete"]
