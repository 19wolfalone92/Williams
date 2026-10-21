import math

import pandas as pd
import pytest

from campaign_model import SignalRole, SignalType
from ntd_balance_line import ntd_balance_line_evidence
from williams_signals import extract_ntd_balance_line_signal_specs


def evidence(highs, *, lows=None, jaw=100.0, jaw_values=None, teeth=99.0, lips=101.0, side="LONG", zone="GRAY"):
    lows = lows or [v - 2.0 for v in highs]
    n = len(highs)
    ao = [0.0] * n
    ac = [0.0] * n
    if zone == "RED":
        ao[-2:] = [2.0, 1.0]
        ac[-2:] = [2.0, 1.0]
    elif zone == "GREEN":
        ao[-2:] = [1.0, 2.0]
        ac[-2:] = [1.0, 2.0]
    return ntd_balance_line_evidence(
        highs, lows, jaw_values if jaw_values is not None else [jaw] * n, [teeth] * n, [lips] * n,
        ao, ac, side=side, tick_size=0.1
    )


def test_buy_above_jaw_requires_one_more_high_read_right_to_left():
    e = evidence([110, 108, 106, 105, 104])
    assert (e.status,e.required_steps,e.confirmed_steps,e.base_index,e.reference_index) == (
        "CANDIDATE",1,1,4,3)
    assert e.trigger_price == pytest.approx(105.1)
    assert e.protective_reference == pytest.approx(102)


def test_buy_below_jaw_requires_two_rising_highs():
    e = evidence([108, 106, 104, 101, 98], jaw_values=[100,100,100,120,100])
    assert e.status == "CANDIDATE"
    assert e.required_steps == 2
    assert e.reference_index == 2
    assert e.trigger_price == pytest.approx(104.1)


def test_red_zone_doubles_buy_uphill_requirement_to_four():
    e = evidence([115, 110, 107, 104, 102, 98], jaw_values=[100,100,100,100,110,100], zone="RED")
    assert e.status == "CANDIDATE"
    assert e.required_steps == 4
    assert e.trigger_price == pytest.approx(110.1)
    no_fourth = evidence([106, 104, 102, 98, 98], jaw_values=[100,100,120,100,100], zone="RED")
    assert no_fourth.status == "INCOMPLETE"


def test_sell_below_jaw_one_lower_low():
    lows = [70, 80, 90, 95, 96]
    e = evidence([v + 2 for v in lows], lows=lows, side="SHORT", jaw=110, teeth=111, lips=112)
    assert e.status == "CANDIDATE"
    assert e.required_steps == 1
    assert e.trigger_price == pytest.approx(94.9)


def test_green_zone_doubles_sell_uphill_requirements():
    lows = [70, 75, 80, 85, 90, 95]
    e = evidence([v + 2 for v in lows], lows=lows, side="SHORT", jaw=90,
                 teeth=91, lips=92, zone="GREEN")
    assert e.status == "CANDIDATE"
    assert e.required_steps == 4
    assert e.trigger_price == pytest.approx(74.9)


def test_already_touched_signal_is_never_rearmed():
    e = evidence([110,108,106,104,105,107])
    assert e.base_index == 3
    assert e.reason == "TRIGGER_ALREADY_TOUCHED"
    assert e.status == "BLOCKED"


def test_mouth_gate_blocks_inside_jaw_even_with_valid_highs():
    e = evidence([110,108,106,105,104],jaw=200,teeth=199,lips=201)
    assert e.status == "BLOCKED"
    assert e.reason == "INSIDE_ALLIGATOR_MOUTH"


def test_no_candidate_with_missing_indicators_or_bad_tick():
    x = evidence([110,109,108,107,106])
    assert not x.can_place_orders
    assert ntd_balance_line_evidence([105]*5,[103]*5,[100]*5,[100]*5,[100]*5,
        [0,0,0,0,float("nan")],[0]*5,side="LONG",tick_size=0.1).status == "BLOCKED"
    assert ntd_balance_line_evidence([105]*5,[103]*5,[100]*5,[100]*5,[100]*5,
        [0]*5,[0]*5,side="LONG",tick_size=0).status == "BLOCKED"
    assert ntd_balance_line_evidence([105]*5,[103]*5,[100]*5,[100]*5,[100]*5,
        [0]*5,[0]*5,side="WRONG",tick_size=0.1).status == "BLOCKED"


def test_python_ntd_spec_is_research_only_add_on_and_symmetric():
    highs = [110,108,106,105,104]
    n = len(highs)
    df = pd.DataFrame({
        "high": highs, "low": [x-2 for x in highs], "close": [x-1 for x in highs],
        "jaw_current": [100.0]*n, "teeth_current": [99.0]*n,
        "lips_current": [101.0]*n, "ao": [0.0]*n,"ac":[0.0]*n,
        "open_time_ms": [(i+1)*3_600_000 for i in range(n)]
    })
    specs = extract_ntd_balance_line_signal_specs("BTCUSDT",df,side="LONG",timeframe="1h",tick_size=0.1)
    assert len(specs)==1
    assert specs[0].signal_type == SignalType.BALANCE_LINE
    assert specs[0].role == SignalRole.ENTRY
    assert specs[0].side == "BUY"
    assert specs[0].trigger_price == pytest.approx(105.1)
    assert "research-only" in specs[0].reason.lower()
    assert extract_ntd_balance_line_signal_specs("BTCUSDT",df,side="SHORT",timeframe="1h",tick_size=0.1) == []


def test_future_bar_cannot_resurrect_past_ntd_candidate():
    highs = [110,108,106,105,104]
    base = evidence(highs)
    assert base.status == "CANDIDATE"
    touched = evidence(highs + [107])
    assert touched.status == "BLOCKED"
    assert touched.reason == "TRIGGER_ALREADY_TOUCHED"


def test_no_synthetic_short_or_long_on_flat_price():
    flat = [100,100,100,100,100,100]
    assert evidence(flat).reason == "NO_NEW_REFERENCE_EXTREME"
    assert evidence([v+2 for v in flat],lows=flat,side="SHORT",jaw=110,teeth=111,lips=112).reason == "NO_NEW_REFERENCE_EXTREME"

def test_book_figure_signal_reference_location_overrides_base_jaw_location():
    prices=[108,106,104,101,98]
    away=evidence(prices,jaw=100)
    uphill=evidence(prices,jaw_values=[100,100,100,120,100])
    assert away.status=="CANDIDATE" and away.required_steps==1
    assert uphill.status=="CANDIDATE" and uphill.required_steps==2
    assert uphill.trigger_price==pytest.approx(104.1)
    assert not uphill.can_place_orders
