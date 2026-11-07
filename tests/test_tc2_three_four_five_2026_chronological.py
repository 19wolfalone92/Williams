"""Cross-segment TC2 stop-choice audit: never optimize with future H1 evidence."""
import pytest
from pathlib import Path
from research import tc2_context_walkforward as source
from research import tc2_three_four_five_2026_chronological as study

ROOT=Path(__file__).resolve().parents[1]

def test_calendar_segment_does_not_steal_months_from_embargo():
    assert study.MONTHS[0][1]==source.OOS_START
    assert study.MONTHS[-1][2]==source.END
    assert all(a[2]==b[1] for a,b in zip(study.MONTHS,study.MONTHS[1:]))
    assert source.TRAIN_END<source.OOS_START<source.END

@pytest.mark.parametrize("symbol",["BTCUSDT","ETHUSDT","XRPUSDT"])
def test_frozen_source_metadata_and_no_future_2026_bars(symbol):
    f=source.load(symbol,ROOT/"research/data")
    assert len(f["1h"])==7398 and len(f["4h"])==1849 and len(f["1d"])==308
    for frame,rows in f.items():
        assert rows[-1][0]+source.FRAMES[frame]<=source.END

def test_2026_locked_book_stop_comparison_has_no_trade_authority():
    assert study.source is source
    assert study.audit.WINDOWS==(3,4,5)
    assert study.audit.MODELS==(False,True)
    assert study.audit.HORIZONS==(6,24)

def test_common_cohort_rejects_inconsistent_stop_evidence():
    # Offline cohort logic must never mark a candidate as comparable if any
    # one of the three book windows lacks eligible confirmed historical data.
    def candidate(valid4:bool):
        return {"source":"WM3_FRACTAL","outcomes":{
            f"{h}h_{'trail' if tr else 'static'}":{
                n:{"valid": n!=4 or valid4,"delta_bps":0.0,
                   "adverse_bps":0.0,"stop_hit":False}
                for n in (3,4,5)}
            for h in (6,24) for tr in (False,True)}}
    result=study.audit._cohort([candidate(False),candidate(True)])
    assert result["windows"]["24h_trail"]["common_matched_eligible"]==1
    assert result["windows"]["24h_static"]["common_matched_eligible"]==1
