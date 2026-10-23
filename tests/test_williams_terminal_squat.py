from dataclasses import replace
import pytest

from williams_wave_hierarchy import WaveBar,terminal_squat_volume_proxy


@pytest.mark.parametrize('mirror',[False,True])
def test_only_top_three_of_the_fifth_can_supply_terminal_squat(mirror):
    # TC1 PDF53 and Course PDF77; synthetic boundary data, not book OHLC.
    rows=[WaveBar(i*3_600_000,129,131,128,129,100) for i in range(180)]
    rows[120]=replace(rows[120],o=154,h=155,l=153,c=154)
    rows[125]=replace(rows[125],o=152,h=153,l=151,c=152)
    rows[129]=replace(rows[129],o=150.9,h=151,l=150.9,c=150.9,v=300)
    rows[130]=replace(rows[130],o=159,h=160,l=158,c=159)
    direction='DOWN' if mirror else 'UP'
    def reflected(source):
        return [replace(b,o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c) for b in source] if mirror else source
    assert not terminal_squat_volume_proxy(reflected(rows),direction,100,130)
    rows[120]=replace(rows[120],o=154.8,h=155,l=154.6,c=154.8,v=300)
    assert terminal_squat_volume_proxy(reflected(rows),direction,100,130)
    assert not terminal_squat_volume_proxy(reflected(rows),direction,100,119)
    assert not terminal_squat_volume_proxy(reflected(rows),direction,130,130)
    rows[120]=replace(rows[120],v=float('nan'))
    assert not terminal_squat_volume_proxy(reflected(rows),direction,100,130)


def test_invalid_side_or_unknown_volume_does_not_create_terminal_squat():
    rows=[WaveBar(i,100,101,99,100,0) for i in range(6)]
    assert not terminal_squat_volume_proxy(rows,'UP',0,5)
    assert not terminal_squat_volume_proxy(rows,'UNKNOWN',0,5)
