"""Release policy and causal structural validity, not profitability tests."""
from dataclasses import replace
from types import SimpleNamespace
import pandas as pd
import pytest

from campaign_model import SignalRole, SignalSpec, SignalType
from futures_williams_runtime import _pending_entry_preflight_reason
from williams_edition_rules import TC2, NTD, COURSE
from williams_execution_policy import new_exposure_blocker, closed_h1_history_blocker
from williams_profiles import observe_edition_signals
from williams_signal_lifecycle import signal_facts, signal_lifecycle
from williams_signals import extract_long_signal_specs, extract_short_signal_specs
from williams_wave_hierarchy import WaveBar
from strategy import calculate_indicators, canonical_indicator_config_from_env

H=3_600_000
START=1_700_002_800_000//H*H


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_100_bar_old_unbroken_fractal_remains_an_entry_with_current_proof(side):
    bars=[WaveBar(START+i*H,101,high,99,101,100) for i,high in enumerate([102,103,110,106,105]+[104]*100)]
    if side=="SHORT":
        bars=[WaveBar(b.t,220-b.o,220-b.l,220-b.h,220-b.c,b.v) for b in bars]
    frame=pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,close=b.c,volume=b.v) for b in bars])
    ind=calculate_indicators(frame,canonical_indicator_config_from_env())
    extract=extract_long_signal_specs if side=="LONG" else extract_short_signal_specs
    found=[s for s in extract("BTCUSDT",ind,timeframe="1h",tick_size=.1) if s.signal_type==SignalType.FRACTAL]
    assert len(found)==1
    s=found[0]
    assert s.signal_bar_time_ms==bars[4].t and s.expires_at_ms==0
    assert s.role==SignalRole.ENTRY
    now=bars[-1].t+H+1000
    fact=signal_facts([s],bars,H)[0]
    assert signal_lifecycle(fact,None,now,now,True)=="VALID"
    assert signal_lifecycle(fact,None,now,now-121_000,True)=="DETECTED"
    intent=SimpleNamespace(signal_id=s.signal_id,side=s.side)
    pending={"state":"VALIDATED","signal":s.to_dict()}
    assert _pending_entry_preflight_reason(pending,intent,bars[-1].c,now) == ""
    assert "snapshot_stale" in _pending_entry_preflight_reason(pending,intent,bars[-1].c,now+H)
    crossed=s.trigger_price+.1 if side=="LONG" else s.trigger_price-.1
    assert "already_crossed" in _pending_entry_preflight_reason(pending,intent,crossed,now)


def test_one_observer_failure_is_not_no_signal_or_a_tc2_failure(monkeypatch):
    import williams_profiles as profiles
    calls=[]
    def detect(*args,profile,side,**kwargs):
        calls.append((profile,side))
        if profile==NTD:raise ValueError("bad observer data")
        return []
    monkeypatch.setattr(profiles,"extract_profile_signals",detect)
    _,observations=observe_edition_signals("BTCUSDT",pd.DataFrame(),timeframe="1h",tick_size=.1)
    by_source={o["profile"]:o for o in observations}
    assert by_source[NTD]["state"]=="ANALYSIS_FAILED"
    assert by_source[TC2]["state"]==by_source[COURSE]["state"]=="NO_VALID_FORMATION"
    assert (COURSE,"SHORT") in calls
    assert all(o["can_place_orders"] is False for o in observations)
    assert new_exposure_blocker(TC2) is None
    assert new_exposure_blocker(NTD) is None
    assert all(new_exposure_blocker(s)=="WILLIAMS_SOURCE_OBSERVATION_ONLY" for s in (COURSE,"WILLIAMS_UNIFIED",""))


def test_structural_validity_does_not_accept_stale_gapped_or_future_h1_data():
    bars=[WaveBar(START+i*H,100,101,99,100,1) for i in range(45)]
    now=bars[-1].t+H+1000
    assert closed_h1_history_blocker(bars,now) is None
    assert closed_h1_history_blocker(bars,now+H)=="H1_SNAPSHOT_STALE"
    gap=bars.copy();gap[20]=replace(gap[20],t=gap[20].t+1000)
    assert closed_h1_history_blocker(gap,now)=="H1_HISTORY_GAP"
    assert closed_h1_history_blocker(bars+[replace(bars[-1],t=bars[-1].t+2*H)],now)=="CLOSED_H1_HISTORY_NOT_PROVEN"


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_ntd_pdf43_old_fractal_lasts_until_trigger_or_new_valid_fractal(side):
    from williams_profiles import extract_profile_signals
    from williams_execution_policy import h1_signal_expiry
    heights = [104]*45+[104,105,110,106,105]+[104]*100
    bars = [WaveBar(START+i*H,101,float(h),99,101,100) for i,h in enumerate(heights)]
    if side == "SHORT":
        bars = [WaveBar(b.t,220-b.o,220-b.l,220-b.h,220-b.c,b.v) for b in bars]
    def detect(series):
        ind = calculate_indicators(pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,
            close=b.c,volume=b.v) for b in series]),canonical_indicator_config_from_env())
        return [s for s in extract_profile_signals("BTCUSDT",ind,profile=NTD,side=side,timeframe="1h",tick_size=.1)
                if s.signal_type == SignalType.FRACTAL]
    old = detect(bars)
    assert len(old) == 1 and old[0].signal_bar_time_ms == bars[49].t
    assert old[0].expires_at_ms == h1_signal_expiry(old[0].signal_bar_time_ms,"FRACTAL",NTD) == 0
    extra = [WaveBar(bars[-1].t+(i+1)*H,101,h,99,101,100) for i,h in enumerate([104.,105.,104.,103.])]
    if side == "SHORT":
        extra = [WaveBar(b.t,220-b.o,220-b.l,220-b.h,220-b.c,b.v) for b in extra]
    newer = detect(bars+extra)
    assert len(newer) == 1 and newer[0].signal_bar_time_ms == extra[-1].t
    assert newer[0].signal_id != old[0].signal_id
    crossed = replace(bars[-1],h=111.) if side == "LONG" else replace(bars[-1],l=109.)
    assert not detect(bars[:-1]+[crossed])


@pytest.mark.parametrize("edges",[[1,2,5,2,1],[1,2,5,5,2,1],[1,2,5,3,5,3,5,2,1]])
@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_book_figure_11_2_five_six_and_nine_bar_groups_wait_for_two_right_bars(edges,side):
    from williams_edition_rules import edition_fractals
    bars=[WaveBar(START+i*H,100,100+e,99,100,1) for i,e in enumerate(edges)]
    if side=="SHORT":bars=[WaveBar(b.t,220-b.o,220-b.l,220-b.h,220-b.c,b.v) for b in bars]
    kind="HIGH" if side=="LONG" else "LOW"
    last_equal=max(i for i,e in enumerate(edges) if e==5)
    # These are synthetic rule fixtures derived from the diagram's extremum
    # relationships, not reconstructed historical prices from the book image.
    assert not [f for f in edition_fractals(bars[:-1]) if f.kind==kind and f.center==last_equal]
    found=[f for f in edition_fractals(bars) if f.kind==kind and f.center==last_equal]
    assert len(found)==1 and found[0].confirmation==len(bars)-1
