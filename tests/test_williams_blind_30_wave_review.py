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
