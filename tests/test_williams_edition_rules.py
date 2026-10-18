from dataclasses import replace
import pytest
from williams_wave_hierarchy import WaveBar
from williams_edition_rules import (
    ntd_balance_line_setup,course_hump_setup,course_thumb_setup,course_airbag_stop,
    course_two_fractal_stop,edition_fractals,market_on_close_reason,
)


def bars(highs,lows=None,volumes=None):
    lows=lows or [p-1 for p in highs]
    return [WaveBar((i+1)*3_600_000,(h+l)/2,h,l,(h+l)/2,(volumes or [100]*len(highs))[i]) for i,(h,l) in enumerate(zip(highs,lows))]


def mirror(series,total=300):
    return [replace(b,o=total-b.o,h=total-b.l,l=total-b.h,c=total-b.c) for b in series]


@pytest.mark.parametrize("adverse,toward,needed,level",[(False,False,1,11.1),(False,True,2,12.1),(True,False,2,12.1),(True,True,4,14.1)])
@pytest.mark.parametrize("short",[False,True])
def test_balance_line_base_record_count_and_adverse_zone_doubling(adverse,toward,needed,level,short):
    series=bars([14,13,12,11,10],[8]*5)
    jaw=[15]*4+[6] if toward else [6]*5
    teeth,lips=[5]*5,[4]*5
    ao=[5-i if adverse else i+1 for i in range(5)];ac=ao[:]
    if short:
        series=mirror(series);jaw=[300-v for v in jaw];teeth=[300-v for v in teeth];lips=[300-v for v in lips];ao=[-v for v in ao];ac=[-v for v in ac]
    setup=ntd_balance_line_setup("SHORT" if short else "LONG",series,jaw,teeth,lips,ao,ac,.1)
    assert setup is not None
    assert setup.context["required_extremes"]==needed
    assert setup.known_index==4
    assert setup.trigger==pytest.approx(300-level if short else level)


def test_balance_line_higher_bars_after_a_base_do_not_move_the_order_or_resurrect_a_crossed_level():
    def run(highs):
        n=len(highs)
        return ntd_balance_line_setup("LONG",bars(highs,[8]*n),[6]*n,[5]*n,[4]*n,list(range(n)),list(range(n)),.1)
    assert run([12,11,10]).trigger==run([12,11,10,10.5]).trigger==11.1
    assert run([12,11,10,11.2]) is None
    assert run([10,11,12]) is None


@pytest.mark.parametrize("short",[False,True])
def test_course_thumb_is_the_oldest_counted_finger_not_the_last_bar(short):
    series=bars([10,11,10.8,12],[8,9,9.2,10],[100,200,50,400])
    if not short:series=mirror(series)
    setup=course_thumb_setup("SHORT" if short else "LONG",series,.1)
    assert setup is not None
    assert setup.context["finger_indices"]==[0,1,3]
    assert setup.trigger==pytest.approx(7.9 if short else 292.1)
    assert setup.stop==pytest.approx(12.1 if short else 287.9)
    series=[replace(b,v=100) for b in series]
    assert course_thumb_setup("SHORT" if short else "LONG",series,.1) is None


def hump_bars():
    prices=[105,104,100,102,104,105,106,104,102,101,98,100,102,104,108,110,107,106,107]
    return bars([p+.2 for p in prices],[p-.2 for p in prices])


@pytest.mark.parametrize("short",[False,True])
def test_course_hump_has_real_start_signal_leverage_and_the_further_of_two_stops(short):
    series=hump_bars()
    if short:series=mirror(series)
    side="SHORT" if short else "LONG"
    setup=course_hump_setup(side,series,.1)
    assert setup is not None
    assert setup.known_index==17
    assert setup.context["start_index"]==10
    assert setup.trigger==pytest.approx(189.7 if short else 110.3)
    assert setup.stop==pytest.approx(202.3 if short else 97.7)
    assert course_two_fractal_stop(side,series,.1)==setup.stop
    breached=series+[replace(series[-1],t=series[-1].t+3_600_000,
        o=setup.invalidation,c=setup.invalidation,h=setup.invalidation+1,l=setup.invalidation-1)]
    assert course_hump_setup(side,breached,.1) is None


def test_airbag_skips_inside_higher_frame_bars_and_uses_the_actual_older_low():
    series=bars([100,103,104,105],[90,93,96,95])
    assert course_airbag_stop("LONG",series,.1)==pytest.approx(92.9)
    assert course_airbag_stop("SHORT",mirror(series),.1)==pytest.approx(207.1)


def test_an_outside_fractal_is_not_deleted_but_cannot_establish_two_ordered_turns():
    series=bars([10,11,15,11,10],[8,7,3,7,8])
    assert {p.kind for p in edition_fractals(series)}=={"HIGH","LOW"}
    assert course_hump_setup("LONG",series,.1) is None


@pytest.mark.parametrize("age,price,reason",[(-1,100,"CLOSE_EVENT_NOT_CLOSED"),(0,100,None),(90_000,100,None),
    (90_001,100,"CLOSE_EVENT_EXPIRED"),(10,101,"CLOSE_EVENT_SLIPPAGE_LIMIT")])
def test_close_event_requires_a_real_just_closed_bar_and_bounded_actual_market_price(age,price,reason):
    assert market_on_close_reason(1000,3_600_000,3_601_000+age,100,price)==reason
