"""NTD pp.94–96 fifth joint AO/AC zone bar and sticky stop replay.

The Python observer must match native Kotlin management invariants. Every
proposed stop is hypothetical: a history touched event cannot imply a real
Binance stop order or execution, and NONE authorizes NTD new risk.
"""
from dataclasses import replace

import pytest

from williams_wave_hierarchy import WaveBar
from williams_edition_rules import ntd_management_plan

H1=3_600_000


def candle(i,hi,lo):
    return WaveBar(i*H1,(hi+lo)/2,hi,lo,(hi+lo)/2,100.0)


def shape(n=6,side="LONG"):
    series=[candle(i+1,101.0+i,99.0+i) for i in range(n)]
    if side=="SHORT":
        series=[replace(b,o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c)
                for b in series]
    scale=1 if side=="LONG" else -1
    return series,[float(scale*i) for i in range(n)]


def plan(series,osc,side="LONG",state=None,opened_at=1,tick=.1):
    return ntd_management_plan(side,series,[80.0]*len(series),
        [90.0]*len(series),osc,osc,tick,opened_at,state)


@pytest.mark.parametrize("side,first,second",[
    ("LONG",103.9,104.9),("SHORT",196.1,195.1),
])
def test_stop_ratchets_through_other_colour_without_widening(side,first,second):
    series,osc=shape(side=side)
    x=plan(series,osc,side)
    assert x["stop"]==pytest.approx(first)
    assert x["state"]["zone_book_stop_authorized_order"] is False
    assert x["state"]["zone_book_evidence"]=="REPLAYED_COMPLETE_CLOSED_H1_PATH"
    assert x["state"]["zone_active_at"]==series[-1].t
    next_bar=shape(n=7,side=side)[0][-1]
    lower_direction=osc+([osc[-1]-1] if side=="LONG" else [osc[-1]+1])
    y=plan(series+[next_bar],lower_direction,side,x["state"])
    assert y["stop"]==pytest.approx(second)
    assert y["state"]["zone_book_stop_source_time_ms"]==next_bar.t
    assert y["state"]["zone_book_stop_authorized_order"] is False
    z=plan(series+[next_bar],lower_direction,side,y["state"])
    assert z["stop"]==pytest.approx(second)
    assert z["state"]["zone_book_stop"]==y["state"]["zone_book_stop"]


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_prior_derived_stop_touch_is_ambiguous_not_a_closed_exchange_trade(side):
    series,osc=shape(side=side)
    x=plan(series,osc,side)
    next_bar=shape(n=7,side=side)[0][-1]
    next_bar=(replace(next_bar,l=103.8) if side=="LONG"
              else replace(next_bar,h=196.2))
    y=plan(series+[next_bar],osc+[osc[-1]],side,x["state"])
    assert y["stop"] is None
    assert y["state"]["zone_historical_stop_touch_at_ms"]==next_bar.t
    assert y["state"]["zone_book_evidence"]=="HISTORICAL_BOOK_STOP_TOUCHED_RECONCILE_EXCHANGE"
    assert y["state"]["zone_book_stop_authorized_order"] is False


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_off_grid_bar_or_missing_valid_tick_cannot_mint_book_stop(side):
    series,osc=shape(side=side)
    if side=="LONG":series[-1]=replace(series[-1],l=103.95)
    else:series[-1]=replace(series[-1],h=196.05)
    x=plan(series,osc,side)
    assert x["stop"] is None
    assert x["state"]["zone_book_evidence"]=="NTD_BOOK_PRICE_OFF_EXCHANGE_TICK_GRID"
    good,osc=shape(side=side)
    for tick in (float("nan"),float("inf"),0.0,-.1):
        assert plan(good,osc,side,tick=tick)["stop"] is None


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_after_candles_roll_out_durable_matching_checkpoint_is_required(side):
    series,osc=shape(n=12,side=side)
    # First 10 bars include the five-zone origin. Persist a complete
    # exchange-independent source-replay checkpoint at bar 10.
    first=plan(series[:10],osc[:10],side)
    assert first["stop"] is not None
    last6,osc6=series[6:12],osc[6:12]
    good=plan(last6,osc6,side,first["state"])
    assert good["stop"] is not None
    assert good["state"]["zone_book_evidence"]=="REPLAYED_FROM_DURABLE_CLOSED_BAR_CHECKPOINT"
    tampered=dict(first["state"],zone_book_last_checked_ohlcv="INVALID")
    rejected=plan(last6,osc6,side,tampered)
    assert rejected["stop"] is None
    assert rejected["state"]["zone_book_evidence"]=="NTD_ZONE_HISTORY_WINDOW_UNVERIFIED"
    bare=dict(first["state"])
    bare.pop("zone_book_last_checked_at_ms")
    assert plan(last6,osc6,side,bare)["stop"] is None


def test_missing_h1_timestamp_is_not_five_consecutive_closed_price_bars():
    series,osc=shape()
    series[3]=replace(series[3],t=series[3].t+60_000)
    fail=plan(series,osc)
    assert fail["stop"] is None
    assert fail["state"]["zone_book_evidence"]=="NTD_H1_HISTORY_GAP"
