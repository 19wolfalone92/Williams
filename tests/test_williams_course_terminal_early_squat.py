"""TC1 PDF53,82-83 and Course Level III PDF77: no fifth-wave squat before W5.
Read-only, synthetic causal bars; never an exchange execution policy test.
"""
from dataclasses import replace
import pytest

from williams_wave_hierarchy import (
    FRAME_MS, WaveBar, WaveAnchor, hypotheses_from_anchors, terminal_squat_volume_proxy,
)

HOUR = FRAME_MS["1h"]


@pytest.mark.parametrize("mirror", [False, True])
def test_w1_to_w4_adjacent_squat_is_not_w5_terminal_evidence(mirror):
    bars = [WaveBar(i*HOUR,129.0,131.0,128.0,129.0,100.0)
            for i in range(180)]
    bars[10] = WaveBar(10*HOUR,101.0,102.0,100.0,101.0,100.0)
    bars[30] = WaveBar(30*HOUR,109.0,110.0,108.0,109.0,100.0)
    bars[50] = WaveBar(50*HOUR,106.0,107.0,105.0,106.0,100.0)
    bars[75] = WaveBar(75*HOUR,144.0,145.0,143.0,144.0,100.0)
    bars[129] = WaveBar(129*HOUR,120.0,120.1,119.9,120.0,300.0)
    prices = [100.0,110.0,105.0,145.0,119.9]
    indices = [10,30,50,75,129]
    anchors = [WaveAnchor("LOW" if i%2==0 else "HIGH",
                          prices[i],idx,idx*HOUR,(idx+3)*HOUR)
               for i,idx in enumerate(indices)]
    if mirror:
        bars = [replace(b,o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c)
                for b in bars]
        anchors = [replace(a,kind="HIGH" if a.kind=="LOW" else "LOW",
                           price=300-a.price) for a in anchors]
    last,previous = bars[129],bars[128]
    assert last.v>previous.v
    assert (last.h-last.l)/last.v<(previous.h-previous.l)/previous.v
    hypotheses = hypotheses_from_anchors("1h",bars,anchors)
    assert any(s.label=="W4" for s in hypotheses)
    assert all(not s.squat for s in hypotheses)
    assert all(not s.terminal_fractal for s in hypotheses)


@pytest.mark.parametrize("direction", ["UP","DOWN"])
def test_true_w5_top_three_squat_remains_observable_as_noncanonical_volume_proxy(direction):
    bars = [WaveBar(i*HOUR,129.0,131.0,128.0,129.0,100.0)
            for i in range(180)]
    bars[120] = replace(bars[120],o=154.8,h=155.0,l=154.6,c=154.8,v=300.0)
    bars[125] = replace(bars[125],o=152.0,h=153.0,l=151.0,c=152.0)
    bars[130] = replace(bars[130],o=159.0,h=160.0,l=158.0,c=159.0)
    if direction=="DOWN":
        bars = [replace(b,o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c)
                for b in bars]
    assert terminal_squat_volume_proxy(bars,direction,100,130)
    assert not terminal_squat_volume_proxy(bars,direction,130,130)
