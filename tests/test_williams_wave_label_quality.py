"""Accept no artificial claim of 100% accuracy from model-derived or book graphics."""
import json
from pathlib import Path
from williams_wave_label_quality import evaluate_wave_label_quality
from williams_wave_subdivisions import course_checklist_leg
from williams_tc1_terminal_evidence import tc1_five_bullet_evidence

BOOK=Path(__file__).parents[1]/"docs/evidence/williams_book_visual_figure_registry_20261023.json"
def case(origin="INDEPENDENT_HUMAN_OHLC"):
    return {"symbol":"BTCUSDT","frame":"1h","as_of":1732320000000,
        "label_origin":origin,"label":"MOTIVE_FIVE","raw_ohlc_sha256":"a"*64,
        "annotation_frozen_at_ms":1732400000000,
        "independent_reviewer_id":"human-reviewer-1"}
def prediction():
    return {"symbol":"BTCUSDT","frame":"1h","as_of":1732320000000,
        "label":"MOTIVE_FIVE","raw_ohlc_sha256":"a"*64,
        "prediction_run_at_ms":1732500000000,"last_signal_known_at":1732320000000}

def test_no_human_gold_is_not_measurable_even_with_many_engine_cases():
    artifacts=[dict(case("ENGINE_DISCOVERED"),symbol=f"PAIR{i}USDT") for i in range(100)]
    r=evaluate_wave_label_quality(artifacts,[])
    assert r["independent_eligible"]==0
    assert r["complete_accuracy"] is None
    assert r["status"]=="NOT_MEASURABLE_NO_INDEPENDENT_LABELS"
    assert r["accuracy_certified"] is False

def test_blind_human_label_vs_prediction_scored_without_future():
    r=evaluate_wave_label_quality([case()],[prediction()],min_cases=1,min_symbols=1)
    assert r["status"]=="ELIGIBLE_MEASUREMENT_REQUIRES_HUMAN_SIGNOFF"
    assert r["complete_accuracy"]==1.0
    assert not r["accuracy_certified"]

def test_future_or_post_replay_relabel_is_invalid():
    a=case();a["annotation_frozen_at_ms"]=1732500000001
    r=evaluate_wave_label_quality([a],[prediction()],min_cases=1,min_symbols=1)
    assert r["status"]=="NOT_MEASURABLE_NO_INDEPENDENT_LABELS"
    assert r["invalid_references"][0]["reason"]=="INVALID_OR_NONBLIND_REFERENCE_OR_FUTURE_LEAK"
    a=case();p=prediction();p["last_signal_known_at"]=a["as_of"]+1
    assert evaluate_wave_label_quality([a],[p])["independent_eligible"]==0

def test_sha_mismatch_unknown_class_and_abstention():
    a=case();p=prediction();p["raw_ohlc_sha256"]="b"*64
    assert evaluate_wave_label_quality([a],[p])["invalid_references"]
    p=prediction();p["label"]="UNRESOLVED"
    x=evaluate_wave_label_quality([a],[p],min_cases=1,min_symbols=1)
    assert x["coverage"]==0.0 and x["selective_accuracy"] is None and x["abstained"]==1

def test_duplicate_predictions_and_fake_authority_never_count_as_canonical():
    r=evaluate_wave_label_quality([case()],[prediction(),prediction()],min_cases=1,min_symbols=1)
    assert r["invalid_predictions"][0]["reason"]=="DUPLICATE_PREDICTION"
    assert r["can_place_orders"] is False and r["can_close"] is False

def test_figures_are_topological_guide_not_accuracy_ground_truth():
    data=json.loads(BOOK.read_text())
    assert len(data["figures"])>=5
    assert {f["pdf_page"] for f in data["figures"] if f["source_file"].startswith("Bill") }=={59,60}
    assert all(f["annotation_origin"]=="BOOK_SCHEMATIC" and not f["eligible_for_accuracy"] for f in data["figures"])
    assert course_checklist_leg("W3",3)=="RECOUNT_W3_WRONG_WAVE_NUMBER"
    assert tc1_five_bullet_evidence({"label":"W5","status":"PROVISIONAL","divergence":True})["point_zero_certified"] is False
