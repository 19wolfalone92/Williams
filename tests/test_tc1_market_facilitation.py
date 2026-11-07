"""TC1 chapter 6 user PDF42–45: four MFI/volume windows.

Use ONE shared fixture with Kotlin, never pretend kline volume is genuine
tick volume or use squat as proof of reversal/entry/STOP.
"""
import json
from pathlib import Path

import pytest

from williams_tc1_market_facilitation import tc1_mfi_four_window

ROOT=Path(__file__).resolve().parents[1]
DATA=json.loads((ROOT/"app/src/test/resources/williams_tc1_mfi_windows_contract.json").read_text())
H=3_600_000

def bars(high,low,volume,t=2*H):
    return [
        dict(t=H,o=101.,h=110.,l=100.,c=101.,v=100.),
        dict(t=t,o=101.,h=high,l=low,c=101.,v=volume),
    ]

@pytest.mark.parametrize("case",DATA["cases"],ids=lambda x:x["id"])
def test_mfi_four_windows_parity_with_android(case):
    p=tc1_mfi_four_window(bars(case["high"],case["low"],case["volume"]),
                            "1h",3*H)
    assert p["status"]==case["status"]
    assert p["window"]==case["window"]
    assert p["canonical_tick_volume_verified"] is False
    assert p["pattern_can_prove_w5_end"] is False
    assert not p["execution_authority"]
    assert not p["can_place_orders"]
    assert not p["can_close"]
    assert not p["can_move_stop"]
    if p["status"]=="OBSERVED_BASE_VOLUME_PROXY":
        assert p["mfi_previous"]==pytest.approx(.1)
        assert p["known_at_ms"]==3*H
        assert "Binance" in p["explanation_ru"]

@pytest.mark.parametrize("broken,as_of",[
    (bars(112,100,120,4*H),5*H),
    (bars(112,100,120,3*H),3*H),
    (bars(112,100,0),3*H),
    (bars(112,100,120),5*H),
    (bars(float("nan"),100,120),3*H),
    (bars(100,112,120),3*H),
])
def test_bad_or_gapped_bars_cannot_be_four_window_observation(broken,as_of):
    x=tc1_mfi_four_window(broken,"1h",as_of)
    assert x["status"]=="SOURCE_H1_OR_FRAME_UNVERIFIED"
    assert x["window"]=="UNKNOWN"
    assert not x["can_place_orders"]

def test_equal_volume_or_equal_mfi_are_neither_green_nor_squat():
    equal_volume=tc1_mfi_four_window(bars(124,100,100),"1h",3*H)
    equal_mfi=tc1_mfi_four_window(bars(112,100,120),"1h",3*H)
    assert equal_volume["window"]=="UNCHANGED_OR_TIED"
    assert equal_mfi["window"]=="UNCHANGED_OR_TIED"
    assert equal_mfi["mfi_direction"]=="TIED"
    assert equal_volume["volume_direction"]=="TIED"
