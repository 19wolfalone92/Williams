"""Two nonhuman toy reviewers test the evaluator; no fabricated human acceptance."""
import csv,importlib.util
from datetime import datetime,timezone
from pathlib import Path
import pytest

ROOT=Path(__file__).parents[1]
FILE=ROOT/"tools/score_blind_human_wave_review.py"
spec=importlib.util.spec_from_file_location("dual_wave_review",FILE)
dual=importlib.util.module_from_spec(spec)
spec.loader.exec_module(dual)

def filled_csv(tmp_path,reviewer,*,alter=None,all_fifteen=False):
    original=ROOT/"app/src/test/resources"
    rows=[]
    for (symbol,frame,as_of),(source,prefix,window) in sorted(dual.selected_prefixes(all_fifteen=all_fifteen).items()):
        row={"symbol":symbol,"frame":frame,"as_of":as_of,
          "prefix_sha256":prefix,"source_window_sha256":window,
          "reviewer_id":reviewer,"expert_label":"NO_VALID_COUNT","degree":"UNDETERMINED",
          "direction":"UNRESOLVED","pivot_times_utc":"","alternative_count":"2",
          "confidence_0_1":"0.5","reason_ru":"Множество допустимых структур без единственной доказанной разметки",
          "review_frozen_at_utc":"2026-10-23T06:00:00Z"}
        if alter:alter(row)
        rows.append(row)
    f=tmp_path/(reviewer+".csv")
    with f.open("w",newline="",encoding="utf-8") as h:
        w=csv.DictWriter(h,fieldnames=sorted(dual.FIELDS))
        w.writeheader();w.writerows(rows)
    return f

TIME=1792756800000

def test_dual_rater_consumes_30_frozen_real_ohlc_windows_and_remains_unverified(tmp_path):
    a=filled_csv(tmp_path,"audit-reviewer-1")
    b=filled_csv(tmp_path,"audit-reviewer-2")
    out=dual.compare_reviews(a,b,compared_at=TIME)
    assert out["total_cases"]==30
    assert out["agreement_count"]==30 and out["cohen_kappa"] is None
    assert out["reviewer_identity_and_independence_verified"] is False
    assert out["human_elliott_accuracy_certified"] is False
    assert out["classified_consensus"]+out["abstained_consensus"]==30
    assert not out["can_place_orders"] and not out["can_close"]

def test_wrong_hash_and_unsigned_future_pivot_rejected(tmp_path):
    a=filled_csv(tmp_path,"reviewer_a",alter=lambda x:x.update(prefix_sha256="bad"))
    with pytest.raises(ValueError,match="CHECKSUM_MISMATCH"):
        dual.load_blind_reviewer_csv(a,dual.selected_prefixes(),TIME)
    def future(x):
        x["expert_label"]="MOTIVE_FIVE"
        x["direction"]="UP"
        x["pivot_times_utc"]="2024-12-20T00:00:00Z"
    b=filled_csv(tmp_path,"reviewer_b",alter=future)
    with pytest.raises(ValueError,match="PIVOT_FUTURE_LEAK"):
        dual.load_blind_reviewer_csv(b,dual.selected_prefixes(),TIME)

def test_duplicate_reviewers_are_not_independent(tmp_path):
    a=filled_csv(tmp_path,"reviewer_same")
    with pytest.raises(ValueError,match="TWO_DISTINCT_REVIEWERS"):
        dual.compare_reviews(a,a,compared_at=TIME)

def test_reviewer_freeze_after_comparison_rejected(tmp_path):
    a=filled_csv(tmp_path,"reviewer_late",
         alter=lambda x:x.update(review_frozen_at_utc="2090-01-01T00:00:00Z"))
    with pytest.raises(ValueError,match="NOT_FROZEN_BEFORE"):
        dual.load_blind_reviewer_csv(a,dual.selected_prefixes(),TIME)

def test_conflicting_labels_are_not_merged_as_independent_truth(tmp_path):
    a=filled_csv(tmp_path,"one")
    b=filled_csv(tmp_path,"two",alter=lambda x:x.update(
        expert_label="COMPLEX_CORRECTION",direction="UP",
        pivot_times_utc=datetime.fromtimestamp((int(x["as_of"])-12*3_600_000)/1000,timezone.utc).isoformat()) if x["symbol"]=="BTCUSDT"
        else None)
    got=dual.compare_reviews(a,b,compared_at=TIME)
    assert got["disagreement_count"]==6
    assert got["consensus_cases"]==24
    assert all(x["consensus_label"] is None for x in got["cases"] if x["symbol"]=="BTCUSDT")
    assert got["human_elliott_accuracy_certified"] is False


def test_dual_rater_90_frozen_cases_have_no_synthetic_human_certification(tmp_path):
    a=filled_csv(tmp_path,"reviewer_a",all_fifteen=True)
    b=filled_csv(tmp_path,"reviewer_b",all_fifteen=True)
    result=dual.compare_reviews(a,b,compared_at=TIME,all_fifteen=True)
    assert result["schema"]=="WILLIAMS_BLIND_90_TWO_REVIEWERS_V1"
    assert result["total_cases"]==90 and result["agreement_count"]==90
    assert result["abstained_consensus"]+result["classified_consensus"]==90
    assert result["reviewer_identity_and_independence_verified"] is False
    assert result["human_elliott_accuracy_certified"] is False
    assert not result["can_place_orders"] and not result["can_close"]
    with pytest.raises(ValueError,match="EXPECTED_ALL_FROZEN_ANNOTATIONS"):
        dual.compare_reviews(a,b,compared_at=TIME)
