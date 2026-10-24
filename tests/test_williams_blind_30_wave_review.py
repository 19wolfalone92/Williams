"""Thirty independent-implementation PRICE proxy examples vs Williams inference."""
from datetime import datetime
from pathlib import Path
import importlib.util

S=Path(__file__).parents[1]/"tools/benchmark_blind_30_h1_wave_cases.py"
spec=importlib.util.spec_from_file_location("blind_benchmark",S)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

def test_closed_history_contains_30_predeclared_chart_slices_and_stable_hashes():
    assert len(mod.SYMBOLS)==5 and len(mod.DATES)==6
    assert len(set(mod.DATES))==6
    for symbol in mod.SYMBOLS:
        rows,checksum=mod.load_source(symbol)
        assert len(rows)==480 and len(checksum)==64
        for dt in mod.DATES:
            now=int(datetime.fromisoformat(dt).timestamp()*1000)
            bars=[r for r in rows if r[0]+3600000<=now][-160:]
            assert len(bars)==160 and bars[-1][0]+3600000<=now
            assert mod.digest(bars)==mod.digest(bars[:])

def test_thirty_real_cases_measured_but_never_claim_human_elliott_accuracy(tmp_path):
    m=mod.run(tmp_path)
    assert m["snapshot_count"]==30
    assert m["independent_expert_annotations"]==0
    assert m["accuracy_vs_human_expert"] is None
    assert m["independent_accuracy_certified"] is False
    assert m["classified"]+m["abstained"]==30
    assert m["can_place_orders"] is False and m["can_close"] is False
    assert (tmp_path/"blind_reference_frozen.json").exists()
    assert (tmp_path/"model_vs_proxy_results.json").exists()

def test_blind_review_produces_same_reference_with_future_poisoning():
    from wave_blind_reference_price_grammar import blind_reference
    rows,_=mod.load_source("BTCUSDT")
    now=int(datetime.fromisoformat(mod.DATES[0]).timestamp()*1000)
    prefix=[r for r in rows if r[0]+3600000<=now][-160:]
    r1=blind_reference(prefix)
    suffix=[[r[0],*r[1:]] for r in rows if r[0]+3600000>now]
    for r in suffix:r[4]="-999999"
    r2=blind_reference((prefix+suffix)[:160])
    assert r1==r2

def test_fifteen_real_assets_expand_the_causal_blind_wave_sample_without_fake_experts(tmp_path):
    assert len(mod.ALL_15)==15 and len(set(mod.ALL_15))==15
    for symbol in mod.ALL_15:
        rows,checksum=mod.load_source(symbol)
        assert len(rows)==480 and len(checksum)==64
    got=mod.run(tmp_path,symbols=mod.ALL_15)
    assert got["schema"]=="WILLIAMS_BLIND_H1_RULE_PROXY_90_V1"
    assert got["snapshot_count"]==90 and len(got["instruments"])==15
    assert got["classified"]+got["abstained"]==90
    assert got["independent_expert_annotations"]==0
    assert got["accuracy_vs_human_expert"] is None
    assert got["independent_accuracy_certified"] is False
    assert not got["can_place_orders"] and not got["can_close"] and not got["can_reverse"]

def test_unresolved_completed_wave_still_exports_read_only_live_phase_alternatives(tmp_path):
    mod.run(tmp_path)
    import json
    rows=json.loads((tmp_path/"model_vs_proxy_results.json").read_text())["cases"]
    assert any(x["model_label"]=="UNRESOLVED" for x in rows)
    for x in rows:
        if x["model_label"]!="UNRESOLVED":
            continue
        evidence=x["model_evidence"]
        assert isinstance(evidence["developing_phase_candidates"],list)
        assert isinstance(evidence["developing_direction_candidates"],list)
        assert isinstance(evidence["partial_hypothesis_count"],int)
        assert evidence["partial_hypothesis_count"]>=0
        assert evidence["book_wave_degree_certified"] is False
        assert evidence["can_place_orders"] is False
        assert evidence["can_close"] is False and evidence["can_reverse"] is False
        assert all(p in {"W2","W3","W4","W5","A","B","C",
            "NEXT_SEQUENCE_UNRESOLVED"} for p in evidence["developing_phase_candidates"])
