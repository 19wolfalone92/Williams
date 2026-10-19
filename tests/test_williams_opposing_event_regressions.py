"""Price events are evaluated at their own time, using only closed history."""
import pytest

from williams_wave_hierarchy import WaveBar, opposing_level_triggered

H = 3_600_000


@pytest.mark.parametrize("short", [False, True])
def test_a_future_or_forming_bar_cannot_manufacture_an_opposite_exit(short):
    bar = WaveBar(21*H, 100, 112, 98, 101)
    if short:
        bar = WaveBar(bar.t, 200-bar.o, 200-bar.l, 200-bar.h, 200-bar.c)
    for now in (20*H, 21*H + H//2):
        assert not opposing_level_triggered("SHORT" if short else "LONG", 90 if short else 110,
            20*H, 19*H, 100, [bar], now, 24*H)


@pytest.mark.parametrize("short", [False, True])
def test_a_proven_cross_during_signal_lifetime_survives_later_expiry(short):
    bars = [WaveBar(20*H, 100, 101, 99, 100), WaveBar(21*H, 100, 112, 99, 101)]
    if short:
        bars = [WaveBar(b.t, 200-b.o, 200-b.l, 200-b.h, 200-b.c) for b in bars]
    assert opposing_level_triggered("SHORT" if short else "LONG", 90 if short else 110,
        20*H, 19*H, 100, bars, 25*H, 24*H)
