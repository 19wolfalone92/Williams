"""Read the market without inventing an Elliott label or order permission."""
import json
from dataclasses import replace
from pathlib import Path

import pytest

from williams_market_phase import OBSERVED_MARKET_FRAMES, market_phase_context, observed_market_context
from williams_market_reading import market_reading
from williams_wave_hierarchy import FRAME_MS, WaveBar
from williams_exit_policy import plan_williams_stop

AS_OF=1_792_578_000_000


def history(direction=1):
    result={}
    for frame in OBSERVED_MARKET_FRAMES:
        duration=FRAME_MS[frame]
        end=AS_OF//duration*duration
        result[frame]=[WaveBar(end-(80-i)*duration,100+direction*i*.1,
            101+direction*i*.1,99+direction*i*.1,100.5+direction*i*.1,100+i) for i in range(80)]
    return result


@pytest.mark.parametrize("direction,bias",[(1,"UP"),(-1,"DOWN")])
def test_all_real_timeframes_have_independent_observations_even_without_wave_pivots(direction,bias):
    raw=observed_market_context(history(direction),AS_OF)
    phase=market_phase_context(raw)
    assert raw["as_of"]==AS_OF
    assert [f["frame"] for f in phase["frames"]]==list(OBSERVED_MARKET_FRAMES)
    for row in phase["frames"]:
        assert row["state"]=="UNRESOLVED"  # a straight series has no confirmed alternating pivots
        reading=row["market_reading"]
        assert reading["state"]=="OBSERVED"
        assert reading["observed_bias"]==bias
        assert reading["alligator"]["order"]==bias
        assert reading["frame"]==row["frame"]
        assert reading["as_of_close_ms"]==AS_OF//FRAME_MS[row["frame"]]*FRAME_MS[row["frame"]]
        assert reading["bias_is_forecast"] is False
        assert reading["can_place_orders"] is False
        assert reading["can_close"] is False
    assert raw["can_place_orders"] is raw["can_close"] is raw["can_reverse"] is False


def test_future_candles_do_not_change_snapshot_or_displace_its_bounded_history():
    rows=history()
    expected=observed_market_context(rows,AS_OF)
    poisoned={f:b+[WaveBar(AS_OF+i*FRAME_MS[f],1,1e12,.01,1,1e12) for i in range(400)] for f,b in rows.items()}
    assert observed_market_context(poisoned,AS_OF)==expected


def test_alligator_tail_contains_only_available_known_samples_and_does_not_invent_missing_lips():
    reading=market_reading(history()["1h"],{},"1h",AS_OF)
    tail=reading["alligator_known_tail"]
    assert tail["common_known_plot_steps"]==3
    assert len(tail["points"])==8
    assert all(p["jaw"] is not None for p in tail["points"])
    assert all(p["lips"] is not None for p in tail["points"][:3])
    assert all(p["lips"] is None for p in tail["points"][3:])
    assert all(p["teeth"] is None for p in tail["points"][5:])
    assert tail["source_through_close_ms"]<=AS_OF
    assert tail["extrapolated"] is tail["price_forecast"] is tail["uses_future_prices"] is False


def test_absent_gapped_stale_and_failed_frames_have_different_reasons_without_hiding_h1():
    rows=history()
    rows["1d"]=[]
    rows["6h"].pop(50)
    rows["4h"]=rows["4h"][:-2]
    phase=market_phase_context(observed_market_context(rows,AS_OF,{"15m":"HISTORY_READ_FAILED:SQLiteException"}))
    by_frame={f["frame"]:f for f in phase["frames"]}
    assert by_frame["1d"]["history_error"]=="INSUFFICIENT_CLOSED_HISTORY"
    assert by_frame["6h"]["history_error"]=="GAPPED_OR_UNORDERED_HISTORY"
    assert by_frame["4h"]["history_error"]=="STALE_CLOSED_HISTORY"
    assert by_frame["15m"]["market_reading"]["state"]=="HISTORY_READ_FAILED"
    assert by_frame["1h"]["market_reading"]["state"]=="OBSERVED"
    assert by_frame["1h"]["history_error"] is None


def test_an_analyser_that_never_ran_is_not_an_unresolved_wave_count():
    assert all(f["state"]=="NOT_ANALYSED" and f["reason"]=="WAVE_ANALYSIS_NOT_RUN"
               for f in market_phase_context({})["frames"])


def test_rising_ao_below_zero_is_reported_separately_from_positive_momentum():
    bars=history(-1)["1h"]
    bars[-1]=replace(bars[-1],h=bars[-1].h+.2,l=bars[-1].l+.2,c=bars[-1].c+.2)
    reading=market_reading(bars,{},"1h",AS_OF)
    assert reading["ao_sign"]=="NEGATIVE"
    assert reading["ao_direction"]=="UP"
    assert reading["observed_bias"]=="MIXED"  # close moved into the mouth; AO colour alone cannot choose a side


def test_actual_xrp_screenshot_has_readable_indicators_without_a_fabricated_wave_number():
    fixture=json.loads((Path(__file__).parent/"app/src/test/resources/williams_market_context_xrp.json").read_text())
    bars=[WaveBar(*b) for b in fixture["bars"]]
    raw=observed_market_context({"1h":bars},fixture["now"])
    row=next(f for f in market_phase_context(raw)["frames"] if f["frame"]=="1h")
    reading=row["market_reading"]
    assert raw["as_of"]>0 and row["state"]!="NOT_ANALYSED"
    assert reading["alligator"]["state"]=="SATIATED"
    assert reading["ao"]==pytest.approx(.02362529411764691,abs=1e-12)
    assert reading["ac"]==pytest.approx(.003266294117646895,abs=1e-12)
    assert reading["observed_bias"]=="UP"
    assert reading["zone"]=="GREEN"
    assert reading["bias_is_forecast"] is False


def test_book_stop_requires_explicit_opt_in_for_non_book_one_r_overlay_and_never_widens():
    bars=[WaveBar(i*3_600_000,100,106+i,94+i,100) for i in range(5)]
    args=("LONG",90,100,90,111,.1,1,bars)
    book=plan_williams_stop(*args)
    assert book["reason"]=="TC2_LAST_5_CLOSED_BARS"
    assert book["stop"]==pytest.approx(93.9)
    overlay=plan_williams_stop(*args,break_even=True)
    assert overlay["reason"]=="COST_BUFFERED_BREAKEVEN_1R"
    assert overlay["stop"]==pytest.approx(100.4)
    assert plan_williams_stop("LONG",100.4,100,90,111,.1,1,bars) is None


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("tail",[(5,4,5,6,5),(4,5,6,5,5.5)])
def test_opposite_ac_colour_cancels_pending_signal_and_later_recovery_does_not_resurrect_it(side,tail):
    import pandas as pd
    from williams_signals import extract_ntd_ac_signal_specs
    sign=1 if side=="LONG" else -1
    rows=[]
    for i in range(60):
        high=110 if i in (57,58) else 100
        rows.append(dict(open_time=(i+1)*3_600_000,open=99,high=high,low=98,close=99,
                         ac=sign*(([0]*55+list(tail))[i])))
    if side=="SHORT":
        for row in rows:
            row.update(high=200-row["low"],low=200-row["high"],open=101,close=101)
    assert extract_ntd_ac_signal_specs("BTCUSDT",pd.DataFrame(rows),side=side,timeframe="1h",tick_size=.1)==[]
