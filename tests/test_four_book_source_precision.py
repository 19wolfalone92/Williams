"""Read-only book-rule tests linked to original NTD PDF pp.63-64 and TC2 ch11.
No live trading, no private diagnostic archives or API keys are needed.
"""
import math

import pandas as pd
import pytest

from williams_signals import extract_ntd_ao_signal_specs, _source_exact_one_tick

HOUR = 3_600_000


def _ao_frame(ao, *, high=2351.28, low=2339.62):
    rows=[]
    for i, value in enumerate(ao):
        previous=ao[i-1] if i else value
        rows.append(dict(open_time_ms=(i+1)*HOUR,open=2340.0,
                         high=high,low=low,close=2347.17,ao=value,
                         ao_green=value>previous,ao_red=value<previous))
    return pd.DataFrame(rows)


@pytest.mark.parametrize("side,old,new,expected", [
    ("LONG",-0.1,1.0,True),
    ("LONG",0.0,1.0,False),
    ("LONG",0.1,1.0,False),
    ("SHORT",0.1,-1.0,True),
    ("SHORT",0.0,-1.0,False),
    ("SHORT",-0.1,-1.0,False),
])
def test_ntd_pdf63_true_zero_cross_excludes_a_zero_touch(side,old,new,expected):
    frame=_ao_frame([-1.0 if side=="LONG" else 1.0,old,new])
    signals=extract_ntd_ao_signal_specs("ETHUSDT",frame,side=side,timeframe="1h",tick_size=.01)
    crosses=[signal for signal in signals if "ZERO_LINE" in str(signal.signal_type)]
    assert bool(crosses)==expected
    if crosses:
        assert crosses[0].trigger_price == (2351.29 if side=="LONG" else 2339.61)


def test_ntd_pdf57_saucer_price_bar_avoids_double_tick_drift():
    # Real price scale from sanitized Oct27 ETH public H1 observation.
    frame=_ao_frame([1.8,1.0,1.5])
    signal=extract_ntd_ao_signal_specs("ETHUSDT",frame,side="LONG",
                                      timeframe="1h",tick_size=.01)
    assert len(signal)==1
    assert "SAUCER" in str(signal[0].signal_type)
    assert signal[0].trigger_price==2351.29


@pytest.mark.parametrize("bad", [0.0,-0.01,float("nan"),float("inf"),None])
def test_missing_exchange_tick_cannot_create_ntd_price_signal(bad):
    frame=_ao_frame([-1,-0.1,1])
    assert not extract_ntd_ao_signal_specs(
        "ETHUSDT",frame,side="LONG",timeframe="1h",tick_size=bad)


def test_four_book_price_tick_source_math_rejects_offgrid_and_nan():
    assert _source_exact_one_tick(79524.8,.1,above=False)==79524.7
    assert _source_exact_one_tick(79524.8,.1,above=True)==79524.9
    assert _source_exact_one_tick(2297.0,.01,above=False)==2296.99
    assert _source_exact_one_tick(.2060,.0001,above=True)==.2061
    assert _source_exact_one_tick(99.005,.01,above=False) is None
    assert _source_exact_one_tick(float("nan"),.01,above=True) is None
